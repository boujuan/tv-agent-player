# Changelog

One section per release, newest first; each `## X.Y.Z` section becomes that tag's GitHub Release notes. Format:
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions: [SemVer](https://semver.org/).

## 0.9.1 — 2026-10-25

### Added
- The player's options for tv-agent's backup: a `settings` event with all ten (after `welcome`, and a second after
  one changes, here or on the home screen) and a `settings` command that sets them back, each only to a value its
  option offers. The hello lists `settings` in `features`.

## 0.9.0 — 2026-10-25

### Added
- `subs_unless` on `load` and `preload`: with the audio chosen in that language, subtitles are off; otherwise the
  `subs` asked for. The player decides it in its first choice, before the first frame, where the agent used to
  correct the subtitles after the start (a moment with none, or with ones that shouldn't show). A track picked
  later isn't undone by it.
- The original audio: `audio: "original"` plays the track the file marks original (Matroska FlagOriginal, read from
  the file's first bytes, which Media3 doesn't read), and audio tracks say `original` in `tracks`.
- The hello lists both in `features` (`subs_unless`, `original`), so tv-agent sends them only to this player.

## 0.8.1 — 2026-10-25

### Changed
- Starts are 3-4 times faster to the first frame: on a 4K episode, 0.6-0.7 s instead of 1.9-2.7 s (DESIGN.md,
  Starts).
  - A resume starts at the keyframe at or before its position (at most 10 s earlier), instead of decoding every
    frame from that keyframe without showing it: 5 s past one, that was 19 MB and 1.3 s more. A `load` with
    `exact` (a jump to a moment) starts exactly.
  - `prefetch` on `load` and `preload`: the parts of the file tv-agent knows a start needs (the seek index, the
    cluster it starts from) are read at once, alongside the header, instead of one after another.

## 0.8.0 — 2026-10-24

### Added
- A Skip intro / Skip recap button and a Next episode card, from tv-agent's markers (`markers` in `load` and
  `preload`, or the `markers` command when they're found later). The button shows bottom right inside an intro or
  recap: OK presses it while the time bar is hidden, and with the bar up it's a chip after the controls; Back hides
  it. Intros and recaps marked `auto` are skipped by the player as playing reaches them (once, never after a seek
  into one), with a short toast. At the credits the card offers the next episode, counting down while the film
  plays when tv-agent gives a countdown; OK plays it at once, Back keeps watching. The player tells the agent
  (`skipped`, `next_episode`), and says in its hello that it does this (`features`: `markers`), so the agent stops
  skipping by itself. Inflated on first use; the checks ride the position tick.

## 0.7.1 — 2026-10-24

### Added
- Each start's reads in its `timing` event and in the TV's log ("start: first byte …"): where in the file each of
  the first 12 network reads was, when it was asked for and answered, how much it got and when it stopped, and the
  video decoder's start. Real starts' first frames take 2.1 s on the median against 1.3 s in DESIGN.md's tests;
  this shows which reads (the header, the index, the place to start from) the difference goes to.

## 0.7.0 — 2026-10-24

### Added
- Subtitles from add-ons in time with the file by themselves: tv-agent (its next server release, after 0.12.0) measures how far an added
  track runs from the file's own subtitles and sends it (`sub_delay`, or `delay_ms` with `add_sub` when it's known
  already). The subtitles panel shows it as "Synced −2.3 s"; changing the timing by hand wins over it for the rest of
  the file. Only added tracks are moved this way, never the file's own, and never by more than 10 s.

## 0.6.2 — 2026-10-24

### Changed
- Back (or Home) while a start's loading screen is up sends `closed` for that start (`why` "back", `pos` 0), so
  tv-agent can stop it rather than load it into the closed screen and say "That didn't work" (two starts on
  2026-10-24 16:55 ended that way after a Back about a second after the screen came up). PROTOCOL.md says so.

## 0.6.1 — 2026-10-24

### Fixed
- With a control picked under the bar, ← and → move between the controls again instead of moving the time bar's
  thumb (Media3's time bar took the keys when focused). The player screen now takes the remote's keys before any
  view, and which keys are the controls' and which the film's is tested.

## 0.6.0 — 2026-10-24

### Added
- Stremio's external player: TV Agent Player is listed in Android's "Open with" when Stremio's player is set to
  External, and plays the link by itself (ExternalPlayerActivity; tv-agent isn't involved). It starts where Stremio
  says (`position` / `startfrom`) and gives back the position and length as MX Player does (`extra_position`,
  `extra_duration`, `end_by`), which Stremio saves as progress; at the end Stremio goes on to the next episode.
- Controls under the time bar, as in Stremio's player: play/pause, back and ahead, the previous and next episode (a
  series, with tv-agent), subtitles, audio, speed, picture size and Settings. ↓ goes into them while the bar is up,
  ←/→ move, OK uses one, ↑ or Back leaves; the time is next to them ("20:23 / 2:35:26").
- A subtitles panel, see-through over the top of the picture: the languages on the left, that language's tracks in
  the middle (the file's and add-ons'), and the timing and look on the right. Timing moves this file's subtitles
  later or sooner in 0.1 s steps (up to 10 s); size, height, colour, background, edges and font change on a sample
  line as they're picked.
- Speed, from 0.5× to 2×. A bitstream (DD+, DTS) is decoded on the TV while the speed isn't normal, and goes back to
  the receiver at normal speed; TrueHD, which the TV can't decode, says so.
- Picture size: fit, fill (stretched) or zoom (cropped).

### Changed
- The time bar is thin with a round thumb (Media3's DefaultTimeBar), the title above it.
- Subtitles rise above the time bar while it's up, instead of running behind it.
- The badges show once: in the pause screen's corner, no longer on the bar as well.
- The pause screen keeps its information (the clock, when it ends, the logo, year and length, the plot, the source
  and the file); its row of pills is now the controls under the bar, and the file's line has room for four lines
  where it was cut after two.

## 0.5.0 — 2026-10-24

### Added
- Holding ← or → shows a picture of where it's going, above the time bar at the target. tv-agent makes it on the
  computer from the file (this TV can't decode a second 4K frame while the film plays): the keyframe at or before
  the target, tone-mapped from HDR and Dolby Vision. On the TV it took 0.45 s from asking to the picture (median of
  24, at most 1.15 s), with the film playing as before (DESIGN.md, "Previews while seeking"). It needs tv-agent server
  0.9.0 and a Matroska file; otherwise the bar works as before.
- PROTOCOL.md: the `thumb_req` event and the `thumb` command (optional: protocol version 1 still).

## 0.4.0 — 2026-10-24

### Added
- A pause screen over the paused picture: the clock and when the film ends, what's playing (from the agent's `info`:
  logo or title, plot, add-on, debrid service, size, release, the stream's description), corner badges (4K, DV,
  HDR10+, TrueHD, Atmos, 7.1… from the tracks and the release), and a row of small pills: audio track, subtitles,
  subtitle size (Extra small to Larger) and Settings. Tracks picked there reach the agent as its own `select` does.
- The badges on the time bar for a few seconds after a start.
- Settings, from the pause screen or the app's new tile on the home screen: subtitle size in %, height, colour,
  background, edges and font (each "As the TV" until set), shown on a sample line as they're picked; how far ← and →
  jump; how fast a held ←/→ goes; the badges after a start; About, with the version, the agent and whether a newer
  release is out.

### Changed
- Back closes the player again even with a pill picked; with a list open, it closes the list first.
- While the pause screen is up the film's subtitle lines are hidden (the time bar covered them).

## 0.3.0 — 2026-10-24

### Added
- A loading screen while the agent finds the stream, where there was a black screen with a line of text: the
  backdrop (or the poster), the logo or the title, what was heard, and the step ("Finding streams…") with a small
  spinner. It comes with the agent's new `loading` command and fades out at the film's first frame. Pictures are
  fetched only while it's up, decoded to the size shown, and never hold the start up.
- `load` and `preload` take `info` (title, plot, artwork, where the file comes from, the release's badges), kept with
  the item for the pause screen.

## 0.2.1 — 2026-10-24

### Fixed
- The bitstream comes back after the TV's audio drops it. On 2026-10-24 the TV killed Oppenheimer's DTS-HD
  passthrough track (`AudioTrack write failed: -6`); Media3 recovered, but took the route for stereo PCM from then on
  and decoded the rest of the film to PCM. Now the player checks the route once a second while it's PCM and, once it
  takes the format again, re-enables only the audio renderer, which starts again as a bitstream; the picture isn't
  touched. With the same failure simulated: back to DTS-HD 1.2 s after the drop, with a 0.3 s gap.

### Added
- The `audio` event says so: `dropout` `lost` (with its `cause`), then `restored` (with `pcm_ms` and `gap_ms`).

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
