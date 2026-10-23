# Changelog

One section per release, newest first; each `## X.Y.Z` section becomes that tag's GitHub Release notes. Format:
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions: [SemVer](https://semver.org/).

## Unreleased

### Added
- The design ([DESIGN.md](DESIGN.md)) and the control protocol, version 1 ([PROTOCOL.md](PROTOCOL.md)).
- Pairing with tv-agent: `PairReceiver` takes the agent's address, the player port, the player token and the
  agent certificate's SHA-256 from `tv-agent tv pair` (shell only).
- Builds for the TV: Kotlin, Media3 1.11.1 (pinned), Android 14 and newer, `armeabi-v7a`, R8; CI and a signed
  release workflow.
- The player: the agent's TLS link (certificate pinned, token, reconnects by itself); `load` in place with headers
  and a start position, `preload`, `play`/`pause`/`toggle`/`stop`, exact `seek` and merged `seek_by`, `tracks`,
  `select`, `add_sub` (shown at once, no rebuffer), `volume` (the player's own and Android's), `status`, `osd`.
  Events: state, position every second, first frame, tracks, video decoder and HDR, audio output, timing, errors,
  end, and `closed` with the position before the decoder is released.
- Tracks by the agent's rules in Media3's first choice: audio language without commentary, subtitles in your order
  (plain before SDH, Castilian first, Simplified or Traditional Chinese by name), and with subtitles off a forced
  track in the audio's language.
- Dolby Vision profile 7 sent to the TV's profile 5 decoder on purpose; the buffer sized from each file's bitrate
  (40 s ahead, 12 s behind, within what the TV can spare); the decoder lists warmed while the screen comes up.
- Android's media session (the remote's media keys, `dumpsys media_session`), and the remote on the player: OK
  pauses, left and right jump −10 s / +30 s, Back stops; a time bar.
