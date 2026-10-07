#pragma once

#include <string>
#include <vector>

namespace ultraslim {

struct ProbeResult {
  int errorCode = 0;  // 0 = ok, otherwise failed
  std::string error;
  bool hasGainMap = false;
  int container = -1;  // 0 = JPEG, 1 = HEIF, 2 = AVIF, 3 = JXL, -1 = unknown
  int width = 0;
  int height = 0;
  int gainmapWidth = 0;
  int gainmapHeight = 0;
  bool hasExif = false;
  float maxContentBoost = 1.0f;
  float hdrCapacityMax = 1.0f;
};

struct TranscodeOptions {
  int outCodec = 1;  // 0 = JPEG, 1 = HEIF, 2 = AVIF, 3 = JXL
  int quality = 90;
  // Legacy rebuild parameters are only used by host fixture generation.
  bool preserveGainMap = true;
  int gainmapQuality = 60;
  int gainmapScale = 4;
  bool multiChannelGainmap = true;
  bool keepExif = true;
  bool keepLocation = true;
  bool allowSdrTranscode = true;  // re-encode plain SDR images too (no gain map)
  int speedLevel = 1;             // 0 = 画质优先, 1 = 平衡, 2 = 速度优先
  int resizePercent = 100;        // 25..100, output resolution as % of source
  // Threads for one encoder instance. 0 = half the machine, so two parallel jobs fit.
  int codecThreads = 0;
};

struct TranscodeResult {
  int errorCode = 0;
  std::string error;
  bool inputHadGainMap = false;
  int width = 0;
  int height = 0;
  int gainmapWidth = 0;
  int gainmapHeight = 0;
  long inputBytes = 0;
  long outputBytes = 0;
  double durationMs = 0.0;
};

ProbeResult probeImage(int fd);
TranscodeResult transcodeImage(int inFd, int outFd, const TranscodeOptions& options);

/**
 * Decodes the primary (SDR) rendition of an image and re-encodes it as a small
 * JPEG, for previews of files the platform decoder may not understand
 * (e.g. ISO 21496-1 gain map HEIC). Returns an empty vector on failure.
 */
std::vector<uint8_t> decodePreviewJpeg(int fd, int maxDim, int quality = 82);

/** Debug: decode the primary image with heif_chroma_undefined and return the raw Y plane. */
std::vector<uint8_t> debugDecodePlaneY(int fd, int& w, int& h);

}  // namespace ultraslim
