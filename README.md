# PPP Link

An Android/Linux/MacOS PPP server for retro computers. Plug a USB serial adapter into the
phone, connect it to any machine with a serial port and a PPP stack, and the
machine gets onto the internet through the phone's connection. No root.

Inside: slirp (Danny Gasparovski et al., patched for Android) terminates PPP and
NATs the client's traffic through ordinary Android sockets; usb-serial-for-android
drives the adapter (PL2303, FTDI, CP210x, CH34x).

## Build
1. Open this folder in Android Studio; install NDK (r25+) and CMake 3.22+.
2. Build > Make Project, or `./gradlew assembleDebug`.
3. `adb install app/build/outputs/apk/debug/app-debug.apk`

The application ID is `org.ppplink`, so it installs alongside earlier PsiLink /
EPOC Link builds (`org.psilink`) rather than replacing them.

## Phone setup
- Allow notifications; set Battery usage to Unrestricted (App info > Battery).
- Plugging in the adapter opens the app and starts the link (setting).

## Client setup (the retro computer)
| Setting | Value |
| --- | --- |
| Serial | Same speed as PPP Link, 8N1, null-modem cable |
| Flow control | Hardware (RTS/CTS) if enabled in PPP Link, else none |
| Connection | PPP. Direct mode if it starts PPP without dialling; Modem emulation if it dials (any number, AT commands answered OK) |
| Login | No script; PAP/CHAP not needed (any username/password if required) |
| IP address | 10.0.2.15 static, or obtain automatically |
| Gateway | The PPP link; 10.0.2.2 if an address is required |
| DNS | 10.0.2.3, secondary 1.1.1.1 |
| MTU | Default; 576 here and on the client if transfers stall |

The phone itself is 10.0.2.2 from the client. In Automatic DNS mode, lookups go
through the phone's current network (and its Private DNS setting).

## Linux and macOS
`desktop/` has the same thing for computers: a `ppplink` command for any Linux
(32- or 64-bit) and macOS, a systemd service for an always-on Linux box or
Raspberry Pi, and a macOS menu-bar app. All share one engine and this repo's
patched slirp. See `desktop/README.md`.

## Licences
PPP Link is licensed under the Apache License 2.0 (see `LICENSE` and `NOTICE`).
Third-party components are all permissive (BSD-style, MIT, Apache 2.0); their notices are in
`THIRD_PARTY_NOTICES.txt` and in the app under About > Open-source licences.
slirp's licence is in `app/src/main/cpp/slirp/COPYRIGHT`.

## Publishing
Targets Android 16 (API 36). No restricted permissions. See `PRIVACY.md` for a
privacy policy you can host for the Play listing.
