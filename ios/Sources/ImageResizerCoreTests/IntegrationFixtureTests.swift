import CoreGraphics
import Foundation
import ImageIO
import ImageResizerCore

// End-to-end tests: real ImageIO codec + golden fixtures from testdata/.
// Regenerate fixtures with: python3 scripts/make_fixtures.py

private var fixtures: URL {
    URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // ImageResizerCoreTests/
        .deletingLastPathComponent() // Sources/
        .deletingLastPathComponent() // ios/
        .deletingLastPathComponent() // repo root
        .appendingPathComponent("testdata")
}

private func fixture(_ name: String) -> URL {
    fixtures.appendingPathComponent(name)
}

private func skipUnlessFixtures() throws {
    try skipUnless(
        FileManager.default.fileExists(atPath: fixture("small_photo.jpg").path),
        "fixtures missing — run: python3 scripts/make_fixtures.py")
}

/// Each integration test gets an isolated temp output directory, cleaned up after.
private func withIntegrationEnvironment(_ body: (URL) throws -> Void) throws {
    try skipUnlessFixtures()
    let temp = FileManager.default.temporaryDirectory
        .appendingPathComponent("fixture-tests-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: temp, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: temp) }
    try body(temp)
}

private func resize(_ name: String, target: Int64, outputDir: URL) throws -> (ResizeResult, URL) {
    let input = fixture(name)
    let session = ImageIOResizeSession(url: input)
    let result = try TargetSizeResizer().run(session: session, targetBytes: target)
    let output = outputDir.appendingPathComponent("out-\(name)")
    let onDisk = try DiskOutput.writeVerified(result.data, to: output,
                                              targetBytes: target)
    XCTAssertEqual(onDisk, result.report.bytes,
                   "on-disk size must equal the measured size")
    return (result, output)
}

private func properties(of url: URL) -> [CFString: Any]? {
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else {
        return nil
    }
    return CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
}

private func fileSize(_ url: URL) -> Int64 {
    (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? -1
}

/// Raw-byte hunt for the GPS latitude rationals 48/1, 51/1, 30/1 (48°51'30"N),
/// in either TIFF byte order. Physical presence/absence in the file itself —
/// decoder property tables can lie; bytes cannot.
private func gpsLatitudeBytesPresent(_ data: Data) -> Bool {
    let values: [UInt32] = [48, 1, 51, 1, 30, 1]
    for littleEndian in [false, true] {
        var needle = Data(capacity: values.count * 4)
        for v in values {
            withUnsafeBytes(of: littleEndian ? v.littleEndian : v.bigEndian) {
                needle.append(contentsOf: $0)
            }
        }
        if data.range(of: needle) != nil { return true }
    }
    return false
}

// MARK: - Typical path: quality search at full resolution

func testSmoothJPEG12MBTo5MBKeepsFullResolution() throws {
    try withIntegrationEnvironment { temp in
        let (result, output) = try resize("photo_smooth_12mb.jpg",
                                          target: 5 * 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .jpeg)
        XCTAssertLessThanOrEqual(result.report.bytes, 5 * 1024 * 1024)
        XCTAssertEqual(result.report.pixelWidth, 4000, "resolution must be preserved")
        XCTAssertEqual(result.report.pixelHeight, 3000)
        XCTAssertLessThanOrEqual(result.report.encodesUsed, 24)
        XCTAssertNotNil(result.report.quality)
        XCTAssertGreaterThan(fileSize(output), 0)
    }
}

// MARK: - Hard case: reduction required

func testNoiseJPEGTo1MBAlwaysUnderTarget() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("photo_noise_large.jpg",
                                     target: 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertLessThanOrEqual(result.report.bytes, 1024 * 1024)
        XCTAssertLessThanOrEqual(result.report.encodesUsed, 24 + 8)
    }
}

// MARK: - Pass-through

func testSmallJPEGPassesThroughWithoutReencode() throws {
    try withIntegrationEnvironment { temp in
        let input = fixture("small_photo.jpg")
        let (result, output) = try resize("small_photo.jpg",
                                          target: 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .passThrough)
        XCTAssertEqual(result.report.encodesUsed, 0)
        XCTAssertLessThanOrEqual(result.report.bytes, 1024 * 1024)
        // Validates the no-re-encode assumption: bytes ≈ original (+ tiny software tag).
        XCTAssertLessThanOrEqual(result.report.bytes, fileSize(input) + 512,
                                 "pass-through must copy bytes, not re-encode")
        XCTAssertLessThanOrEqual(fileSize(output), fileSize(input) + 512)
    }
}

func testFlatPNGPassesThroughKeepingPNGContainer() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("flat_graphic.png",
                                     target: 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .passThrough)
        XCTAssertEqual(result.report.encodesUsed, 0)
        XCTAssertLessThanOrEqual(result.report.bytes,
                                 fileSize(fixture("flat_graphic.png")) + 512)
    }
}

