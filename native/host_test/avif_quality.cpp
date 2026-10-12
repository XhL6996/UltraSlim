// End-to-end quality regression: the top AVIF setting must retain fine texture,
// through both the SDR and gain-map-preserving paths (SVT and AOM fallback).
#include "../jni/transcode.h"
#include <ultrahdr_api.h>
#include <libheif/heif.h>
#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstring>
#include <jpeglib.h>
#include <fcntl.h>
#include <unistd.h>

static void check(bool ok, const char* message) {
  if (!ok) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}

static std::vector<uint8_t> textureJpeg(int w, int h) {
  jpeg_compress_struct c{};
  jpeg_error_mgr error{};
  c.err = jpeg_std_error(&error);
  jpeg_create_compress(&c);
  unsigned char* data = nullptr;
  unsigned long size = 0;
  jpeg_mem_dest(&c, &data, &size);
  c.image_width = w; c.image_height = h;
  c.input_components = 1; c.in_color_space = JCS_GRAYSCALE;
  jpeg_set_defaults(&c); jpeg_set_quality(&c, 97, TRUE);
  jpeg_start_compress(&c, TRUE);
  uint32_t rng = 12345;
  std::vector<uint8_t> row(w);
  while (c.next_scanline < c.image_height) {
    for (int x = 0; x < w; ++x) {
      rng = rng * 1664525u + 1013904223u;
      // Fine grain over gradients and edges; no chroma-subsampling penalty.
      row[x] = 32 + (x / 8 + c.next_scanline / 8) % 160 + (rng >> 27);
    }
    JSAMPROW scanline = row.data(); jpeg_write_scanlines(&c, &scanline, 1);
  }
  jpeg_finish_compress(&c);
  std::vector<uint8_t> result(data, data + size);
  free(data); jpeg_destroy_compress(&c);
  return result;
}

static std::vector<uint8_t> decodeJpeg(const std::vector<uint8_t>& data) {
  jpeg_decompress_struct c{}; jpeg_error_mgr error{};
  c.err = jpeg_std_error(&error); jpeg_create_decompress(&c);
  jpeg_mem_src(&c, data.data(), data.size()); jpeg_read_header(&c, TRUE);
  c.out_color_space = JCS_RGB; jpeg_start_decompress(&c);
  std::vector<uint8_t> pixels(size_t(c.output_width) * c.output_height * 3);
  while (c.output_scanline < c.output_height) {
    JSAMPROW row = pixels.data() + size_t(c.output_scanline) * c.output_width * 3;
    jpeg_read_scanlines(&c, &row, 1);
  }
  jpeg_finish_decompress(&c); jpeg_destroy_decompress(&c);
  return pixels;
}

static void write(const std::string& path, const uint8_t* data, size_t size) {
  FILE* f = fopen(path.c_str(), "wb"); check(f != nullptr, "open fixture");
  check(fwrite(data, 1, size, f) == size, "write fixture");
  check(fclose(f) == 0, "close fixture");
}

static void fixture(const std::string& path, const std::vector<uint8_t>& base, bool hdr) {
  if (!hdr) { write(path, base.data(), base.size()); return; }
  auto map = textureJpeg(80, 60);
  auto* e = uhdr_create_encoder(); check(e != nullptr, "create encoder");
  uhdr_compressed_image_t b{};
  b.data = const_cast<uint8_t*>(base.data()); b.data_sz = b.capacity = base.size();
  b.cg = UHDR_CG_BT_709; b.ct = UHDR_CT_SRGB; b.range = UHDR_CR_FULL_RANGE;
  auto g = b; g.data = map.data(); g.data_sz = g.capacity = map.size();
  uhdr_gainmap_metadata_t m{};
  for (int c = 0; c < 3; ++c) {
    m.min_content_boost[c] = 1; m.max_content_boost[c] = 4; m.gamma[c] = 1;
  }
  m.hdr_capacity_min = 1; m.hdr_capacity_max = 4; m.use_base_cg = 1;
  check(uhdr_enc_set_compressed_image(e, &b, UHDR_BASE_IMG).error_code == UHDR_CODEC_OK, "base");
  check(uhdr_enc_set_gainmap_image(e, &g, &m).error_code == UHDR_CODEC_OK, "map");
  check(uhdr_encode(e).error_code == UHDR_CODEC_OK, "mux");
  auto* result = uhdr_get_encoded_stream(e);
  write(path, static_cast<const uint8_t*>(result->data), result->data_sz);
  uhdr_release_encoder(e);
}

