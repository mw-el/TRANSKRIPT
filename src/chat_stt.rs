use crossbeam_channel;
use jni::objects::{JClass, JObject};
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::{Arc, Mutex};
use transcribe_rs::engines::parakeet::{ParakeetInferenceParams, TimestampGranularity};
use transcribe_rs::TranscriptionEngine;

use crate::engine;

struct ChatSttState {
    buffer: Arc<Mutex<Vec<f32>>>,
    worker_tx: crossbeam_channel::Sender<Vec<f32>>,
    total_samples: u64,
    last_process_sample: u64,
}

// Eigener static — kein Konflikt mit LIVE_STATE aus subtitle.rs
static CHAT_STATE: Lazy<Mutex<Option<ChatSttState>>> = Lazy::new(|| Mutex::new(None));

#[no_mangle]
pub unsafe extern "system" fn Java_dev_transcribe_ChatActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    let vm = env.get_java_vm().expect("JavaVM");
    let vm_arc = Arc::new(vm);
    let activity_ref = env.new_global_ref(&activity).expect("GlobalRef");

    let (tx, rx) = crossbeam_channel::unbounded::<Vec<f32>>();

    *CHAT_STATE.lock().unwrap() = Some(ChatSttState {
        buffer: Arc::new(Mutex::new(Vec::new())),
        worker_tx: tx,
        total_samples: 0,
        last_process_sample: 0,
    });

    std::thread::spawn(move || {
        let mut env = match vm_arc.attach_current_thread() {
            Ok(e) => e,
            Err(e) => {
                log::error!("chat_stt worker attach failed: {}", e);
                return;
            }
        };
        let obj = activity_ref.as_obj();

        while let Ok(samples) = rx.recv() {
            if let Some(engine_arc) = engine::get_engine() {
                let params = ParakeetInferenceParams {
                    timestamp_granularity: TimestampGranularity::Segment,
                };
                let res = {
                    let mut eng = engine_arc.lock().unwrap();
                    eng.transcribe_samples(samples, Some(params))
                };
                if let Ok(r) = res {
                    let text = r.text.trim().to_string();
                    if !text.is_empty() {
                        if let Ok(jstr) = env.new_string(&text) {
                            let _ = env.call_method(
                                obj,
                                "onChatSegment",
                                "(Ljava/lang/String;)V",
                                &[(&jstr).into()],
                            );
                        }
                    }
                }
            }
        }
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_transcribe_ChatActivity_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *CHAT_STATE.lock().unwrap() = None;
}

/// Chunk-Größe: alle 2s (32000 samples @ 16kHz) an Parakeet senden.
/// Rolling buffer: max 3s (48000 samples) behalten.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_transcribe_ChatActivity_pushAudio(
    env: JNIEnv,
    _class: JClass,
    data: jni::objects::JFloatArray,
    length: jni::sys::jint,
) {
    let mut guard = CHAT_STATE.lock().unwrap();
    if let Some(state) = guard.as_mut() {
        let len = length as usize;
        let mut input = vec![0.0f32; len];
        env.get_float_array_region(&data, 0, &mut input).unwrap();

        let mut buf = state.buffer.lock().unwrap();
        buf.extend_from_slice(&input);
        state.total_samples += len as u64;

        if state.total_samples >= state.last_process_sample + 32000 {
            let sum_sq: f32 = buf.iter().map(|&x| x * x).sum();
            let rms = (sum_sq / buf.len() as f32).sqrt();
            if rms > 0.002 {
                let _ = state.worker_tx.send(buf.clone());
            }
            state.last_process_sample = state.total_samples;
            let buf_len = buf.len();
            if buf_len > 48000 {
                let keep = buf_len - 48000;
                *buf = buf[keep..].to_vec();
            }
        }
    }
}
