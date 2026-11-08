# warp-android-scribe

A dictation-first Android keyboard with a full Turkish QWERTY layout. Hold (or
tap) the green key in the top bar, speak, and the transcript is committed into
the focused field. Audio goes to a small bridge on <nas-name>, which hands it to
Superwhisper's S1-Voice model through the `superwhisper` CLI.

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

The bridge needs a running `superwhisper.service` in the same user manager.
The NAS profile is currently on Superwhisper's free cloud quota; activate Pro
there for sustained use. The install steps used here:

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

The bridge is reachable two ways:

- **LAN** `http://<nas-lan>:8787`, with UFW restricted to
  `<nas-subnet>`.
- **Public** `https://<nas-host>` through the existing Cloudflare
  Tunnel into Nginx Proxy Manager, whose `<nas-host>` proxy host
  forwards to `<nas-lan>:8787`.

The bearer token is the only access control on the public route. Keep it at 64
hex characters; if the endpoint is shared further, put Cloudflare Access or a
rate limit in front of it.

### Public endpoint

NPM runs in the `npm` container (`http://<nas-lan>:81`). The proxy host
`<nas-host>` forwards to the bridge, and the wildcard
`*.engin.page` certificate covers it. The Cloudflare Tunnel public hostname
points at NPM (`http://<nas-lan>:80`). The tunnel is configured in the
Cloudflare dashboard, not in this repo.

```sh
curl -s -o /dev/null -w '%{http_code}\n' https://<nas-host>/health
# 401 without the bearer token
```

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

One-time SDK packages (JDK 17 required):

```sh
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

Install and enable the IME:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.engin.warpandroidscribe/.ScribeImeService
adb shell ime set    com.engin.warpandroidscribe/.ScribeImeService
```

### Settings

The launcher activity is the settings screen. It stores the token and the
Local/Remote choice in the app's private `SharedPreferences` (the token is only
as protected as the app sandbox; there is no keystore yet) and offers:

- **Yerel (LAN)** / **Uzak (Cloudflare)** modes with fixed endpoints
  (`http://<nas-lan>:8787` and `https://<nas-host>`), one shared
  token, and a "save and test" button that calls `GET /health`;
- the Android 17 local-network permission for the Local mode (the remote route
  does not need it);
- the dictation language: `Türkçe` (default) or `Otomatik`;
- the system input-method settings/picker and the microphone permission.

### Keyboard

- Full Turkish QWERTY: number row, `q…ü`, `a…i`,
  shift/`z…ç`/backspace, `?123`, comma, space, period and a blue enter key.
  The `?123` page carries the symbols and an `ABC` return.
- The top bar is the dictation surface: a **Local/Remote** chip, the green
  **Dikte** pill, `TR`/`OTO`, and the clipboard button. A recording replaces the
  mode chip with a cancel `X`; a quick tap locks the recording, a hold is
  push-to-talk.
- The clipboard button replaces the old keyboard-switch key. It opens a panel
  over the key area with one grid of cards: text clips, `SON` transcripts and
  image thumbnails together, with no separate sections. Tapping a text clip
  commits it, tapping an image commits it with `InputConnection.commitContent`.
  "Sistem klavyesi" inside the panel opens the system input-method picker.
- There is no chips row and no bottom transcript strip. The last transcripts
  live in the clipboard panel as `SON` entries. Errors appear as one compact
  line with "Tekrar" (retry the same WAV) and "Ayarlar".

### Network security

`network_security_config.xml` permits cleartext only for `<nas-lan>`. The
Cloudflare endpoint is HTTPS, so it needs no cleartext exception. Android 17
blocks app traffic to LAN addresses unless the app holds the
`ACCESS_LOCAL_NETWORK` runtime permission; the app declares it and the settings
screen can request it. The public route is the fallback when the permission is
denied or the phone is away from the LAN. The bridge port is `8787` because
`8765` is already taken on the NAS.

## Decisions

- Language: Turkish by default, with a `TR ↔ OTO` toggle on the keyboard. In a
  NAS test the same short Turkish sentence came back as English gibberish with
  `auto`, and correctly with `tr` in ~0.4 s.
- Layout: full Turkish QWERTY with the dictation/clipboard/mode bar on top;
  no compact chips row, no bottom transcript strip.
- Connection: one token, two fixed modes. **Yerel** is
  `http://<nas-lan>:8787`; **Uzak** is `https://<nas-host>`. The
  keyboard's mode chip and the settings screen toggle the same preference.
- Clipboard: text and images. Images are copied into the app cache and shared
  through a `FileProvider`; insertion uses `InputConnection.commitContent`, so
  fields that do not accept content show a clear error.
- Package: `com.engin.warpandroidscribe`; minSdk 26, targetSdk 37.
- License: the NAS profile currently runs on Superwhisper's free 10 000-word
  cloud quota (`license status` says `unlicensed`). File transcription works;
  activating Pro on the NAS is a follow-up, not a code change.
- Superwhisper's language pin is global to the profile. The bridge serializes
  requests and sets it per request; the NAS bridge is a dedicated profile.
- Android 17: the app targets SDK 37, so LAN traffic needs
  `ACCESS_LOCAL_NETWORK`; the settings screen requests it. The Cloudflare
  endpoint is the no-permission path.
- Public endpoint: `https://<nas-host>` via Cloudflare/NPM. This
  supersedes the earlier LAN-only decision; the 64-hex bearer token is the
  authentication and should be rotated if it leaks.

## Known limits

- Cloud dictation sends the audio to Superwhisper's servers; it needs internet.
- The free quota is shared per Superwhisper device/profile. Pro is required for
  sustained use.
- `commitText` does not work in every field (passwords and `TYPE_NULL` editors).
- The public endpoint is reachable from the internet; a leaked token allows
  transcription requests. Cloudflare Access/WAF is the next hardening step.
- LAN access needs the Android 17 local-network permission; without it the app
  shows a clear error and the Remote mode is the way to continue.
- Clipboard images only paste into fields that implement `commitContent`; a
  chat composer usually does, a plain text field does not.
- The system keyboard bar (switch/hide) is Android's, not part of the
  keyboard. The app measures the IME window against the display and pads the
  keys and the panel by the navigation-bar height, so nothing is hidden under
  it.
- The Android build is a debug APK; release signing is not set up yet.
