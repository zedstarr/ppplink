# Homebrew formula for the `ppplink` command.
#
# Homebrew installs formulae from taps: copy this file to Formula/ppplink.rb in a
# GitHub repo named homebrew-ppplink (github.com/zedstarr/homebrew-ppplink), then
#   brew install zedstarr/ppplink/ppplink
# For each release: tag vX.Y.Z in the ppplink repo, then update `url` and `sha256`
# (curl -L <url> | shasum -a 256).
class Ppplink < Formula
  desc "PPP server for retro computers over a USB serial adapter, no root needed"
  homepage "https://github.com/zedstarr/ppplink"
  url "https://github.com/zedstarr/ppplink/archive/refs/tags/v1.0.0.tar.gz"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"
  # PPP Link is Apache-2.0; the bundled slirp is under several BSD-style
  # licences (see THIRD_PARTY_NOTICES.txt), which SPDX can't express in one id.
  license all_of: ["Apache-2.0", :cannot_represent]
  head "https://github.com/zedstarr/ppplink.git", branch: "main"

  def install
    system "make", "-C", "macos", "install", "PREFIX=#{prefix}", "VERSION=#{version}"
  end

  def caveats
    <<~EOS
      Plug in a USB serial adapter, connect the retro computer, and run:
        ppplink
      Settings for the retro computer:  ppplink setup
      Your defaults:                    ppplink config init

      FTDI adapters work with macOS's built-in driver. PL2303 adapters need
      Prolific's macOS driver, which refuses some older and clone chips.
    EOS
  end

  test do
    assert_match version.to_s, shell_output("#{bin}/ppplink version")
    assert_match "10.0.2.15", shell_output("#{bin}/ppplink setup")
    assert_match "ppplink-engine", shell_output("#{libexec}/ppplink/ppplink-engine --help")
  end
end