// MARK: - Orientation + EXIF/GPS policy

func testPortraitOrientationReencodeBakesOrientationAndStripsGPS() throws {
    try withIntegrationEnvironment { temp in
        let source = try XCTUnwrap(properties(of: fixture("portrait_orient6.jpg")))
        XCTAssertNotNil(source[kCGImagePropertyGPSDictionary],
                        "fixture must contain GPS — otherwise the strip test is vacuous")

        let (result, output) = try resize("portrait_orient6.jpg",
                                          target: 800 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertLessThanOrEqual(result.report.bytes, 800 * 1024)
        // Stored 2000×1500 with Orientation=6. Noise may force a resolution
        // reduction at this target, so assert the invariants: upright portrait
        // (orientation baked), never upscaled, within target.
        XCTAssertGreaterThan(result.report.pixelHeight, result.report.pixelWidth,
                             "orientation must be baked → upright portrait")
        XCTAssertLessThanOrEqual(result.report.pixelWidth, 1500)
        XCTAssertLessThanOrEqual(result.report.pixelHeight, 2000)

        let out = try XCTUnwrap(properties(of: output))
        XCTAssertNil(out[kCGImagePropertyGPSDictionary], "GPS must always be stripped")
        let orientation = (out[kCGImagePropertyOrientation] as? NSNumber)?.intValue
        XCTAssertTrue(orientation == nil || orientation == 1, "output must be upright")
        let tiff = out[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        XCTAssertEqual(tiff?[kCGImagePropertyTIFFModel] as? String, "TestModel",
                       "camera model should be preserved")
    }
}

func testPortraitPassThroughStripsGPSKeepsOrientation() throws {
    try withIntegrationEnvironment { temp in
        let (result, output) = try resize("portrait_orient6.jpg",
                                          target: 10 * 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .passThrough)
        XCTAssertLessThanOrEqual(result.report.bytes,
                                 fileSize(fixture("portrait_orient6.jpg")) + 512)

        let out = try XCTUnwrap(properties(of: output))
        XCTAssertNil(out[kCGImagePropertyGPSDictionary],
                     "GPS stripped even on pass-through")
        // Pixels unchanged on this path, so the original orientation flag must remain
        // (readers apply it).
        let orientation = (out[kCGImagePropertyOrientation] as? NSNumber)?.intValue
        XCTAssertEqual(orientation, 6,
                       "pass-through keeps orientation because pixels are untouched")

        // Forensic check: decoder properties can be wrong, bytes cannot.
        let inputBytes = try Data(contentsOf: fixture("portrait_orient6.jpg"))
        let outputBytes = try Data(contentsOf: output)
        XCTAssertTrue(gpsLatitudeBytesPresent(inputBytes),
                      "fixture must contain raw GPS bytes — otherwise this check is vacuous")
        XCTAssertFalse(gpsLatitudeBytesPresent(outputBytes),
                       "GPS coordinates must be physically zeroed, not just hidden from the decoder")
    }
}

// MARK: - Format matrix on real files

func testPhotoPNGWithoutAlphaConvertsToJPEGKeepingResolution() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("photo_png_noalpha.png",
                                     target: 2 * 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .jpeg)
        XCTAssertLessThanOrEqual(result.report.bytes, 2 * 1024 * 1024)
        XCTAssertEqual(result.report.pixelWidth, 1600, "resolution must be preserved")
        XCTAssertEqual(result.report.pixelHeight, 1600)
    }
}

func testTransparentPNGStaysPNGReducesPixelsToFit() throws {
    try withIntegrationEnvironment { temp in
        let (result, output) = try resize("transparent_noise.png",
                                          target: 500 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .png)
        XCTAssertLessThanOrEqual(result.report.bytes, 500 * 1024)
        XCTAssertLessThan(result.report.pixelWidth, 1000,
                          "lossless PNG can only shrink by pixels")

        let out = try XCTUnwrap(properties(of: output))
        XCTAssertEqual((out[kCGImagePropertyHasAlpha] as? Bool) ?? false, true,
                       "transparency must survive")
    }
}

