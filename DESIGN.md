# Design

A video player for one TV, the living-room TCL 65Q6C, driven by one controller, tv-agent. These are the decisions
behind it and the evidence for each. "The tests" means tv-agent's `docs/player-tests-2026-10-23.md` (branch
`player-tests`), run on this TV with Just Player 0.217 (Media3 1.11.1) and Kodi 22 RC1. "The report" means the
research report *Kodi alternatives for Android TV*. Where the two differ, the tests win.

## The TV

| | |
|---|---|
| Chip | MediaTek MT5896 (Pentonic 700), 4 × Cortex-A73 (`CPU part 0xd09`) with the ARMv8 crypto extensions (AES, PMULL, SHA) usable from 32-bit code |
| Android | 14 (API 34), 32-bit userspace only (`armeabi-v7a`) |
| Memory | 2.45 GB; 0.55–0.9 GB available. Java heap per app 192 MB, 512 MB with `largeHeap` |
| Network | 5 GHz Wi-Fi 5, RSSI −26 dBm, 780–866 Mbit/s link (Ethernet unplugged) |
| Install-time compilation | `pm.dexopt.install = speed-profile` |
| Decoders (the tests) | DV: `c2.mtk.dvhe.stn` (P5), `c2.mtk.dvhe.st` (P8) and others; P7 listed nowhere, yet `stn` plays a P7 FEL disc in DV. HEVC plays HDR10+. No decoder offers tunneling |
| Sound (the tests) | TV speakers. AC3, E-AC3 (Atmos), TrueHD, DTS and DTS-HD all go to the TV's own decoders as bitstreams |
| Display (the tests) | One Android mode, 3840×2160 at 60 Hz. The MediaTek video plane drives the panel at 120 Hz for 24p by itself |

## Decisions at a glance

