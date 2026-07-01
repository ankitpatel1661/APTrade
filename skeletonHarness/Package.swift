// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SkeletonHarness",
    platforms: [.macOS(.v13)],
    targets: [
        .binaryTarget(
            name: "Shared",
            path: "../shared/build/XCFrameworks/release/Shared.xcframework"
        ),
        .executableTarget(
            name: "SkeletonHarness",
            dependencies: ["Shared"],
            path: "Sources/SkeletonHarness"
        ),
    ]
)
