#include "transcode.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <vector>

#include <jpeglib.h>
#include <libheif/heif.h>

#include "ultrahdr_api.h"

// strong definition; libultrahdr's patched HEIF/AVIF encoders read this via a weak default
int ultraslim_codec_speed_level = 1;

namespace ultraslim {
namespace {

std::vector<uint8_t> readAll(int fd) {
  std::vector<uint8_t> out;
  struct stat st {};
  if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
    out.reserve(static_cast<size_t>(st.st_size));
  }
  lseek(fd, 0, SEEK_SET);
  uint8_t chunk[1 << 16];
  ssize_t n;
  while ((n = read(fd, chunk, sizeof(chunk))) > 0) {
    out.insert(out.end(), chunk, chunk + n);
  }
  return out;
}

bool writeAll(int fd, const void* data, size_t size) {
  const uint8_t* p = static_cast<const uint8_t*>(data);
  size_t left = size;
  while (left > 0) {
    ssize_t n = write(fd, p, left);
    if (n <= 0) return false;
    p += n;
    left -= static_cast<size_t>(n);
  }
  return true;
}

int sniffContainer(const uint8_t* d, size_t n) {
  if (n >= 2 && d[0] == 0xFF && d[1] == 0xD8) return 0;  // JPEG
  if (n >= 12 && memcmp(d + 4, "ftyp", 4) == 0) {
    for (size_t i = 8; i + 4 <= n && i < 64; i += 4) {
      if (memcmp(d + i, "avif", 4) == 0 || memcmp(d + i, "avis", 4) == 0) return 2;
    }
    return 1;  // some HEIF flavour
  }
  return -1;
}

std::string describe(const uhdr_error_info_t& e, const char* what) {
  std::string s(what);
  if (e.has_detail) {
    s += ": ";
    s += e.detail;
  }
  return s;
}

struct DecoderDeleter {
  void operator()(uhdr_codec_private_t* p) const { uhdr_release_decoder(p); }
};
struct EncoderDeleter {
  void operator()(uhdr_codec_private_t* p) const { uhdr_release_encoder(p); }
};
using DecoderPtr = std::unique_ptr<uhdr_codec_private_t, DecoderDeleter>;
using EncoderPtr = std::unique_ptr<uhdr_codec_private_t, EncoderDeleter>;

// ---------------------------------------------------------------- SDR helpers

struct SdrImage {
  std::vector<uint8_t> rgba;  // RGBA8888
  int width = 0;
  int height = 0;
  std::vector<uint8_t> exif;  // APP1 payload (may be empty)
};

bool decodeJpegSdr(const std::vector<uint8_t>& data, SdrImage& out, std::string& err) {
  jpeg_decompress_struct cinfo {};
  jpeg_error_mgr jerr {};
  cinfo.err = jpeg_std_error(&jerr);
  jpeg_create_decompress(&cinfo);
  jpeg_mem_src(&cinfo, data.data(), data.size());
  jpeg_save_markers(&cinfo, JPEG_APP0 + 1, 0xFFFF);
  if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
    jpeg_destroy_decompress(&cinfo);
    err = "无法解析 JPEG";
    return false;
  }
  cinfo.out_color_space = JCS_EXT_RGBA;
  jpeg_start_decompress(&cinfo);
  out.width = static_cast<int>(cinfo.output_width);
  out.height = static_cast<int>(cinfo.output_height);
  const size_t stride = static_cast<size_t>(out.width) * 4;
  out.rgba.resize(stride * out.height);
  while (cinfo.output_scanline < cinfo.output_height) {
    JSAMPROW row = out.rgba.data() + cinfo.output_scanline * stride;
    if (jpeg_read_scanlines(&cinfo, &row, 1) != 1) break;
  }
  jpeg_finish_decompress(&cinfo);
  for (jpeg_saved_marker_ptr m = cinfo.marker_list; m != nullptr; m = m->next) {
    if (m->marker == JPEG_APP0 + 1 && m->data_length >= 6 &&
        memcmp(m->data, "Exif\0\0", 6) == 0) {
      out.exif.assign(m->data, m->data + m->data_length);
      break;
    }
  }
  jpeg_destroy_decompress(&cinfo);
  return true;
}

