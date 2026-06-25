// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "APTradeLite",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "APTradeDomain"),
        .target(name: "APTradeApplication", dependencies: ["APTradeDomain"]),
        .target(name: "APTradeInfrastructure", dependencies: ["APTradeApplication", "APTradeDomain"]),
        .executableTarget(
            name: "APTradeApp",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain"],
            resources: [.process("Resources")]
        ),
        .testTarget(name: "APTradeDomainTests", dependencies: ["APTradeDomain"]),
        .testTarget(name: "APTradeApplicationTests", dependencies: ["APTradeApplication", "APTradeDomain"]),
        .testTarget(
            name: "APTradeInfrastructureTests",
            dependencies: ["APTradeInfrastructure", "APTradeApplication", "APTradeDomain"],
            resources: [.process("Fixtures")]
        ),
        .testTarget(name: "APTradeAppTests", dependencies: ["APTradeApp", "APTradeApplication", "APTradeDomain"]),
    ]
)
