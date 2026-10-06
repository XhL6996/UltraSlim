#include "transcode.h"

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <csetjmp>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>

#include <jpeglib.h>
#include <libheif/heif.h>

#include "ultrahdr/gainmapmetadata.h"
#include "ultrahdr/ultrahdrcommon.h"
#include "ultrahdr_api.h"

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

// strong definitions; libultrahdr's patched HEIF/AVIF encoders read these via a weak default
int ultraslim_codec_speed_level = 1;
int ultraslim_codec_threads = 0;

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
  std::vector<uint8_t> exif;  // JPEG APP1 payload starting with "Exif\0\0" (may be empty)
};

struct JpegErrMgr {
  jpeg_error_mgr pub;
  jmp_buf jump;
  char message[JMSG_LENGTH_MAX];
};

void jpegErrExit(j_common_ptr cinfo) {
  auto* err = reinterpret_cast<JpegErrMgr*>(cinfo->err);
  (*cinfo->err->format_message)(cinfo, err->message);
  longjmp(err->jump, 1);
}

// libjpeg-turbo only scales by 1/2, 1/4, 1/8. Pick the largest shrink that still
// covers targetLongEdge; the caller fine-scales the rest. 0 keeps full resolution.
void applyJpegDecodeScale(jpeg_decompress_struct& cinfo, int targetLongEdge) {
  if (targetLongEdge <= 0) return;
  const int longEdge = std::max(static_cast<int>(cinfo.image_width), static_cast<int>(cinfo.image_height));
  int denom = 1;
  while (denom < 8 && longEdge / (denom * 2) >= targetLongEdge) denom *= 2;
  cinfo.scale_num = 1;
  cinfo.scale_denom = denom;
}

bool decodeJpegSdr(const std::vector<uint8_t>& data, SdrImage& out, std::string& err,
                   int targetLongEdge = 0) {
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_decompress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_decompress(&cinfo);
    err = jerr.message[0] ? jerr.message : "JPEG 解码失败";
    return false;
  }
  jpeg_create_decompress(&cinfo);
  created = true;
  jpeg_mem_src(&cinfo, data.data(), static_cast<unsigned long>(data.size()));
  jpeg_save_markers(&cinfo, JPEG_APP0 + 1, 0xFFFF);
  if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
    jpeg_destroy_decompress(&cinfo);
    err = "无法解析 JPEG";
    return false;
  }
  applyJpegDecodeScale(cinfo, targetLongEdge);
  cinfo.out_color_space = JCS_EXT_RGBA;
  jpeg_start_decompress(&cinfo);
  out.width = static_cast<int>(cinfo.output_width);
  out.height = static_cast<int>(cinfo.output_height);
  const size_t stride = static_cast<size_t>(out.width) * 4;
  out.rgba.resize(stride * static_cast<size_t>(out.height));
  while (cinfo.output_scanline < cinfo.output_height) {
    JSAMPROW row = out.rgba.data() + static_cast<size_t>(cinfo.output_scanline) * stride;
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
  return out.width > 0 && out.height > 0;
}

bool probeJpegDims(const std::vector<uint8_t>& data, int& w, int& h, bool& hasExif) {
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_decompress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_decompress(&cinfo);
    return false;
  }
  jpeg_create_decompress(&cinfo);
  created = true;
  jpeg_mem_src(&cinfo, data.data(), static_cast<unsigned long>(data.size()));
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

// HEIF stores Exif as a 4-byte big-endian offset followed by a TIFF header.
// Canonical form in this file is the JPEG APP1 payload ("Exif\0\0" + TIFF).
bool readHeifExif(heif_image_handle* handle, std::vector<uint8_t>& out) {
  heif_item_id id = 0;
  if (heif_image_handle_get_list_of_metadata_block_IDs(handle, "Exif", &id, 1) <= 0) return false;
  const size_t size = heif_image_handle_get_metadata_size(handle, id);
  if (size <= 4) return false;
  std::vector<uint8_t> raw(size);
  if (heif_image_handle_get_metadata(handle, id, raw.data()).code != heif_error_Ok) return false;
  const uint32_t offset = (static_cast<uint32_t>(raw[0]) << 24) | (static_cast<uint32_t>(raw[1]) << 16) |
                          (static_cast<uint32_t>(raw[2]) << 8) | raw[3];
  const size_t tiff = 4u + offset;
  if (tiff >= raw.size()) return false;
  out.clear();
  out.insert(out.end(), {'E', 'x', 'i', 'f', 0, 0});
  out.insert(out.end(), raw.begin() + static_cast<std::ptrdiff_t>(tiff), raw.end());
  return true;
}

// TIFF bytes for heif_context_add_exif_metadata (no "Exif\0\0", no 4-byte offset).
const uint8_t* exifTiff(const std::vector<uint8_t>& app1, size_t& size) {
  if (app1.size() >= 6 && memcmp(app1.data(), "Exif\0\0", 6) == 0) {
    size = app1.size() - 6;
    return app1.data() + 6;
  }
  size = app1.size();
  return app1.data();
}

