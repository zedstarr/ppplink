# PPP Link — notes for Claude Code

Android app (Kotlin + Compose, NDK) that acts as a PPP server for retro computers
over a USB serial adapter, plus a macOS command and menu-bar app (`macos/`).
App ID `org.ppplink`. Author: zedstarr.
Repository: https://codeberg.org/zedstarr/ppplink (Codeberg, not GitHub).

## Architecture (don't redesign)
- `app/src/main/cpp/slirp/` — Slirp-CK 1.0.18 from github.com/MarkWllms/slirp-termux,
  built as an executable packaged as `libslirp.so` (so Android lets us exec it).
  Local patches, all marked "PPP Link" in comments:
  - `options.c`: strip trailing CR/LF from config lines (Bionic inet_aton rejects them).
  - `dnsport N` option (main.c/h, options.c/p, socket.c, tcp_subr.c): DNS for the
    10.0.2.3 alias goes to 127.0.0.1:N instead of :53.
  - `udp.c`: replies from aliased sockets carry the port the client addressed (53).
  - `main.c`: no exec_list services (upstream's telnet shell on 10.0.2.1:23 and
    slirp.ftpd on :21 removed).
  - `tcp_subr.c`: remote command console (telnet 10.0.2.0) disabled unless built
    with -DPPPLINK_CONTROL_CONSOLE. Never enable it in a release.
  - Config always includes `nozeros` (no five-0s exit / five-1s detach escape).
- `ppplinknative.c` — JNI forkpty launcher for slirp (resets signal mask, EINTR-safe wait).
- `LinkEngine.kt` — session control on a single-threaded dispatcher; one line reader
  per session survives slirp restarts; identity checks stop stale loops acting.
- `SerialLine.kt` — USB (usb-serial-for-android) or debug TCP bridge.
- `DnsRelay.kt` — answers slirp's DNS via Android DnsResolver on the current network.
- `PppSniffer.kt` — read-only PPP frame watcher for status (handles ACFC/PFC).

## macOS (`macos/`)
- `engine/ppplink-engine.c` + `engine/dnsrelay.c`: one C program shared by the
  `ppplink` command and the menu-bar app. Same behaviour as LinkEngine.kt; with
  `--status machine` it prints `STATE/ADAPTER/DNS/BYTES/LOG` lines the app parses.
- slirp is built by `macos/Makefile` straight from `app/src/main/cpp/slirp`.
  Don't fork a copy: patches must stay shared. Any slirp change must still build
  for Android (CMake) and macOS/Linux (Makefile).
- `bin/ppplink`: must run on macOS's bash 3.2 (no associative arrays, no
  `${var,,}`, no `mapfile`; guard empty arrays under `set -u`).
- `app/`: SwiftPM package (macOS 13+, no Xcode project). `make-app.sh` builds the
  helpers, `swift build`, assembles `build/PPP Link.app` with `ppplink-engine` and
  `slirp` in Contents/MacOS, makes the .icns, and signs (ad-hoc unless
  SIGN_IDENTITY is set).
- No `@State` (or other SwiftUI macros such as `@Entry`, `#Preview`) in the app:
  with only the Command Line Tools, recent SDKs fail with "SwiftUIMacros ... not
  found". Use a stored `State(initialValue:)` plus a nonmutating accessor, or
  `@AppStorage`/`@StateObject`, which aren't macros.
- `Formula/ppplink.rb`: template for the tap at codeberg.org/zedstarr/homebrew-ppplink;
  update `url` (https://codeberg.org/zedstarr/ppplink/archive/vX.Y.Z.tar.gz) and
  `sha256` per release. Non-GitHub taps need `brew tap zedstarr/ppplink <url>` first.
- Status: the command works with the MC218 on a Mac; the app builds with the
  Command Line Tools (`make -C macos app`) and runs. Build and test:
  `make -C macos && make -C macos test && make -C macos app`.
- No CI: Codeberg has no macOS runners. Run `make -C macos test` before pushing.

## Constraints
- Project licence: Apache-2.0 (`LICENSE`, `NOTICE`), copyright zedstarr.
  Never relicense to GPL: slirp and Juha Pirkola's code carry the BSD advertising
  clause, which is GPL-incompatible. Keep `NOTICE` in sync with the third-party credits.
- Keep every third-party notice: `THIRD_PARTY_NOTICES.txt`,
  `app/src/main/assets/open_source_notices.txt` (shown in About > Open-source
  licences) and `app/src/main/cpp/slirp/COPYRIGHT`. Regenerate the asset if a
  dependency changes.
- targetSdk/compileSdk 36 (Play requirement). AGP 8.7 + `android.suppressUnsupportedCompileSdk=36`.
- No restricted permissions (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS was removed on purpose).
- No proprietary dependencies (no Play Services, Firebase, analytics).
- Public identity is the handle `zedstarr` only. Never add the author's real name or
  personal email to any file, metadata or commit. Commits use the Codeberg no-reply
  address (zedstarr@noreply.codeberg.org; confirm in Codeberg settings); check `git config user.name` / `user.email` before the first commit.

## Open tasks: F-Droid release
1. ~~Add `LICENSE`~~ done: Apache-2.0. Use `License: Apache-2.0` in the F-Droid recipe.
2. Add `fastlane/metadata/android/en-US/`: `title.txt`, `short_description.txt`
   (≤80 chars), `full_description.txt`, `changelogs/1.txt`, `images/icon.png`
   (512×512, render from `res/drawable/ic_launcher_foreground.xml` on #2E3033),
   `images/phoneScreenshots/` (the author supplies).
3. Pin the NDK version in `app/build.gradle.kts` (`ndkVersion = "..."`) so builds
   are reproducible; F-Droid's recipe must name the same one.
4. Make sure `./gradlew assembleRelease` builds from a clean checkout with no
   local-only files, and that `versionName`/`versionCode` are set.
5. Commit, tag `v1.0.0`, push.
6. Draft `metadata/org.ppplink.yml` for fdroiddata (Categories: Connectivity,
   System; License; SourceCode: https://codeberg.org/zedstarr/ppplink,
   IssueTracker: https://codeberg.org/zedstarr/ppplink/issues, Repo: the .git URL; Builds entry with `ndk:`,
   `gradle: [yes]`, `subdir: app`; AutoUpdateMode: Version; UpdateCheckMode: Tags).
   Test with `fdroid lint`/`fdroid build` if fdroidserver is installed; otherwise
   leave it for the fdroiddata fork's CI.
7. Possible review issue: usb-serial-for-android comes from JitPack. If F-Droid
   asks, vendor the library source (MIT) as a Gradle module.

## Testing
macOS/Linux engine: `make -C macos test`. Android: no unit tests yet. Verify on device: plug adapter -> Ready -> client connects ->
Connected; DNS resolves; unplug/replug resumes; client hang-up restarts slirp.
