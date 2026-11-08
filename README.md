# warp-android-scribe

A compact, dictation-first Android keyboard. Hold (or tap) the green key, speak,
and the transcript is committed into the focused field. Audio goes to a small
bridge on <nas-name>, which hands it to Superwhisper's S1-Voice model through the
`superwhisper` CLI.

```text
phone IME ──HTTP/1.1, bearer token──▶ NAS bridge ──CLI──▶ Superwhisper daemon ──▶ S1-Voice
   │                                      │
   └─ 16 kHz mono WAV, ≤ 10 MB / 120 s     └─ /transcribe?lang=tr, poll status, return text
```

The Android app is deliberately not part of the suite's desktop release: it is
not in `apps.toml`, `scripts/build.sh` never sees it, and it ships as a sideloaded
APK. The bridge *is* a member of the suite Cargo workspace so the normal Rust
gate compiles and tests it.

## Layout

```text
android-apps/warp-android-scribe/
  bridge/                       # warp-android-scribe-bridge (Rust, axum + tokio)
    src/{main,lib,api,config,wav}.rs
    src/transcriber/{mod,superwhisper}.rs
    tests/{api_probe,superwhisper_cli_probe}.rs
  android/                      # Gradle project, Kotlin + Compose IME
    app/src/main/java/com/engin/warpandroidscribe/
  deploy/                       # systemd unit + env example
```

## The bridge

### HTTP API

```text
POST /transcribe?lang=tr
Authorization: Bearer <token>
Content-Type: audio/wav

<WAV bytes>

200 {"text":"...","language":"tr","duration":5.83}
```

- `lang` is optional: `tr`, `auto`, or any Superwhisper language code. The
  bridge sets the daemon's global language pin immediately before each request,
  under a mutex, so overlapping requests cannot fight over one session.
- `GET /health` (also token-checked) answers the version, the default language
  and the active transcriber; the phone's settings screen uses it as a test.
- 401 missing/wrong token, 413 too large or too long, 415 non-WAV content type,
  502 the CLI failed or was rejected, 504 the request timed out.
- Limits: 10 MiB, 120 s of audio, 60 s of processing. The recorded WAV is a
  private temp file and is deleted when the request returns (success or error).

### Configuration

Copy [`deploy/bridge.env.example`](deploy/bridge.env.example) to
`~/.config/warp-android-scribe/bridge.env` and `chmod 600` it. The token is
required and must be at least 16 characters; generate it with
`openssl rand -hex 32`.

| Variable | Default | Meaning |
|---|---|---|
| `WARP_SCRIBE_ADDR` | `0.0.0.0:8787` | Listen address; 8765 is already taken on the NAS |
| `WARP_SCRIBE_TOKEN` | — | Bearer token (required) |
| `WARP_SCRIBE_TRANSCRIBER` | `superwhisper-cli` | `superwhisper-cli` or `mock` |
| `WARP_SCRIBE_SUPERWHISPER_BIN` | `superwhisper` | CLI path |
| `WARP_SCRIBE_TIMEOUT_SECS` | `60` | Whole request budget |
| `WARP_SCRIBE_LANGUAGE` | `tr` | Default when the request omits `lang` |
| `WARP_SCRIBE_MAX_AUDIO_BYTES` | `10485760` | Request body limit |
| `WARP_SCRIBE_MAX_AUDIO_SECS` | `120` | WAV duration limit |

### Install on the NAS

The bridge needs a running `superwhisper.service` in the same user manager, and
the NAS user must have Superwhisper signed in. The install steps used here:

```sh
# Build on the desktop, install on the NAS.
ssh <nas-lan> 'mkdir -p ~/.local/bin ~/.config/warp-android-scribe ~/.config/systemd/user'
cargo build --release -p warp-android-scribe-bridge
scp .build/target/release/warp-android-scribe-bridge \
    <nas-lan>:.local/bin/warp-android-scribe-bridge
scp android-apps/warp-android-scribe/deploy/warp-android-scribe-bridge.service \
    <nas-lan>:.config/systemd/user/

# The env file stays 0600 and holds the only copy of the token.
ssh <nas-lan>
install -m 600 /dev/null ~/.config/warp-android-scribe/bridge.env
$EDITOR ~/.config/warp-android-scribe/bridge.env
systemctl --user daemon-reload
systemctl --user enable --now warp-android-scribe-bridge.service
```

