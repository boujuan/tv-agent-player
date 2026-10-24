# Control protocol, version 1

tv-agent on the laptop drives the player over one connection, which the player opens as soon as its screen
starts. Everything the agent needs goes over it: the stream link with its headers, every command, and every event
back (state, position, tracks, decoder, errors, the final position). Nothing goes through `am start` extras or
logcat. ADB only brings the screen up (`am start -n com.boujuan.tvagent.player/.PlayerActivity`, no extras) and
pairs the app.

Why this shape, and the alternatives considered, are in [DESIGN.md](DESIGN.md#control-channel).

## Connection

- **Direction**: the player connects to the agent, at the addresses and port it got at pairing (`player_port`,
  8770 by default). The addresses come in the order to try: the agent's on the home network first, then others
  such as its Tailscale address for when the computer is away. The player tries the one that answered last first,
  and gives each 2 s to answer. The TV listens on nothing.
- **TLS** 1.3, with the agent's certificate pinned by the SHA-256 of its DER encoding, also from pairing. The host
  name isn't checked (the certificate is self-signed; the pin is the check). It's the certificate the agent already
  uses for paired phones.
- **Who may connect**: the agent accepts connections from the TV's addresses only, and only with the player token
  in the hello.
- **Framing**: one JSON object per line, UTF-8, ending in `\n`. A line is at most 4 MiB (an inline subtitle file is
  the only large message). Unknown fields are ignored. Every message has `"t"`, its type.
- **Hello**: the player's first line, within 3 s of connecting:

  ```json
  {"t": "hello", "v": 1, "token": "…", "app": "0.1.0", "build": 1, "sdk": 34}
  ```

  The agent answers `{"t": "welcome", "v": 1, "agent": "0.31.0"}`, or `{"t": "bye", "error": "unauthorized"}` (or
  `"version"`) and closes.
- **One player at a time**: a new authenticated connection replaces the old one, which gets
  `{"t": "bye", "error": "replaced"}`.
- **Liveness**: each side sends `{"t": "ping"}` after 10 s without sending anything else (nobody answers it; while
  something plays, the player's `pos` every second is traffic enough). Either side treats 30 s of silence as a dead
  connection.
- **Reconnecting**: if the connection drops while the screen is up, the player goes on playing and retries after
  0.5, 1, 2, 4… s, at most 10 s apart. After the new `welcome` it sends `state`, `pos` and `tracks` again, so the
  agent catches up.

## Commands (agent → player)

Each command has an `"id"` (an integer the agent picks). Each gets exactly one answer:

```json
{"t": "ack", "id": 7}
{"t": "ack", "id": 7, "error": "no such track"}
```

An answer can carry results (`tracks`, `status`). Answers come once the command has been applied, within 2 s,
except `bench`. Commands are applied in the order they arrive.

### `load`: play a link

```json
{"t": "load", "id": 1, "url": "https://…", "headers": {"Referer": "…"},
 "item": "tt0903747:1:1", "title": "Breaking Bad S1E1",
 "start_ms": 1234000, "bitrate": 25300000,
 "audio": "eng", "subs": ["eng", "spa"], "play": true}
```

| Field | Meaning |
|---|---|
| `url` | The link (http or https). Never logged |
| `headers` | Extra request headers for the link and anything it redirects to. Optional |
| `item` | The agent's id for what this is. Every event about it carries it back, so an event from the previous file can't be taken for one from this file |
| `title` | Shown on the player's screen when it shows the time |
| `start_ms` | Where to start, to the millisecond. 0 or absent: the beginning |
| `bitrate` | The file's average bit/s if the agent knows it (size / length). It sizes the buffer; without it, the player works it out once the file's header is read |
| `audio` | ISO 639-2 language for the audio (`eng`, `spa`, `chs`/`cht` for the two Chinese scripts). Commentary and audio-description tracks are never chosen by language. Absent or null: the file's default track |
| `subs` | Subtitle languages in order of preference. A full track is chosen, never a forced one, plain before SDH, Castilian before Latin American Spanish. `null`: subtitles off, except a forced track in the audio's language (it translates the lines that aren't in that language) |
| `play` | `false` loads it paused. Default `true` |
| `http` | `httpengine` (the default: Android's built-in Cronet) or `platform` (HttpURLConnection): the HTTP stack for this link, for measuring |
| `info` | What the pause screen shows about it, kept with the item: `title`, `subtitle`, `plot`, `poster`, `backdrop`, `logo` (public artwork links: Cinemeta, metahub, TMDB; never a stream link), `source` (the add-on), `description` (the add-on's stream title, as text, links removed; the player removes any it finds too), `debrid`, `size` (bytes), `release` (the group), `badges` (what the file's header can't tell: `atmos`, `dtsx`, `hdr10+`, `imax`, `remux`). All optional |

It replaces what's loaded in place: the screen stays, there's no relaunch. If a `preload` of the same `url` is
ready, it's used. The answer comes as soon as loading has started; `first_frame` follows when the picture moves.

### `preload`: get the next one ready

```json
{"t": "preload", "id": 2, "url": "https://…", "headers": {}, "item": "tt0903747:1:2", "start_ms": 0,
 "bitrate": 25300000}
```

Opens the link in the background: the file's header, its seek index (an MKV's Cues) and the first few seconds from
`start_ms`, in memory. A `load` of the same `url` then starts from there. One preload at a time: a new one replaces
the previous one. It's dropped when the screen closes. `preloaded` follows when it's ready. Its `info` (as for
`load`) comes along when it's played, unless the `load` has its own.

### `loading`: the loading screen

```json
{"t": "loading", "id": 3, "item": "tt15239678#k3x-2", "heard": "Play Dune Part Two",
 "title": "Dune: Part Two", "subtitle": "2024 · 2 h 46 min",
 "poster": "https://…", "backdrop": "https://…", "logo": "https://…", "step": "Finding streams…"}
```

Sent as soon as the agent knows what it will play (the title is found), before the streams are, and again as the
steps change. All fields are optional.

| Field | Meaning |
|---|---|
| `item` | The `item` the `load` will have |
| `heard` | What the user said, as transcribed; shown in quotes |
| `title`, `subtitle` | `subtitle`: year and runtime for a film, "S4E1 · <episode name>" for an episode |
| `poster`, `backdrop`, `logo` | Public artwork, as in `info`. The backdrop fills the screen (or the poster, without one); the logo takes the title's place once it's in. A picture that's missing or slow is left out: it never holds anything up |
| `step` | What's happening: "Finding streams…", "Checking the link…", "Starting…". It may be an error ("No playable stream for …"): the agent then sends `stop` or another `loading` |

A later `loading` for the same `item` changes what it has (usually only `step`); one for another item replaces it.
The screen goes when the picture of the next `load` appears (whatever its `item`), on `osd ""` or on `stop`.

### `play`, `pause`, `toggle`

```json
{"t": "pause", "id": 3}
```

`play` and `pause` are explicit (a repeated command can't undo itself). `toggle` is for the remote's play/pause.

### `stop`

Stops, sends `closed` (`"why": "stop"`) and closes the screen. The decoder is released before the answer.

### `seek`: to a time

```json
{"t": "seek", "id": 4, "ms": 5400000, "mode": "exact"}
```

`mode`: `"exact"` (the default: to the millisecond, for jumps to a time, skipped intros and found scenes),
`"sync"` (the nearest keyframe), `"prev"` (the keyframe at or before), `"next"` (the keyframe at or after).

### `seek_by`: forward or back

```json
{"t": "seek_by", "id": 5, "ms": -10000}
```

`mode` as for `seek`, default `"sync"` (the nearest keyframe: the fastest). Commands that come less than 1 s apart
add up from the previous target, not from where the picture is at that moment, so three `+30000` in a row move 90 s.

### `tracks`: list them

The answer has `audio` and `text`, lists of [tracks](#track).

### `select`: switch tracks

```json
{"t": "select", "id": 6, "audio": 2}
{"t": "select", "id": 7, "text": null}
```

`audio` and `text` are indexes `i` from `tracks`. `"text": null` turns subtitles off (a forced track in the audio's
language stays).

### `add_sub`: subtitles from elsewhere

```json
{"t": "add_sub", "id": 8, "url": "https://…/file.srt", "lang": "spa", "label": "Spanish (OpenSubtitles)",
 "show": true}
```

The file comes from `url`, or inline as `text`. `format` is `srt`, `vtt`, `ass`, `ssa` or `ttml`; without it, the
player guesses from the content. It becomes a text track like the file's own. With `show` it's shown at once, at
the current position, with no rebuffering. The answer has its index (`"text": 9`).

### `volume`

```json
{"t": "volume", "id": 9, "level": 0.5}
{"t": "volume", "id": 10, "system": 24}
```

`level` (0–1) is the player's own output, for ducking while the voice pill listens. `system` (0–100) is Android's
media volume, the one the TV's volume bar shows.

### `status`

The answer has the fields of `state` and `pos`.

### `osd`: a message on the player's screen

```json
{"t": "osd", "id": 11, "text": "Finding Breaking Bad…", "ms": 0}
```

`ms` is how long it stays: 0 means until the next `osd` or `load`; `""` clears it, and the loading screen too.

### `bench`: measure

```json
{"t": "bench", "id": 12, "url": "https://…", "headers": {}, "stacks": ["platform", "httpengine"],
 "seconds": 8, "seeks": 6}
```

For each HTTP stack, it measures the time to the first byte, the throughput over `seconds`, and `seeks` reads at
random offsets (each a new range request). Nothing is played. The answer has the numbers per stack. It can take a
minute: no 2 s limit.

## Events (player → agent)

Events have no `id`. Each one about a file carries its `item`.

| Event | Fields | When |
|---|---|---|
| `state` | `state` (`idle`, `buffering`, `ready`, `ended`), `playing` | On every change |
| `pos` | `pos`, `dur`, `buf` (ms; `buf` is how far ahead is buffered) | Every second while playing, every 10 s otherwise, and at once after a load, seek, pause or play |
| `first_frame` | `ms` (since the `load` or `seek` arrived), `after` (`load` or `seek`), `in_buffer` for a seek | When the picture first moves after either |
| `tracks` | `audio`, `text` (as for the command) | When they're known, and after every change (the agent's `select`, or a track picked on the pause screen) |
| `video` | `decoder`, `mime`, `codecs`, `w`, `h`, `fps`, `hdr` (`dv`, `hdr10`, `hlg`, `sdr`; HDR10+ is metadata in each frame, not in the header, so an HDR10+ file says `hdr10`), `dv_profile`, `init_ms` (the decoder's start-up) | When the video decoder starts |
| `audio` | `mime`, `ch`, `rate`, `out` (`passthrough` or `pcm`), `encoding`, `offload`; after the TV's audio dropped a bitstream, `dropout`: `lost` on the PCM output that replaced it (with `cause`: `dead_object`, `write_failed` or `init_failed`), then `restored` when it's a bitstream again (with `pcm_ms`, how long it was PCM, and `gap_ms`, the sound's gap while switching back; no `gap_ms` when Media3 switched back by itself) | When the audio output starts; a `restored` one when its sound starts |
| `timing` | `ms`: `{"first_byte", "prepared", "first_frame", "ready"}` from the load's arrival; `bytes`, `requests`, `preloaded`, `cap_mb` (the buffer's byte cap) | Once per load, when it's ready and the picture has moved |
| `error` | `code` (Media3's name, e.g. `ERROR_CODE_IO_BAD_HTTP_STATUS`; `LOAD_FAILED` for a read that failed and is being retried), `cause` (for `LOAD_FAILED`: the exception, e.g. `UnknownHostException`), `http` (the status, if any), `message`, `fatal` | A fatal one ends the file; a `LOAD_FAILED` (not fatal) comes once per file, at the first failed read. Messages never contain the link |
| `ended` | `pos`, `dur` | At the end of the file |
| `closed` | `pos`, `dur`, `why` (`stop`, `back`, `home`, `error`, `replaced`, `idle`) | The last word about an item: on `stop`, Back, leaving the screen, a fatal error, a `load` of something else, or a minute after the end with nothing loaded since (the screen then closes). Sent before the decoder is released |
| `preloaded` | `ms` (how long it took) | When a `preload` is ready |
| `key` | `key` (`next`, `previous`) | The remote's next or previous while the player is in front: the agent decides what comes next |

### Track

```json
{"i": 2, "lang": "en", "label": "English Commentary", "codec": "eac3", "ch": 6, "default": false, "forced": false,
 "sdh": false, "external": false, "selected": true, "supported": true}
```

`lang` is the file's language, normalised the way Media3 does it: ISO 639-1 where one exists (`en`, `es`, `zh`),
else ISO 639-2. `label` is the track's name in the file (an MKV's `Name`): Simplified or Traditional Chinese,
commentary and SDH are only told apart there. `ch` and `commentary` (commentary or audio description, by name) are
for audio tracks only. `codec` is short: `eac3`, `truehd`, `dts-hd`, `srt`, `ass`, `pgs`…

## Security

- The link and its headers travel only inside this TLS connection. The player never logs them, never puts them in
  an intent or in the media session, and its error messages never contain them. Media3's own logging is set to
  warnings and errors, which don't carry the link.
- The token is per install of the agent, sent to the app at pairing over ADB (an intent only the shell may send),
  and kept in the app's private storage. The agent keeps it in `secrets.toml` (mode 600).
- Only the TV's addresses may connect; the player only talks to the pinned certificate, so another machine on the
  network can neither take links from the agent nor send the player commands.

## Versions

`v` in the hello is this document's version. The agent refuses a player with a version it doesn't know. New
optional fields and new events don't change the version; anything an older agent or player would get wrong does.
