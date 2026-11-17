// swift-tools-version: 5.9
import PackageDescription
let package = Package(name: "FamicalCore", products: [.library(name: "FamicalCore", targets: ["FamicalCore"])], targets: [.target(name: "FamicalCore"), .testTarget(name: "FamicalCoreTests", dependencies: ["FamicalCore"])])
