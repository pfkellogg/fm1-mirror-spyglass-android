//! Rich sounds on Android (and Fire TV): the desktop app's synth (rustysynth playing GeneralUser GS, src/rich.rs),
//! for Rich.java. A handle is a boxed synth; every call takes it. Mono out, as the Mellotron's mix is.
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jfloat, jint, jlong};
use jni::JNIEnv;
use rustysynth::{SoundFont, Synthesizer, SynthesizerSettings};
use std::sync::Arc;

struct Rich {
    synth: Synthesizer,
    left: Vec<f32>,
    right: Vec<f32>,
    mono: Vec<f32>,
}

fn rich<'a>(h: jlong) -> Option<&'a mut Rich> {
    if h == 0 {
        None
    } else {
        Some(unsafe { &mut *(h as *mut Rich) })
    }
}

/// The synth for the SoundFont at `path`, at `rate` Hz: 0 when it can't be read.
#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nNew(mut env: JNIEnv, _: JClass, path: JString, rate: jint) -> jlong {
    let Ok(path) = env.get_string(&path).map(String::from) else { return 0 };
    let Ok(mut f) = std::fs::File::open(&path) else { return 0 };
    let Ok(font) = SoundFont::new(&mut std::io::BufReader::new(&mut f)) else { return 0 };
    let mut settings = SynthesizerSettings::new(rate);
    settings.maximum_polyphony = 48;
    let Ok(synth) = Synthesizer::new(&Arc::new(font), &settings) else { return 0 };
    Box::into_raw(Box::new(Rich { synth, left: Vec::new(), right: Vec::new(), mono: Vec::new() })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nFree(_: JNIEnv, _: JClass, h: jlong) {
    if h != 0 {
        drop(unsafe { Box::from_raw(h as *mut Rich) });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nProgram(_: JNIEnv, _: JClass, h: jlong, p: jint) {
    if let Some(r) = rich(h) {
        r.synth.process_midi_message(0, 0xC0, p.clamp(0, 127), 0);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nNoteOn(_: JNIEnv, _: JClass, h: jlong, key: jint, vel: jint) {
    if let Some(r) = rich(h) {
        r.synth.note_on(0, key, vel.clamp(1, 127));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nNoteOff(_: JNIEnv, _: JClass, h: jlong, key: jint) {
    if let Some(r) = rich(h) {
        r.synth.note_off(0, key);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nAllOff(_: JNIEnv, _: JClass, h: jlong) {
    if let Some(r) = rich(h) {
        r.synth.note_off_all(false);
    }
}

/// `n` mono samples into `out`, times `gain` (GeneralUser GS leaves ~20 dB of headroom: the desktop uses 6).
#[no_mangle]
pub extern "system" fn Java_com_pfkellogg_fm1mirror_Rich_nRender(env: JNIEnv, _: JClass, h: jlong, out: JFloatArray, n: jint, gain: jfloat) {
    let Some(r) = rich(h) else { return };
    let n = n.max(0) as usize;
    r.left.resize(n, 0.0);
    r.right.resize(n, 0.0);
    r.mono.resize(n, 0.0);
    r.synth.render(&mut r.left, &mut r.right);
    for i in 0..n {
        r.mono[i] = (r.left[i] + r.right[i]) * 0.5 * gain;
    }
    let _ = env.set_float_array_region(&out, 0, &r.mono);
}