bool probeJpegDims(const std::vector<uint8_t>& data, int& w, int& h, bool& hasExif) {
  jpeg_decompress_struct cinfo {};
  jpeg_error_mgr jerr {};
  cinfo.err = jpeg_std_error(&jerr);
  jpeg_create_decompress(&cinfo);
  jpeg_mem_src(&cinfo, data.data(), data.size());
  jpeg_save_markers(&cinfo, JPEG_APP0 + 1, 0xFFFF);
  const int rc = jpeg_read_header(&cinfo, TRUE);
  if (rc == JPEG_HEADER_OK) {
    w = static_cast<int>(cinfo.image_width);
    h = static_cast<int>(cinfo.image_height);
    for (jpeg_saved_marker_ptr m = cinfo.marker_list; m != nullptr; m = m->next) {
      if (m->marker == JPEG_APP0 + 1 && m->data_length >= 6 &&
          memcmp(m->data, "Exif\0\0", 6) == 0) {
        hasExif = true;
        break;
      }
    }
  }
  jpeg_destroy_decompress(&cinfo);
  return rc == JPEG_HEADER_OK;
}

bool probeHeifDims(const std::vector<uint8_t>& data, int& w, int& h, bool& hasExif) {
  heif_context* ctx = heif_context_alloc();
  heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    heif_context_free(ctx);
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code == heif_error_Ok && handle != nullptr) {
    w = heif_image_handle_get_width(handle);
    h = heif_image_handle_get_height(handle);
    heif_item_id ids[4];
    hasExif = heif_image_handle_get_list_of_metadata_block_IDs(handle, "Exif", ids, 4) > 0;
  }
  if (handle) heif_image_handle_release(handle);
  heif_context_free(ctx);
  return e.code == heif_error_Ok && handle != nullptr;
}

bool decodeHeifSdr(const std::vector<uint8_t>& data, SdrImage& out, std::string& err) {
  heif_context* ctx = heif_context_alloc();
  heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message;
    heif_context_free(ctx);
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code != heif_error_Ok) {
    err = e.message;
    heif_context_free(ctx);
    return false;
  }
  heif_image* himg = nullptr;
  e = heif_decode_image(handle, &himg, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message;
    heif_image_handle_release(handle);
    heif_context_free(ctx);
    return false;
  }
  out.width = heif_image_get_width(himg, heif_channel_interleaved);
  out.height = heif_image_get_height(himg, heif_channel_interleaved);
  int srcStride = 0;
  const uint8_t* plane = heif_image_get_plane_readonly(himg, heif_channel_interleaved, &srcStride);
  const size_t rowBytes = static_cast<size_t>(out.width) * 4;
  out.rgba.resize(rowBytes * out.height);
  for (int y = 0; y < out.height; y++) {
    memcpy(out.rgba.data() + rowBytes * y, plane + static_cast<size_t>(srcStride) * y, rowBytes);
  }
  heif_image_release(himg);
  heif_image_handle_release(handle);
  heif_context_free(ctx);
  return true;
}

heif_error fdWriteCallback(heif_context* /*ctx*/, const void* data, size_t size, void* userdata) {
  const int fd = *static_cast<int*>(userdata);
  if (writeAll(fd, data, size)) {
    return heif_error {heif_error_Ok, heif_suberror_Unspecified, nullptr};
  }
  return heif_error {heif_error_Encoding_error, heif_suberror_Cannot_write_output_data,
                     "write failed"};
}