bool decodeHeifHandle(heif_image_handle* handle, SdrImage& out, std::string& err, bool wantExif) {
  heif_image* himg = nullptr;
  heif_error e =
      heif_decode_image(handle, &himg, heif_colorspace_RGB, heif_chroma_interleaved_RGBA, nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "HEIF 解码失败";
    return false;
  }
  out.width = heif_image_get_width(himg, heif_channel_interleaved);
  out.height = heif_image_get_height(himg, heif_channel_interleaved);
  int srcStride = 0;
  const uint8_t* plane = heif_image_get_plane_readonly(himg, heif_channel_interleaved, &srcStride);
  if (plane == nullptr || out.width <= 0 || out.height <= 0) {
    heif_image_release(himg);
    err = "HEIF 解码结果为空";
    return false;
  }
  const size_t rowBytes = static_cast<size_t>(out.width) * 4;
  out.rgba.resize(rowBytes * static_cast<size_t>(out.height));
  if (srcStride == static_cast<int>(rowBytes)) {
    memcpy(out.rgba.data(), plane, out.rgba.size());
  } else {
    for (int y = 0; y < out.height; y++) {
      memcpy(out.rgba.data() + rowBytes * y, plane + static_cast<size_t>(srcStride) * y, rowBytes);
    }
  }
  heif_image_release(himg);
  if (wantExif) readHeifExif(handle, out.exif);
  return true;
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
  const bool ok = e.code == heif_error_Ok && handle != nullptr;
  if (handle) heif_image_handle_release(handle);
  heif_context_free(ctx);
  return ok;
}

bool decodeHeifSdr(const std::vector<uint8_t>& data, SdrImage& out, std::string& err) {
  heif_context* ctx = heif_context_alloc();
  heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "无法解析 HEIF";
    heif_context_free(ctx);
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code != heif_error_Ok || handle == nullptr) {
    err = e.message ? e.message : "没有主图像";
    heif_context_free(ctx);
    return false;
  }
  const bool ok = decodeHeifHandle(handle, out, err, true);
  heif_image_handle_release(handle);
  heif_context_free(ctx);
  return ok;
}

// Prefer an embedded thumbnail when it already covers the requested long edge.
bool decodeHeifPreview(const std::vector<uint8_t>& data, SdrImage& out, std::string& err, int maxDim) {
  heif_context* ctx = heif_context_alloc();
  heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "无法解析 HEIF";
    heif_context_free(ctx);
    return false;
  }
  heif_image_handle* handle = nullptr;
  e = heif_context_get_primary_image_handle(ctx, &handle);
  if (e.code != heif_error_Ok || handle == nullptr) {
    err = e.message ? e.message : "没有主图像";
    heif_context_free(ctx);
    return false;
  }
  bool ok = false;
  const int thumbs = heif_image_handle_get_number_of_thumbnails(handle);
  if (maxDim > 0 && thumbs > 0) {
    heif_item_id id = 0;
    if (heif_image_handle_get_list_of_thumbnail_IDs(handle, &id, 1) > 0) {
      heif_image_handle* thumb = nullptr;
      if (heif_image_handle_get_thumbnail(handle, id, &thumb).code == heif_error_Ok && thumb != nullptr) {
        const int longEdge = std::max(heif_image_handle_get_width(thumb), heif_image_handle_get_height(thumb));
        if (longEdge >= maxDim || (maxDim <= 512 && longEdge >= 160)) {
          ok = decodeHeifHandle(thumb, out, err, false);
          if (ok) readHeifExif(handle, out.exif);
        }
        heif_image_handle_release(thumb);
      }
    }
  }
  if (!ok) ok = decodeHeifHandle(handle, out, err, true);
  heif_image_handle_release(handle);
  heif_context_free(ctx);
  return ok;
}

heif_error fdWriteCallback(heif_context* /*ctx*/, const void* data, size_t size, void* userdata) {
  const int fd = *static_cast<int*>(userdata);
  if (writeAll(fd, data, size)) {
    return heif_error {heif_error_Ok, heif_suberror_Unspecified, nullptr};
  }
  return heif_error {heif_error_Encoding_error, heif_suberror_Cannot_write_output_data,
                     "write failed"};
}

int evenAtLeast2(int v) { return std::max(2, v - (v & 1)); }

void scaledSize(int sw, int sh, int percent, int& w, int& h) {
  if (percent >= 100) {
    w = sw;
    h = sh;
    return;
  }
  w = evenAtLeast2(sw * std::max(1, percent) / 100);
  h = evenAtLeast2(sh * std::max(1, percent) / 100);
}

