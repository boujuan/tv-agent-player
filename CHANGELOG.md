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