// Bilinear downscale of packed RGBA8888.
SdrImage resizeRgba(const SdrImage& src, int newW, int newH) {
  SdrImage out;
  out.width = newW;
  out.height = newH;
  out.exif = src.exif;
  out.rgba.resize(static_cast<size_t>(newW) * newH * 4);
  const int sw = src.width, sh = src.height;
  for (int y = 0; y < newH; y++) {
    const float fy = (y + 0.5f) * sh / newH - 0.5f;
    const int y0 = std::max(0, static_cast<int>(floorf(fy)));
    const int y1 = std::min(sh - 1, y0 + 1);
    const float wy = fy - y0;
    for (int x = 0; x < newW; x++) {
      const float fx = (x + 0.5f) * sw / newW - 0.5f;
      const int x0 = std::max(0, static_cast<int>(floorf(fx)));
      const int x1 = std::min(sw - 1, x0 + 1);
      const float wx = fx - x0;
      const uint8_t* p00 = src.rgba.data() + (static_cast<size_t>(y0) * sw + x0) * 4;
      const uint8_t* p01 = src.rgba.data() + (static_cast<size_t>(y0) * sw + x1) * 4;
      const uint8_t* p10 = src.rgba.data() + (static_cast<size_t>(y1) * sw + x0) * 4;
      const uint8_t* p11 = src.rgba.data() + (static_cast<size_t>(y1) * sw + x1) * 4;
      uint8_t* d = out.rgba.data() + (static_cast<size_t>(y) * newW + x) * 4;
      for (int c = 0; c < 4; c++) {
        const float v = p00[c] * (1 - wx) * (1 - wy) + p01[c] * wx * (1 - wy) +
                        p10[c] * (1 - wx) * wy + p11[c] * wx * wy;
        d[c] = static_cast<uint8_t>(std::min(255.f, std::max(0.f, v + 0.5f)));
      }
    }
  }
  return out;
}

// Encodes an SDR RGBA8888 image to JPEG in memory with libjpeg-turbo.
std::vector<uint8_t> encodeJpegToMem(const SdrImage& img, int quality) {
  std::vector<uint8_t> out;
  jpeg_compress_struct cinfo {};
  jpeg_error_mgr jerr {};
  cinfo.err = jpeg_std_error(&jerr);
  jpeg_create_compress(&cinfo);
  uint8_t* mem = nullptr;
  unsigned long memSize = 0;
  jpeg_mem_dest(&cinfo, &mem, &memSize);
  cinfo.image_width = img.width;
  cinfo.image_height = img.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, quality, TRUE);
  jpeg_start_compress(&cinfo, TRUE);
  const size_t stride = static_cast<size_t>(img.width) * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = const_cast<uint8_t*>(img.rgba.data()) + cinfo.next_scanline * stride;
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  if (mem != nullptr && memSize > 0) out.assign(mem, mem + memSize);
  free(mem);
  return out;
}

// Encodes an SDR RGBA8888 image to JPEG with libjpeg-turbo.
int encodeJpegSdr(const SdrImage& img, int quality, bool keepExif, int outFd, std::string& err) {
  FILE* f = fdopen(dup(outFd), "wb");
  if (f == nullptr) {
    err = "无法打开输出文件";
    return 30;
  }
  jpeg_compress_struct cinfo {};
  jpeg_error_mgr jerr {};
  cinfo.err = jpeg_std_error(&jerr);
  jpeg_create_compress(&cinfo);
  jpeg_stdio_dest(&cinfo, f);
  cinfo.image_width = img.width;
  cinfo.image_height = img.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, quality, TRUE);
  jpeg_start_compress(&cinfo, TRUE);
  if (keepExif && !img.exif.empty()) {
    jpeg_write_marker(&cinfo, JPEG_APP0 + 1, img.exif.data(), img.exif.size());
  }
  const size_t stride = static_cast<size_t>(img.width) * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = const_cast<uint8_t*>(img.rgba.data()) + cinfo.next_scanline * stride;
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  fflush(f);
  fclose(f);  // closes only the dup'd fd
  return 0;
}