| Question | Decision | Why |
|---|---|---|
| Engine | Media3 ExoPlayer **1.11.1, pinned** | The only engine with DV right on this chip (the report). 1.12.0-alpha01 stalled ~20 s after seeking forward, 2 runs out of 2; 1.11.1 didn't (the tests) |
| Language | **Kotlin**, no coroutines, the platform's `org.json` | Same bytecode as Java on ART, so no speed difference; null safety and less code for a protocol-heavy app. `org.json` is in the boot image (already compiled, nothing to load from the APK) and is plenty for a few messages a second |
| Native code | **None** | Decoding is in hardware, sound goes out as bitstream, and TLS is already native (Conscrypt/BoringSSL). See [below](#native-code) |
| UI | Platform `Activity`, **Views**, a **SurfaceView** | DV and HDR need the video on the hardware overlay plane, which only a SurfaceView gives. No Compose, AppCompat or AndroidX activity: less to load at start, and nothing to draw beyond a time bar |
| Media session | The **platform** `MediaSession` | Media keys, Google TV and `dumpsys media_session` all work with it. `media3-session` (a 954 KB AAR, more than half the size of the 1.7 MB player engine) would be loaded for the same thing |
| Shrinking and compiling | R8 full mode with resource shrinking (a 1.14 MB APK); **compiled ahead of time at install** (`tv-agent tv install` runs `cmd package compile -m speed`: 5.5 s, once) | Reading a file's header measured ~0.2 s faster than interpreted. The TV ignores a profile inside a sideloaded APK (it installs as `verify`), so the compile is explicit; the APK's baseline profile still reaches Android through profileinstaller for its idle-time compile |
| HTTP | **Android 14's built-in Cronet** (`HttpEngine`), HttpURLConnection as the fallback; no OkHttp | Measured against debrid links ([below](#measured-on-the-tv)): one connection for the header, the seek index and the seeks, so starts 0.3–0.7 s faster and seeks steady at ~230 ms where HttpURLConnection took 0.25–1.6 s. Nothing added to the APK |
| ABI | `armeabi-v7a`, the only one this userspace runs | There's no native code, so the APK has no `.so` files to choose between anyway |
| Control | **TLS socket from the player to the agent**, newline JSON, token from pairing, agent certificate pinned | [Below](#control-channel); the protocol is in [PROTOCOL.md](PROTOCOL.md) |
| Where it runs | Its **own app** (`com.boujuan.tvagent.player`) and process | Its own Java heap (the buffer is the big user), its own task (the voice pill over it only pauses it), and a player crash can't take the microphone down |
| HDR | DV, HDR10+ and HDR10 as the file has them. P7 goes to `c2.mtk.dvhe.stn` on purpose (a small codec selector) | The tests: hybrids are fine without stripping HDR10+, `stn` plays P7 in DV, P5 plays natively. Relying on Media3's fallback order for P7 would be luck |
| Sound | Media3's own passthrough, no FFmpeg | The tests: everything this TV's files carry goes out as bitstream and sounds right |
| Frame rate, tunneling | Neither | The panel already shows 24p at 120 Hz; no decoder offers tunneling (the tests) |
| Subtitles from add-ons | Drawn by the player over the picture, from a URL or inline text, at any time | Media3 can only add a subtitle file by re-preparing the whole source (a rebuffer of the 4K file) |

## Native code

Nothing on this player's hot path would get faster in C++:

- **Video** is decoded by the TV's hardware decoder (`c2.mtk.*` through MediaCodec). The app only copies
  compressed samples into its input buffers.
- **Sound** goes out undecoded (AC3, E-AC3, TrueHD, DTS-HD bitstreams to the TV's DSP), so nothing is decoded on
  the CPU at all.
- **TLS** is BoringSSL inside Conscrypt, native already, with hardware AES.
- **What's left in Java** is reading the MKV (MatroskaExtractor) and bookkeeping. At a REMUX's 60–80 Mbit/s that's
  ~10 MB/s, mostly `System.arraycopy`, which is itself a native memcpy. One copy fewer in C++ would save on the order
  of a millisecond of CPU per second of video.

Against that, a native pipeline would mean rewriting what Media3 gets right on this TV: DV decoder choice,
audio/video sync, passthrough, subtitles. Measured: the whole player takes 17–19 % of one of the four cores while
a 25 Mbit/s WEB-DL plays, ~40–60 % while the 55 Mbit/s REMUX fills its buffer at ~100 Mbit/s, and drops 0–2 frames a
session outside seeks.

## Control channel

The options:

| | **A. TLS socket, player → agent** (chosen) | B. Local socket on the TV, reached through ADB | C. A server in the app, agent → player |
|---|---|---|---|
| Latency per message | One LAN trip, ~1–3 ms on this Wi-Fi | One LAN trip plus ADB's per-packet acknowledgement, and a wait whenever the agent's single ADB connection is busy (a `dumpsys` takes ~1 s) | As A |
| Setup | TLS handshake once per screen: a few ms | An extra ADB connection kept open by the agent | As A |
| Who can connect | The agent accepts the TV's addresses only, and only with the token; the player only trusts the pinned certificate | Any app on the TV can connect to an abstract socket, so the app would have to check the peer's uid (shell = 2000). That's strong | A new port open on the TV to the whole network, with its own authentication |
| The link on the network | Encrypted | **In clear**: ADB over TCP isn't encrypted after authentication | Needs a TLS key on the TV |
| When ADB drops | Unaffected | Every command lost until ADB reconnects | Unaffected |
| How the secret gets there | Pairing over ADB (an intent only the shell may send), once | None needed | Pairing, as A |

A wins on every row except the one B gets free (no token). With A, the link is never in an ADB command line or
unencrypted on the network. Today Just Player gets it in an `am start -d` command line, and Kodi's JSON-RPC is
plain HTTP.

How the pieces reach the app: `tv-agent tv pair` sends the agent's addresses (the LAN's first, then its Tailscale
address, so the player finds the agent when the computer is away from home), `player_port`, the player token and
the certificate's SHA-256 in one broadcast to `PairReceiver`. Only the shell can send it (the receiver needs the
`DUMP` permission, as the TV app's pairing does). The player token is the player's own, not the TV app's
`agent_token`: neither app's secret works for the other's endpoints.

When the screen comes up: the agent sends `am start -n com.boujuan.tvagent.player/.PlayerActivity`. It does this as
soon as a play starts, while the add-ons are still being asked for streams (3–6 s), the way it brings Kodi up
today. The activity builds the player, warms the decoder lists and connects. By the time the link is known, the
`load` goes down an open connection to a ready player.

## Speed

Baselines (the tests, link already resolved): Just Player **2.9 s** from command to first frame. Kodi **2.4 s**
warm, **5.8 s** cold. The time goes to these, and here's what this player does about each:

| Where the time goes | What this player does |
|---|---|
| Process start and activity (0.5–0.7 s for Just Player, always cold because of `-S`; 3.1 s for a cold Kodi) | Started during the stream lookup, so off the critical path. A second title replaces the first in place: no relaunch, no home-screen flash between titles (test 4) |
| Building the player and listing decoders (MediaCodecList queries can take hundreds of ms) | Done in `onCreate` before the link arrives; the decoder lists for DV, HEVC, AVC, AV1 and the audio formats are fetched on a background thread into Media3's per-process cache |
| Three HTTP requests before the first frame: the MKV header, its Cues (near the end of the file), back to the start position | Unavoidable for an MKV. Android's built-in Cronet keeps one connection for all three instead of a handshake each: chosen by measurement ([below](#measured-on-the-tv)) over HttpURLConnection and OkHttp |
| Decoding from the keyframe before the start position (Media3 always starts a file exactly where it's told) | No consistent cost measured: The Boys S04E03 from 0:00 showed its first frame 2.30 s after the command, S04E01 from 10:00 and 40:00 2.18 s and 2.50 s (player's own event). Left exact |
| `bufferForPlaybackMs` (Media3's 1 s buffered before playing) | Kept: playing starts 0.15–0.4 s after the first frame, since the CDN delivers ~4× a WEB-DL's bitrate, so a lower threshold gains little and risks a rebuffer right after the start |
| The next episode in binge mode | `preload` while the credits roll: header, Cues and the first seconds are already in memory, so the next one starts at decoder speed (measured: 0.10 s) |
| Seeks | Forward and back buffers in memory, so ±10–30 s jumps stay inside them: no HTTP request, only a decoder flush. Fast `seek_by` commands add up into one seek |

## Measured on the TV

2026-10-23, release build 0.1.0 compiled ahead of time, driven over its connection (no agent play, nothing written to
Stremio), the same files as the tests, links already resolved. "First frame" is the panel's own marker (`VIDEOFUNC …
FirstFrame = 1`) after the command, as in the tests; the TV's clock was 0.075 s ahead of the laptop's and is
corrected for. The marker only appears when the TV sets up a picture path (a new HDR mode), so loads of the same
format back to back have only the player's own first-frame event, which comes 0.15–0.5 s after the panel's.

**Start** (command → first frame on the panel; the player was started during the stream lookup, as the agent does):

| File | This player | Just Player 0.217 | Kodi 22 RC1 |
|---|---|---|---|
| B: DV P8.1 + HDR10+ WEB-DL, 25 Mbit/s, from 30:00 | **1.30–1.36 s** (3 runs) | 2.9 s | 2.4 s warm, 5.8 s cold |
| A: DV hybrid WEBRip, 5.5 Mbit/s, loaded over B | 1.52–1.61 s | — | — |
| C: DV P7 FEL REMUX, 55 Mbit/s, TrueHD 7.1 | 2.57 s | — | — |
| D: DV P5 MP4 (its index is at the start: 47 MB in 19 requests first) | 15.6 s | 22 s | — |
| E: DV P8, DTS-HD MA | 2.70 s | (2.6 s hold before audio) | — |
| F: HDR10+ WEB-DL | 1.76 s | — | — |
| The next episode, preloaded (`preloaded` 2.6 s after `preload`) | **0.10 s** (player's own first frame; ready at 0.14 s) | — | — |

- The screen comes up and connects 0.23–0.33 s after `am start` from a cold process (`am start -W` TotalTime 286–326 ms
  whether compiled or not), during the agent's 3–6 s stream lookup.
- Every file showed the right mode: DV for A–E (`c2.mtk.dvhe.st` for P8, `c2.mtk.dvhe.stn` for P5 and P7), HDR10+ for
  F (`c2.mtk.hevc`). Sound went out as bitstreams: E-AC3, TrueHD, DTS-HD. The TV left HDR 0.06 s after `stop`.

**HTTP stacks** (`bench` on file B's and file C's links; three runs, medians):

| | First byte | Throughput | Seek: request open | Seek: open + 1 MB |
|---|---|---|---|---|
| HttpEngine (Cronet) | 299–316 ms | 83–108 Mbit/s | **221–234 ms**, spread ±25 ms | **453–504 ms** |
| HttpURLConnection | 243–313 ms | 84–120 Mbit/s | 274–945 ms, up to 1.56 s | 551–1234 ms |
| OkHttp 5 | 305–354 ms | 97–104 Mbit/s | 254–267 ms | 522–530 ms |

Throughput is the debrid CDN's (~100 Mbit/s), not the TV's Wi-Fi (780–866 Mbit/s link). In whole loads of B,
HttpEngine's first frame came 0.3–0.7 s sooner than HttpURLConnection's (1.58–1.67 s against 1.88–2.38 s, player's own
event), with one request fewer.

**Seeks** (command → next picture):

| | 25 Mbit/s WEB-DL (B) | 55 Mbit/s REMUX (C) |
|---|---|---|
| +30 s or −10 s inside the buffer | **0.11–0.12 s** | (C's buffer holds ~26 s and was refilling) |
| Outside the buffer, nearest keyframe | 0.68 s (+60 s); 0.90 s (three +30 s merged into +90) | 0.47–0.93 s |
| Exact jump outside the buffer | 1.45 s | 1.45–1.78 s |
| 20 alternating ±10 s in a burst | — | merged; one picture after 0.47–0.73 s, then plays on |

Just Player needed a relaunch for any exact jump (3.5 s in the tests) and buffered 1–4 s after key bursts.

**Memory** (PSS 35–75 s into playback; Just Player 208 MB and Kodi 277 MB playing B):

| File | Buffer cap | PSS | Java heap |
|---|---|---|---|
| B (25 Mbit/s) | 156 MB | 170–204 MB | 142–175 MB |
| A (5.5 Mbit/s), loaded over B | 34 MB | 105 MB | 76 MB |
| C REMUX (55 Mbit/s) | 179–201 MB | 228–263 MB | 202–230 MB |
| D P5 MP4 (8 audio, 76 text tracks: its sample tables) | 156 MB | 312 MB* | 285 MB* |
| Between plays (cached, Android may drop it) | — | **30 MB** | 3.5 MB |

\* Before the allocator fix below; not measured again.

- First finding: in place, a small file kept the big one's freed blocks (204 MB PSS for A after B). Fixed: the
  allocator's target follows each file's cap, and a collection runs once the old file has gone (105 MB).
- Second: after `stop` the runtime only gave the memory back ~30 s later; the player now collects as it releases.

**Smoothness** (`dumpsys media.metrics`, written by Android per decoder session): 23.8–24.4 fps in every session,
0–2 dropped frames and at most one freeze (83–208 ms) per session; the REMUX session with seven seeks dropped 10.
Just Player 0.217 in the tests: 4–9 dropped, up to 2 freezes of 224 ms.

## Memory

Just Player used **208 MB PSS** playing 4K DV and Kodi 277 MB. Most of it is the buffer: Media3's default caps it at
~144 MB, which holds only ~19 s of a 60 Mbit/s REMUX but ~45 s of a 25 Mbit/s WEB-DL. This player sizes the buffer
from the file's bitrate instead, within a budget:

- **Budget**: the smaller of 256 MB, 45 % of the heap limit (`largeHeap`: 512 MB), and 35 % of the memory Android
  says is available when the file loads.
- **Forward**: 40 s (loading starts again below 30 s), so "skip 30 seconds", the agent's default, lands inside it.
- **Back**: 12 s kept from the last keyframe, so "back 10 seconds" doesn't touch the network.
- The cap is the file's bitrate × 52 s within the budget: 34 MB for a 5.5 Mbit/s WEB-DL, 156 MB at 25 Mbit/s, ~200 MB
  for a REMUX (~26 s). Without a bitrate from the agent, the player works it out from the file's size and length.
- Measured ([above](#measured-on-the-tv)): 105–204 MB for WEB-DLs, ~230–260 MB for the REMUX. Inside-the-buffer jumps
  take ~0.1 s against 0.7–1.5 s from the network, which is worth more to voice control than the ~25 MB over Just
  Player at the top of the range.
- Between plays the process holds no player, so it's only Android's cached app, and Android can drop it at any time.
  Kodi keeps 130 MB resident.

## Lifecycle

- `PlayerActivity` is its own task in its own app. The voice pill (`ListenActivity`) and the lists
  (`ChoicesActivity`) are translucent and live in the TV app's task, so opening them only pauses the player, which
  keeps playing. The agent ducks the player's own volume over the socket: no volume bar, no ADB key presses.
- The player is built in `onCreate` and released only in `onStop`, after `closed` has been sent with the position.
  Releasing frees the hardware decoders, including the DV decoder the Aerial Views screensaver uses. Then the
  activity finishes; the next play brings up a fresh one in the still-warm process.
- The screen stays on while playing or buffering, and not while paused, so a long pause ends in the screensaver,
  which stops the player and saves the position.
- Back stops and closes; Home stops. The remote's play/pause, OK, left and right work on the player directly
  (−10 s / +30 s); next and previous are passed to the agent as `key` events.
- Held left or right moves a target along the time bar without seeking, and one exact seek goes there when the key
  comes up. It glides: each repeat of the key (~20 a second) moves it by the time since the last one, at 20× the
  film's speed when the repeats start, doubling every second, up to a 15th of the film a second. A few seconds place
  a scene; half an hour of a two-hour film takes ~7.5 s. Seeking at every step, as 0.1.x did, cancelled the previous
  step's read each time, so neither the picture nor the time moved until the key came up.

## Previews while seeking

Debrid files have no thumbnails, so a preview would have to be decoded from the file: the keyframe before the target
(an MKV's Cues give its offset), one range read, and a second decoder for it while the film plays. Measured on the TV
on 2026-10-24 during a 4K DV play (Dune Part Two WEB-DL on `c2.mtk.dvhe.st`), with a test build that decoded a 4K Main 10
HEVC keyframe on a second `c2.mtk.hevc.decoder` ten times:

- The second decoder is created, configured and started (the codec list allows 16 and 8K at 60 fps), but its first
  frame fails: the vendor's resource manager logs `1hw + 1sw (>fhd), AN RM decide` and the work ends with error -12,
  for a 147 KB and a 2.1 MB keyframe alike. This TV decodes one stream above 1080p in hardware at a time, so a 4K
  keyframe can't be decoded beside the film.
- The software HEVC decoder lists Main profile only (no Main 10), and a profile 5 file's base layer is in Dolby's IPT
  colours, which only the DV decoder's composer turns into a picture.
- Trying cost the film 3 dropped frames and 2 short freezes in that 72 s, against 0–2 and at most one in the plays
  without it.
- Even with a decoder, each preview would be a range read of 0.3–3 MB (~0.5 s, the bench's open + 1 MB) while the
  buffer fills, and an HDR or DV keyframe needs tone mapping to look right on the menus' layer.

So there are no previews on the TV. The way that costs the TV nothing is the agent's: the computer reads the keyframe
(ffmpeg seeks with the Cues: one range read), tone-maps it (libplacebo applies a profile 5 file's reshaping too) and
sends a small JPEG; the player would only show a picture of a few kilobytes. That's a new command, not done yet.

## Tracks and subtitles

The rules are the agent's (`languages.py`), applied by the player when it first chooses tracks, so the first frame
already has the right ones: no switch after the start, and no recheck eight seconds later as with Kodi.

- **Audio**: the `audio` language; never a commentary or audio-description track (by name, since Media3 reads no
  MKV role flags); Castilian before Latin American Spanish; Simplified vs Traditional Chinese by name; then the
  track the file marks default (a release's main one), the most channels, the better codec, as Kodi and Just Player
  would. Oppenheimer (TheBiscuitMan) lists E-AC3 first and its default DTS-HD MA second: it plays the DTS-HD.
- **Subtitles on**: the first `subs` language with a full (not forced) track; plain before SDH; Castilian first.
- **Subtitles off**: a forced track in the audio's language still shows, since it translates lines in other
  languages. In Dune Part Two's WEB-DL the Chakobsa lines are exactly such a track (the tests).
- A custom `DefaultTrackSelector` applies this in its own audio and text choices, rather than an override after the
  fact, which would make Media3 choose twice.
- **Add-on subtitles**: downloaded by the player and drawn over the picture by position, so they can arrive at any
  time. Media3 parses them (SubRip, WebVTT, SSA/ASS, TTML), so the timing and styles are the same as for a file's
  own tracks.

## Not doing, and why

| | Why not |
|---|---|
| FFmpeg audio | Nothing on these speakers needs it (the tests). Revisit for an eARC receiver, DTS:X P2 or DTS Express |
| HDR10+ stripping (PR #3085), P7 → P8.1 conversion | The tests: hybrids and P7 show correctly in DV without them |
| Display-mode switching | One Android mode; the panel handles 24p itself (the tests) |
| Tunneling | No decoder offers it (the tests) |
| A foreground service to stay warm | It would hold memory all day on a TV that's short of it; the screen starts during the stream lookup anyway |
| Compose, AppCompat, media3-session, Cronet from Play services | Size and startup for nothing this app draws or needs; the platform has a session and an HTTP engine already |
| Thumbnails while seeking, decoded on the TV | Its one hardware decoder above 1080p is the film's ([above](#previews-while-seeking)) |
