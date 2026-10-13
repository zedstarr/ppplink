// swift-tools-version:5.7
// PPP Link menu-bar app. Build the .app with ./make-app.sh (or `make app` in macos/).
import PackageDescription

let package = Package(
    name: "PPPLink",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "PPPLink", path: "Sources/PPPLink")
    ]
)
