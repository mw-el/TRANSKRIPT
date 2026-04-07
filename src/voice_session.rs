use std::sync::{Arc, Mutex};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use jni::objects::{GlobalRef, JObject, JByteArray};
use jni::JNIEnv;
use transcribe_rs::TranscriptionEngine;

use crate::engine;

pub struct SendStream(#[allow(dead_code)] pub cpal::Stream);
unsafe impl Send for SendStream {}
unsafe impl Sync for SendStream {}

pub struct VoiceSessionState {
    pub stream: Option<SendStream>,
    pub audio_buffer: Arc<Mutex<Vec<f32>>>,
    pub jvm: Arc<jni::JavaVM>,
    pub target_ref: GlobalRef,
    pub last_level_sent: Arc<Mutex<std::time::Instant>>,
}

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(
            obj,
            "onStatusUpdate",
            "(Ljava/lang/String;)V",
            &[(&jmsg).into()],
        );
    }
}

fn notify_level(env: &mut JNIEnv, obj: &JObject, level: f32) {
    let _ = env.call_method(obj, "onAudioLevel", "(F)V", &[level.into()]);
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(
            obj,
            "onTextTranscribed",
            "(Ljava/lang/String;)V",
            &[(&jtxt).into()],
        );
    }
}

/// Converts f32 PCM samples [-1.0, 1.0] to signed 16-bit little-endian bytes
/// and passes them to `RustInputMethodService.onPcmAvailable(byte[])` via JNI.
///
/// Called after a successful transcription so that `pendingTranscriptForSave`
/// is already set on the Java side before the bytes arrive.
fn notify_pcm_available(env: &mut JNIEnv, obj: &JObject, samples: &[f32]) {
    // f32 -> i16 LE bytes
    let mut pcm_bytes: Vec<u8> = Vec::with_capacity(samples.len() * 2);
    for &s in samples {
        let clamped = s.clamp(-1.0, 1.0);
        let i16_val = (clamped * 32767.0) as i16;
        let le = i16_val.to_le_bytes();
        pcm_bytes.push(le[0]);
        pcm_bytes.push(le[1]);
    }

    // Create Java byte[] from the PCM bytes.
    // JNI requires i8 (signed), so we reinterpret the u8 slice.
    let signed: Vec<i8> = pcm_bytes.iter().map(|&b| b as i8).collect();

    match env.new_byte_array(signed.len() as i32) {
        Ok(jarray) => {
            if env.set_byte_array_region(&jarray, 0, &signed).is_ok() {
                let _ = env.call_method(
                    obj,
                    "onPcmAvailable",
                    "([B)V",
                    &[(&jarray).into()],
                );
            }
        }
        Err(e) => log::warn!("notify_pcm_available: could not create byte array: {}", e),
    }
}

pub fn init_session(env: JNIEnv, target: JObject) -> VoiceSessionState {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&target).expect("Failed to ref target");

    let state = VoiceSessionState {
        stream: None,
        audio_buffer: Arc::new(Mutex::new(Vec::new())),
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        last_level_sent: Arc::new(Mutex::new(std::time::Instant::now())),
    };

    // Load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();

    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });

    state
}

pub fn start_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    let host = cpal::default_host();
    let device = match host.default_input_device() {
        Some(d) => d,
        None => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                "Error: no microphone available. Check permissions.",
            );
            return;
        }
    };

    let config = cpal::StreamConfig {
        channels: 1,
        sample_rate: cpal::SampleRate(16000),
        buffer_size: cpal::BufferSize::Default,
    };

    state.audio_buffer.lock().unwrap().clear();
    let buffer_clone = state.audio_buffer.clone();

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    let last_sent = state.last_level_sent.clone();

    let stream = device.build_input_stream(
        &config,
        move |data: &[f32], _: &_| {
            buffer_clone.lock().unwrap().extend_from_slice(data);

            // compute RMS
            let mut sum = 0.0f32;
            for &x in data {
                sum += x * x;
            }
            let rms = (sum / (data.len() as f32)).sqrt();
            let level = (rms * 6.0).clamp(0.0, 1.0);

            // throttle updates
            let mut last = last_sent.lock().unwrap();
            if last.elapsed() >= std::time::Duration::from_millis(50) {
                *last = std::time::Instant::now();

                if let Ok(mut env) = jvm.attach_current_thread() {
                    let obj = target_ref.as_obj();
                    notify_level(&mut env, obj, level);
                }
            }
        },
        |e| log::error!("Stream err: {}", e),
        None,
    );

    match stream {
        Ok(s) => {
            s.play().ok();
            state.stream = Some(SendStream(s));
            notify_status(&mut env, state.target_ref.as_obj(), "Listening...");
        }
        Err(e) => {
            notify_status(
                &mut env,
                state.target_ref.as_obj(),
                &format!("Error: failed to open microphone: {}", e),
            );
        }
    }
}

pub fn stop_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    // Drop the stream to stop recording
    state.stream = None;

    let buffer = state.audio_buffer.lock().unwrap().clone();

    // Guard against empty buffer
    if buffer.is_empty() {
        notify_status(
            &mut env,
            state.target_ref.as_obj(),
            "Error: no audio recorded. Check microphone permissions.",
        );
        return;
    }

    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();

    notify_status(&mut env, target_ref.as_obj(), "Transcribing...");

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Wait for engine if still loading
        if engine::get_engine().is_none() {
            if let Err(_) = engine::ensure_loaded(&mut env, obj) {
                return;
            }
        }

        if let Some(eng_arc) = engine::get_engine() {
            let res = {
                let mut eng = eng_arc.lock().unwrap();
                eng.transcribe_samples(buffer.clone(), None)
            };

            match res {
                Ok(r) => {
                    notify_status(&mut env, obj, "Ready");
                    // 1. Send transcribed text first so Java sets pendingTranscriptForSave
                    notify_text(&mut env, obj, &r.text);
                    // 2. Then send raw PCM so Java saves it under the same slug filename
                    notify_pcm_available(&mut env, obj, &buffer);
                }
                Err(e) => notify_status(&mut env, obj, &format!("Error: {}", e)),
            }
        } else {
            notify_status(&mut env, obj, "Error: model not loaded");
        }
    });
}

pub fn cancel_recording(mut env: JNIEnv, state: &mut VoiceSessionState) {
    state.stream = None;
    state.audio_buffer.lock().unwrap().clear();
    notify_status(&mut env, state.target_ref.as_obj(), "Canceled");
}
