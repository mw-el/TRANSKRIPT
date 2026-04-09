use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JFloatArray, JObject};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::Lazy;

use transcribe_rs::TranscriptionEngine;

use crate::engine;

/// 60 seconds × 16 000 Hz = 960 000 samples per chunk
const CHUNK_SAMPLES: usize = 60 * 16_000;

struct TranscribeFileState {
    jvm: Arc<jni::JavaVM>,
    target_ref: jni::objects::GlobalRef,
    /// Accumulates decoded audio samples chunk-by-chunk from Java.
    /// Samples are streamed here to avoid a huge Java-heap float[] allocation.
    pending_samples: Vec<f32>,
}

static STATE: Lazy<Mutex<Option<TranscribeFileState>>> = Lazy::new(|| Mutex::new(None));

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

/// Sends the currently accumulated text to Java.
/// Java replaces the pulsing dots with this text on the first call,
/// and updates the text on every subsequent call.
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

/// Fired once after all chunks are done — triggers auto-save and action buttons.
fn notify_complete(env: &mut JNIEnv, obj: &JObject) {
    let _ = env.call_method(obj, "onTranscriptionComplete", "()V", &[]);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env
        .new_global_ref(&activity)
        .expect("Failed to ref activity");

    *STATE.lock().unwrap() = Some(TranscribeFileState {
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
        pending_samples: Vec::new(),
    });

    // Pre-load engine in the background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();
    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *STATE.lock().unwrap() = None;
}

/// Called repeatedly from Java during decoding to stream decoded audio into native memory.
/// This avoids allocating a large Java-heap float[] for the entire audio file.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_appendAudioSamples(
    env: JNIEnv,
    _class: JClass,
    chunk: JFloatArray,
    len: jint,
) {
    let len = len as usize;
    if len == 0 {
        return;
    }
    let mut guard = STATE.lock().unwrap();
    let state = match guard.as_mut() {
        Some(s) => s,
        None => return,
    };
    let prev_len = state.pending_samples.len();
    state.pending_samples.resize(prev_len + len, 0.0f32);
    let _ = env.get_float_array_region(&chunk, 0, &mut state.pending_samples[prev_len..]);
}

/// Called from Java after all audio chunks have been appended via appendAudioSamples.
/// Splits the buffer into 60-second chunks and transcribes them one by one,
/// calling onTextTranscribed after each chunk so text appears progressively.
/// Calls onTranscriptionComplete once everything is done.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_notune_transcribe_TranscribeFileActivity_transcribeAccumulated(
    _env: JNIEnv,
    _class: JClass,
) {
    let mut guard = STATE.lock().unwrap();
    let state = match guard.as_mut() {
        Some(s) => s,
        None => return,
    };

    if state.pending_samples.is_empty() {
        log::warn!("transcribeAccumulated called with empty buffer");
        let jvm = state.jvm.clone();
        let target_ref = state.target_ref.clone();
        drop(guard);
        if let Ok(mut env) = jvm.attach_current_thread() {
            notify_status(
                &mut env,
                target_ref.as_obj(),
                "Error: no audio data to transcribe",
            );
        }
        return;
    }

    // Drain the pending samples — the transcription thread owns them now.
    let buffer = std::mem::take(&mut state.pending_samples);
    let jvm = state.jvm.clone();
    let target_ref = state.target_ref.clone();
    drop(guard);

    std::thread::spawn(move || {
        let mut env = match jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let obj = target_ref.as_obj();

        // Ensure engine is loaded (waits if another thread is loading)
        if engine::get_engine().is_none() {
            if engine::ensure_loaded(&mut env, obj).is_err() {
                return;
            }
        }

        let eng_arc = match engine::get_engine() {
            Some(e) => e,
            None => {
                notify_status(&mut env, obj, "Error: model not loaded");
                return;
            }
        };

        // Split buffer into 60-second chunks and transcribe progressively.
        let total_chunks = (buffer.len() + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES;
        let mut accumulated = String::new();

        for (i, chunk) in buffer.chunks(CHUNK_SAMPLES).enumerate() {
            log::info!(
                "Transcribing chunk {}/{} ({:.1}s)",
                i + 1,
                total_chunks,
                chunk.len() as f32 / 16_000.0
            );

            let res = {
                let mut eng = eng_arc.lock().unwrap();
                eng.transcribe_samples(chunk.to_vec(), None)
            };

            match res {
                Ok(r) => {
                    let trimmed = r.text.trim();
                    if !trimmed.is_empty() {
                        if !accumulated.is_empty() {
                            accumulated.push(' ');
                        }
                        accumulated.push_str(trimmed);
                    }
                    // Send after every chunk — on the first call Java hides the
                    // pulsing dots and shows the text area.
                    notify_text(&mut env, obj, &accumulated);
                }
                Err(e) => {
                    notify_status(&mut env, obj, &format!("Error: {}", e));
                    return;
                }
            }
        }

        // All chunks done — trigger auto-save and reveal action buttons.
        notify_complete(&mut env, obj);
    });
}