// Fixed-point bilinear. Sample positions are computed once per axis.
SdrImage resizeRgba(const SdrImage& src, int newW, int newH) {
  SdrImage out;
  out.width = newW;
  out.height = newH;
  out.exif = src.exif;
  out.rgba.assign(static_cast<size_t>(newW) * static_cast<size_t>(newH) * 4, 0);
  const int sw = src.width, sh = src.height;
  if (newW <= 0 || newH <= 0 || sw <= 0 || sh <= 0 || src.rgba.empty()) return out;

  std::vector<int> x0s(static_cast<size_t>(newW)), x1s(static_cast<size_t>(newW)),
      wxs(static_cast<size_t>(newW));
  for (int x = 0; x < newW; x++) {
    const int64_t fx = (static_cast<int64_t>(x) * 2 + 1) * sw * 32768 / newW - 32768;
    int x0 = 0, x1 = 0, wx = 0;
    if (fx > 0) {
      x0 = static_cast<int>(fx >> 15);
      wx = static_cast<int>(fx & 32767);
      if (x0 >= sw - 1) {
        x0 = x1 = sw - 1;
        wx = 0;
      } else {
        x1 = x0 + 1;
      }
    }
    x0s[static_cast<size_t>(x)] = x0;
    x1s[static_cast<size_t>(x)] = x1;
    wxs[static_cast<size_t>(x)] = wx;
  }

  const uint8_t* s = src.rgba.data();
  uint8_t* d = out.rgba.data();
  for (int y = 0; y < newH; y++) {
    const int64_t fy = (static_cast<int64_t>(y) * 2 + 1) * sh * 32768 / newH - 32768;
    int y0 = 0, y1 = 0, wy = 0;
    if (fy > 0) {
      y0 = static_cast<int>(fy >> 15);
      wy = static_cast<int>(fy & 32767);
      if (y0 >= sh - 1) {
        y0 = y1 = sh - 1;
        wy = 0;
      } else {
        y1 = y0 + 1;
      }
    }
    const int wy0 = 32768 - wy;
    const uint8_t* row0 = s + static_cast<size_t>(y0) * static_cast<size_t>(sw) * 4;
    const uint8_t* row1 = s + static_cast<size_t>(y1) * static_cast<size_t>(sw) * 4;
    uint8_t* dst = d + static_cast<size_t>(y) * static_cast<size_t>(newW) * 4;
    for (int x = 0; x < newW; x++) {
      const int x0 = x0s[static_cast<size_t>(x)];
      const int x1 = x1s[static_cast<size_t>(x)];
      const int wx = wxs[static_cast<size_t>(x)];
      const int wx0 = 32768 - wx;
      const uint8_t* p00 = row0 + static_cast<size_t>(x0) * 4;
      const uint8_t* p01 = row0 + static_cast<size_t>(x1) * 4;
      const uint8_t* p10 = row1 + static_cast<size_t>(x0) * 4;
      const uint8_t* p11 = row1 + static_cast<size_t>(x1) * 4;
      for (int c = 0; c < 4; c++) {
        const int64_t v = static_cast<int64_t>(p00[c]) * wx0 * wy0 +
                          static_cast<int64_t>(p01[c]) * wx * wy0 +
                          static_cast<int64_t>(p10[c]) * wx0 * wy +
                          static_cast<int64_t>(p11[c]) * wx * wy;
        dst[c] = static_cast<uint8_t>((v + (1 << 29)) >> 30);
      }
      dst += 4;
    }
  }
  return out;
}

void maybeDownscale(SdrImage& img, int nw, int nh) {
  if (nw > 0 && nh > 0 && (nw < img.width || nh < img.height)) img = resizeRgba(img, nw, nh);
}

// Encodes an SDR RGBA8888 image to JPEG in memory with libjpeg-turbo.
std::vector<uint8_t> encodeJpegToMem(const SdrImage& img, int quality, bool keepExif = false) {
  std::vector<uint8_t> out;
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_compress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  uint8_t* mem = nullptr;
  unsigned long memSize = 0;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_compress(&cinfo);
    free(mem);
    return {};
  }
  jpeg_create_compress(&cinfo);
  created = true;
  jpeg_mem_dest(&cinfo, &mem, &memSize);
  cinfo.image_width = img.width;
  cinfo.image_height = img.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, std::max(1, std::min(100, quality)), TRUE);
  jpeg_start_compress(&cinfo, TRUE);
  if (keepExif && !img.exif.empty()) {
    jpeg_write_marker(&cinfo, JPEG_APP0 + 1, img.exif.data(), static_cast<unsigned int>(img.exif.size()));
  }
  const size_t stride = static_cast<size_t>(img.width) * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = const_cast<uint8_t*>(img.rgba.data()) + static_cast<size_t>(cinfo.next_scanline) * stride;
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
  JpegErrMgr jerr {};
  jerr.message[0] = '\0';
  jpeg_compress_struct cinfo {};
  cinfo.err = jpeg_std_error(&jerr.pub);
  jerr.pub.error_exit = jpegErrExit;
  bool created = false;
  if (setjmp(jerr.jump)) {
    if (created) jpeg_destroy_compress(&cinfo);
    fclose(f);
    err = jerr.message[0] ? jerr.message : "JPEG 编码失败";
    return 31;
  }
  jpeg_create_compress(&cinfo);
  created = true;
  jpeg_stdio_dest(&cinfo, f);
  cinfo.image_width = img.width;
  cinfo.image_height = img.height;
  cinfo.input_components = 4;
  cinfo.in_color_space = JCS_EXT_RGBA;
  jpeg_set_defaults(&cinfo);
  jpeg_set_quality(&cinfo, std::max(1, std::min(100, quality)), TRUE);
  jpeg_start_compress(&cinfo, TRUE);
  if (keepExif && !img.exif.empty()) {
    jpeg_write_marker(&cinfo, JPEG_APP0 + 1, img.exif.data(), static_cast<unsigned int>(img.exif.size()));
  }
  const size_t stride = static_cast<size_t>(img.width) * 4;
  while (cinfo.next_scanline < cinfo.image_height) {
    JSAMPROW row = const_cast<uint8_t*>(img.rgba.data()) + static_cast<size_t>(cinfo.next_scanline) * stride;
    jpeg_write_scanlines(&cinfo, &row, 1);
  }
  jpeg_finish_compress(&cinfo);
  jpeg_destroy_compress(&cinfo);
  fflush(f);
  fclose(f);  // closes only the dup'd fd
  return 0;
}

