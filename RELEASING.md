# Releasing

The version lives in `app/build.gradle.kts` (`versionName`; bump `versionCode` with every release). Releases are
tags `vX.Y.Z` (SemVer).

```bash
# 1. bump versionName and versionCode, and add a "## X.Y.Z — YYYY-MM-DD" section to CHANGELOG.md
git commit -am "0.2.0"
git tag -a v0.2.0 -m "0.2.0"
git push origin main v0.2.0
```

`.github/workflows/release.yml` then checks the tag against `versionName`, runs the unit tests, builds the release
APK, refuses one signed with any other key than the release key, and creates the GitHub Release with the APK,
`SHA256SUMS` and that CHANGELOG section. CI (`.github/workflows/ci.yml`) builds (debug and release), tests and lints
every push.

Media3 is pinned (`gradle/libs.versions.toml`). Before changing its version, run tv-agent's player test script on
the TV with the new one (`docs/player-tests-2026-10-23.md`, test 3): 1.12.0-alpha01 stalled after seeking forward.

## Signing

Every release APK is signed with the same release key, so a newer one installs over an older one (and over a local
build signed with it). It is tv-agent's project key (one publisher, one key to keep safe):

- Key: PKCS12 keystore, alias `tv-agent`, RSA 4096, valid until 2054; one password for the keystore and the key.
- Certificate SHA-256: `bd934e07d6784766bbfb3948bf3467ef9b2cb0c9c1146fdf9528dc76c8eb1e08` (pinned in `release.yml`).
- Backup: the maintainer's password manager. Without the key there are no more updates: the player would have to be
  uninstalled and installed again (and paired again).
- CI: repo secrets `ANDROID_KEYSTORE_BASE64` (the keystore, `base64 -w0`) and `ANDROID_KEYSTORE_PASSWORD`; only
  `release.yml` uses them.

`app/build.gradle.kts` reads the key from Gradle properties, or else environment variables, of these names; the
`TV_AGENT_PLAYER_*` ones first, else tv-agent's `TV_AGENT_*` ones, so a computer already set up to sign tv-agent
signs this app too:

| Name | Value |
|---|---|
| `TV_AGENT_PLAYER_KEYSTORE` (or `TV_AGENT_KEYSTORE`) | Path to the keystore |
| `TV_AGENT_PLAYER_KEYSTORE_PASSWORD` (or `TV_AGENT_KEYSTORE_PASSWORD`) | Its password |
| `TV_AGENT_PLAYER_KEY_ALIAS` (or `TV_AGENT_KEY_ALIAS`) | `tv-agent` |
| `TV_AGENT_PLAYER_KEY_PASSWORD` (or `TV_AGENT_KEY_PASSWORD`) | Only if the key's password differs from the keystore's |

To sign local release builds, copy the keystore from the backup into a private folder and add the names to
`~/.gradle/gradle.properties` (`chmod 600`):

```properties
TV_AGENT_KEYSTORE=/home/<you>/.local/share/tv-agent/signing/android-release.p12
TV_AGENT_KEYSTORE_PASSWORD=<password>
TV_AGENT_KEY_ALIAS=tv-agent
```

Then `./gradlew assembleRelease` and check with `apksigner verify --print-certs
app/build/outputs/apk/release/app-release.apk` (the digest above). Neither file goes in the repo (`.gitignore` skips
`*.jks`, `*.keystore`, `*.p12`).

To set the repo secrets (a new repo, a lost secret), from this repository's folder:

```bash
base64 -w0 ~/.local/share/tv-agent/signing/android-release.p12 | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
```

The second asks for the value.

Without any key (CI on branches and pull requests, any other computer), release builds are signed with that
computer's debug key: they build and install, but can't update an install signed with the release key, nor the other
way round.