If the NAS firewall is active, allow only the LAN:

```sh
sudo ufw allow from <nas-subnet> to any port 8787 proto tcp \
  comment 'warp-android-scribe bridge'
```

The bridge must not be exposed beyond the LAN. It listens on all interfaces
because the phone reaches it over Wi-Fi; the token is the only access control.

### Tests

```sh
cargo test -p warp-android-scribe-bridge
cargo clippy --locked --all-targets -p warp-android-scribe-bridge -- -D warnings
```

`tests/superwhisper_cli_probe.rs` drives a fake `superwhisper` shell script and
checks the session-id polling, refusals and timeout. `tests/api_probe.rs` covers
auth, size/duration limits, temp-file cleanup and the 502/504 mapping.

## The Android app

### Build

```sh
cd android-apps/warp-android-scribe/android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=$HOME/tools/android-sdk
./gradlew assembleDebug
```

`local.properties` is gitignored; point it at the SDK (`sdk.dir=...`) or export
`ANDROID_HOME`. The build uses AGP 9.5.1 with built-in Kotlin (no
`org.jetbrains.kotlin.android` plugin), the Compose compiler plugin 2.4.21,
compile/target SDK 37, min SDK 26.

Install and enable the IME:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.engin.warpandroidscribe/.ScribeImeService
adb shell ime set    com.engin.warpandroidscribe/.ScribeImeService
```

### Settings

The launcher activity is the settings screen. It stores the host, port and token
in the app's private `SharedPreferences` (the token is only as protected as the
app sandbox; there is no keystore yet) and offers:

- host/port/token, with a "save and test" button that calls `GET /health`;
- the dictation language: `Türkçe` (default) or `Otomatik`;
- the system input-method settings/picker and the microphone permission.

### Keyboard

- One compact layer: clipboard/recent chips, backspace, the green dictate key,
  `TR`/`OTO`, the system keyboard picker, space and enter.
- **Hold** the green key to dictate and release to send. A **quick tap** locks
  the recording on (hands-free); tap again to stop. While recording, the status
  row shows the timer, a lock action and cancel.
- `X`/backspace: tap deletes one character, long-press hides the keyboard.
- The top chips are the last clipboard entries and the last transcripts; tap
  inserts, long-press removes. The bottom strip repeats the latest transcript.
- Errors stay in the keyboard with "Tekrar" (retry the same WAV) and "Ayarlar".

### Network security

`network_security_config.xml` permits cleartext only for `<nas-lan>`. The
settings screen warns if the host is changed; another host needs HTTPS or an
edit to that file. The bridge port is `8787` because `8765` is already taken by
another service on the NAS.

## Decisions

- Language: Turkish by default, with a `TR ↔ OTO` toggle on the keyboard. In a
  NAS test the same short Turkish sentence came back as English gibberish with
  `auto`, and correctly with `tr` in ~0.4 s.
- X: tap is backspace, long-press hides the keyboard.
- Package: `com.engin.warpandroidscribe`; minSdk 26, targetSdk 37.
- License: the NAS profile currently runs on Superwhisper's free 10 000-word
  cloud quota (`license status` says `unlicensed`). File transcription works;
  activating Pro on the NAS is a follow-up, not a code change.
- Superwhisper's language pin is global to the profile. The bridge serializes
  requests and sets it per request; the NAS bridge is a dedicated profile.

## Known limits

- Cloud dictation sends the audio to Superwhisper's servers; it needs internet.
- The free quota is shared per Superwhisper device/profile. Pro is required for
  sustained use.
- `commitText` does not work in every field (passwords and `TYPE_NULL` editors).
- The Android build is a debug APK; release signing is not set up yet.