static double measure(const std::string& input, const std::string& output,
                      const std::vector<uint8_t>& reference, int w, int h,
                      bool hdr, int quality, int speed) {
  int in = open(input.c_str(), O_RDONLY);
  int out = open(output.c_str(), O_RDWR | O_CREAT | O_TRUNC, 0600);
  check(in >= 0 && out >= 0, "open conversion");
  ultraslim::TranscodeOptions options;
  options.outCodec = 2; options.quality = quality; options.speedLevel = speed;
  options.codecThreads = 2; options.retrySmaller = false;
  auto result = ultraslim::transcodeImage(in, out, options);
  close(in); close(out);
  if (result.errorCode) fprintf(stderr, "%s\n", result.error.c_str());
  check(result.errorCode == 0, "AVIF conversion");
  auto* ctx = heif_context_alloc();
  check(heif_context_read_from_file(ctx, output.c_str(), nullptr).code == heif_error_Ok, "read AVIF");
  heif_image_handle* handle = nullptr;
  check(heif_context_get_primary_image_handle(ctx, &handle).code == heif_error_Ok, "primary");
  check(heif_image_handle_get_width(handle) == w && heif_image_handle_get_height(handle) == h, "dimensions");
  heif_image* image = nullptr;
  check(heif_decode_image(handle, &image, heif_colorspace_RGB, heif_chroma_interleaved_RGB,
                          nullptr).code == heif_error_Ok, "decode AVIF");
  int stride = 0;
  const uint8_t* pixels = heif_image_get_plane_readonly(image, heif_channel_interleaved, &stride);
  check(pixels != nullptr, "RGB plane");
  double sum = 0;
  for (int y = 0; y < h; ++y) for (int x = 0; x < w * 3; ++x) {
    double delta = pixels[y * stride + x] - reference[size_t(y) * w * 3 + x];
    sum += delta * delta;
  }
  heif_image_release(image); heif_image_handle_release(handle); heif_context_free(ctx);
  int fd = open(output.c_str(), O_RDONLY);
  auto probe = ultraslim::probeImage(fd); close(fd);
  check(probe.errorCode == 0 && probe.hasGainMap == hdr, "HDR state retained");
  if (hdr) check(probe.gainmapWidth == 80 && probe.gainmapHeight == 60 &&
                 std::abs(probe.maxContentBoost - 4) < 0.001, "gain-map geometry and headroom");
  double psnr = 10 * std::log10(255.0 * 255 / (sum / reference.size()));
  printf("%s q=%d speed=%d: %.3f dB, %ld bytes\n", hdr ? "HDR" : "SDR", quality, speed, psnr, result.outputBytes);
  return psnr;
}

int main(int argc, char** argv) {
  check(argc == 2, "usage: avif_quality <existing-output-dir>");
  const std::string dir = argv[1];
  for (bool hdr : {false, true}) for (int w : {320, 321}) {
    const int h = 240;
    const auto jpeg = textureJpeg(w, h), reference = decodeJpeg(jpeg);
    const auto stem = dir + "/quality-" + (hdr ? "hdr-" : "sdr-") + std::to_string(w);
    fixture(stem + ".jpg", jpeg, hdr);
    const auto medium = measure(stem + ".jpg", stem + "-92.avif", reference, w, h, hdr, 92, 1);
    for (int speed : {0, 1, 2}) {
      auto high = measure(stem + ".jpg", stem + "-100-" + std::to_string(speed) + ".avif",
                          reference, w, h, hdr, 100, speed);
      // RGB/YUV rounding is allowed; a capped AV1 quantizer loses this texture.
      check(high > 46, "quality 100 must retain texture at every speed");
      check(high > medium + 1, "the top of the quality slider must improve fidelity");
    }
  }
  puts("PASS: full-range AVIF quality, SDR/HDR, SVT/AOM, all speed levels");
}
