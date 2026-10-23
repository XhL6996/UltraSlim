// Include the implementation so parity tests can retain the original in-memory
// parser and pixel-resize reference without exporting test-only production APIs.
#include "../jni/transcode.cpp"
#include <fcntl.h>
#include <cstdlib>
#include <iostream>

static void check(bool ok, const char* message) {
  if (!ok) { std::cerr << "FAIL: " << message << '\n'; std::exit(1); }
}
static int openFile(const std::string& path) {
  int fd = open(path.c_str(), O_RDONLY); check(fd >= 0, "open fixture"); return fd;
}
static void writeFile(const std::string& path, const std::vector<uint8_t>& data) {
  int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY, 0600);
  check(fd >= 0 && ultraslim::writeAll(fd, data.data(), data.size()), "write fixture"); close(fd);
}
static void equalProbe(const ultraslim::ProbeResult& a, const ultraslim::ProbeResult& b) {
  check(a.errorCode == b.errorCode && a.error == b.error && a.container == b.container &&
        a.width == b.width && a.height == b.height && a.hasGainMap == b.hasGainMap &&
        a.gainmapWidth == b.gainmapWidth && a.gainmapHeight == b.gainmapHeight && a.hasExif == b.hasExif &&
        a.maxContentBoost == b.maxContentBoost && a.hdrCapacityMax == b.hdrCapacityMax, "probe field parity");
}
static void parity(const std::string& path) {
  int fd = openFile(path); auto data = ultraslim::readAll(fd);
  auto old = ultraslim::probeImageMemory(data); auto fresh = ultraslim::probeImage(fd);
  equalProbe(old, fresh);
  ultraslim::DecoderPtr decoder;
  equalProbe(old, ultraslim::probeImageMemory(data, &decoder));
  if (old.hasGainMap && old.container != 3) check(bool(decoder), "decoder handoff retains metadata");
  close(fd);
}
static std::vector<uint8_t> load(const std::string& path) {
  int fd = openFile(path); auto data = ultraslim::readAll(fd); close(fd); return data;
}
static void preview(const std::string& path, int limit) {
  int fd = openFile(path);
  check(ultraslim::decodePreviewJpeg(fd, limit, 82, 1).empty(), "tiny preview budget rejects");
  check(ultraslim::decodePreviewJpeg(fd, limit, 82, 16u * 1024 * 1024).empty(), "header budget rejects before pixel allocation");
  auto bytes = ultraslim::decodePreviewJpeg(fd, limit);
  check(!bytes.empty(), "preview succeeds");
  int w = 0, h = 0; bool exif = false;
  check(ultraslim::probeJpegDims(bytes, w, h, exif) && std::max(w, h) <= limit, "preview dimensions bounded");
  // Same decoder, original full-copy -> resize -> JPEG pipeline. Equality covers
  // channel order, row padding, interpolation, ICC/NCLX and HEIF transforms.
  auto data = ultraslim::readAll(fd); ultraslim::SdrImage original; std::string error;
  if (ultraslim::sniffContainer(data.data(), data.size()) == 1 || ultraslim::sniffContainer(data.data(), data.size()) == 2) {
    check(ultraslim::decodeHeifSdr(data, original, error), "reference full decode");
    int edge = std::max(original.width, original.height);
    if (edge > limit) original = ultraslim::resizeRgba(original, std::max(1, original.width * limit / edge), std::max(1, original.height * limit / edge));
    check(ultraslim::prepareJpegProfile(original), "reference color profile");
    check(bytes == ultraslim::encodeJpegToMem(original, 82), "direct-plane preview matches reference JPEG exactly");
  }
  close(fd);
}
static void conversionResizeParity(const std::string& path) {
  auto bytes = load(path);
  ultraslim::SdrImage full; std::string error;
  check(ultraslim::decodeHeifSdr(bytes, full, error), "full conversion reference decode");
  for (int percent : {1, 37, 50, 99, 100}) {
    int width, height; ultraslim::scaledSize(full.width, full.height, percent, width, height);
    auto reference = full;
    ultraslim::maybeDownscale(reference, width, height);
    ultraslim::SdrImage direct;
    check(ultraslim::decodeHeifSdr(bytes, direct, error, percent), "direct conversion resize decode");
    check(direct.width == reference.width && direct.height == reference.height && direct.rgba == reference.rgba,
          "conversion direct-plane resize matches full-copy reference pixels and even rounding");
    check(direct.exif == reference.exif && direct.icc == reference.icc &&
          direct.hasProfile == reference.hasProfile, "conversion resize retains EXIF and color profile");
    if (direct.hasProfile) check(direct.profile.color_primaries == reference.profile.color_primaries &&
        direct.profile.transfer_characteristics == reference.profile.transfer_characteristics &&
        direct.profile.matrix_coefficients == reference.profile.matrix_coefficients &&
        direct.profile.full_range_flag == reference.profile.full_range_flag, "conversion resize retains NCLX");
  }
}
static void jxlBufferLifetime(const std::string& source, const std::string& output) {
  auto data = load(source); ultraslim::SdrImage base; std::string error;
  check(ultraslim::decodeJpegSdr(data, base, error), "JXL lifetime source decode");
  // Compare the same encode with and without retained retry pixels. Decoding after
  // source storage is released also exercises AddImageFrame's ownership contract.
  for (bool retry : {false, true}) {
    std::vector<uint8_t> rgb; ultraslim::prepareJxlRgb(base, rgb);
    const auto path = output + (retry ? "/jxl-retained.jxl" : "/jxl-released.jxl");
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    check(fd >= 0 && ultraslim::encodeJxl(base, 90, true, fd, 2, 2, nullptr, rgb, retry, error) == 0,
          "JXL encode after input-buffer lifetime change");
    close(fd);
    check(retry ? !rgb.empty() : rgb.capacity() == 0, "only retry retains JXL main RGB allocation");
    ultraslim::SdrImage decoded;
    check(ultraslim::decodeJxlToSdr(load(path), decoded, nullptr, error) &&
          decoded.width == base.width && decoded.height == base.height && decoded.exif == base.exif,
          "JXL released-input output decodes with dimensions and EXIF");
  }
  check(load(output + "/jxl-retained.jxl") == load(output + "/jxl-released.jxl"),
        "JXL buffer retention does not change encoded bytes");
}
static void jxlGainmapBufferLifetime(const std::string& source, const std::string& output) {
  auto data = load(source); ultraslim::DecoderPtr decoder;
  check(ultraslim::probeImageMemory(data, &decoder).hasGainMap && bool(decoder), "JXL lifetime HDR source");
  ultraslim::SdrImage base; ultraslim::GainMapPayload payload; std::string error;
  auto baseBytes = ultraslim::copyBlock(uhdr_dec_get_base_image(decoder.get()));
  auto gmBytes = ultraslim::copyBlock(uhdr_dec_get_gainmap_image(decoder.get()));
  check(ultraslim::decodeJpegSdr(baseBytes, base, error) &&
        ultraslim::decodeJpegSdr(gmBytes, payload.image, error), "JXL lifetime HDR pixels");
  const auto originalMap = payload.image;
  payload.iso = ultraslim::isoFromMetadata(*uhdr_dec_get_gainmap_metadata(decoder.get()));
  payload.multiChannel = ultraslim::jpegChannels(gmBytes) > 1;
  payload.cacheForRetry = true;
  std::vector<uint8_t> rgb; ultraslim::prepareJxlRgb(base, rgb);
  for (int quality : {90, 85}) {
    const auto path = output + "/jxl-map-lifetime-" + std::to_string(quality) + ".jxl";
    int fd = open(path.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
    check(fd >= 0 && ultraslim::encodeJxl(base, quality, true, fd, 2, 2, &payload, rgb, true, error) == 0,
          "JXL HDR encode reuses cached map after releasing its source pixels");
    close(fd);
    check(payload.image.rgba.capacity() == 0 && !payload.encodedJxl.empty(),
          "JXL retry retains compressed gain map instead of source RGBA");
    ultraslim::SdrImage decodedBase, decodedMap; std::vector<uint8_t> jhgm;
    check(ultraslim::decodeJxlToSdr(load(path), decodedBase, &jhgm, error), "JXL HDR lifetime output decodes");
    ultraslim::JxlGainMapView parsed;
    check(ultraslim::parseJxlGainMap(jhgm, parsed), "JXL lifetime ISO payload");
    const std::vector<uint8_t> stream(parsed.codestream.data(), parsed.codestream.data() + parsed.codestream.size());
    check(ultraslim::decodeJxlToSdr(stream, decodedMap, nullptr, error) &&
          decodedMap.width == originalMap.width && decodedMap.height == originalMap.height &&
          decodedMap.rgba == originalMap.rgba, "released JXL gain-map pixels remain exact through retry");
    check(std::vector<uint8_t>(parsed.iso.data(), parsed.iso.data() + parsed.iso.size()) == payload.iso,
          "JXL lifetime retains exact ISO metadata");
  }
}
static std::string convert(const std::string& source, const std::string& target, int codec, bool compatibility) {
  int in = openFile(source), out = open(target.c_str(), O_CREAT | O_TRUNC | O_RDWR, 0600);
  ultraslim::TranscodeOptions options; options.outCodec = codec; options.speedLevel = 2; options.codecThreads = 2;
  options.heicGainmapCompatibility = options.avifGainmapCompatibility = compatibility;
  auto result = ultraslim::transcodeImage(in, out, options); close(in); close(out);
  if (result.errorCode) std::cerr << result.error << '\n';
  check(result.errorCode == 0, "create conversion fixture"); return target;
}
static void thumbnailFixture(const std::string& path, bool largeFtyp = false) {
  auto* ctx = heif_context_alloc(); heif_encoder* enc = nullptr;
  if (largeFtyp) for (uint32_t i = 0; i < 300; ++i) heif_context_add_compatible_brand(ctx, heif_brand2(0x7a000000u + i));
  check(heif_context_get_encoder_for_format(ctx, heif_compression_HEVC, &enc).code == heif_error_Ok, "thumbnail encoder");
  heif_encoder_set_parameter_string(enc, "preset", "ultrafast");
  heif_image* image = nullptr;
  check(heif_image_create(1024, 768, heif_colorspace_RGB, heif_chroma_interleaved_RGB, &image).code == heif_error_Ok, "thumbnail source");
  check(heif_image_add_plane(image, heif_channel_interleaved, 1024, 768, 8).code == heif_error_Ok, "thumbnail plane");
  int stride; auto* pixels = heif_image_get_plane(image, heif_channel_interleaved, &stride);
  for (int y = 0; y < 768; ++y) for (int x = 0; x < 1024; ++x) {
    pixels[y * stride + 3*x] = x % 256; pixels[y * stride + 3*x + 1] = y % 256; pixels[y * stride + 3*x + 2] = 73;
  }
  heif_image_handle *base = nullptr, *thumb = nullptr;
  check(heif_context_encode_image(ctx, image, enc, nullptr, &base).code == heif_error_Ok, "encode thumbnail base");
  check(heif_context_encode_thumbnail(ctx, image, base, enc, nullptr, 192, &thumb).code == heif_error_Ok && thumb, "encode embedded thumbnail");
  check(heif_context_write_to_file(ctx, path.c_str()).code == heif_error_Ok, "save thumbnail fixture");
  heif_image_handle_release(thumb); heif_image_handle_release(base); heif_image_release(image); heif_encoder_release(enc); heif_context_free(ctx);
}
static void rotatedOddFixture(const std::string& path, int codec, int orientation) {
  auto* ctx = heif_context_alloc(); heif_encoder* encoder = nullptr;
  const heif_encoder_descriptor* descriptor = nullptr;
  check(heif_context_get_encoder_descriptors(ctx, codec == 1 ? heif_compression_HEVC : heif_compression_AV1,
      codec == 1 ? "x265" : "aom", &descriptor, 1) == 1, "odd fixture encoder descriptor");
  check(heif_context_get_encoder(ctx, descriptor, &encoder).code == heif_error_Ok, "odd fixture encoder");
  if (codec == 1) heif_encoder_set_parameter_string(encoder, "preset", "ultrafast");
  else heif_encoder_set_parameter_integer(encoder, "speed", 8);
  heif_image* image = nullptr;
  check(heif_image_create(163, 99, heif_colorspace_RGB, heif_chroma_interleaved_RGB, &image).code == heif_error_Ok &&
        heif_image_add_plane(image, heif_channel_interleaved, 163, 99, 8).code == heif_error_Ok, "odd fixture plane");
  int stride; auto* pixels = heif_image_get_plane(image, heif_channel_interleaved, &stride);
  for (int y = 0; y < 99; ++y) for (int x = 0; x < 163; ++x) {
    pixels[y * stride + 3*x] = uint8_t(x * 13 + y * 7);
    pixels[y * stride + 3*x + 1] = uint8_t(x * 3 + y * 11);
    pixels[y * stride + 3*x + 2] = uint8_t(x + y * 17);
  }
  auto* options = heif_encoding_options_alloc(); check(options, "odd fixture options");
  options->image_orientation = static_cast<heif_orientation>(orientation);
  heif_image_handle* handle = nullptr;
  check(heif_context_encode_image(ctx, image, encoder, options, &handle).code == heif_error_Ok &&
        heif_context_write_to_file(ctx, path.c_str()).code == heif_error_Ok, "write rotated odd fixture");
  heif_encoding_options_free(options); heif_image_handle_release(handle);
  heif_image_release(image); heif_encoder_release(encoder); heif_context_free(ctx);
}
int main(int argc, char** argv) {
  check(argc == 3, "usage preview_probe fixtures output");
  const std::string fixtures = argv[1], output = argv[2];
  // The C reader advertises only the requested initial 1024 bytes; the memory
  // reader advertises the entire file. A larger valid ftyp requires fallback.
  const auto largeFtyp = output + "/preview-large-ftyp.heic";
  thumbnailFixture(largeFtyp, true);
  auto largeBytes = load(largeFtyp);
  check(ultraslim::loadBE32(largeBytes.data()) > 1024, "large ftyp fixture");
  auto memoryProbe = ultraslim::probeImageMemory(largeBytes);
  check(memoryProbe.errorCode == 0 && memoryProbe.width == 1024, "memory accepts large ftyp");
  int largeFd = openFile(largeFtyp);
  ultraslim::HeifFdReader largeReader(largeFd); check(largeReader.open(), "large ftyp reader");
  check(ultraslim::probeHeifReader(largeReader, largeReader.container()).errorCode == 4, "direct streaming rejects large ftyp");
  equalProbe(memoryProbe, ultraslim::probeImage(largeFd));
  check(!ultraslim::decodePreviewJpeg(largeFd, 128, 82, 20u * 1024 * 1024).empty(), "preview parse fallback succeeds");
  check(ultraslim::decodePreviewJpeg(largeFd, 513, 82, 20u * 1024 * 1024).empty(), "fallback still admits selected pixels");
  close(largeFd);
  const auto largeInput = output + "/preview-large-ftyp-input.heic";
  writeFile(largeInput, largeBytes);
  largeFd = open(largeInput.c_str(), O_RDWR);
  check(largeFd >= 0 && ftruncate(largeFd, 32u * 1024 * 1024) == 0, "large fallback input");
  check(ultraslim::decodePreviewJpeg(largeFd, 128, 82, 20u * 1024 * 1024).empty(), "fallback input allocation respects budget");
  close(largeFd);
  std::vector<uint8_t> conflicting = {0xff, 0xd8, 0, 0, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'};
  writeFile(output + "/probe-conflicting-signature", conflicting);
  parity(output + "/probe-conflicting-signature");
  ultraslim_codec_threads = 2;
  const auto jpg = fixtures + "/apple_gainmap_new.jpg";
  const auto sdr = fixtures + "/minnie-320x240-yuv-icc.jpg";
  parity(jpg); parity(sdr); preview(jpg, 97); jxlBufferLifetime(sdr, output);
  jxlGainmapBufferLifetime(jpg, output);
  // Padded stride and exact no-resize copy must ignore row padding.
  std::vector<uint8_t> packed(7 * 5 * 4), padded(40 * 5, 0xff);
  for (size_t i = 0; i < packed.size(); ++i) packed[i] = uint8_t(i * 31);
  for (int y = 0; y < 5; ++y) memcpy(padded.data() + y * 40, packed.data() + y * 28, 28);
  check(ultraslim::resizeRgbaPlane(padded.data(), 7, 5, 40, 7, 5) == packed, "padded plane exact copy");
  check(ultraslim::resizeRgbaPlane(padded.data(), 7, 5, 40, 3, 2) == ultraslim::resizeRgbaPlane(packed.data(), 7, 5, 28, 3, 2), "padded plane resize");
  check(!ultraslim::previewFits(10001, 10000, 64, UINT64_MAX), "pixel ceiling predecode");
  check(!ultraslim::previewFits(INT32_MAX, INT32_MAX, 64, UINT64_MAX), "overflow dimensions rejected");
  for (int codec : {1, 2}) for (bool compatibility : {false, true}) {
    const auto path = convert(jpg, output + "/probe-hdr-" + std::to_string(codec) + (compatibility ? "-compat" : "-strict"), codec, compatibility);
    parity(path); preview(path, 97); preview(path, 4096); conversionResizeParity(path);
    auto data = load(path);
    int directFd = openFile(path);
    ultraslim::HeifFdReader directReader(directFd); check(directReader.open(), "direct reader opens");
    equalProbe(ultraslim::probeImageMemory(data), ultraslim::probeHeifReader(directReader, directReader.container()));
    ultraslim::SdrImage rejected; std::string rejectedError; bool parseFailed = true;
    check(!ultraslim::decodeHeifPreview({}, rejected, rejectedError, 97, 16u * 1024 * 1024, &directReader, &parseFailed) && !parseFailed,
          "pixel budget rejection cannot request memory fallback");
    close(directFd);
    auto* ctx = heif_context_alloc(); check(heif_context_read_from_memory_without_copy(ctx, data.data(), data.size(), nullptr).code == heif_error_Ok, "read metadata fixture");
    heif_image_handle* base = nullptr; heif_context_get_primary_image_handle(ctx, &base);
    std::vector<uint8_t> metadata(heif_image_handle_get_gain_map_metadata_size(base));
    check(!metadata.empty() && heif_image_handle_get_gain_map_metadata(base, metadata.data()).code == heif_error_Ok, "get ISO metadata");
    heif_image_handle_release(base); heif_context_free(ctx);
    auto where = std::search(data.begin(), data.end(), metadata.begin(), metadata.end());
    check(where != data.end() && where != data.begin(), "locate ISO payload");
    const size_t offset = where - data.begin();
    auto broken = data; broken[offset - 1] = 1; // unsupported ToneMapImage version
    writeFile(path + "-version", broken); parity(path + "-version");
    broken = data; std::fill(broken.begin() + offset, broken.begin() + offset + metadata.size(), 0xff);
    writeFile(path + "-metadata", broken); parity(path + "-metadata");
    broken = data; memcpy(broken.data() + 8, "hevx", 4); // UHDR's unsupported-brand fallback
    writeFile(path + "-brand", broken); parity(path + "-brand");
    broken = data; broken.resize(32); writeFile(path + "-truncated", broken); parity(path + "-truncated");
    // Large trailing data is never read by the streaming metadata probe.
    const auto sparse = path + "-sparse"; writeFile(sparse, data);
    int fd = open(sparse.c_str(), O_RDWR); check(fd >= 0 && ftruncate(fd, 32 * 1024 * 1024) == 0, "sparse fixture");
    ultraslim::HeifFdReader reader(fd); check(reader.open(), "reader opens");
    const int container = reader.container();
    equalProbe(ultraslim::probeImageMemory(data), ultraslim::probeHeifReader(reader, container));
    check(reader.bytesRead < 1024 * 1024, "probe reads metadata, not whole payload");
    check(lseek(fd, 0, SEEK_CUR) == 0, "reader leaves fd cursor unchanged");
    check(ultraslim::readHeifXmp(fd).empty() && lseek(fd, 0, SEEK_CUR) == 0, "streaming XMP probe leaves fd cursor unchanged");
    std::cout << "probe file=32MiB read=" << reader.bytesRead << " bytes\n"; close(fd);
  }
  for (int codec : {1, 2, 3}) {
    auto path = convert(sdr, output + "/probe-sdr-" + std::to_string(codec), codec, false);
    parity(path); preview(path, 97);
    if (codec != 3) conversionResizeParity(path);
  }
  for (int codec : {1, 2}) for (int orientation = 1; orientation <= 8; ++orientation) {
    const auto path = output + "/resize-odd-" + std::to_string(codec) + "-" + std::to_string(orientation);
    rotatedOddFixture(path, codec, orientation); conversionResizeParity(path);
  }
  const auto thumb = output + "/preview-thumbnail.heic"; thumbnailFixture(thumb); parity(thumb);
  int fd = openFile(thumb);
  const uint64_t smallBudget = 20u * 1024 * 1024;
  check(!ultraslim::decodePreviewJpeg(fd, 128, 82, smallBudget).empty(), "budget uses selected thumbnail, not full primary");
  check(ultraslim::decodePreviewJpeg(fd, 513, 82, smallBudget).empty(), "primary over budget rejects when thumbnail insufficient");
  close(fd);
  auto huge = load(thumb);
  for (size_t i = 4; i + 16 <= huge.size(); ++i) if (!memcmp(huge.data() + i, "ispe", 4)) {
    ultraslim::storeBE32(10001, huge.data() + i + 8); ultraslim::storeBE32(10000, huge.data() + i + 12);
  }
  auto hugePrimary = load(thumb);
  for (size_t i = 4; i + 16 <= hugePrimary.size(); ++i) if (!memcmp(hugePrimary.data() + i, "ispe", 4)) {
    if (ultraslim::loadBE32(hugePrimary.data() + i + 8) == 1024) {
      ultraslim::storeBE32(10001, hugePrimary.data() + i + 8); ultraslim::storeBE32(10000, hugePrimary.data() + i + 12);
    }
  }
  writeFile(thumb + "-huge-primary", hugePrimary); fd = openFile(thumb + "-huge-primary");
  check(!ultraslim::decodePreviewJpeg(fd, 128, 82, smallBudget).empty(), "oversized unselected primary does not reject safe thumbnail"); close(fd);
  writeFile(thumb + "-huge", huge); fd = openFile(thumb + "-huge");
  check(ultraslim::decodePreviewJpeg(fd, 128).empty(), "oversized handles rejected before decode"); close(fd);
  // Nonseekable JPEG uses the existing whole-file fallback.
  auto bytes = load(sdr); int pipefd[2]; check(pipe(pipefd) == 0, "pipe");
  std::thread writer([&] { ultraslim::writeAll(pipefd[1], bytes.data(), bytes.size()); close(pipefd[1]); });
  equalProbe(ultraslim::probeImageMemory(bytes), ultraslim::probeImage(pipefd[0])); close(pipefd[0]); writer.join();
  std::cout << "PASS: streaming probe parity, metadata failures, bounded previews, direct-plane pixels and thumbnail budget\n";
}
