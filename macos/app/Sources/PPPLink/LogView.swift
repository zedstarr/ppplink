// PPP Link for macOS - log and licence windows.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.

import AppKit
import SwiftUI

struct LogView: View {
    @EnvironmentObject var link: LinkController
    // Plain State values, not @State: with only the Command Line Tools (no Xcode),
    // newer SDKs can't expand the @State macro ("SwiftUIMacros ... not found").
    private var followState = State(initialValue: true)
    private var follow: Bool {
        get { followState.wrappedValue }
        nonmutating set { followState.wrappedValue = newValue }
    }

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(link.log) { line in
                            Text("\(Self.timeFormat.string(from: line.time))  \(line.text)")
                                .font(.system(.caption, design: .monospaced))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(line.id)
                        }
                    }
                    .padding(8)
                    .textSelection(.enabled)
                }
                .onChange(of: link.log.last?.id) { id in
                    if follow, let id { proxy.scrollTo(id, anchor: .bottom) }
                }
                .onAppear {
                    if let id = link.log.last?.id { proxy.scrollTo(id, anchor: .bottom) }
                }
            }
            Divider()
            HStack {
                Toggle("Follow", isOn: followState.projectedValue)
                Spacer()
                Button("Copy All") { copyAll() }
                Button("Clear") { link.clearLog() }
            }
            .padding(8)
        }
        .frame(minWidth: 560, minHeight: 320)
    }

    private func copyAll() {
        let text = link.log
            .map { "\(Self.timeFormat.string(from: $0.time))  \($0.text)" }
            .joined(separator: "\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

struct LicencesView: View {
    private let text: String = {
        var parts: [String] = []
        for name in ["NOTICE", "LICENSE", "THIRD_PARTY_NOTICES.txt"] {
            if let url = Bundle.main.url(forResource: name, withExtension: nil),
               let s = try? String(contentsOf: url, encoding: .utf8) {
                parts.append(s)
            }
        }
        return parts.isEmpty ? "Licence files are missing from this build." :
            parts.joined(separator: "\n\n" + String(repeating: "-", count: 72) + "\n\n")
    }()

    var body: some View {
        ScrollView {
            Text(text)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
        }
        .frame(minWidth: 620, minHeight: 420)
    }
}
