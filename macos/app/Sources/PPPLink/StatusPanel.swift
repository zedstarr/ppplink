// PPP Link for macOS - the panel that opens from the menu-bar icon.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.

import AppKit
import SwiftUI

struct StatusPanel: View {
    @EnvironmentObject var link: LinkController
    @Environment(\.openWindow) private var openWindow
    // Plain State values, not @State: with only the Command Line Tools (no Xcode),
    // newer SDKs can't expand the @State macro ("SwiftUIMacros ... not found").
    private var showSetupState = State(initialValue: false)

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Circle()
                    .fill(color(for: link.state.kind))
                    .frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(link.state.text)
                        .font(.headline)
                        .fixedSize(horizontal: false, vertical: true)
                    if let since = link.connectedSince {
                        TimelineView(.periodic(from: since, by: 1)) { context in
                            Text("Connected for \(duration(from: since, to: context.date))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                Spacer(minLength: 0)
            }

            Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 4) {
                row("Adapter", link.adapter)
                row("DNS", link.dns)
                row("From client", bytes(link.bytesIn))
                row("To client", bytes(link.bytesOut))
            }
            .font(.callout)

            Button {
                link.running ? link.stop() : link.start()
            } label: {
                Text(link.running ? "Stop" : "Start")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .keyboardShortcut(.defaultAction)

            DisclosureGroup("Client setup", isExpanded: showSetupState.projectedValue) {
                ClientSetupText()
                    .padding(.top, 4)
            }
            .font(.callout)

            Divider()

            HStack {
                Button("Settings…") { show("settings") }
                Button("Log…") { show("log") }
                Spacer()
                Menu {
                    Button("About PPP Link") {
                        NSApp.activate(ignoringOtherApps: true)
                        NSApp.orderFrontStandardAboutPanel(options: [.credits: aboutCredits()])
                    }
                    Button("Open-Source Licences…") { show("licences") }
                    Divider()
                    Button("Quit PPP Link") { NSApp.terminate(nil) }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .menuStyle(.button)
                .buttonStyle(.borderless)
                .menuIndicator(.hidden)
                .fixedSize()
            }
        }
        .padding(16)
        .frame(width: 320)
    }

    private func show(_ id: String) {
        openWindow(id: id)
        NSApp.activate(ignoringOtherApps: true)
    }

    @ViewBuilder
    private func row(_ label: String, _ value: String) -> some View {
        GridRow {
            Text(label).foregroundStyle(.secondary)
            Text(value)
                .lineLimit(2)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
    }

    private func color(for kind: LinkKind) -> Color {
        switch kind {
        case .connected: return .green
        case .ready, .negotiating: return .blue
        case .notready, .stale, .waiting: return .orange
        case .error: return .red
        case .idle, .stopped: return .gray
        }
    }

    private func bytes(_ n: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(clamping: n), countStyle: .file)
    }

    private func duration(from: Date, to: Date) -> String {
        let s = max(0, Int(to.timeIntervalSince(from)))
        return String(format: "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}

struct ClientSetupText: View {
    @AppStorage(SettingKey.baud) private var baud = SettingDefault.baud
    @AppStorage(SettingKey.flowControl) private var flow = SettingDefault.flowControl
    @AppStorage(SettingKey.mode) private var mode = SettingDefault.mode

    var body: some View {
        Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 3) {
            GridRow { label("Serial"); Text("\(baud) baud, 8N1, flow control \(flow ? "RTS/CTS" : "none")") }
            GridRow { label("Cable"); Text("Null-modem to the USB serial adapter") }
            GridRow { label("Connection"); Text(mode == "modem" ? "Modem: dial any number" : "Direct cable, no dialling") }
            GridRow { label("Protocol"); Text("PPP, no login needed") }
            GridRow { label("IP address"); Text("10.0.2.15, or automatic") }
            GridRow { label("Gateway"); Text("The PPP link (10.0.2.2)") }
            GridRow { label("DNS"); Text("10.0.2.3, secondary 1.1.1.1") }
        }
        .font(.caption)
        .textSelection(.enabled)
    }

    private func label(_ s: String) -> some View {
        Text(s).foregroundStyle(.secondary)
    }
}

func aboutCredits() -> NSAttributedString {
    // The NOTICE file carries the acknowledgements slirp's BSD-style licences require.
    var text = "A PPP server for retro computers over a USB serial adapter.\n\n"
    if let url = Bundle.main.url(forResource: "NOTICE", withExtension: nil),
       let notice = try? String(contentsOf: url, encoding: .utf8) {
        text += notice
    } else {
        text += "Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0."
    }
    text += "\nslirp comes to PPP Link via Slirp-CK (Kelly Price, Cameron Kaiser) " +
        "and slirp-termux (Mark Williams)."
    return NSAttributedString(string: text, attributes: [
        .font: NSFont.systemFont(ofSize: NSFont.smallSystemFontSize),
        .foregroundColor: NSColor.labelColor
    ])
}