// Converts RGBA8888 to BT.601 limited-range YUV420 (the standard layout for SDR HEIF/AVIF).
void rgbaToYuv420(const SdrImage& img, std::vector<uint8_t>& yPlane, std::vector<uint8_t>& uPlane,
                  std::vector<uint8_t>& vPlane) {
  const int w = img.width, h = img.height;
  yPlane.resize(static_cast<size_t>(w) * h);
  uPlane.resize(static_cast<size_t>((w + 1) / 2) * ((h + 1) / 2));
  vPlane.resize(static_cast<size_t>((w + 1) / 2) * ((h + 1) / 2));
  auto px = [&](int x, int y) -> const uint8_t* {
    return img.rgba.data() + (static_cast<size_t>(y) * w + x) * 4;
  };
  for (int y = 0; y < h; y++) {
    for (int x = 0; x < w; x++) {
      const uint8_t* p = px(x, y);
      yPlane[static_cast<size_t>(y) * w + x] = static_cast<uint8_t>(
          ((66 * p[0] + 129 * p[1] + 25 * p[2] + 128) >> 8) + 16);
      if ((y % 2 == 0) && (x % 2 == 0)) {
        // average the 2x2 block (clamped at edges), then BT.601 chroma
        int r = 0, g = 0, b = 0, n = 0;
        for (int dy = 0; dy < 2; dy++) {
          for (int dx = 0; dx < 2; dx++) {
            const uint8_t* q = px(std::min(x + dx, w - 1), std::min(y + dy, h - 1));
            r += q[0];
            g += q[1];
            b += q[2];
            n++;
          }
        }
        r /= n;
        g /= n;
        b /= n;
        const size_t ci = static_cast<size_t>(y / 2) * ((w + 1) / 2) + (x / 2);
        uPlane[ci] = static_cast<uint8_t>(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
        vPlane[ci] = static_cast<uint8_t>(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
      }
    }
  }
}

// Encodes an SDR RGBA8888 image to HEIC/AVIF directly with libheif.
int encodeSdrWithLibheif(const SdrImage& img, int outCodec, int quality, bool keepExif, int outFd,
                         int speedLevel, std::string& err) {
  std::vector<uint8_t> yPlane, uPlane, vPlane;
  rgbaToYuv420(img, yPlane, uPlane, vPlane);

  heif_context* ctx = heif_context_alloc();
  heif_encoder* encoder = nullptr;
  heif_error e = heif_context_get_encoder_for_format(
      ctx, outCodec == 2 ? heif_compression_AV1 : heif_compression_HEVC, &encoder);
  if (e.code != heif_error_Ok) {
    err = std::string("获取编码器失败: ") + e.message;
    heif_context_free(ctx);
    return 20;
  }
  heif_encoder_set_lossy_quality(encoder, quality);
  heif_encoder_set_parameter(encoder, "chroma", "420");
  {
    const int level = speedLevel < 0 || speedLevel > 2 ? 1 : speedLevel;
    if (outCodec == 1) {
      static const char* kX265Presets[] = {"slow", "medium", "veryfast"};
      heif_encoder_set_parameter(encoder, "preset", kX265Presets[level]);
    } else {
      // aom (0..9) and svt-av1 (-2..13) both take "speed"; out-of-range fails silently
      static const int kSpeeds[] = {4, 6, 9};
      heif_encoder_set_parameter_integer(encoder, "speed", kSpeeds[level]);
    }
  }

  heif_image* himg = nullptr;
  e = heif_image_create(img.width, img.height, heif_colorspace_YCbCr, heif_chroma_420, &himg);
  if (e.code != heif_error_Ok) {
    err = std::string("创建图像失败: ") + e.message;
    heif_encoder_release(encoder);
    heif_context_free(ctx);
    return 21;
  }
  const int cw = (img.width + 1) / 2, ch = (img.height + 1) / 2;
  heif_image_add_plane(himg, heif_channel_Y, img.width, img.height, 8);
  heif_image_add_plane(himg, heif_channel_Cb, cw, ch, 8);
  heif_image_add_plane(himg, heif_channel_Cr, cw, ch, 8);
  struct PlaneDef {
    heif_channel channel;
    const std::vector<uint8_t>& data;
    int width;
  };
  for (const PlaneDef& def : std::vector<PlaneDef>{
           {heif_channel_Y, yPlane, img.width},
           {heif_channel_Cb, uPlane, cw},
           {heif_channel_Cr, vPlane, cw},
       }) {
    int dstStride = 0;
    uint8_t* dst = heif_image_get_plane(himg, def.channel, &dstStride);
    const int rows = def.channel == heif_channel_Y ? img.height : ch;
    for (int row = 0; row < rows; row++) {
      memcpy(dst + static_cast<size_t>(dstStride) * row,
             def.data.data() + static_cast<size_t>(def.width) * row, def.width);
    }
  }
  heif_color_profile_nclx nclx {};
  nclx.version = 1;
  nclx.color_primaries = heif_color_primaries_ITU_R_BT_709_5;
  nclx.transfer_characteristics = heif_transfer_characteristic_IEC_61966_2_1;
  nclx.matrix_coefficients = heif_matrix_coefficients_ITU_R_BT_601_6;
  nclx.full_range_flag = 0;
  heif_image_set_nclx_color_profile(himg, &nclx);

  heif_image_handle* outHandle = nullptr;
  e = heif_context_encode_image(ctx, himg, encoder, nullptr, &outHandle);
  if (e.code != heif_error_Ok) {
    err = std::string("编码失败: ") + e.message;
    heif_image_release(himg);
    heif_encoder_release(encoder);
    heif_context_free(ctx);
    return 22;
  }
  if (keepExif && !img.exif.empty() && outHandle != nullptr) {
    heif_context_add_exif_metadata(ctx, outHandle, img.exif.data(), img.exif.size());
  }
  heif_writer writer {};
  writer.writer_api_version = 1;
  writer.write = fdWriteCallback;
  e = heif_context_write(ctx, &writer, &outFd);
  if (e.code != heif_error_Ok) {
    err = std::string("写出文件失败: ") + e.message;
    if (outHandle) heif_image_handle_release(outHandle);
    heif_image_release(himg);
    heif_encoder_release(encoder);
    heif_context_free(ctx);
    return 23;
  }
  if (outHandle) heif_image_handle_release(outHandle);
  heif_image_release(himg);
  heif_encoder_release(encoder);
  heif_context_free(ctx);
  return 0;
}

// ------------------------------------------------------------------- uhdr HDR

// Probes with the uhdr decoder; returns true when the image parses and carries a gain map.
bool probeWithUhdr(const std::vector<uint8_t>& data, ProbeResult* probe, TranscodeResult* tr) {
  DecoderPtr dec(uhdr_create_decoder());
  if (!dec) return false;
  uhdr_compressed_image_t img {};
  img.data = const_cast<uint8_t*>(data.data());
  img.data_sz = img.capacity = data.size();
  img.cg = UHDR_CG_UNSPECIFIED;
  img.ct = UHDR_CT_UNSPECIFIED;
  img.range = UHDR_CR_UNSPECIFIED;
  if (uhdr_dec_set_image(dec.get(), &img).error_code != UHDR_CODEC_OK) return false;
  if (uhdr_dec_probe(dec.get()).error_code != UHDR_CODEC_OK) return false;
  const int w = uhdr_dec_get_image_width(dec.get());
  const int h = uhdr_dec_get_image_height(dec.get());
  const int gw = uhdr_dec_get_gainmap_width(dec.get());
  const int gh = uhdr_dec_get_gainmap_height(dec.get());
  const bool hasGainMap = gw > 0 && gh > 0;
  if (!hasGainMap) return false;
  if (probe != nullptr) {
    probe->width = w;
    probe->height = h;
    probe->gainmapWidth = gw;
    probe->gainmapHeight = gh;
    probe->hasGainMap = true;
    if (uhdr_mem_block_t* exif = uhdr_dec_get_exif(dec.get())) {
      probe->hasExif = exif->data != nullptr && exif->data_sz > 0;
    }
    if (uhdr_gainmap_metadata_t* meta = uhdr_dec_get_gainmap_metadata(dec.get())) {
      probe->maxContentBoost = meta->max_content_boost[0];
      probe->hdrCapacityMax = meta->hdr_capacity_max;
    }
  }
  if (tr != nullptr) {
    tr->width = w;
    tr->height = h;
    tr->gainmapWidth = gw;
    tr->gainmapHeight = gh;
    tr->inputHadGainMap = true;
  }
  return true;
}

}  // namespace

ProbeResult probeImage(int fd) {
  ProbeResult r;
  std::vector<uint8_t> data = readAll(fd);
  if (data.empty()) {
    r.errorCode = 1;
    r.error = "无法读取图片数据";
    return r;
  }
  r.container = sniffContainer(data.data(), data.size());
  if (r.container < 0) {
    r.errorCode = 2;
    r.error = "不支持的图片格式";
    return r;
  }
  if (probeWithUhdr(data, &r, nullptr)) {
    return r;  // gain map image
  }
  // SDR fallback: plain JPEG / HEIF / AVIF without a gain map
  bool ok = false;
  if (r.container == 0) {
    ok = probeJpegDims(data, r.width, r.height, r.hasExif);
  } else {
    ok = probeHeifDims(data, r.width, r.height, r.hasExif);
  }
  if (!ok) {
    r.errorCode = 4;
    r.error = "解析图像失败";
    return r;
  }
  return r;
}

TranscodeResult transcodeImage(int inFd, int outFd, const TranscodeOptions& options) {
  TranscodeResult r;
  const auto start = std::chrono::steady_clock::now();
  ultraslim_codec_speed_level = options.speedLevel;

  std::vector<uint8_t> data = readAll(inFd);
  if (data.empty()) {
    r.errorCode = 1;
    r.error = "无法读取图片数据";
    return r;
  }
  r.inputBytes = static_cast<long>(data.size());
  const int container = sniffContainer(data.data(), data.size());
  if (container < 0) {
    r.errorCode = 2;
    r.error = "不支持的图片格式";
    return r;
  }

  if (!probeWithUhdr(data, nullptr, &r)) {
    r.inputHadGainMap = false;
  }

  if (r.inputHadGainMap) {
    // ---------------- HDR path: decode with libultrahdr, re-encode with gain map
    DecoderPtr dec(uhdr_create_decoder());
    if (!dec) {
      r.errorCode = 3;
      r.error = "创建解码器失败";
      return r;
    }
    uhdr_compressed_image_t img {};
    img.data = data.data();
    img.data_sz = img.capacity = data.size();
    img.cg = UHDR_CG_UNSPECIFIED;
    img.ct = UHDR_CT_UNSPECIFIED;
    img.range = UHDR_CR_UNSPECIFIED;
    uhdr_error_info_t st = uhdr_dec_set_image(dec.get(), &img);
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 4;
      r.error = describe(st, "设置输入图像失败");
      return r;
    }
    st = uhdr_dec_probe(dec.get());
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 5;
      r.error = describe(st, "解析图像失败");
      return r;
    }
    uhdr_dec_set_out_img_format(dec.get(), UHDR_IMG_FMT_32bppRGBA1010102);
    uhdr_dec_set_out_color_transfer(dec.get(), UHDR_CT_HLG);
    if (options.resizePercent < 100) {
      const int nw = std::max(2, r.width * options.resizePercent / 100 / 2 * 2);
      const int nh = std::max(2, r.height * options.resizePercent / 100 / 2 * 2);
      uhdr_add_effect_resize(dec.get(), nw, nh);
    }
    st = uhdr_decode(dec.get());
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 6;
      r.error = describe(st, "解码图像失败");
      return r;
    }
    uhdr_raw_image_t* raw = uhdr_get_decoded_image(dec.get());
    if (raw == nullptr || raw->planes[0] == nullptr) {
      r.errorCode = 7;
      r.error = "解码结果为空";
      return r;
    }
    raw->range = UHDR_CR_FULL_RANGE;  // encoder expects full-range RGB
    r.width = static_cast<int>(raw->w);
    r.height = static_cast<int>(raw->h);

    uhdr_mem_block_t* exif = options.keepExif ? uhdr_dec_get_exif(dec.get()) : nullptr;

    EncoderPtr enc(uhdr_create_encoder());
    if (!enc) {
      r.errorCode = 8;
      r.error = "创建编码器失败";
      return r;
    }
    st = uhdr_enc_set_raw_image(enc.get(), raw, UHDR_HDR_IMG);
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 9;
      r.error = describe(st, "设置原始图像失败");
      return r;
    }
    st = uhdr_enc_set_output_format(
        enc.get(), options.outCodec == 2
                       ? UHDR_CODEC_AVIF
                       : options.outCodec == 1 ? UHDR_CODEC_HEIF : UHDR_CODEC_JPG);
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 10;
      r.error = describe(st, "设置输出格式失败");
      return r;
    }
    uhdr_enc_set_quality(enc.get(), options.quality, UHDR_BASE_IMG);
    uhdr_enc_set_quality(enc.get(), options.gainmapQuality, UHDR_GAIN_MAP_IMG);
    uhdr_enc_set_gainmap_scale_factor(enc.get(), options.gainmapScale);
    uhdr_enc_set_using_multi_channel_gainmap(enc.get(), options.multiChannelGainmap ? 1 : 0);
    if (exif != nullptr && exif->data != nullptr && exif->data_sz > 0) {
      uhdr_enc_set_exif_data(enc.get(), exif);
    }

    st = uhdr_encode(enc.get());
    if (st.error_code != UHDR_CODEC_OK) {
      r.errorCode = 11;
      r.error = describe(st, "编码失败");
      return r;
    }
    uhdr_compressed_image_t* out = uhdr_get_encoded_stream(enc.get());
    if (out == nullptr || out->data == nullptr || out->data_sz == 0) {
      r.errorCode = 12;
      r.error = "编码结果为空";
      return r;
    }
    if (!writeAll(outFd, out->data, out->data_sz)) {
      r.errorCode = 13;
      r.error = std::string("写入输出文件失败: ") + strerror(errno);
      return r;
    }
    r.outputBytes = static_cast<long>(out->data_sz);
  } else {
    // ---------------- SDR path: decode with libjpeg/libheif, encode with libheif
    if (!options.allowSdrTranscode) {
      r.errorCode = 14;
      r.error = "图片不包含 gain map";
      return r;
    }
    SdrImage sdr;
    std::string err;
    const bool ok = container == 0 ? decodeJpegSdr(data, sdr, err) : decodeHeifSdr(data, sdr, err);
    if (!ok) {
      r.errorCode = 15;
      r.error = err;
      return r;
    }
    if (options.resizePercent < 100) {
      const int nw = std::max(2, sdr.width * options.resizePercent / 100 / 2 * 2);
      const int nh = std::max(2, sdr.height * options.resizePercent / 100 / 2 * 2);
      if (nw < sdr.width || nh < sdr.height) {
        sdr = resizeRgba(sdr, nw, nh);
      }
    }
    r.width = sdr.width;
    r.height = sdr.height;
    if (!options.keepExif) sdr.exif.clear();
    ultraslim_codec_speed_level = options.speedLevel;
    int encErr;
    if (options.outCodec == 0) {
      encErr = encodeJpegSdr(sdr, options.quality, options.keepExif, outFd, err);
    } else {
      encErr = encodeSdrWithLibheif(sdr, options.outCodec, options.quality, options.keepExif,
                                    outFd, options.speedLevel, err);
    }
    if (encErr != 0) {
      r.errorCode = encErr;
      r.error = err;
      return r;
    }
    struct stat st {};
    if (fstat(outFd, &st) == 0) r.outputBytes = static_cast<long>(st.st_size);
  }

  fsync(outFd);
  r.durationMs =
      std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
  return r;
}

std::vector<uint8_t> decodePreviewJpeg(int fd, int maxDim, int quality) {
  std::vector<uint8_t> empty;
  std::vector<uint8_t> data = readAll(fd);
  if (data.empty()) return empty;
  const int container = sniffContainer(data.data(), data.size());
  if (container < 0) return empty;

  SdrImage sdr;
  std::string err;
  const bool ok = container == 0 ? decodeJpegSdr(data, sdr, err) : decodeHeifSdr(data, sdr, err);
  if (!ok || sdr.width <= 0 || sdr.height <= 0) return empty;

  const int larger = std::max(sdr.width, sdr.height);
  if (larger > maxDim) {
    const int nw = std::max(1, sdr.width * maxDim / larger);
    const int nh = std::max(1, sdr.height * maxDim / larger);
    sdr = resizeRgba(sdr, nw, nh);
  }
  return encodeJpegToMem(sdr, quality);
}

}  // namespace ultraslim
