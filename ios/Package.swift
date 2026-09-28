// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ImageResizerCore",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "ImageResizerCore", targets: ["ImageResizerCore"])
    ],
    targets: [
        .target(
            name: "ImageResizerCore",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        // Test runner (zero-dependency harness — XCTest/swift-testing cannot discover
        // tests on Command Line Tools-only machines). Run: swift run ImageResizerCoreTests
        .executableTarget(
            name: "ImageResizerCoreTests",
            dependencies: ["ImageResizerCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
