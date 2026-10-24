# Changelog

One section per release, newest first; each `## X.Y.Z` section becomes that tag's GitHub Release notes. Format:
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions: [SemVer](https://semver.org/).

## 0.2.0 — 2026-10-24

### Changed
- Holding ← or → no longer seeks on the way. The target glides along the time bar, with how far it is from the
  picture ("+6:12"), faster the longer the key is held, and one exact seek goes there when the key comes up. Before,
  each step's seek cancelled the last one's read, so neither the picture nor the time moved until the key was let go.
  A short press still jumps at once (−10 s / +30 s).

### Not done
- Thumbnails while seeking: on this TV a second decoder can't decode a 4K frame while the film plays (measured;
  DESIGN.md, "Previews while seeking").

## 0.1.1 — 2026-10-24

### Fixed
- Holding ← or → on the remote jumps once per press and then once every half second, instead of on every repeat of
  the key (~20 a second): held for a few seconds, it had run 25 minutes ahead.

### Added
- A read of the file that fails (and is retried) is reported to the agent at once, as a non-fatal `error` with
  `code` `LOAD_FAILED` and its `cause` (e.g. `UnknownHostException`: the TV couldn't look up the server's name), so
  the agent can say why on the TV instead of a silent wait. Once per file.

## 0.1.0 — 2026-10-24

The first release. Needs the tv-agent voice agent from its main branch (after server 0.4.0), with `player = "tvplayer"`.

### Added
- The design ([DESIGN.md](DESIGN.md)) and the control protocol, version 1 ([PROTOCOL.md](PROTOCOL.md)).
- Pairing with tv-agent: `PairReceiver` takes the agent's addresses in the order to try (the home network's, then
  e.g. Tailscale's), the player port, the player token and the agent certificate's SHA-256 from `tv-agent tv pair`
  (shell only). The player tries the address that answered last first.
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
- Tuned on the TV (measurements in DESIGN.md): Android 14's built-in Cronet (HttpEngine) for links, chosen over
  HttpURLConnection and OkHttp with `bench` (one connection for the header, the seek index and the seeks); the
  buffer's memory follows each file, also when one is loaded over another, and goes back to Android at once when
  the player closes; audio in your language prefers the file's main track (default flag, channels, codec); a ping
  from the player when nothing plays. Command to first frame 1.3 s (Just Player 2.9 s, Kodi 2.4 s), a preloaded
  next episode 0.1 s.
