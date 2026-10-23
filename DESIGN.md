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
| Shrinking and compiling | R8 full mode with resource shrinking; a **baseline profile** in the APK, compiled at install (`speed-profile`) | Startup code runs compiled from the first launch instead of interpreted. Measured against a plain install and `cmd package compile -m speed` in step 4 |
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
audio/video sync, passthrough, subtitles. Just Player, on the same Media3, played the 55 Mbit/s P7 REMUX for 250 s
with 5 dropped frames, during seeks (the tests). Step 4 checks the CPU use (`top`) during that file to confirm.

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

How the pieces reach the app: `tv-agent tv pair` sends the agent's LAN address, `player_port`, the player token and
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
| Three HTTP requests before the first frame: the MKV header, its Cues (near the end of the file), back to the start position | Unavoidable for an MKV. With an HTTP/2 stack the three share one TLS connection instead of three handshakes. Which stack wins is measured on the TV with `bench` (the platform's `HttpURLConnection`, Android 14's built-in Cronet `HttpEngine`, OkHttp 5), not assumed |
| Decoding from the keyframe before the start position (Media3 always starts a file exactly where it's told) | Measured: a start at 0 against one mid-file |
| `bufferForPlaybackMs` (Media3's default 1 s before starting) | Measured at 0.5–1 s; this Wi-Fi isn't the limit |
| The next episode in binge mode | `preload` while the credits roll: header, Cues and the first seconds are already in memory, so the next one starts at decoder speed |
| Seeks | Forward and back buffers in memory, so ±10–30 s jumps stay inside them: no HTTP request, only a decoder flush. Fast `seek_by` commands add up into one seek |

What step 4 measures, against the baselines: command to first frame (cold process, warm process, preloaded), seek
to first frame (inside and outside the buffer), memory (PSS) while playing 4K DV, and CPU during the REMUX.

## Memory

Just Player used **208 MB PSS** playing 4K DV and Kodi 277 MB. Most of it is the buffer: Media3's default caps it at
~144 MB, which holds only ~19 s of a 60 Mbit/s REMUX but ~45 s of a 25 Mbit/s WEB-DL. This player sizes the buffer
from the file's bitrate instead, within a budget:

- **Budget**: the smaller of 256 MB, 45 % of the heap limit (`largeHeap`: 512 MB), and 35 % of the memory Android
  says is available when the file loads.
- **Forward**: 40 s, within the budget. A 5–25 Mbit/s WEB-DL needs 25–125 MB; a REMUX hits the 256 MB budget at
  ~30 s.
- **Back**: 15 s kept from the last keyframe, inside the same budget, so "back 10 seconds" doesn't touch the network.
- A WEB-DL should therefore come in under Just Player's 208 MB; a REMUX trades memory for seconds of buffer. Step 4
  measures both.
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

## Tracks and subtitles

The rules are the agent's (`languages.py`), applied by the player when it first chooses tracks, so the first frame
already has the right ones: no switch after the start, and no recheck eight seconds later as with Kodi.

- **Audio**: the `audio` language; never a commentary or audio-description track (by name, since Media3 reads no
  MKV role flags); Castilian before Latin American Spanish; Simplified vs Traditional Chinese by name.
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
