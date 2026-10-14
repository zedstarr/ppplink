// PPP Link for macOS - settings window.
// Copyright 2026 zedstarr. Licensed under the Apache License, Version 2.0.

import Combine
import ServiceManagement
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var link: LinkController

    @AppStorage(SettingKey.port) private var port = ""
    @AppStorage(SettingKey.baud) private var baud = SettingDefault.baud
    @AppStorage(SettingKey.flowControl) private var flowControl = SettingDefault.flowControl
    @AppStorage(SettingKey.mode) private var mode = SettingDefault.mode
    @AppStorage(SettingKey.mtu) private var mtu = SettingDefault.mtu
    @AppStorage(SettingKey.dnsAutomatic) private var dnsAutomatic = SettingDefault.dnsAutomatic
    @AppStorage(SettingKey.customDns) private var customDns = SettingDefault.customDns
    @AppStorage(SettingKey.startOnLaunch) private var startOnLaunch = false
    @AppStorage(SettingKey.debugBridge) private var debugBridge = false
    @AppStorage(SettingKey.bridgePort) private var bridgePort = SettingDefault.bridgePort
    @AppStorage(SettingKey.hexLog) private var hexLog = false

    // Plain State values, not @State: with only the Command Line Tools (no Xcode),
    // newer SDKs can't expand the @State macro ("SwiftUIMacros ... not found").
    private var portsState = State(initialValue: [String]())
    private var openAtLoginState = State(initialValue: SMAppService.mainApp.status == .enabled)
    private var loginErrorState = State(initialValue: String?.none)
    private var ports: [String] {
        get { portsState.wrappedValue }
        nonmutating set { portsState.wrappedValue = newValue }
    }
    private var openAtLogin: Bool {
        get { openAtLoginState.wrappedValue }
        nonmutating set { openAtLoginState.wrappedValue = newValue }
    }
    private var loginError: String? {
        get { loginErrorState.wrappedValue }
        nonmutating set { loginErrorState.wrappedValue = newValue }
    }

    private let timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    var body: some View {
        Form {
            Section("Serial line") {
                Picker("Port", selection: $port) {
                    Text("Automatic (first USB serial adapter)").tag("")
                    ForEach(ports, id: \.self) { Text($0).tag($0) }
                    if !port.isEmpty && !ports.contains(port) {
                        Text("\(port) (not plugged in)").tag(port)
                    }
                }
                Picker("Speed", selection: $baud) {
                    ForEach(baudRates, id: \.self) { Text("\($0) baud").tag($0) }
                }
                Toggle("Hardware flow control (RTS/CTS)", isOn: $flowControl)
                Picker("Connection", selection: $mode) {
                    Text("Direct cable").tag("direct")
                    Text("Modem emulation (answers AT, CONNECT on ATD)").tag("modem")
                }
            }

            Section {
                Picker("MTU", selection: $mtu) {
                    ForEach(mtuChoices, id: \.self) { Text(String($0)).tag($0) }
                }
                Toggle("Use the Mac's DNS (follows network changes)", isOn: $dnsAutomatic)
                if !dnsAutomatic {
                    TextField("DNS server", text: $customDns)
                    if !isValidIPv4(customDns.trimmingCharacters(in: .whitespaces)) {
                        Text("Enter an IPv4 address such as 1.1.1.1. Until then the Mac's DNS is used.")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            } header: {
                Text("Network")
            } footer: {
                Text("Try an MTU of 576 on slow or unreliable lines.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Startup") {
                Toggle("Start the link when PPP Link opens", isOn: $startOnLaunch)
                Toggle("Open PPP Link at login", isOn: openAtLoginState.projectedValue)
                    .onChange(of: openAtLogin) { setLoginItem($0) }
                if let loginError {
                    Text(loginError).font(.caption).foregroundStyle(.red)
                }
            }

            Section("Debugging") {
                Toggle("TCP bridge instead of the serial line", isOn: $debugBridge)
                if debugBridge {
                    TextField("Listen on 127.0.0.1 port", value: $bridgePort, format: .number.grouping(.never))
                }
                Toggle("Log every byte (hex)", isOn: $hexLog)
            }

            if link.running {
                Section {
                    HStack {
                        Text("Changes apply when the link restarts.")
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button("Restart Link") { link.restart() }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .fixedSize(horizontal: false, vertical: true)
        .onAppear(perform: refreshPorts)
        .onReceive(timer) { _ in refreshPorts() }
    }

    private func refreshPorts() {
        let now = listSerialPorts()
        if now != ports { ports = now }
    }

    private func setLoginItem(_ on: Bool) {
        let isOn = SMAppService.mainApp.status == .enabled
        guard on != isOn else { return }
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            loginError = nil
        } catch {
            loginError = "Couldn't change the login item: \(error.localizedDescription)"
            openAtLogin = isOn
        }
    }
}
