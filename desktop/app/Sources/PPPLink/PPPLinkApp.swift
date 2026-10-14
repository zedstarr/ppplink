// PPP Link for macOS - menu-bar app.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.

import AppKit
import SwiftUI

@main
struct PPPLinkApp: App {
    @StateObject private var link = LinkController()

    var body: some Scene {
        // The menu-bar item comes first, so no window opens at launch.
        MenuBarExtra {
            StatusPanel()
                .environmentObject(link)
        } label: {
            Image(nsImage: MenuBarIcon.image(for: link.state.kind))
                .accessibilityLabel("PPP Link: \(link.state.text)")
        }
        .menuBarExtraStyle(.window)

        Window("PPP Link Settings", id: "settings") {
            SettingsView()
                .environmentObject(link)
        }
        .windowResizability(.contentSize)

        Window("PPP Link Log", id: "log") {
            LogView()
                .environmentObject(link)
        }

        Window("Open-Source Licences", id: "licences") {
            LicencesView()
        }
    }
}
