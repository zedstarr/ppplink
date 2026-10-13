# PPP Link for macOS

A PPP server for retro computers, for the Mac. Plug a USB serial adapter into
the Mac, connect it to any machine with a serial port and a PPP stack, and that
machine gets onto the internet through the Mac's connection.

No root, no `pppd`, no `ppp0`, no NAT rules. Like the Android app, PPP Link runs
slirp, which ends the client's PPP link inside an ordinary program and remakes
each of its connections as a normal connection from the Mac. It's the same slirp,
with the same patches, built from `../app/src/main/cpp/slirp`.

There are two ways to use it:

- **`ppplink`**: a command for Terminal, installable with Homebrew.
- **PPP Link.app**: a menu-bar app (macOS 13 Ventura or later).

Both run the same engine (`ppplink-engine`), so they behave the same.

## The command

### Install

With Homebrew (once the tap is published):

```
brew install zedstarr/ppplink/ppplink
```

Or from this repository (needs the Xcode command line tools: `xcode-select --install`):

```
cd macos
make
sudo make install              # or: make install PREFIX=$HOME/.local
```

### Use

```
ppplink                        # serve PPP on the first USB serial adapter
ppplink -b 57600 --flow off    # slower, no hardware flow control
ppplink -m modem               # answer AT commands; CONNECT when the client dials
ppplink -p /dev/cu.usbserial-1410
ppplink list                   # serial ports, and hints if an adapter has no driver
ppplink setup                  # what to set on the retro computer
ppplink config init            # write ~/.config/ppplink/ppplink.conf with your defaults
```

Ctrl-C stops it; Ctrl-T prints byte counts. While it runs the Mac won't
idle-sleep (`--no-keep-awake` to allow it); closing the lid still sleeps unless an
external display is attached.

If the adapter is unplugged, `ppplink` waits for it to come back (`--once` exits
instead). If the client hangs up, slirp restarts, ready for the next call.

## The menu-bar app

### Build

```
cd macos
make app                       # builds build/PPP Link.app (ad-hoc signed)
open "build/PPP Link.app"
```

Drag it to Applications to keep it. `UNIVERSAL=1 make app` builds for Apple
silicon and Intel (needs full Xcode).

The app shows the link state in the menu bar: a flat line when stopped or
waiting for the adapter, a square wave when ready, a solid screen when
connected, `!` when something needs attention. Click it for status, Start/Stop,
the client settings, Settings and the log. Settings can start the link when the
app opens and open the app at login, for an always-on setup.

### Distributing the app

An ad-hoc signed app runs on the Mac that built it. To give it to other people
without Gatekeeper warnings, sign it with a Developer ID (Apple Developer
Program) and notarise it:

```
SIGN_IDENTITY="Developer ID Application: <name> (<team>)" ZIP=1 make app
xcrun notarytool submit "build/PPP-Link-1.0.0-macOS.zip" --keychain-profile <profile> --wait
xcrun stapler staple "build/PPP Link.app"
```

Then zip it again for release. The app needs no special entitlements. It isn't
sandboxed, which rules out the Mac App Store as it stands.

## USB serial adapters on the Mac

Ports appear as `/dev/cu.*`. Unlike Android, where PPP Link drives the chip
itself, the Mac needs a driver for the adapter:

| Chip | macOS |
| --- | --- |
| FTDI (FT232R etc.) | Built-in driver: `/dev/cu.usbserial-…` |
| CH340 / CH341 | Built-in driver on recent macOS; WCH's own driver gives `/dev/cu.wchusbserial…` |
| CP210x | Built-in on recent macOS, or Silicon Labs' driver: `/dev/cu.SLAB_USBtoUART` |
| PL2303 | Needs Prolific's driver, which refuses some older and clone chips |
| USB CDC/ACM devices | Built-in: `/dev/cu.usbmodem…` |

`ppplink list` notices a known adapter that's plugged in with no port and says
which case it is. For a PL2303 that the driver won't accept, an FTDI-based
adapter is the easy fix.

## Client setup (the retro computer)

| Setting | Value |
| --- | --- |
| Serial | Same speed as PPP Link, 8N1, null-modem cable |
| Flow control | Hardware (RTS/CTS) if enabled in PPP Link, else none |
| Connection | PPP. Direct if the client starts PPP without dialling; modem mode if it dials (any number) |
| Login | None needed |
| IP address | 10.0.2.15 static, or obtain automatically |
| Gateway | The PPP link; 10.0.2.2 if an address is required |
| DNS | 10.0.2.3, secondary 1.1.1.1 |

The Mac is 10.0.2.2 as seen from the client. With automatic DNS, lookups use
the Mac's current DNS servers (from `/etc/resolv.conf`) and follow it from
network to network without reconnecting.

## How it works

```
retro computer --serial--> USB adapter --> /dev/cu.* --> ppplink-engine --pty--> slirp --sockets--> internet
                                                              |
                                                     DNS relay (127.0.0.1) --> the Mac's DNS servers
```

`ppplink-engine` (in `engine/`) opens the serial port, starts slirp on a
pseudo-terminal and copies bytes between them. It also answers AT commands in
modem mode, watches PPP frames to report Negotiating/Connected, notices a client
that is asleep (holding CTS low) or still on a previous link, follows unplug and
replug, and runs the DNS relay. With `--status machine` it reports in lines the
app reads; `ppplink-engine --help` lists the options.

## Development

```
make            # build/slirp-bin, build/ppplink-engine, build/ppplink (runs uninstalled)
make test       # end-to-end test: a pty as the serial line, a scripted PPP client
                # (LCP, IPCP, DNS, TCP), modem mode, hang-up, replug, debug bridge
make app        # the menu-bar app
```

The engine and slirp also build on Linux (`make`, `make test`), where `ppplink`
works with `/dev/ttyUSB*` and `/dev/ttyACM*` adapters.

## Licence

Apache License 2.0; see `../LICENSE`. slirp and the PPP code carry their own
BSD-style licences, collected in `../THIRD_PARTY_NOTICES.txt` and installed with
the command (`share/doc/ppplink`) and inside the app (Open-Source Licences).
