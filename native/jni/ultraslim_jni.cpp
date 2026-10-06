#include <jni.h>

#include "transcode.h"

namespace {

jobject toProbeResult(JNIEnv* env, const ultraslim::ProbeResult& r) {
  jclass cls = env->FindClass("app/ultraslim/bridge/ProbeResult");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;ZIIIIIZFF)V");
  jstring err = env->NewStringUTF(r.error.c_str());
  return env->NewObject(cls, ctor, r.errorCode, err, r.hasGainMap ? JNI_TRUE : JNI_FALSE,
                        r.container, r.width, r.height, r.gainmapWidth, r.gainmapHeight,
                        r.hasExif ? JNI_TRUE : JNI_FALSE, r.maxContentBoost, r.hdrCapacityMax);
}

jobject toTranscodeResult(JNIEnv* env, const ultraslim::TranscodeResult& r) {
  jclass cls = env->FindClass("app/ultraslim/bridge/TranscodeResult");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;ZIIIIJJD)V");
  jstring err = env->NewStringUTF(r.error.c_str());
  return env->NewObject(cls, ctor, r.errorCode, err,
                        r.inputHadGainMap ? JNI_TRUE : JNI_FALSE, r.width, r.height,
                        r.gainmapWidth, r.gainmapHeight, static_cast<jlong>(r.inputBytes),
                        static_cast<jlong>(r.outputBytes), static_cast<jdouble>(r.durationMs));
}

}  // namespace

extern "C" {

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeProbe(JNIEnv* env,
                                                                               jclass /*unused*/,
                                                                               jint fd) {
  return toProbeResult(env, ultraslim::probeImage(static_cast<int>(fd)));
}

JNIEXPORT jobject JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeTranscode(
    JNIEnv* env, jclass /*unused*/, jint inFd, jint outFd, jint outCodec, jint quality,
    jint gainmapQuality, jint gainmapScale, jboolean multiChannelGainmap, jboolean keepExif,
    jboolean allowSdrTranscode, jint speedLevel, jint resizePercent) {
  ultraslim::TranscodeOptions o;
  o.outCodec = outCodec;
  o.quality = quality;
  o.gainmapQuality = gainmapQuality;
  o.gainmapScale = gainmapScale;
  o.multiChannelGainmap = multiChannelGainmap == JNI_TRUE;
  o.keepExif = keepExif == JNI_TRUE;
  o.allowSdrTranscode = allowSdrTranscode == JNI_TRUE;
  o.speedLevel = speedLevel;
  o.resizePercent = resizePercent;
  return toTranscodeResult(env, ultraslim::transcodeImage(inFd, outFd, o));
}

JNIEXPORT jstring JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeVersion(JNIEnv* env,
                                                                                 jclass /*unused*/) {
  return env->NewStringUTF("libultrahdr 2.0.2 / libheif / x265 / SVT-AV1 / aom / libde265 / libjpeg-turbo");
}

JNIEXPORT jbyteArray JNICALL Java_app_ultraslim_bridge_UltraHdrBridge_nativeDecodePreview(
    JNIEnv* env, jclass /*unused*/, jint fd, jint maxDim) {
  std::vector<uint8_t> jpeg = ultraslim::decodePreviewJpeg(static_cast<int>(fd), maxDim);
  if (jpeg.empty()) return nullptr;
  jbyteArray arr = env->NewByteArray(static_cast<jsize>(jpeg.size()));
  if (arr == nullptr) return nullptr;
  env->SetByteArrayRegion(arr, 0, static_cast<jsize>(jpeg.size()),
                          reinterpret_cast<const jbyte*>(jpeg.data()));
  return arr;
}

}  // extern "C"
