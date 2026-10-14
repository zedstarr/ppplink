// PPP Link for macOS - runs the bundled ppplink-engine and publishes its status.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.
//
// All the link work (serial line, slirp, modem emulation, DNS relay, replug)
// happens in ppplink-engine, the same program the `ppplink` command uses. This
// class starts and stops it and turns its status lines into published state.
// Everything here runs on the main thread.

import AppKit
import Foundation

enum LinkKind: String {
    case idle, waiting, ready, negotiating, connected, notready, stale, error, stopped

    /// The link is in use (or about to be): keep the Mac awake.
    var keepsAwake: Bool {
        switch self {
        case .ready, .negotiating, .connected, .notready, .stale: return true
        default: return false
        }
    }
}

struct LinkState: Equatable {
    var kind: LinkKind
    var text: String
}

struct LogLine: Identifiable {
    let id: Int
    let time: Date
    let text: String
}

final class LinkController: ObservableObject {
    @Published private(set) var state = LinkState(kind: .idle, text: "Stopped")
    @Published private(set) var running = false
    @Published private(set) var adapter = "None"
    @Published private(set) var dns = "-"
    @Published private(set) var bytesIn: UInt64 = 0
    @Published private(set) var bytesOut: UInt64 = 0
    @Published private(set) var connectedSince: Date?
    @Published private(set) var log: [LogLine] = []

    private var process: Process?
    private var outputBuffer = Data()
    private var stopRequested = false
    private var restartAfterStop = false
    private var activity: NSObjectProtocol?
    private var nextLogId = 0
    private let maxLogLines = 2000

    init() {
        NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.stopAndWait() }

