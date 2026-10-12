// Dumps gainmap pixel stats from a HEIC/AVIF gain map file or Ultra HDR JPEG.
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <libheif/heif.h>
#include "ultrahdr_api.h"

namespace {
bool dumpJpegMap(const char* path, const std::vector<uint8_t>& data) {
  uhdr_codec_private_t* dec = uhdr_create_decoder();
  uhdr_compressed_image_t img {};
  img.data = const_cast<uint8_t*>(data.data());
  img.data_sz = img.capacity = data.size();
  img.cg = UHDR_CG_UNSPECIFIED;
  img.ct = UHDR_CT_UNSPECIFIED;
  img.range = UHDR_CR_UNSPECIFIED;
  if (uhdr_dec_set_image(dec, &img).error_code != UHDR_CODEC_OK) return false;
  if (uhdr_dec_probe(dec).error_code != UHDR_CODEC_OK) return false;
  uhdr_mem_block_t* block = uhdr_dec_get_gainmap_image(dec);
  if (block == nullptr || block->data == nullptr) return false;
  std::string outPath = std::string(path) + ".map.jpg";
  FILE* f = fopen(outPath.c_str(), "wb");
  fwrite(block->data, 1, block->data_sz, f);
  fclose(f);
  printf("gainmap jpeg written: %s (%zu bytes)\n", outPath.c_str(), block->data_sz);
  uhdr_release_decoder(dec);
  return true;
}
}  // namespace

int main(int argc, char** argv) {
  if (argc < 2) return 2;
  FILE* f = fopen(argv[1], "rb");
  if (!f) return 1;
  fseek(f, 0, SEEK_END);
  long sz = ftell(f);
  fseek(f, 0, SEEK_SET);
  std::vector<uint8_t> data(sz);
  fread(data.data(), 1, sz, f);
  fclose(f);

  if (sz >= 2 && data[0] == 0xFF && data[1] == 0xD8) {
    dumpJpegMap(argv[1], data);
    return 0;
  }

  heif_context* ctx = heif_context_alloc();
  if (heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr).code !=
      heif_error_Ok) {
    printf("read fail\n");
    return 1;
  }
  heif_image_handle* handle = nullptr;
  heif_context_get_primary_image_handle(ctx, &handle);
  heif_image_handle* gm = nullptr;
  heif_error e = heif_image_handle_get_gain_map_image_handle(handle, &gm);
  if (e.code != heif_error_Ok || !gm) {
    printf("no gainmap: %s\n", e.message);
    return 1;
  }
  size_t metaSize = heif_image_handle_get_gain_map_metadata_size(handle);
  std::vector<uint8_t> meta(metaSize);
  heif_image_handle_get_gain_map_metadata(handle, meta.data());
  printf("iso metadata (%zu bytes): ", metaSize);
  for (size_t i = 0; i < metaSize && i < 64; i++) printf("%02x", meta[i]);
  printf("\n");

  auto dump = [&](heif_image_handle* h, const char* name) {
    heif_image* img = nullptr;
    e = heif_decode_image(h, &img, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, nullptr);
    if (e.code != heif_error_Ok) {
      printf("%s decode fail: %s\n", name, e.message);
      return;
    }
    int w = heif_image_get_width(img, heif_channel_interleaved);
    int hh = heif_image_get_height(img, heif_channel_interleaved);
    int stride = 0;
    const uint8_t* p = heif_image_get_plane_readonly(img, heif_channel_interleaved, &stride);
    long sum[3] = {0, 0, 0};
    int mx[3] = {0, 0, 0};
    for (int y = 0; y < hh; y++)
      for (int x = 0; x < w; x++)
        for (int c = 0; c < 3; c++) {
          int v = p[y * stride + x * 4 + c];
          sum[c] += v;
          if (v > mx[c]) mx[c] = v;
        }
    printf("%s %dx%d mean=[%.1f %.1f %.1f] max=[%d %d %d]\n", name, w, hh,
           (double)sum[0] / (w * hh), (double)sum[1] / (w * hh), (double)sum[2] / (w * hh), mx[0],
           mx[1], mx[2]);
    heif_image_release(img);
  };
  dump(handle, "base");
  dump(gm, "gainmap");
  return 0;
}