void yuvPixel(const uint8_t* p, uint8_t& y, uint8_t& u, uint8_t& v) {
  const int r = p[0], g = p[1], b = p[2];
  y = static_cast<uint8_t>(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
  u = static_cast<uint8_t>(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
  v = static_cast<uint8_t>(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
}

#if defined(__ARM_NEON)
void storeY8(uint8_t* dst, const uint8_t* rgba) {
  const uint8x8x4_t p = vld4_u8(rgba);
  const uint16x8_t r = vmovl_u8(p.val[0]);
  const uint16x8_t g = vmovl_u8(p.val[1]);
  const uint16x8_t b = vmovl_u8(p.val[2]);
  uint16x8_t acc = vmulq_n_u16(r, 66);
  acc = vmlaq_n_u16(acc, g, 129);
  acc = vmlaq_n_u16(acc, b, 25);
  acc = vaddq_u16(acc, vdupq_n_u16(128));
  vst1_u8(dst, vadd_u8(vmovn_u16(vshrq_n_u16(acc, 8)), vdup_n_u8(16)));
}
#endif

// Converts RGBA8888 to BT.601 limited-range YUV420 (the standard layout for SDR HEIF/AVIF).
void rgbaToYuv420(const SdrImage& img, std::vector<uint8_t>& yPlane, std::vector<uint8_t>& uPlane,
                  std::vector<uint8_t>& vPlane) {
  const int w = img.width, h = img.height;
  const int cw = (w + 1) / 2, ch = (h + 1) / 2;
  yPlane.resize(static_cast<size_t>(w) * h);
  uPlane.resize(static_cast<size_t>(cw) * ch);
  vPlane.resize(static_cast<size_t>(cw) * ch);
  const uint8_t* rgba = img.rgba.data();
  for (int y = 0; y < h; y++) {
    uint8_t* yRow = yPlane.data() + static_cast<size_t>(y) * w;
    const uint8_t* src = rgba + static_cast<size_t>(y) * w * 4;
    int x = 0;
#if defined(__ARM_NEON)
    for (; x + 8 <= w; x += 8) storeY8(yRow + x, src + x * 4);
#endif
    for (; x < w; x++) {
      uint8_t yy, uu, vv;
      yuvPixel(src + x * 4, yy, uu, vv);
      yRow[x] = yy;
    }
    // Even heights are fully covered by the preceding even row. An odd last row is not.
    const bool uncoveredLastRow = (y == h - 1) && ((h & 1) != 0);
    if ((y & 1) != 0 && !uncoveredLastRow) continue;
    const int y2 = std::min(y + 1, h - 1);
    const uint8_t* src2 = rgba + static_cast<size_t>(y2) * w * 4;
    uint8_t* uRow = uPlane.data() + static_cast<size_t>(y / 2) * cw;
    uint8_t* vRow = vPlane.data() + static_cast<size_t>(y / 2) * cw;
    for (int cx = 0; cx < cw; cx++) {
      const int x0 = std::min(cx * 2, w - 1);
      const int x1 = std::min(x0 + 1, w - 1);
      int r = 0, g = 0, b = 0;
      const uint8_t* samples[4] = {src + x0 * 4, src + x1 * 4, src2 + x0 * 4, src2 + x1 * 4};
      for (const uint8_t* q : samples) {
        r += q[0];
        g += q[1];
        b += q[2];
      }
      r /= 4;
      g /= 4;
      b /= 4;
      uRow[cx] = static_cast<uint8_t>(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
      vRow[cx] = static_cast<uint8_t>(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
    }
  }
}

void copyPlane(heif_image* image, heif_channel channel, const uint8_t* src, int width, int rows) {
  int dstStride = 0;
  uint8_t* dst = heif_image_get_plane(image, channel, &dstStride);
  if (dst == nullptr) return;
  for (int row = 0; row < rows; row++) {
    memcpy(dst + static_cast<size_t>(dstStride) * row, src + static_cast<size_t>(width) * row, width);
  }
}

int resolveCodecThreads(int requested) {
  if (requested > 0) return requested;
  unsigned cores = std::thread::hardware_concurrency();
  if (cores == 0) cores = 4;
  return std::max(1, static_cast<int>(cores) / 2);
}

void configureEncoder(heif_encoder* encoder, int outCodec, int speedLevel, int threads) {
  const int level = speedLevel < 0 || speedLevel > 2 ? 1 : speedLevel;
  if (outCodec == 1) {
    static const char* kPresets[] = {"slow", "medium", "veryfast"};
    heif_encoder_set_parameter(encoder, "preset", kPresets[level]);
    if (threads > 0) {
      char pools[16];
      snprintf(pools, sizeof pools, "%d", threads);
      heif_encoder_set_parameter(encoder, "x265:pools", pools);
      heif_encoder_set_parameter(encoder, "x265:frame-threads", "1");
    }
  } else {
    static const int kSpeeds[] = {4, 6, 9};
    heif_encoder_set_parameter_integer(encoder, "speed", kSpeeds[level]);
    if (threads > 0) heif_encoder_set_parameter_integer(encoder, "threads", threads);
  }
}

struct GainMapPayload {
  SdrImage image;
  std::vector<uint8_t> iso;
  int quality = 60;
  bool multiChannel = true;
  // Not owned. Null selects a BT.2100 HLG profile for the derived rendition.
  const heif_color_profile_nclx* derivedNclx = nullptr;
};

int attachGainMap(heif_context* ctx, heif_image_handle* base, heif_encoder* encoder,
                  const GainMapPayload& gm, std::string& err) {
  if (gm.iso.empty() || gm.image.width <= 0 || gm.image.height <= 0) {
    err = "gain map 数据不完整";
    return 24;
  }
  heif_encoder_set_lossy_quality(encoder, std::max(1, std::min(100, gm.quality)));
  heif_image* image = nullptr;
  heif_error e;
  if (gm.multiChannel) {
    std::vector<uint8_t> y, u, v;
    rgbaToYuv420(gm.image, y, u, v);
    const int cw = (gm.image.width + 1) / 2, ch = (gm.image.height + 1) / 2;
    e = heif_image_create(gm.image.width, gm.image.height, heif_colorspace_YCbCr, heif_chroma_420, &image);
    if (e.code != heif_error_Ok) {
      err = e.message ? e.message : "创建 gain map 失败";
      return 24;
    }
    heif_image_add_plane(image, heif_channel_Y, gm.image.width, gm.image.height, 8);
    heif_image_add_plane(image, heif_channel_Cb, cw, ch, 8);
    heif_image_add_plane(image, heif_channel_Cr, cw, ch, 8);
    copyPlane(image, heif_channel_Y, y.data(), gm.image.width, gm.image.height);
    copyPlane(image, heif_channel_Cb, u.data(), cw, ch);
    copyPlane(image, heif_channel_Cr, v.data(), cw, ch);
    heif_encoder_set_parameter(encoder, "chroma", "420");
  } else {
    std::vector<uint8_t> y(static_cast<size_t>(gm.image.width) * gm.image.height);
    for (size_t i = 0; i < y.size(); i++) y[i] = gm.image.rgba[i * 4];
    e = heif_image_create(gm.image.width, gm.image.height, heif_colorspace_monochrome,
                          heif_chroma_monochrome, &image);
    if (e.code != heif_error_Ok) {
      err = e.message ? e.message : "创建 gain map 失败";
      return 24;
    }
    heif_image_add_plane(image, heif_channel_Y, gm.image.width, gm.image.height, 8);
    copyPlane(image, heif_channel_Y, y.data(), gm.image.width, gm.image.height);
  }
  heif_color_profile_nclx nclx {};
  nclx.version = 1;
  nclx.color_primaries = heif_color_primaries_unspecified;
  nclx.transfer_characteristics = heif_transfer_characteristic_unspecified;
  nclx.matrix_coefficients = heif_matrix_coefficients_ITU_R_BT_601_6;
  nclx.full_range_flag = gm.multiChannel ? 0 : 1;
  heif_image_set_nclx_color_profile(image, &nclx);

  heif_color_profile_nclx* owned = nullptr;
  const heif_color_profile_nclx* derived = gm.derivedNclx;
  if (derived == nullptr) {
    owned = heif_nclx_color_profile_alloc();
    if (owned != nullptr) {
      heif_nclx_color_profile_set_color_primaries(owned, heif_color_primaries_ITU_R_BT_2020_2_and_2100_0);
      heif_nclx_color_profile_set_transfer_characteristics(owned, heif_transfer_characteristic_ITU_R_BT_2100_0_HLG);
      heif_nclx_color_profile_set_matrix_coefficients(
          owned, heif_matrix_coefficients_ITU_R_BT_2020_2_non_constant_luminance);
      owned->full_range_flag = 1;
      derived = owned;
    }
  }
  heif_image_handle* gmHandle = nullptr;
  e = heif_context_encode_gain_map_image(ctx, base, encoder, image, nullptr, gm.iso.data(),
                                        static_cast<int>(gm.iso.size()), derived, &gmHandle);
  if (gmHandle) heif_image_handle_release(gmHandle);
  heif_image_release(image);
  if (owned) heif_nclx_color_profile_free(owned);
  if (e.code != heif_error_Ok) {
    err = e.message ? e.message : "写入 gain map 失败";
    return 24;
  }
  return 0;
}

// Encodes an SDR RGBA8888 image to HEIC/AVIF directly with libheif.
// gainmap, when set, is attached without rebuilding it from an HDR decode.
int encodeSdrWithLibheif(const SdrImage& img, int outCodec, int quality, bool keepExif, int outFd,
                         int speedLevel, int threads, const GainMapPayload* gainmap, std::string& err) {
  std::vector<uint8_t> yPlane, uPlane, vPlane;
  rgbaToYuv420(img, yPlane, uPlane, vPlane);

  heif_context* ctx = heif_context_alloc();
  heif_encoder* encoder = nullptr;
  heif_error e = heif_context_get_encoder_for_format(
      ctx, outCodec == 2 ? heif_compression_AV1 : heif_compression_HEVC, &encoder);
  if (e.code != heif_error_Ok) {
    err = std::string("获取编码器失败: ") + (e.message ? e.message : "");
    heif_context_free(ctx);
    return 20;
  }
  heif_encoder_set_lossy_quality(encoder, quality);
  heif_encoder_set_parameter(encoder, "chroma", "420");
  configureEncoder(encoder, outCodec, speedLevel, threads);

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
    size_t tiffSize = 0;
    const uint8_t* tiff = exifTiff(img.exif, tiffSize);
    if (tiffSize > 0) heif_context_add_exif_metadata(ctx, outHandle, tiff, static_cast<int>(tiffSize));
  }
  if (gainmap != nullptr && outHandle != nullptr) {
    const int gmErr = attachGainMap(ctx, outHandle, encoder, *gainmap, err);
    if (gmErr != 0) {
      heif_image_handle_release(outHandle);
      heif_image_release(himg);
      heif_encoder_release(encoder);
      heif_context_free(ctx);
      return gmErr;
    }
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

bool channelsIdentical(const uhdr_gainmap_metadata_t& m) {
  const float e = 0.001f;
  auto same = [e](const float* v) {
    return std::fabs(v[0] - v[1]) < e && std::fabs(v[0] - v[2]) < e;
  };
  return same(m.max_content_boost) && same(m.min_content_boost) && same(m.gamma) &&
         same(m.offset_sdr) && same(m.offset_hdr);
}

// Reuse the existing gain map when the request does not need a finer map or a
// different channel count. Coarser maps are produced by downscaling.
bool canReuseGainMap(int srcW, int srcH, int gmW, int gmH, const uhdr_gainmap_metadata_t& meta,
                     const TranscodeOptions& opt) {
  if (srcW < 2 || srcH < 2 || gmW < 2 || gmH < 2) return false;
  const int srcScale = std::max(1, (srcW + gmW / 2) / gmW);
  if (opt.gainmapScale < srcScale) return false;
  const bool srcMulti = !channelsIdentical(meta);
  return opt.multiChannelGainmap == srcMulti;
}

std::vector<uint8_t> isoFromMetadata(const uhdr_gainmap_metadata_t& meta) {
  uhdr_gainmap_metadata_t copy = meta;
  ultrahdr::uhdr_gainmap_metadata_ext_t ext(copy, ultrahdr::kJpegrVersion);
  ultrahdr::uhdr_gainmap_metadata_frac frac {};
  if (ultrahdr::uhdr_gainmap_metadata_frac::gainmapMetadataFloatToFraction(&ext, &frac).error_code !=
      UHDR_CODEC_OK) {
    return {};
  }
  std::vector<uint8_t> out;
  if (ultrahdr::uhdr_gainmap_metadata_frac::encodeGainmapMetadata(&frac, out).error_code !=
      UHDR_CODEC_OK) {
    return {};
  }
  return out;
}

std::vector<uint8_t> copyBlock(const uhdr_mem_block_t* block) {
  if (block == nullptr || block->data == nullptr || block->data_sz == 0) return {};
  return {block->data, block->data + block->data_sz};
}

bool looksLikeJpeg(const std::vector<uint8_t>& bytes) {
  return bytes.size() >= 2 && bytes[0] == 0xFF && bytes[1] == 0xD8;
}

void fitGainMap(SdrImage& gm, int baseW, int baseH, int scale) {
  const int div = std::max(1, scale);
  const int tw = evenAtLeast2(std::max(2, baseW / div));
  const int th = evenAtLeast2(std::max(2, baseH / div));
  maybeDownscale(gm, tw, th);
}

int muxJpegUltraHdr(const std::vector<uint8_t>& baseJpeg, const std::vector<uint8_t>& gmJpeg,
                    uhdr_gainmap_metadata_t* meta, int outFd) {
  EncoderPtr enc(uhdr_create_encoder());
  if (!enc || meta == nullptr) return -1;
  uhdr_compressed_image_t base {};
  base.data = const_cast<uint8_t*>(baseJpeg.data());
  base.data_sz = base.capacity = baseJpeg.size();
  base.cg = UHDR_CG_BT_709;
  base.ct = UHDR_CT_SRGB;
  base.range = UHDR_CR_LIMITED_RANGE;
  uhdr_compressed_image_t gm {};
  gm.data = const_cast<uint8_t*>(gmJpeg.data());
  gm.data_sz = gm.capacity = gmJpeg.size();
  gm.cg = UHDR_CG_BT_709;
  gm.ct = UHDR_CT_UNSPECIFIED;
  gm.range = UHDR_CR_FULL_RANGE;
  if (uhdr_enc_set_compressed_image(enc.get(), &base, UHDR_BASE_IMG).error_code != UHDR_CODEC_OK) {
    return -1;
  }
  if (uhdr_enc_set_gainmap_image(enc.get(), &gm, meta).error_code != UHDR_CODEC_OK) return -1;
  if (uhdr_enc_set_output_format(enc.get(), UHDR_CODEC_JPG).error_code != UHDR_CODEC_OK) return -1;
  if (uhdr_encode(enc.get()).error_code != UHDR_CODEC_OK) return -1;
  uhdr_compressed_image_t* out = uhdr_get_encoded_stream(enc.get());
  if (out == nullptr || out->data == nullptr || out->data_sz == 0) return -1;
  return writeAll(outFd, out->data, out->data_sz) ? 0 : -1;
}

// 0 = wrote output, -1 = caller should rebuild the gain map.
int tryReuseGainMap(const std::vector<uint8_t>& data, int container, uhdr_codec_private_t* dec,
                    const TranscodeOptions& opt, int outFd, TranscodeResult& r) {
  uhdr_gainmap_metadata_t* meta = uhdr_dec_get_gainmap_metadata(dec);
  const int srcW = uhdr_dec_get_image_width(dec);
  const int srcH = uhdr_dec_get_image_height(dec);
  const int gmW = uhdr_dec_get_gainmap_width(dec);
  const int gmH = uhdr_dec_get_gainmap_height(dec);
  if (meta == nullptr || !canReuseGainMap(srcW, srcH, gmW, gmH, *meta, opt)) return -1;

  SdrImage base, gm;
  std::string err;
  std::vector<uint8_t> iso;
  heif_color_profile_nclx* derived = nullptr;

  if (container == 0) {
    const std::vector<uint8_t> baseBytes = copyBlock(uhdr_dec_get_base_image(dec));
    const std::vector<uint8_t> gmBytes = copyBlock(uhdr_dec_get_gainmap_image(dec));
    if (!looksLikeJpeg(baseBytes) || !looksLikeJpeg(gmBytes)) return -1;
    const int targetLong =
        opt.resizePercent >= 100 ? 0 : std::max(2, std::max(srcW, srcH) * opt.resizePercent / 100);
    if (!decodeJpegSdr(baseBytes, base, err, targetLong) || !decodeJpegSdr(gmBytes, gm, err)) {
      return -1;
    }
    iso = isoFromMetadata(*meta);
  } else {
    heif_context* ctx = heif_context_alloc();
    heif_error e = heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr);
    if (e.code != heif_error_Ok) {
      heif_context_free(ctx);
      return -1;
    }
    heif_image_handle* handle = nullptr;
    e = heif_context_get_primary_image_handle(ctx, &handle);
    heif_image_handle* gmHandle = nullptr;
    if (e.code == heif_error_Ok && handle != nullptr) {
      e = heif_image_handle_get_gain_map_image_handle(handle, &gmHandle);
    }
    bool decoded = false;
    if (e.code == heif_error_Ok && handle != nullptr && gmHandle != nullptr) {
      const size_t metaSize = heif_image_handle_get_gain_map_metadata_size(handle);
      if (metaSize > 0) {
        iso.resize(metaSize);
        if (heif_image_handle_get_gain_map_metadata(handle, iso.data()).code != heif_error_Ok) {
          iso.clear();
        }
      }
      heif_color_profile_nclx* got = nullptr;
      if (heif_image_handle_get_derived_image_nclx_color_profile(handle, &got).code != heif_error_Ok) {
        if (got != nullptr) heif_nclx_color_profile_free(got);
        derived = nullptr;
      } else {
        derived = got;
      }
      decoded = decodeHeifHandle(handle, base, err, true) && decodeHeifHandle(gmHandle, gm, err, false);
    }
    if (gmHandle) heif_image_handle_release(gmHandle);
    if (handle) heif_image_handle_release(handle);
    heif_context_free(ctx);
    if (!decoded) {
      if (derived) heif_nclx_color_profile_free(derived);
      return -1;
    }
    if (iso.empty()) iso = isoFromMetadata(*meta);
  }

  if (iso.empty() && opt.outCodec != 0) {
    if (derived) heif_nclx_color_profile_free(derived);
    return -1;
  }

  // Source dimensions, not the JPEG-scaled buffer: libjpeg already shrank toward this size.
  int bw = 0, bh = 0;
  scaledSize(srcW, srcH, opt.resizePercent, bw, bh);
  maybeDownscale(base, bw, bh);
  fitGainMap(gm, base.width, base.height, opt.gainmapScale);
  if (!opt.keepExif) base.exif.clear();
  const int outW = base.width, outH = base.height, outGmW = gm.width, outGmH = gm.height;

  int rc = -1;
  if (opt.outCodec == 0) {
    const std::vector<uint8_t> baseJpeg = encodeJpegToMem(base, opt.quality, opt.keepExif);
    const std::vector<uint8_t> gmJpeg = encodeJpegToMem(gm, opt.gainmapQuality, false);
    if (!baseJpeg.empty() && !gmJpeg.empty()) rc = muxJpegUltraHdr(baseJpeg, gmJpeg, meta, outFd);
  } else {
    GainMapPayload payload;
    payload.image = std::move(gm);
    payload.iso = std::move(iso);
    payload.quality = opt.gainmapQuality;
    payload.multiChannel = opt.multiChannelGainmap;
    payload.derivedNclx = derived;
    rc = encodeSdrWithLibheif(base, opt.outCodec, opt.quality, opt.keepExif, outFd, opt.speedLevel,
                              ultraslim_codec_threads, &payload, err) == 0
             ? 0
             : -1;
  }
  if (derived) heif_nclx_color_profile_free(derived);
  if (rc != 0) return -1;
  r.width = outW;
  r.height = outH;
  r.gainmapWidth = outGmW;
  r.gainmapHeight = outGmH;
  r.inputHadGainMap = true;
  return 0;
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
  ultraslim_codec_threads = resolveCodecThreads(options.codecThreads);

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

  DecoderPtr dec(uhdr_create_decoder());
  uhdr_compressed_image_t img {};
  img.data = data.data();
  img.data_sz = img.capacity = data.size();
  img.cg = UHDR_CG_UNSPECIFIED;
  img.ct = UHDR_CT_UNSPECIFIED;
  img.range = UHDR_CR_UNSPECIFIED;
  const bool probed = dec && uhdr_dec_set_image(dec.get(), &img).error_code == UHDR_CODEC_OK &&
                      uhdr_dec_probe(dec.get()).error_code == UHDR_CODEC_OK;
  if (probed) {
    const int gw = uhdr_dec_get_gainmap_width(dec.get());
    const int gh = uhdr_dec_get_gainmap_height(dec.get());
    if (gw > 0 && gh > 0) {
      r.inputHadGainMap = true;
      r.width = uhdr_dec_get_image_width(dec.get());
      r.height = uhdr_dec_get_image_height(dec.get());
      r.gainmapWidth = gw;
      r.gainmapHeight = gh;
    }
  }

  if (r.inputHadGainMap) {
    const int srcW = r.width;
    const int srcH = r.height;
    const int srcGmW = r.gainmapWidth;
    const int srcGmH = r.gainmapHeight;
    // Recompress the base and keep the original gain map when the request allows it.
    if (tryReuseGainMap(data, container, dec.get(), options, outFd, r) == 0) {
      struct stat st {};
      if (fstat(outFd, &st) == 0) r.outputBytes = static_cast<long>(st.st_size);
      r.durationMs =
          std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
      return r;
    }
    if (lseek(outFd, 0, SEEK_SET) >= 0) ftruncate(outFd, 0);
    r.width = srcW;
    r.height = srcH;
    r.gainmapWidth = srcGmW;
    r.gainmapHeight = srcGmH;

    // ---------------- HDR path: decode with libultrahdr, rebuild the gain map
    uhdr_dec_set_out_img_format(dec.get(), UHDR_IMG_FMT_32bppRGBA1010102);
    uhdr_dec_set_out_color_transfer(dec.get(), UHDR_CT_HLG);
    if (options.resizePercent < 100) {
      int nw = 0, nh = 0;
      scaledSize(r.width, r.height, options.resizePercent, nw, nh);
      uhdr_add_effect_resize(dec.get(), nw, nh);
    }
    uhdr_error_info_t st = uhdr_decode(dec.get());
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
    int srcW = 0, srcH = 0;
    int jpegTarget = 0;
    if (container == 0 && options.resizePercent < 100) {
      bool ignoredExif = false;
      if (probeJpegDims(data, srcW, srcH, ignoredExif)) {
        jpegTarget = std::max(2, std::max(srcW, srcH) * options.resizePercent / 100);
      }
    }
    const bool ok = container == 0 ? decodeJpegSdr(data, sdr, err, jpegTarget) : decodeHeifSdr(data, sdr, err);
    if (!ok) {
      r.errorCode = 15;
      r.error = err;
      return r;
    }
    if (options.resizePercent < 100) {
      const int basisW = srcW > 0 ? srcW : sdr.width;
      const int basisH = srcH > 0 ? srcH : sdr.height;
      int nw = 0, nh = 0;
      scaledSize(basisW, basisH, options.resizePercent, nw, nh);
      maybeDownscale(sdr, nw, nh);
    }
    r.width = sdr.width;
    r.height = sdr.height;
    if (!options.keepExif) sdr.exif.clear();
    int encErr;
    if (options.outCodec == 0) {
      encErr = encodeJpegSdr(sdr, options.quality, options.keepExif, outFd, err);
    } else {
      encErr = encodeSdrWithLibheif(sdr, options.outCodec, options.quality, options.keepExif, outFd,
                                    options.speedLevel, ultraslim_codec_threads, nullptr, err);
    }
    if (encErr != 0) {
      r.errorCode = encErr;
      r.error = err;
      return r;
    }
    struct stat st {};
    if (fstat(outFd, &st) == 0) r.outputBytes = static_cast<long>(st.st_size);
  }

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
  const int limit = std::max(1, maxDim);
  const bool ok = container == 0 ? decodeJpegSdr(data, sdr, err, limit)
                                 : decodeHeifPreview(data, sdr, err, limit);
  if (!ok || sdr.width <= 0 || sdr.height <= 0) return empty;

  const int larger = std::max(sdr.width, sdr.height);
  if (larger > limit) {
    const int nw = std::max(1, sdr.width * limit / larger);
    const int nh = std::max(1, sdr.height * limit / larger);
    sdr = resizeRgba(sdr, nw, nh);
  }
  return encodeJpegToMem(sdr, quality, false);
}

}  // namespace ultraslim
