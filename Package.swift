// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "APTradeLite",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "APTradeApp", targets: ["APTradeApp"])
    ],
    targets: [
        .binaryTarget(
            name: "Shared",
            path: "shared/build/XCFrameworks/release/Shared.xcframework"
        ),
        .target(name: "APTradeDomain"),
        .target(name: "APTradeApplication", dependencies: ["APTradeDomain"]),
        .target(name: "APTradeInfrastructure", dependencies: ["APTradeApplication", "APTradeDomain", "Shared"]),
        .target(
            name: "APTradeApp",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain"],
            resources: [.process("Resources")]
        ),
        .executableTarget(
            name: "APTradeMac",
            dependencies: ["APTradeApp"]
        ),
        .testTarget(name: "APTradeDomainTests", dependencies: ["APTradeDomain"]),
        .testTarget(name: "APTradeApplicationTests", dependencies: ["APTradeApplication", "APTradeDomain"]),
        .testTarget(
            name: "APTradeInfrastructureTests",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain", "Shared"],
            resources: [.process("Fixtures")]
        ),
        .testTarget(name: "APTradeAppTests", dependencies: ["APTradeApp", "APTradeApplication", "APTradeDomain"]),
    ]
)