        if UserDefaults.standard.bool(forKey: SettingKey.startOnLaunch) {
            DispatchQueue.main.async { [weak self] in self?.start() }
        }
    }

    // MARK: - Start / stop

    func start() {
        guard process == nil else { return }
        guard let engine = Bundle.main.url(forAuxiliaryExecutable: "ppplink-engine"),
              let slirp = Bundle.main.url(forAuxiliaryExecutable: "slirp") else {
            setState(.error, "Engine missing from the app bundle - rebuild with make-app.sh")
            return
        }

        let s = AppSettings.load()
        var dnsArg = "auto"
        if !s.dnsAutomatic {
            let custom = s.customDns.trimmingCharacters(in: .whitespaces)
            if isValidIPv4(custom) {
                dnsArg = custom
            } else {
                appendLog("Custom DNS '\(custom)' is not a valid IPv4 address - using automatic")
            }
        }

        var args = ["--slirp", slirp.path, "--status", "machine",
                    "--baud", String(s.baud),
                    "--flow", s.flowControl ? "rtscts" : "none",
                    "--mode", s.mode == "modem" ? "modem" : "direct",
                    "--mtu", String(s.mtu),
                    "--dns", dnsArg]
        if s.debugBridge {
            args += ["--tcp", String(s.bridgePort)]
        } else {
            args += ["--line", s.port.isEmpty ? "auto" : s.port, "--wait"]
        }
        if s.hexLog { args.append("--hex") }

        let p = Process()
        p.executableURL = engine
        p.arguments = args
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            if data.isEmpty {                              // end of output
                handle.readabilityHandler = nil
                return
            }
            DispatchQueue.main.async { self?.consume(data) }
        }
        p.terminationHandler = { [weak self] proc in
            let status = proc.terminationStatus
            DispatchQueue.main.async { self?.engineExited(status: status) }
        }

        do {
            try p.run()
        } catch {
            pipe.fileHandleForReading.readabilityHandler = nil
            setState(.error, "Could not start the engine: \(error.localizedDescription)")
            return
        }
        process = p
        running = true
        stopRequested = false
        bytesIn = 0
        bytesOut = 0
        outputBuffer.removeAll()
        appendLog("Started: \(args.dropFirst(2).joined(separator: " "))")
    }

    func stop() {
        guard let p = process else { return }
        stopRequested = true
        p.interrupt()                                     // SIGINT: the engine stops slirp and exits
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            if let self, self.process === p, p.isRunning { p.terminate() }
        }
    }

    /// Stops and starts again so changed settings take effect.
    func restart() {
        if process == nil { start(); return }
        restartAfterStop = true
        stop()
    }

    /// At quit: the engine must not outlive the app (it would keep the port open).
    private func stopAndWait() {
        guard let p = process, p.isRunning else { return }
        p.interrupt()
        let deadline = Date().addingTimeInterval(2)
        while p.isRunning && Date() < deadline { usleep(50_000) }
        if p.isRunning { p.terminate() }
    }

    private func engineExited(status: Int32) {
        // Let the engine's last lines (often the reason it stopped) arrive first.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.finishExit(status: status)
        }
    }

    private func finishExit(status: Int32) {
        process = nil
        running = false
        connectedSince = nil
        if stopRequested {
            setState(.idle, "Stopped")
        } else if state.kind != .error {
            setState(.error, "The engine stopped unexpectedly (code \(status))")
        }
        stopRequested = false
        adapter = "None"
        appendLog("Engine exited with code \(status)")
        if restartAfterStop {
            restartAfterStop = false
            start()
        }
    }

    // MARK: - Engine output

    private func consume(_ data: Data) {
        guard !data.isEmpty else { return }
        outputBuffer.append(data)
        while let nl = outputBuffer.firstIndex(of: 0x0A) {
            let lineData = outputBuffer[outputBuffer.startIndex..<nl]
            outputBuffer.removeSubrange(outputBuffer.startIndex...nl)
            let line = String(decoding: lineData, as: UTF8.self)
            if !line.isEmpty { handle(line) }
        }
    }

    private func handle(_ line: String) {
        let (kind, rest) = splitFirst(line)
        switch kind {
        case "STATE":
            let (key, text) = splitFirst(rest)
            setState(LinkKind(rawValue: key) ?? .error, text)
        case "ADAPTER":
            adapter = rest == "-" ? "None" : rest
        case "DNS":
            dns = rest
        case "BYTES":
            let n = rest.split(separator: " ").compactMap { UInt64($0) }
            if n.count == 2 { bytesIn = n[0]; bytesOut = n[1] }
        case "LOG":
            appendLog(rest)
        default:
            appendLog(line)
        }
    }

    private func splitFirst(_ s: String) -> (String, String) {
        guard let sp = s.firstIndex(of: " ") else { return (s, "") }
        return (String(s[..<sp]), String(s[s.index(after: sp)...]))
    }

    private func setState(_ kind: LinkKind, _ text: String) {
        if kind == .stopped { return }      // the engine is exiting: finishExit sets the final state
        state = LinkState(kind: kind, text: text)
        switch kind {
        case .connected:
            if connectedSince == nil { connectedSince = Date() }
        case .notready:
            break
        default:
            connectedSince = nil
        }
        updateActivity()
    }

    func appendLog(_ text: String) {
        log.append(LogLine(id: nextLogId, time: Date(), text: text))
        nextLogId += 1
        if log.count > maxLogLines { log.removeFirst(log.count - maxLogLines) }
    }

    func clearLog() { log.removeAll() }

    // MARK: - Sleep

    /// Keeps the Mac from idle-sleeping while a client may be using the link.
    /// (Closing the lid still sleeps, unless an external display is attached.)
    private func updateActivity() {
        if state.kind.keepsAwake && running {
            if activity == nil {
                activity = ProcessInfo.processInfo.beginActivity(
                    options: [.idleSystemSleepDisabled, .userInitiated],
                    reason: "PPP Link is serving a client")
            }
        } else if let a = activity {
            ProcessInfo.processInfo.endActivity(a)
            activity = nil
        }
    }
}
