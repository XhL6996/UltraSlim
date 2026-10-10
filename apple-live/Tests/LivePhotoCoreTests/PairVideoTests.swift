import XCTest
import AVFoundation
import CoreMedia
import CryptoKit
@testable import LivePhotoCore

/// pairVideo 此前零自动化覆盖：音频/视频 passthrough 完整性、Apple 配对元数据、
/// still-image-time 轨道此前只有手工 SHA256 记录，重构封装参数不会有任何测试报警。
final class PairVideoTests: XCTestCase {
    private let frames = 15

    private func makeSampleVideo(to url: URL, withAudio: Bool) throws {
        let writer = try AVAssetWriter(outputURL: url, fileType: .mov)
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: 64, AVVideoHeightKey: 48,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: videoInput, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 64, kCVPixelBufferHeightKey as String: 48,
        ])
        writer.add(videoInput)
        var audioInput: AVAssetWriterInput?
        if withAudio {
            // PCM 无需编码器，passthrough 直接逐字节比对即可验证完整性。
            audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: 8000,
                AVNumberOfChannelsKey: 1,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
            ])
            writer.add(audioInput!)
        }
        XCTAssertTrue(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        for frame in 0..<frames {
            waitForInput(videoInput)
            XCTAssertTrue(adaptor.append(makePixelBuffer(frame: frame), withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: 30)))
        }
        videoInput.markAsFinished()
        if let audio = audioInput {
            let totalFrames = 4000  // 0.5s @ 8kHz
            var emitted = 0
            while emitted < totalFrames {
                waitForInput(audio)
                let count = min(400, totalFrames - emitted)
                XCTAssertTrue(audio.append(try pcmSampleBuffer(sampleRate: 8000, frames: count, startFrame: emitted)))
                emitted += count
            }
            audio.markAsFinished()
        }
        let finished = expectation(description: "writer finished")
        writer.finishWriting { finished.fulfill() }
        wait(for: [finished], timeout: 30)
        XCTAssertEqual(writer.status, .completed, String(describing: writer.error))
    }

    private func waitForInput(_ input: AVAssetWriterInput) {
        var waited = 0.0
        while !input.isReadyForMoreMediaData && waited < 30 {
            Thread.sleep(forTimeInterval: 0.005); waited += 0.005
        }
        XCTAssertTrue(input.isReadyForMoreMediaData, "writer input 未就绪")
    }

    private func makePixelBuffer(frame: Int) -> CVPixelBuffer {
        var pixel: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, 64, 48, kCVPixelFormatType_32BGRA, nil, &pixel) == noErr, let buffer = pixel else {
            fatalError("无法创建像素缓冲")
        }
        CVPixelBufferLockBaseAddress(buffer, [])
        if let base = CVPixelBufferGetBaseAddress(buffer) {
            let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
            for y in 0..<CVPixelBufferGetHeight(buffer) {
                let row = base.assumingMemoryBound(to: UInt8.self) + y * rowBytes
                for x in 0..<CVPixelBufferGetWidth(buffer) {
                    row[x * 4 + 0] = UInt8(truncatingIfNeeded: frame * 17 + x)  // B
                    row[x * 4 + 1] = UInt8(truncatingIfNeeded: frame * 29)      // G
                    row[x * 4 + 2] = UInt8(truncatingIfNeeded: frame * 41 + y)  // R
                    row[x * 4 + 3] = 255
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(buffer, [])
        return buffer
    }

    private func pcmSampleBuffer(sampleRate: Int, frames: Int, startFrame: Int) throws -> CMSampleBuffer {
        var asbd = AudioStreamBasicDescription(mSampleRate: Double(sampleRate), mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked, mBytesPerPacket: 2, mFramesPerPacket: 1,
            mBytesPerFrame: 2, mChannelsPerFrame: 1, mBitsPerChannel: 16, mReserved: 0)
        var format: CMAudioFormatDescription?
        XCTAssertEqual(CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil,
            magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format), noErr)
        var samples = [Int16](repeating: 0, count: frames)
        for i in 0..<frames { samples[i] = (startFrame + i) % 2 == 0 ? 8000 : -8000 }
        let bytes = frames * 2
        var block: CMBlockBuffer?
        XCTAssertEqual(CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: bytes,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0, dataLength: bytes, flags: 0, blockBufferOut: &block), noErr)
        samples.withUnsafeBytes { raw in
            XCTAssertEqual(CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block!,
                offsetIntoDestination: 0, dataLength: bytes), noErr)
        }
        var timing = CMSampleTimingInfo(duration: CMTime(value: CMTimeValue(frames), timescale: CMTimeScale(sampleRate)),
            presentationTimeStamp: CMTime(value: CMTimeValue(startFrame), timescale: CMTimeScale(sampleRate)), decodeTimeStamp: .invalid)
        var buffer: CMSampleBuffer?
        XCTAssertEqual(CMAudioSampleBufferCreateWithPacketDescriptions(allocator: nil, dataBuffer: block!, dataReady: true,
            makeDataReadyCallback: nil, refcon: nil, formatDescription: format!, sampleCount: frames,
            presentationTimeStamp: timing.presentationTimeStamp, packetDescriptions: nil, sampleBufferOut: &buffer), noErr)
        return buffer!
    }

    /** 逐 sample 的压缩数据 SHA256：passthrough 完整性的最强校验。 */
    private func trackDigest(asset: AVURLAsset, mediaType: AVMediaType) async throws -> (digest: String, samples: Int)? {
        guard let track = try await asset.loadTracks(withMediaType: mediaType).first else { return nil }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        output.alwaysCopiesSampleData = false
        reader.add(output)
        reader.startReading()
        var hasher = SHA256(); var count = 0
        while let sample = output.copyNextSampleBuffer() {
            count += 1
            if let block = CMSampleBufferGetDataBuffer(sample) {
                let length = CMBlockBufferGetDataLength(block)
                var data = Data(count: length)
                data.withUnsafeMutableBytes { raw in
                    _ = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: raw.baseAddress!)
                }
                hasher.update(data: data)
            }
        }
        XCTAssertEqual(reader.status, .completed)
        return (hasher.finalize().map { String(format: "%02x", $0) }.joined(), count)
    }

    func testPairVideoPreservesTrackBytesAndWritesAppleMetadata() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let source = dir.appendingPathComponent("motion.mov"), target = dir.appendingPathComponent("paired.mov")
        try makeSampleVideo(to: source, withAudio: true)
        try await LivePhotoImport.pairVideo(source: source, destination: target, identifier: "pair-video-test", timestampUs: 250_000)

        let input = AVURLAsset(url: source), output = AVURLAsset(url: target)
        let inVideo = try await trackDigest(asset: input, mediaType: .video)
        let outVideo = try await trackDigest(asset: output, mediaType: .video)
        let inAudio = try await trackDigest(asset: input, mediaType: .audio)
        let outAudio = try await trackDigest(asset: output, mediaType: .audio)
        XCTAssertEqual(inVideo?.samples, outVideo?.samples)
        XCTAssertEqual(inVideo?.digest, outVideo?.digest, "视频 passthrough 被重编码")
        XCTAssertNotNil(inAudio); XCTAssertNotNil(outAudio)
        XCTAssertEqual(inAudio?.digest, outAudio?.digest, "音频 passthrough 被重编码或丢失")

        let metadata = try await output.load(.metadata)
        let contentId = metadata.first { $0.identifier?.rawValue.hasSuffix("content.identifier") == true }
        XCTAssertEqual(contentId?.stringValue, "pair-video-test", "缺少 Apple Live Photo 内容标识")

        let metadataTracks = try await output.loadTracks(withMediaType: .metadata)
        XCTAssertEqual(metadataTracks.count, 1, "缺少 still-image-time 元数据轨道")
    }

    func testPairVideoEstimatesTimestampWhenMissing() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let source = dir.appendingPathComponent("motion.mov"), target = dir.appendingPathComponent("paired.mov")
        try makeSampleVideo(to: source, withAudio: false)
        // presentationTimestampUs=-1 表示缺时间点；pairVideo 应取中点而不是越界。
        try await LivePhotoImport.pairVideo(source: source, destination: target, identifier: "pair-estimate-test", timestampUs: -1)
        XCTAssertTrue(FileManager.default.fileExists(atPath: target.path))
    }
}
