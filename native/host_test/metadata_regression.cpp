// Exercise the real metadata readers/writers without exporting test-only APIs.
#include "../jni/transcode.cpp"
#include <fcntl.h>
#include <cstdlib>
#include <iostream>

static void check(bool ok, const char* message) {
  if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static void save(const std::string& path, const std::vector<uint8_t>& bytes) {
  const int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
  check(fd >= 0 && ultraslim::writeAll(fd, bytes.data(), bytes.size()), "write fixture"); close(fd);
}
static std::vector<uint8_t> load(const std::string& path) {
  const int fd = open(path.c_str(), O_RDONLY); check(fd >= 0, "open fixture");
  auto bytes = ultraslim::readAll(fd); close(fd); return bytes;
}

// No dimensions/thumbnail need normalization, so all these bytes must survive.
// Check actual time/camera fields, not just presence of an EXIF marker.
static std::vector<uint8_t> cameraExif() {
  constexpr size_t root = 8, exif = root + 2 + 5 * 12 + 4;
  std::vector<uint8_t> tiff(exif + 2 + 5 * 12 + 4, 0);
  auto put16 = [&](size_t at, uint16_t value) { tiff[at] = value; tiff[at + 1] = value >> 8; };
  auto put32 = [&](size_t at, uint32_t value) { put16(at, value); put16(at + 2, value >> 16); };
  tiff[0] = tiff[1] = 'I'; put16(2, 42); put32(4, root);
  put16(root, 5); put16(exif, 5);
  auto ascii = [&](size_t at, uint16_t tag, const std::string& value) {
    put16(at, tag); put16(at + 2, 2); put32(at + 4, value.size() + 1);
    if (value.size() + 1 <= 4) {
      std::copy(value.begin(), value.end(), tiff.begin() + at + 8);
    } else {
      put32(at + 8, tiff.size()); tiff.insert(tiff.end(), value.begin(), value.end()); tiff.push_back(0);
    }
  };
  ascii(root + 2, 0x010f, "UltraSlim Test Camera");
  ascii(root + 14, 0x0110, "Metadata Fixture");
  put16(root + 26, 0x0112); put16(root + 28, 3); put32(root + 30, 1); put16(root + 34, 1);
  ascii(root + 38, 0x0132, "2021:07:08 09:10:11");
  put16(root + 50, 0x8769); put16(root + 52, 4); put32(root + 54, 1); put32(root + 58, exif);
  ascii(exif + 2, 0x9003, "2020:02:03 04:05:06");
  ascii(exif + 14, 0x9004, "2020:02:03 04:05:07");
  ascii(exif + 26, 0x9011, "+08:00");
  ascii(exif + 38, 0x9291, "123456");
  ascii(exif + 50, 0xa434, "Test Lens 24mm");
  std::vector<uint8_t> result = {'E', 'x', 'i', 'f', 0, 0};
  result.insert(result.end(), tiff.begin(), tiff.end()); return result;
}

static std::vector<uint8_t> jpeg(const std::vector<uint8_t>& exif, bool progressive = false) {
  jpeg_compress_struct c {}; jpeg_error_mgr errors {}; c.err = jpeg_std_error(&errors);
  jpeg_create_compress(&c); unsigned char* data = nullptr; unsigned long size = 0;
  jpeg_mem_dest(&c, &data, &size);
  c.image_width = 96; c.image_height = 64; c.input_components = 3; c.in_color_space = JCS_RGB;
  jpeg_set_defaults(&c); jpeg_set_quality(&c, 90, TRUE);
  if (progressive) jpeg_simple_progression(&c);
  jpeg_start_compress(&c, TRUE);
  if (!exif.empty()) jpeg_write_marker(&c, JPEG_APP0 + 1, exif.data(), exif.size());
  std::vector<uint8_t> row(c.image_width * 3);
  while (c.next_scanline < c.image_height) {
    for (size_t x = 0; x < row.size(); ++x) row[x] = uint8_t(x * 31 + c.next_scanline * 47);
    JSAMPROW scanline = row.data(); jpeg_write_scanlines(&c, &scanline, 1);
  }
  jpeg_finish_compress(&c); std::vector<uint8_t> bytes(data, data + size);
  free(data); jpeg_destroy_compress(&c); return bytes;
}

static std::vector<uint8_t> hdrJpeg(const std::vector<uint8_t>& exif) {
  auto base = jpeg(exif), gainmap = jpeg({});
  auto* enc = uhdr_create_encoder(); check(enc != nullptr, "HDR fixture encoder");
  uhdr_compressed_image_t image {};
  image.data = base.data(); image.data_sz = image.capacity = base.size();
  image.cg = UHDR_CG_BT_709; image.ct = UHDR_CT_SRGB; image.range = UHDR_CR_FULL_RANGE;
  check(uhdr_enc_set_compressed_image(enc, &image, UHDR_BASE_IMG).error_code == UHDR_CODEC_OK, "HDR fixture base");
  image.data = gainmap.data(); image.data_sz = image.capacity = gainmap.size();
  uhdr_gainmap_metadata_t metadata {};
  for (int c = 0; c < 3; ++c) {
    metadata.min_content_boost[c] = 1; metadata.max_content_boost[c] = 4;
    metadata.gamma[c] = 1; metadata.offset_sdr[c] = metadata.offset_hdr[c] = 1.0f / 64;
  }
  metadata.hdr_capacity_min = 1; metadata.hdr_capacity_max = 4; metadata.use_base_cg = true;
  check(uhdr_enc_set_gainmap_image(enc, &image, &metadata).error_code == UHDR_CODEC_OK, "HDR fixture gain map");
  uhdr_mem_block_t block {}; block.data = const_cast<uint8_t*>(exif.data()); block.data_sz = block.capacity = exif.size();
  check(uhdr_enc_set_exif_data(enc, &block).error_code == UHDR_CODEC_OK, "HDR fixture EXIF");
  check(uhdr_encode(enc).error_code == UHDR_CODEC_OK, "HDR fixture mux");
  const auto* stream = uhdr_get_encoded_stream(enc);
  const auto* start = static_cast<const uint8_t*>(stream->data);
  std::vector<uint8_t> bytes(start, start + stream->data_sz); uhdr_release_encoder(enc); return bytes;
}

static std::vector<uint8_t> readExif(const std::string& path) {
  auto bytes = load(path); ultraslim::SdrImage image; std::string error;
  const int container = ultraslim::sniffContainer(bytes.data(), bytes.size());
  if (container == 0) check(ultraslim::decodeJpegSdr(bytes, image, error), "decode JPEG metadata fixture");
  else if (container == 3) check(ultraslim::decodeJxlToSdr(bytes, image, nullptr, error), "decode JXL metadata fixture");
  else check(ultraslim::decodeHeifSdr(bytes, image, error), "decode HEIF metadata fixture");
  return image.exif;
}
static ultraslim::TranscodeResult convert(const std::string& source, const std::string& output, int codec, bool keepExif = true) {
  const int in = open(source.c_str(), O_RDONLY), out = open(output.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  check(in >= 0 && out >= 0, "open transcode files");
  ultraslim::TranscodeOptions options; options.outCodec = codec; options.speedLevel = 2; options.codecThreads = 2;
  options.keepExif = keepExif;
  auto result = ultraslim::transcodeImage(in, out, options);
  if (result.errorCode) std::cerr << source << " -> " << output << ": " << result.error << '\n';
  check(result.errorCode == 0, "metadata transcode succeeds");
  const auto probe = ultraslim::probeImage(out);
  check(probe.errorCode == 0 && result.hasExif == probe.hasExif && result.hasGainMap == probe.hasGainMap,
        "result reports written metadata");
  if (probe.hasGainMap) {
    check(std::abs(probe.maxContentBoost - 4.0f) < 0.001f && std::abs(result.maxContentBoost - probe.maxContentBoost) < 0.001f,
          "JXL/HEIF result headroom matches actual gain map");
  }
  close(in); close(out); return result;
}
static void rejected(const std::string& input, const std::string& output) {
  const int in = open(input.c_str(), O_RDONLY), out = open(output.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  check(in >= 0 && out >= 0, "open malformed transcode");
  ultraslim::TranscodeOptions options; options.outCodec = 0; options.codecThreads = 2;
  const auto result = ultraslim::transcodeImage(in, out, options);
  check(result.errorCode != 0 && lseek(out, 0, SEEK_END) == 0, "malformed source rejected with empty output");
  close(in); close(out);
}

static void exifRoundtrips(const std::string& output, const std::vector<uint8_t>& expected) {
  for (bool hdr : {false, true}) {
    const auto source = output + (hdr ? "/metadata-hdr.jpg" : "/metadata-sdr.jpg");
    save(source, hdr ? hdrJpeg(expected) : jpeg(expected));
    check(readExif(source) == expected, "fixture contains original dates/camera metadata");
    for (int inputCodec = 0; inputCodec < 4; ++inputCodec) {
      const auto input = source + "-input-" + std::to_string(inputCodec);
      convert(source, input, inputCodec);
      check(readExif(input) == expected, "initial conversion retains exact time/camera fields");
      for (int outputCodec = 0; outputCodec < 4; ++outputCodec) {
        if (hdr && inputCodec != 0 && outputCodec == 0) continue; // preserve-or-refuse is covered separately
        const auto target = input + "-output-" + std::to_string(outputCodec);
        convert(input, target, outputCodec);
        check(readExif(target) == expected, "cross-format conversion retains exact time/camera fields");
      }
      const auto privateOutput = input + "-no-exif";
      convert(input, privateOutput, inputCodec, false);
      check(readExif(privateOutput).empty(), "explicit EXIF removal still honored");
    }
  }
}

static bool permissiveJpegCompletes(const std::vector<uint8_t>& bytes) {
  jpeg_decompress_struct c {}; ultraslim::JpegErrMgr errors {}; std::vector<uint8_t> row;
  c.err = jpeg_std_error(&errors.pub); errors.pub.error_exit = ultraslim::jpegErrExit;
  if (setjmp(errors.jump)) { jpeg_destroy_decompress(&c); return false; }
  jpeg_create_decompress(&c); jpeg_mem_src(&c, bytes.data(), bytes.size());
  jpeg_read_header(&c, TRUE); jpeg_start_decompress(&c);
  row.resize(size_t(c.output_width) * c.output_components);
  while (c.output_scanline < c.output_height) {
    JSAMPROW scanline = row.data(); if (jpeg_read_scanlines(&c, &scanline, 1) != 1) break;
  }
  const bool complete = c.output_scanline == c.output_height;
  if (complete) jpeg_finish_decompress(&c);
  const bool warned = errors.pub.num_warnings > 0;
  jpeg_destroy_decompress(&c); return complete && warned;
}

static void truncatedJpegs(const std::string& output, const std::vector<uint8_t>& exif) {
  int completedDespiteDamage = 0;
  for (bool progressive : {false, true}) {
    const auto original = jpeg(exif, progressive);
    size_t scan = 2;
    while (scan + 4 <= original.size() && original[scan + 1] != 0xda) {
      check(original[scan] == 0xff, "fixture JPEG marker");
      scan += 2 + ultraslim::loadBE16(original.data() + scan + 2);
    }
    check(scan + 4 < original.size(), "fixture has scan");
    scan += 2 + ultraslim::loadBE16(original.data() + scan + 2);
    for (int fraction : {25, 50, 75}) {
      auto cut = original; cut.resize(scan + (original.size() - scan) * fraction / 100);
      const auto path = output + "/truncated-" + std::to_string(progressive) + "-" + std::to_string(fraction) + ".jpg";
      save(path, cut);
      const auto probe = ultraslim::probeImageMemory(cut);
      check(probe.errorCode == 0 && probe.width == 96 && probe.height == 64, "truncated scan retains a valid image header");
      if (permissiveJpegCompletes(cut)) ++completedDespiteDamage;
      rejected(path, path + "-output");
    }
  }
  check(completedDespiteDamage > 0, "default libjpeg completes damaged input: scanline-only check was insufficient");
}

static std::vector<uint8_t> jxlWithGainMapBox(const std::vector<uint8_t>& box) {
  auto* enc = JxlEncoderCreate(nullptr); check(enc != nullptr, "JXL fixture encoder");
  check(JxlEncoderUseContainer(enc, JXL_TRUE) == JXL_ENC_SUCCESS && JxlEncoderUseBoxes(enc) == JXL_ENC_SUCCESS, "JXL fixture container");
  JxlBasicInfo info; JxlEncoderInitBasicInfo(&info); info.xsize = info.ysize = 8;
  info.bits_per_sample = 8; info.num_color_channels = 3;
  check(JxlEncoderSetBasicInfo(enc, &info) == JXL_ENC_SUCCESS, "JXL fixture dimensions");
  JxlColorEncoding color; JxlColorEncodingSetToSRGB(&color, JXL_FALSE);
  check(JxlEncoderSetColorEncoding(enc, &color) == JXL_ENC_SUCCESS, "JXL fixture color");
  check(JxlEncoderAddBox(enc, "jhgm", box.data(), box.size(), JXL_FALSE) == JXL_ENC_SUCCESS, "JXL fixture gainmap box");
  JxlEncoderCloseBoxes(enc);
  auto* frame = JxlEncoderFrameSettingsCreate(enc, nullptr); std::vector<uint8_t> rgb(8 * 8 * 3, 128);
  const JxlPixelFormat format = {3, JXL_TYPE_UINT8, JXL_LITTLE_ENDIAN, 0};
  check(JxlEncoderAddImageFrame(frame, &format, rgb.data(), rgb.size()) == JXL_ENC_SUCCESS, "JXL fixture image");
  JxlEncoderCloseInput(enc);
  std::vector<uint8_t> encoded, chunk(4096);
  for (;;) {
    uint8_t* next = chunk.data(); size_t available = chunk.size(); const auto status = JxlEncoderProcessOutput(enc, &next, &available);
    check(status != JXL_ENC_ERROR, "JXL fixture output"); encoded.insert(encoded.end(), chunk.data(), next);
    if (status == JXL_ENC_SUCCESS) break;
  }
  JxlEncoderDestroy(enc); return encoded;
}

static void malformedJxl(const std::string& output) {
  ultraslim::SdrImage image; std::vector<uint8_t> valid; std::string error;
  check(ultraslim::decodeJxlToSdr(load(output + "/metadata-hdr.jpg-input-3"), image, &valid, error), "read valid gainmap box");
  ultraslim::JxlGainMapView parsed; check(ultraslim::parseJxlGainMap(valid, parsed), "valid gainmap layout");
  const size_t isoEnd = 3 + ultraslim::loadBE16(valid.data() + 1);
  std::vector<std::vector<uint8_t>> invalid;
  // Header, ISO boundary (former out-of-bounds read), missing ICC length/stream.
  for (size_t length : {size_t(0), size_t(1), size_t(2), isoEnd - 1, isoEnd, isoEnd + 1, isoEnd + 4, isoEnd + 5})
    invalid.emplace_back(valid.begin(), valid.begin() + length);
  auto bad = valid; bad[0] = 255; invalid.push_back(bad);
  bad = valid; bad[isoEnd] = 255; bad.resize(isoEnd + 2); invalid.push_back(bad);
  bad = valid; ultraslim::storeBE32(UINT32_MAX, bad.data() + isoEnd + 1); invalid.push_back(bad);
  // Structurally valid but invalid ISO metadata must not silently become SDR.
  bad = valid; std::fill(bad.begin() + 3, bad.begin() + isoEnd, 255); invalid.push_back(bad);
  for (size_t i = 0; i < invalid.size(); ++i) {
    const auto bytes = jxlWithGainMapBox(invalid[i]);
    const auto path = output + "/malformed-jhgm-" + std::to_string(i) + ".jxl"; save(path, bytes);
    const auto probe = ultraslim::probeImageMemory(bytes);
    check(probe.errorCode != 0 && !probe.hasGainMap, "malformed JXL gain map rejected during probe");
    rejected(path, path + "-output");
  }
}

int main(int argc, char** argv) {
  check(argc == 2, "usage metadata_regression <output-dir>");
  const std::string output = argv[1]; ultraslim_codec_threads = 2;
  const auto exif = cameraExif();
  exifRoundtrips(output, exif); truncatedJpegs(output, exif); malformedJxl(output);
  std::cout << "PASS: EXIF dates/camera fields across SDR/HDR formats, JXL headroom/bounds, truncated JPEG rejection\n";
}
