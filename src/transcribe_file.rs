use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JFloatArray, JObject};
use jni::sys::{jboolean, jint};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use transcribe_rs::TranscriptionEngine;

use crate::engine;

/// Accumulated transcript across all chunks (reset on initNative).
static ACCUMULATED: Lazy<Mutex<String>> = Lazy::new(|| Mutex::new(String::new()));

struct TranscribeFileState {
    #[allow(dead_code)]
    jvm: Arc<jni::JavaVM>,
    target_ref: jni::objects::GlobalRef,
}

static STATE: Lazy<Mutex<Option<TranscribeFileState>>> = Lazy::new(|| Mutex::new(None));

fn notify_status(env: &mut JNIEnv, obj: &JObject, msg: &str) {
    if let Ok(jmsg) = env.new_string(msg) {
        let _ = env.call_method(obj, "onStatusUpdate", "(Ljava/lang/String;)V", &[(&jmsg).into()]);
    }
}

fn notify_text(env: &mut JNIEnv, obj: &JObject, text: &str) {
    if let Ok(jtxt) = env.new_string(text) {
        let _ = env.call_method(obj, "onTextTranscribed", "(Ljava/lang/String;)V", &[(&jtxt).into()]);
    }
}

fn notify_complete(env: &mut JNIEnv, obj: &JObject) {
    let _ = env.call_method(obj, "onTranscriptionComplete", "()V", &[]);
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_transcribe_TranscribeFileActivity_initNative(
    env: JNIEnv,
    _class: JClass,
    activity: JObject,
) {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );

    // Reset accumulated text for this session
    ACCUMULATED.lock().unwrap().clear();

    let vm = env.get_java_vm().expect("Failed to get JavaVM");
    let vm_arc = Arc::new(vm);
    let target_ref = env.new_global_ref(&activity).expect("Failed to ref activity");

    *STATE.lock().unwrap() = Some(TranscribeFileState {
        jvm: vm_arc.clone(),
        target_ref: target_ref.clone(),
    });

    // Pre-load engine in background
    let vm_clone = vm_arc.clone();
    let target_ref_clone = target_ref.clone();
    std::thread::spawn(move || {
        let _ = engine::ensure_loaded_from_thread(&vm_clone, &target_ref_clone);
    });
}

#[no_mangle]
pub unsafe extern "system" fn Java_dev_transcribe_TranscribeFileActivity_cleanupNative(
    _env: JNIEnv,
    _class: JClass,
) {
    *STATE.lock().unwrap() = None;
    ACCUMULATED.lock().unwrap().clear();
}

/// Called from Java's background thread once per 60-second decoded chunk.
/// Transcribes the chunk synchronously and calls onTextTranscribed with the
/// accumulated text so far.  On the last chunk also calls onTranscriptionComplete.
///
/// Because this runs on Java's background thread the JNI env is already attached
/// and callbacks can be issued directly without spawning another thread.
#[no_mangle]
pub unsafe extern "system" fn Java_dev_transcribe_TranscribeFileActivity_transcribeChunkNative(
    mut env: JNIEnv,
    _class: JClass,
    samples: JFloatArray,
    len: jint,
    is_last: jboolean,
) {
    let len = len as usize;

    // Retrieve the activity reference stored during initNative
    let target_ref = match STATE.lock().unwrap().as_ref() {
        Some(s) => s.target_ref.clone(),
        None => return,
    };
    let obj = target_ref.as_obj();

    // Empty last chunk — just signal completion
    if len == 0 {
        if is_last != 0 {
            ACCUMULATED.lock().unwrap().clear();
            notify_complete(&mut env, obj);
        }
        return;
    }

    // Copy samples from Java heap to Rust
    let mut chunk = vec![0.0f32; len];
    if env.get_float_array_region(&samples, 0, &mut chunk).is_err() {
        log::error!("get_float_array_region failed");
        return;
    }

    // Ensure engine is loaded (waits if the background preload is still running)
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

    log::info!("Transcribing chunk of {:.1}s", len as f32 / 16_000.0);

    let res = {
        let mut eng = eng_arc.lock().unwrap();
        eng.transcribe_samples(chunk, None)
    };

    match res {
        Ok(r) => {
            let trimmed = r.text.trim().to_string();
            // Append to accumulated text and send the full result so far
            let text_snapshot = {
                let mut acc = ACCUMULATED.lock().unwrap();
                if !trimmed.is_empty() {
                    if !acc.is_empty() {
                        acc.push(' ');
                    }
                    acc.push_str(&trimmed);
                }
                acc.clone()
            };
            notify_text(&mut env, obj, &text_snapshot);
        }
        Err(e) => {
            log::error!("Transcription error: {}", e);
            notify_status(&mut env, obj, &format!("Error: {}", e));
            if is_last != 0 {
                ACCUMULATED.lock().unwrap().clear();
            }
            return;
        }
    }

    if is_last != 0 {
        ACCUMULATED.lock().unwrap().clear();
        notify_complete(&mut env, obj);
    }
}
