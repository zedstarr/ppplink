# PPP Link for Linux and macOS

A PPP server for retro computers, for Linux and the Mac. Plug a USB serial
adapter into the computer, connect it to any machine with a serial port and a
PPP stack, and that machine gets onto the internet through the computer's
connection.

No root, no `pppd`, no `ppp0`, no NAT rules. Like the Android app, PPP Link runs
slirp, which ends the client's PPP link inside an ordinary program and remakes
each of its connections as a normal connection from the computer. It's the same
slirp, with the same patches, built from `../app/src/main/cpp/slirp`.

What's here:

- **`ppplink`**: a command for Linux (any distribution, 32- or 64-bit) and macOS.
- **A systemd service** (Linux): an always-on PPP server, e.g. on a Raspberry Pi.
- **PPP Link.app** (macOS 13 Ventura or later): a menu-bar app.

All of them run the same engine (`ppplink-engine`), so they behave the same.

## Install the command

### Linux

Needs a C compiler, make and bash:

```
sudo apt install build-essential            # Debian, Ubuntu, Raspberry Pi OS
sudo dnf install gcc make                   # Fedora, RHEL, Rocky, Alma

cd desktop
make
sudo make install                           # to /usr/local; or: make install PREFIX=$HOME/.local
```

Then let yourself use serial ports, once: add yourself to the group that owns
them (`dialout` on Debian, Ubuntu, Fedora and RHEL; `uucp` on Arch) and log out
and back in:

```
sudo usermod -aG dialout $USER
```

`ppplink` tells you if this is missing.

### macOS

With Homebrew (once the tap is published):

```
brew install zedstarr/ppplink/ppplink
```

Or from this repository (needs the Xcode command line tools: `xcode-select --install`):

```
cd desktop
make
sudo make install              # or: make install PREFIX=$HOME/.local
```

## Use

```
ppplink                        # serve PPP on the first USB serial adapter
ppplink -b 57600 --flow off    # slower, no hardware flow control
ppplink -m modem               # answer AT commands; CONNECT when the client dials
ppplink -p /dev/ttyUSB0        # a particular port (macOS: /dev/cu.usbserial-1410)
ppplink list                   # serial ports, and hints if an adapter has no port
ppplink setup                  # what to set on the retro computer
ppplink config init            # write ~/.config/ppplink/ppplink.conf with your defaults
```

Ctrl-C stops it. For byte counts press Ctrl-T on macOS, or on Linux run
`pkill -USR1 ppplink-engine` from another terminal. While it runs the computer won't
idle-sleep (`--no-keep-awake` to allow it): `caffeinate` on macOS,
`systemd-inhibit` on Linux desktops. Closing a laptop lid still sleeps.

If the adapter is unplugged, `ppplink` waits for it to come back (`--once` exits
instead). If the client hangs up, slirp restarts, ready for the next call.

On Linux, `ppplink list` also shows each adapter's `/dev/serial/by-id/...` name.
That name stays the same whichever USB socket the adapter is in, unlike
`/dev/ttyUSB0`, so it's the one to put in `PORT=` in the settings file.

## Always on: the Linux service

For a Linux machine or Raspberry Pi that's always ready for a retro computer:

```
sudo make install
sudo make install-service      # installs ppplink.service and /etc/ppplink/ppplink.conf
sudo nano /etc/ppplink/ppplink.conf    # speed, flow control, port...
sudo systemctl daemon-reload
sudo systemctl enable --now ppplink
journalctl -u ppplink -f       # watch it
```

The service runs as a throwaway unprivileged user that can only use serial
ports, waits for the adapter, and follows unplug and replug. After changing the
settings: `sudo systemctl restart ppplink`. To remove it:
`sudo make uninstall-service`.

## The macOS menu-bar app

### Build

```
cd desktop
make app                       # builds build/PPP Link.app (ad-hoc signed)
open "build/PPP Link.app"
```

Drag it to Applications to keep it. `UNIVERSAL=1 make app` builds for Apple
silicon and Intel (needs full Xcode).

The app shows the link state in the menu bar: a flat line when stopped or
waiting for the adapter, a square wave when ready, a solid screen when
connected, `!` when something needs attention. Click it for status, Start/Stop,
the client settings, Settings and the log. Settings can start the link when the
app opens and open the app at login, for an always-on setup. If the icon doesn't
appear, the menu bar may be full: macOS hides icons that don't fit.

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

## USB serial adapters

| Chip | Linux | macOS |
| --- | --- | --- |
| FTDI (FT232R etc.) | Built in: `/dev/ttyUSB*` | Built-in driver: `/dev/cu.usbserial-…` |
| CH340 / CH341 | Built in: `/dev/ttyUSB*` (see brltty below) | Built in on recent macOS; WCH's driver gives `/dev/cu.wchusbserial…` |
| CP210x | Built in: `/dev/ttyUSB*` | Built in on recent macOS, or Silicon Labs' driver |
| PL2303 | Built in: `/dev/ttyUSB*`, clones included | Needs Prolific's driver, which refuses some older and clone chips |
| USB CDC/ACM devices | Built in: `/dev/ttyACM*` | Built in: `/dev/cu.usbmodem…` |

`ppplink list` notices a known adapter that's plugged in with no port and says
what to do.

### Linux notes

- **Permission denied**: you're not in the `dialout` (or `uucp`) group yet; see
  Install above.
- **CH340 port appears then vanishes (Ubuntu and others)**: brltty, the braille
  display service, claims these adapters. Unless you use a braille display:
  `sudo apt remove brltty` (or `sudo dnf remove brltty`), then replug.
- **First connection after plugging in fails**: ModemManager may be probing the
  new port with AT commands. `sudo make install-udev` installs a rule telling it
  to leave USB serial adapters alone; replug afterwards.

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

The computer running PPP Link is 10.0.2.2 as seen from the client. With
automatic DNS, lookups use the computer's current DNS servers (from
`/etc/resolv.conf`) and follow it from network to network without reconnecting.

## How it works

```
retro computer --serial--> USB adapter --> /dev/tty* --> ppplink-engine --pty--> slirp --sockets--> internet
                                                              |
                                                     DNS relay (127.0.0.1) --> the computer's DNS servers
```

`ppplink-engine` (in `engine/`) opens the serial port, starts slirp on a
pseudo-terminal and copies bytes between them. It also answers AT commands in
modem mode, watches PPP frames to report Negotiating/Connected, notices a client
that is asleep (holding CTS low) or still on a previous link, follows unplug and
replug, and runs the DNS relay. With `--status machine` it reports in lines the
macOS app reads; `ppplink-engine --help` lists the options.

## Development

```
make                       # build/slirp-bin, build/ppplink-engine, build/ppplink (runs uninstalled)
make test                  # end-to-end test: a pty as the serial line, a scripted PPP client
                           # (LCP, IPCP, DNS, TCP), modem mode, hang-up, replug, debug bridge
make ARCHFLAGS=-m32 test   # the same as a 32-bit build (Linux: needs gcc-multilib)
make app                   # the macOS menu-bar app
```

CI (`.github/workflows/desktop.yml`) runs the tests on 64- and 32-bit Linux and
on macOS, and builds the app.

## Licence

Apache License 2.0; see `../LICENSE`. slirp and the PPP code carry their own
BSD-style licences, collected in `../THIRD_PARTY_NOTICES.txt` and installed with
the command (`share/doc/ppplink`) and inside the app (Open-Source Licences).
