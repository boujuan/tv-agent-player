# TV Agent Player

A video player for Android TV, built for one TV (a TCL 65Q6C: MediaTek Pentonic 700, Android 14, 32-bit) and
driven by one controller: [tv-agent](https://github.com/boujuan/tv-agent), the voice agent on your computer. It plays
the debrid links tv-agent finds through your Stremio add-ons, with Dolby Vision, HDR10+ and lossless audio as the TV
shows and plays them itself, and it answers to the agent at once: load in place, exact jumps, tracks by language and
by name, subtitles from your add-ons, position every second.

It has no menus or library of its own. tv-agent opens it, sends it what to play over its own encrypted connection,
and hears back everything that happens.

> **Status**: in development (0.1.0 not released yet). The design is in [DESIGN.md](DESIGN.md) and the agent's
> protocol in [PROTOCOL.md](PROTOCOL.md).

## Why a player of its own

Kodi and Just Player both get the picture right on this TV, but neither can be driven properly from outside:
Kodi is heavy and its MediaTek fixes are still landing; Just Player takes no request headers, no subtitle links and
no exact jumps without a relaunch. This one is Media3 (ExoPlayer) 1.11.1, pinned after testing on the TV, with
everything else stripped down to what the agent needs. The reasons, and the measurements, are in
[DESIGN.md](DESIGN.md).

## What it needs

- The TV: Android 14 or newer (it's built for the TCL above and tested only there).
- **tv-agent** on a computer on the same network, with the player support (`player = "tvplayer"` in its config).

## Install and pair

From the computer running tv-agent, with a release APK from GitHub Releases or one built here:

```bash
tv-agent tv install app-release.apk
```

That installs it over ADB, compiles it ahead of time, and pairs it: the app learns the agent's address, its own
token and the agent's certificate fingerprint. `tv-agent tv pair` pairs again (after a new token, or a new address).

## Build

JDK 17 and the Android SDK with Platform 37.0; `local.properties` with `sdk.dir=…`, which isn't committed. The Gradle
wrapper fetches Gradle 9.8.2, checked against its SHA-256.

```bash
./gradlew assembleRelease testDebugUnitTest lintDebug
```

Release builds are shrunk with R8 and signed with the project's release key where it's configured, else with the
debug key ([RELEASING.md](RELEASING.md)).

## How it stays private

- Stream links carry your debrid account's key. They reach the player only inside its TLS connection to the agent,
  never in an ADB command line, an intent or logcat, and never in clear on the network.
- The player only talks to the agent whose certificate it was paired with, and the agent only accepts the player
  from the TV's address with the player's own token.
- It has no account, analytics or network access of its own beyond the links it's given.

## Licence

MIT. Media3 is Apache 2.0, OkHttp Apache 2.0.
