#include <fcntl.h>
#include <libgen.h>
#include <string.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <string>

#include "../jni/transcode.h"

namespace {

void printUsage(const char* prog) {
  fprintf(stderr,
          "usage: %s <input> <output.(heic|avif|jpg|jxl)> [quality=90] [gainmapScale=4] [gainmapQuality=60]\n",
          prog);
}

bool endsWith(const std::string& s, const char* suffix) {
  const size_t n = strlen(suffix);
  return s.size() >= n && s.compare(s.size() - n, n, suffix) == 0;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    printUsage(argv[0]);
    return 2;
  }
  const char* input = argv[1];
  const char* output = argv[2];

  int inFd = open(input, O_RDONLY);
  if (inFd < 0) {
    fprintf(stderr, "cannot open %s: %s\n", input, strerror(errno));
    return 1;
  }

  ultraslim::ProbeResult p = ultraslim::probeImage(inFd);
  printf("== probe ==\n");
  printf("  errorCode     : %d %s\n", p.errorCode, p.error.c_str());
  printf("  container     : %d (0=jpeg 1=heif 2=avif 3=jxl)\n", p.container);
  printf("  size          : %dx%d\n", p.width, p.height);
  printf("  hasGainMap    : %s\n", p.hasGainMap ? "yes" : "no");
  printf("  gainmap       : %dx%d\n", p.gainmapWidth, p.gainmapHeight);
  printf("  hasExif       : %s\n", p.hasExif ? "yes" : "no");
  printf("  maxBoost      : %.2f  hdrCapacityMax: %.2f\n", p.maxContentBoost, p.hdrCapacityMax);

  std::string outPathArg(output);
  if (endsWith(outPathArg, ".yplane")) {
    int w = 0, h = 0;
    std::vector<uint8_t> plane = ultraslim::debugDecodePlaneY(inFd, w, h);
    printf("yplane %dx%d bytes=%zu\n", w, h, plane.size());
    if (!plane.empty()) {
      int outFd2 = open(output, O_WRONLY | O_CREAT | O_TRUNC, 0644);
      write(outFd2, plane.data(), plane.size());
      close(outFd2);
    }
    close(inFd);
    return plane.empty() ? 1 : 0;
  }
  if (endsWith(outPathArg, ".prev.jpg")) {
    std::vector<uint8_t> jpeg = ultraslim::decodePreviewJpeg(inFd, 512);
    printf("== decodePreviewJpeg ==\n");
    printf("  bytes         : %zu\n", jpeg.size());
    if (!jpeg.empty()) {
      int outFd2 = open(output, O_RDWR | O_CREAT | O_TRUNC, 0644);
      write(outFd2, jpeg.data(), jpeg.size());
      close(outFd2);
    }
    close(inFd);
    return jpeg.empty() ? 1 : 0;
  }

  if (p.errorCode != 0) {
    close(inFd);
    return 1;
  }

  ultraslim::TranscodeOptions o;
  std::string outPath(output);
  if (endsWith(outPath, ".avif")) {
    o.outCodec = 2;
  } else if (endsWith(outPath, ".heic") || endsWith(outPath, ".heif")) {
    o.outCodec = 1;
  } else if (endsWith(outPath, ".jpg") || endsWith(outPath, ".jpeg")) {
    o.outCodec = 0;
  } else if (endsWith(outPath, ".jxl")) {
    o.outCodec = 3;
  } else {
    fprintf(stderr, "output must end with .heic, .avif, .jpg or .jxl\n");
    close(inFd);
    return 2;
  }
  if (argc > 3) o.quality = atoi(argv[3]);
  if (argc > 4) o.gainmapScale = atoi(argv[4]);
  if (argc > 5) o.gainmapQuality = atoi(argv[5]);
  if (argc > 6) o.speedLevel = atoi(argv[6]);
  if (argc > 7) o.resizePercent = atoi(argv[7]);
  if (argc > 8) o.preserveGainMap = atoi(argv[8]) != 0;
  if (argc > 9) o.codecThreads = atoi(argv[9]);

  if (argc > 10) o.retrySmaller = atoi(argv[10]) != 0;

  int outFd = open(output, O_RDWR | O_CREAT | O_TRUNC, 0644);
  if (outFd < 0) {
    fprintf(stderr, "cannot open %s: %s\n", output, strerror(errno));
    close(inFd);
    return 1;
  }

  ultraslim::TranscodeResult r = ultraslim::transcodeImage(inFd, outFd, o);
  printf("== transcode ==\n");
  printf("  errorCode     : %d %s\n", r.errorCode, r.error.c_str());
  printf("  had gainmap   : %s\n", r.inputHadGainMap ? "yes" : "no");
  printf("  in bytes      : %ld\n", r.inputBytes);
  printf("  out bytes     : %ld\n", r.outputBytes);
  printf("  ratio         : %.1f%%\n", r.inputBytes > 0 ? 100.0 * r.outputBytes / r.inputBytes : 0.0);
  printf("  duration      : %.0f ms\n", r.durationMs);

  close(inFd);
  close(outFd);
  return r.errorCode;
}
