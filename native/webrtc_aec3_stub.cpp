// webrtc_aec3_stub.cpp — JNI bridge for WebRTC AEC3 acoustic echo cancellation.
//
// STATUS: UNCOMPILED STUB. It cannot be built or tested in the Linux sandbox;
// follow native/BUILD_WINDOWS.md on the Windows laptop to fetch WebRTC,
// compile this file to webrtc_aec3.dll, and enable it with
//   setx VBL_ECHO_CANCELLER webrtc_aec3
// (or the audio.echoCanceller preference).
//
// The Kotlin side (platform/audio/WebRtcAec3.kt) declares:
//   external fun nativeCreate(sampleRateHz: Int): Long
//   external fun nativeProcess(handle: Long, micFrame: ByteArray, renderFrame: ByteArray, outFrame: ByteArray)
//   external fun nativeReset(handle: Long)
//   external fun nativeDestroy(handle: Long)
//
// Frame contract from Kotlin: 16-bit LE mono @ sampleRateHz, micFrame and
// renderFrame the same length (pipeline uses 32 ms = 1024 bytes @ 16 kHz),
// outFrame pre-allocated with micFrame.size bytes. AEC3 itself works on
// 10 ms frames, so this stub internally chunks/buffers.

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

// --- WebRTC headers (from a `fetch webrtc` checkout, see BUILD_WINDOWS.md) ---
// TODO(laptop): match these includes to the pinned WebRTC revision. The
// public AEC3 shape (verified against upstream docs) is:
//   webrtc::EchoCanceller3(config, multichannel_config, sample_rate_hz,
//                          num_render_channels, num_capture_channels)
//   aec->AnalyzeRender(render_buffer);
//   aec->AnalyzeCapture(capture_buffer);
//   aec->ProcessCapture(capture_buffer, linear_output, /*echo_path_change=*/false);
//   aec->Initialize();  // reset
#include "modules/audio_processing/aec3/echo_canceller3.h"
#include "modules/audio_processing/audio_buffer.h"

namespace {

constexpr int kAec3FrameMs = 10;

struct Aec3State {
  std::unique_ptr<webrtc::EchoCanceller3> aec;
  int sample_rate_hz = 16000;
  int frame_samples = 160;  // 10 ms @ 16 kHz
  // Residual carry-over when Kotlin frames are not multiples of 10 ms.
  std::vector<int16_t> mic_carry;
  std::vector<int16_t> render_carry;

  // TODO(laptop): the AudioBuffer constructor signature varies across WebRTC
  // revisions. Adapt these helpers to the pinned revision (see
  // BUILD_WINDOWS.md "pinning the revision").
  std::unique_ptr<webrtc::AudioBuffer> MakeBuffer() {
    // Historical shape:
    //   AudioBuffer(sample_rate_hz, num_channels,
    //               samples_per_split_channel, num_bands,
    //               samples_per_full_channel, num_full_channels)
    return std::make_unique<webrtc::AudioBuffer>(
        sample_rate_hz, /*num_channels=*/1,
        frame_samples, /*num_bands=*/1,
        frame_samples, /*num_full_channels=*/1);
  }

  void FillBuffer(webrtc::AudioBuffer* buffer, const int16_t* samples) {
    webrtc::StreamConfig config(sample_rate_hz, /*num_channels=*/1);
    const int16_t* channel_data[1] = {samples};
    buffer->CopyFrom(channel_data, config);
  }

  void ReadBuffer(webrtc::AudioBuffer* buffer, int16_t* out) {
    webrtc::StreamConfig config(sample_rate_hz, /*num_channels=*/1);
    int16_t* channel_data[1] = {out};
    buffer->CopyTo(config, channel_data);
  }

