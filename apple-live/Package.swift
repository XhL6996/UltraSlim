// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "UltraSlimLive", platforms: [.macOS("15.0")], products: [
    .executable(name: "UltraSlimLive", targets: ["UltraSlimLive"]),
    .executable(name: "livephoto-check", targets: ["livephoto-check"]),
], targets: [
    .target(name: "LivePhotoCore"),
    .executableTarget(name: "UltraSlimLive", dependencies: ["LivePhotoCore"]),
    .executableTarget(name: "livephoto-check", dependencies: ["LivePhotoCore"]),
    .testTarget(name: "LivePhotoCoreTests", dependencies: ["LivePhotoCore"]),
])
