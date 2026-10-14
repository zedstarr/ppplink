// PPP Link for macOS - settings (UserDefaults), shared by the views and the controller.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.

import Foundation

enum SettingKey {
    static let port = "port"                  // "" = automatic
    static let baud = "baud"
    static let flowControl = "flowControl"
    static let mode = "mode"                  // "direct" or "modem"
    static let mtu = "mtu"
    static let dnsAutomatic = "dnsAutomatic"
    static let customDns = "customDns"
    static let startOnLaunch = "startOnLaunch"
    static let debugBridge = "debugBridge"
    static let bridgePort = "bridgePort"
    static let hexLog = "hexLog"
}

enum SettingDefault {
    static let baud = 115200
    static let flowControl = true
    static let mode = "direct"
    static let mtu = 1500
    static let dnsAutomatic = true
    static let customDns = "1.1.1.1"
    static let bridgePort = 8000
}

let baudRates = [9600, 19200, 38400, 57600, 115200, 230400]
let mtuChoices = [1500, 1006, 576, 296]

struct AppSettings {
    var port: String
    var baud: Int
    var flowControl: Bool
    var mode: String
    var mtu: Int
    var dnsAutomatic: Bool
    var customDns: String
    var debugBridge: Bool
    var bridgePort: Int
    var hexLog: Bool

    static func load(_ d: UserDefaults = .standard) -> AppSettings {
        func int(_ k: String, _ def: Int) -> Int { d.object(forKey: k) == nil ? def : d.integer(forKey: k) }
        func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }
        return AppSettings(
            port: d.string(forKey: SettingKey.port) ?? "",
            baud: int(SettingKey.baud, SettingDefault.baud),
            flowControl: bool(SettingKey.flowControl, SettingDefault.flowControl),
            mode: d.string(forKey: SettingKey.mode) ?? SettingDefault.mode,
            mtu: int(SettingKey.mtu, SettingDefault.mtu),
            dnsAutomatic: bool(SettingKey.dnsAutomatic, SettingDefault.dnsAutomatic),
            customDns: d.string(forKey: SettingKey.customDns) ?? SettingDefault.customDns,
            debugBridge: bool(SettingKey.debugBridge, false),
            bridgePort: int(SettingKey.bridgePort, SettingDefault.bridgePort),
            hexLog: bool(SettingKey.hexLog, false)
        )
    }
}

func isValidIPv4(_ s: String) -> Bool {
    let parts = s.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count == 4 else { return false }
    return parts.allSatisfy { p in
        !p.isEmpty && p.count <= 3 && p.allSatisfy(\.isASCIIDigit) && Int(p).map { $0 <= 255 } == true
    }
}

extension Character {
    var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}

/// Serial ports a user might pick: /dev/cu.* minus the built-in ones.
func listSerialPorts() -> [String] {
    let names = (try? FileManager.default.contentsOfDirectory(atPath: "/dev")) ?? []
    let skip = ["bluetooth", "debug-console", "wlan-debug"]
    return names
        .filter { name in
            let lower = name.lowercased()
            return name.hasPrefix("cu.") && !skip.contains { lower.contains($0) }
        }
        .sorted()
        .map { "/dev/" + $0 }
}