  // Runs one 10 ms frame through AEC3.
  void Process10ms(const int16_t* mic, const int16_t* render, int16_t* out) {
    auto render_buffer = MakeBuffer();
    auto capture_buffer = MakeBuffer();
    auto linear_buffer = MakeBuffer();
    FillBuffer(render_buffer.get(), render);
    FillBuffer(capture_buffer.get(), mic);
    aec->AnalyzeRender(*render_buffer);
    aec->AnalyzeCapture(*capture_buffer);
    aec->ProcessCapture(*capture_buffer, *linear_buffer,
                        /*echo_path_change=*/false);
    ReadBuffer(linear_buffer.get(), out);
  }
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_voicebrainlive_desktop_platform_audio_WebRtcAec3_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jint sampleRateHz) {
  auto* state = new (std::nothrow) Aec3State();
  if (!state) return 0;
  state->sample_rate_hz = sampleRateHz;
  state->frame_samples = sampleRateHz / (1000 / kAec3FrameMs);
  webrtc::EchoCanceller3Config config;  // defaults are the recommended tuning
  state->aec = std::make_unique<webrtc::EchoCanceller3>(
      config, webrtc::EchoCanceller3Config(), sampleRateHz,
      /*num_render_channels=*/1, /*num_capture_channels=*/1);
  return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_example_voicebrainlive_desktop_platform_audio_WebRtcAec3_nativeProcess(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray micFrame,
    jbyteArray renderFrame, jbyteArray outFrame) {
  auto* state = reinterpret_cast<Aec3State*>(handle);
  if (!state || !state->aec) return;

  const jsize len = env->GetArrayLength(micFrame);
  if (len != env->GetArrayLength(renderFrame) ||
      len != env->GetArrayLength(outFrame) || (len & 1) != 0) {
    return;  // contract violation: leave outFrame untouched
  }
  const int samples = len / 2;

  std::vector<int16_t> mic(samples), render(samples), out(samples, 0);
  env->GetByteArrayRegion(micFrame, 0, len,
                          reinterpret_cast<jbyte*>(mic.data()));
  env->GetByteArrayRegion(renderFrame, 0, len,
                          reinterpret_cast<jbyte*>(render.data()));

  // Feed both streams in lockstep, 10 ms at a time, carrying remainders.
  state->mic_carry.insert(state->mic_carry.end(), mic.begin(), mic.end());
  state->render_carry.insert(state->render_carry.end(), render.begin(), render.end());
  int out_pos = 0;
  const int F = state->frame_samples;
  std::vector<int16_t> clean(F);
  while (state->mic_carry.size() >= static_cast<size_t>(F) &&
         state->render_carry.size() >= static_cast<size_t>(F)) {
    state->Process10ms(state->mic_carry.data(), state->render_carry.data(),
                       clean.data());
    const int copy_n = std::min(F, samples - out_pos);
    std::copy(clean.begin(), clean.begin() + copy_n, out.begin() + out_pos);
    out_pos += copy_n;
    state->mic_carry.erase(state->mic_carry.begin(),
                           state->mic_carry.begin() + F);
    state->render_carry.erase(state->render_carry.begin(),
                              state->render_carry.begin() + F);
  }
  // Any trailing partial frame (< 10 ms) is passed through unprocessed so
  // audio is never dropped; it joins the next call's carry buffers.
  if (out_pos < samples) {
    std::copy(mic.begin() + out_pos, mic.end(), out.begin() + out_pos);
  }

  env->SetByteArrayRegion(outFrame, 0, len,
                          reinterpret_cast<const jbyte*>(out.data()));
}

JNIEXPORT void JNICALL
Java_com_example_voicebrainlive_desktop_platform_audio_WebRtcAec3_nativeReset(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* state = reinterpret_cast<Aec3State*>(handle);
  if (!state || !state->aec) return;
  state->aec->Initialize();
  state->mic_carry.clear();
  state->render_carry.clear();
}

JNIEXPORT void JNICALL
Java_com_example_voicebrainlive_desktop_platform_audio_WebRtcAec3_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  delete reinterpret_cast<Aec3State*>(handle);
}

}  // extern "C"