func testHEICToSmallerHEIC() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("photo.heic", target: 1024 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .heic)
        XCTAssertLessThanOrEqual(result.report.bytes, 1024 * 1024)
    }
}

func testPortraitHEICUprightAndGPSFree() throws {
    try withIntegrationEnvironment { temp in
        let (result, output) = try resize("portrait_orient6.heic",
                                          target: 400 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertLessThanOrEqual(result.report.bytes, 400 * 1024)
        // Orientation=6 source → upright portrait output; the noisy source may
        // reduce resolution to fit 400 KB, so assert invariants not exact dims.
        XCTAssertGreaterThan(result.report.pixelHeight, result.report.pixelWidth,
                             "orientation must be baked → upright portrait")
        XCTAssertLessThanOrEqual(result.report.pixelWidth, 1500)
        XCTAssertLessThanOrEqual(result.report.pixelHeight, 2000)

        let out = try XCTUnwrap(properties(of: output))
        XCTAssertNil(out[kCGImagePropertyGPSDictionary])
    }
}

func testOpaqueWebPConvertsToJPEG() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("test.webp", target: 300 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .jpeg,
                       "iOS cannot encode WebP → JPEG for opaque input")
        XCTAssertLessThanOrEqual(result.report.bytes, 300 * 1024)
    }
}

func testTransparentWebPBecomesPNG() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("transparent.webp",
                                     target: 200 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .png,
                       "transparency must survive on iOS (no WebP encoder)")
        XCTAssertLessThanOrEqual(result.report.bytes, 200 * 1024)
    }
}

func testAnimatedGIFFirstFrameToJPEG() throws {
    try withIntegrationEnvironment { temp in
        let (result, _) = try resize("animated.gif", target: 4 * 1024, outputDir: temp)
        XCTAssertEqual(result.report.outcome, .processed)
        XCTAssertEqual(result.report.outputFormat, .jpeg)
        XCTAssertLessThanOrEqual(result.report.bytes, 4 * 1024)
    }
}

// MARK: - Failures

func testCorruptFileThrowsWithoutSuccess() throws {
    try withIntegrationEnvironment { temp in
        XCTAssertThrowsError(try resize("corrupt.jpg", target: 512 * 1024,
                                        outputDir: temp)) { error in
            guard let error = error as? ResizeError else {
                return XCTFail("expected ResizeError, got \(error)")
            }
            switch error {
            case .corrupted, .encodeFailed, .targetInfeasible: break
            default: XCTFail("unexpected error \(error)")
            }
        }
    }
}

func testEmptyFileThrowsCorrupted() throws {
    try withIntegrationEnvironment { temp in
        XCTAssertThrowsError(try resize("empty.jpg", target: 512 * 1024,
                                        outputDir: temp)) { error in
            XCTAssertEqual(error as? ResizeError, .corrupted)
        }
    }
}

// MARK: - Disk guarantee

func testDiskOutputRejectsOversizedDataAndDeletesFile() throws {
    try withIntegrationEnvironment { temp in
        let target: Int64 = 1024
        let url = temp.appendingPathComponent("oversized.jpg")
        XCTAssertThrowsError(
            try DiskOutput.writeVerified(Data(count: 4096), to: url, targetBytes: target)
        ) { error in
            XCTAssertEqual(error as? ResizeError,
                           .targetExceeded(measured: 4096, target: target))
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path),
                       "violating output must not remain on disk")
    }
}

// MARK: - Mini batch

func testBatchTwoInputsProduceVerifiedOutputs() throws {
    try withIntegrationEnvironment { temp in
        let inputs = [("small_photo.jpg", Int64(1024 * 1024)),
                      ("photo_smooth_12mb.jpg", Int64(5 * 1024 * 1024))] as [(String, Int64)]
        var processed = 0
        var passThrough = 0
        for (name, target) in inputs {
            let (result, output) = try resize(name, target: target, outputDir: temp)
            XCTAssertLessThanOrEqual(result.report.bytes, target)
            XCTAssertGreaterThan(fileSize(output), 0)
            switch result.report.outcome {
            case .processed: processed += 1
            case .passThrough: passThrough += 1
            }
        }
        XCTAssertEqual(processed, 1)
        XCTAssertEqual(passThrough, 1)
    }
}
