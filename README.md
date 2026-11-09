# warp-android-scribe

A dictation-first Android keyboard with a full English QWERTY layout. Hold (or
tap) the green key in the top bar, speak, and the transcript is committed into
the focused field. Audio goes to a small bridge on <nas-name>, which hands it to
Superwhisper's S1-Voice model through the `superwhisper` CLI. The Settings
screen also reads and edits the parts of the NAS Superwhisper profile that
change a dictation: language, cleanup tone, vocabulary and processing mode.

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
POST /transcribe?lang=tr&tone=semi-formal
Authorization: Bearer <token>
Content-Type: audio/wav

<WAV bytes>

200 {"text":"...","language":"tr","duration":5.83}
```

- `lang` is optional: `tr`, `auto`, or any Superwhisper language code. The
  bridge sets the daemon's global language pin immediately before each request,
  under a mutex, so overlapping requests cannot fight over one session.
- `tone` is optional: `casual`, `semi-casual`, `balanced`, `semi-formal` or
  `formal`. The bridge validates it and sets the cleanup tone under the same
  session mutex, immediately after the language pin. Omitted means "leave the
  profile's tone".
- `GET /health` (also token-checked) answers the version, the default language
  and the active transcriber; the phone's settings screen uses it as a test.
- 401 missing/wrong token, 413 too large or too long, 415 non-WAV content type,
  502 the CLI failed or was rejected, 504 the request timed out.
- Limits: 10 MiB, 120 s of audio, 60 s of processing. The recorded WAV is a
  private temp file and is deleted when the request returns (success or error).

### Settings API

All three endpoints are token-checked and speak JSON. They are the phone's only
way to read or change the NAS Superwhisper profile.

```text
GET  /settings
PUT  /settings/vocabulary   {"terms":["Omarchy"],"replacements":[{"original":"Oh Marchie","with":"Omarchy"}]}
POST /settings/processing   {"mode":"cloud" | "offline" | "offline_lite"}
```

`GET /settings` returns:

```json
{
  "tone": "semi-formal",
  "tones": [{"label": "Casual", "value": "casual"}],
  "languages": [{"label": "Turkish", "value": "tr"}],
  "vocabulary": {"terms": ["Omarchy"], "replacements": []},
  "processing": "cloud",
  "processing_modes": [
    {"value": "cloud", "label": "Cloud", "download_bytes": 0, "missing": []},
    {"value": "offline", "label": "Offline", "download_bytes": 2262730392, "missing": []}
  ],
  "processing_target": null,
  "processing_error": null,
  "download_bytes": 0,
  "download_total": 0,
  "download_percent": 0,
  "models": [{"id": "cleanup", "title": "S1 Mini", "state": "installed", "bytes": 0, "total": 0, "disk_bytes": 367803296}]
}
```

`PUT /settings/vocabulary` replaces the whole list: the bridge diffs it against
`vocab show` and runs only the `vocab add`, `vocab remove`, `vocab replace` and
`vocab remove-replacement` calls that are needed. Terms are at most 200 entries
of 64 characters; replacements are at most 100.

`POST /settings/processing` switches to Cloud immediately. For Offline and
Offline Lite it starts a background task that downloads the missing models one
at a time and selects the mode when the last one is installed; the request
returns at once and the phone polls `GET /settings` for `download_percent`.
While the task runs, `processing_target` names the mode and `processing` stays
on the old engine, so dictation keeps working. Sending `{"mode":"cloud"}`
cancels a running download target.

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

The launcher activity is the settings screen. It stores the mode, the token,
both server URLs, the dictation language, the tone and the shortcuts in the app's private
`SharedPreferences` (the token is only as protected as the app sandbox; there
is no keystore yet) and offers:

- **Local** / **Remote** modes. The mode's URL is the field on screen; the
  other one waits as a one-line summary until **Edit** is tapped. The defaults
  are the owner's bridge (`http://<nas-lan>:8787` and
  `https://<nas-host>`); the Remote URL must be HTTPS. One shared
  token and a **Save & test** button that calls `GET /health`;
- the Android 17 local-network permission for the Local mode (the remote route
  does not need it);
- the dictation language: all 103 languages Superwhisper 1.2.0 exposes, with a
  filter field in the picker; Turkish is the initial default;
- the cleanup tone: Casual, Semi-casual, Balanced, Semi-formal or Formal. The
  phone sends it on every request, so it wins over the NAS profile's tone;
- the server profile's vocabulary: word chips with add/remove, simple
  `heard → write` replacements, and one **Save vocabulary** button that PUTs
  the whole list;
- the processing mode: Cloud, Offline or Offline Lite, with the download size,
  a progress bar and a cancel button. The bridge downloads missing models to
  the NAS in the background and switches when they are ready;
- a comma-separated list of quick inserts (words or slash commands) shown
  above the compact keyboard;
- the system input-method settings/picker and the microphone permission.

**Save** and **Save & test** sit on a pinned bar at the bottom of the screen,
together with the result message, so the action is never three cards away from
the field it saves. The top-right badge is `ACTIVE` only when this app is the
system's current keyboard, `ENABLED` when it is enabled but another IME is
selected, and `SETUP` otherwise.

### Keyboard

- The keyboard opens in a compact dictation/prompt layout. The top bar, left
  to right: the keyboard icon, the green **Dictate** pill with its trailing
  language segment (`TR`, `EN-GB`, `AUTO`, …), clipboard and, in compact only,
  delete. The keyboard icon switches to the full QWERTY; long-press opens
  Settings. Tapping the language segment opens the in-keyboard language panel,
  using the same list as Settings.
- After a transcript lands the pill reads **Inserted** for four seconds and the
  segment becomes **Undo**, which deletes exactly the code points that
  dictation inserted. The confirmation costs no extra row.
- Under the top bar, the app-configured shortcut row shows slash commands
  (`/goal`, `/compact` by default). Tapping inserts the command plus a space;
  edit the comma-separated list in Settings.
- The compact body: cursor arrows (hold to repeat), the unlabelled space bar
  and enter, then
  icon buttons for `Undo`, `Cut`, `Copy` and `Paste`. Below the icons, the
  clipboard appears as a single horizontal row of boxes; tap one to insert it.
  Delete is the top-bar key (hold to repeat); there is no backspace beside the
  arrows. Vibration is off.
- The keyboard icon opens the full English QWERTY (number row,
  `qwertyuiop`, `asdfghjkl`, `zxcvbnm`, shift/backspace, `?123`,
  space/enter); the same icon returns to the compact surface. The compact
  layout is the default on every keyboard show; the full switch is temporary.
  Shift applies to one letter; a second tap within 300 ms locks it, and the
  locked key shows a small bar under the arrow.
- The Enter key follows the focused editor: when it declares a send action the
  key shows `↑` and performs that action, so Enter sends in a chat composer;
  plain and multiline fields still get a newline. The system's own app actions
  (go, search, done) use the same path.
- There is no **Local/Remote** chip on the keyboard. The mode lives in
  Settings, opened by long-pressing the keyboard icon. A recording replaces
  the keyboard icon with a cancel `X`; a quick tap locks the recording, a hold
  is push-to-talk.
- The clipboard button replaces the old keyboard-switch key. It opens a panel
  over the key area with one grid of cards: `TEXT` clips, `DICTATION`
  transcripts and `IMAGE` thumbnails together, with no separate sections.
  Tapping a text clip commits it, tapping an image commits it with
  `InputConnection.commitContent`. The expand icon opens the full clipboard view
  with `ALL`/`TEXT`/`IMAGES`/`DICTATION` filters, relative timestamps,
  long-press actions (`Insert`, `Copy`, `Delete`) and a two-tap `Clear all`
  (clips only; transcripts stay). The picked card keeps a green outline while
  the rest dim, and an empty filter says which kind is missing. Search is not
  implemented yet. The panel has no "System keyboard" action; the picker lives
  in Settings.
- There is no chips row and no bottom transcript strip. The last transcripts
  live in the clipboard panel as `DICTATION` entries. Errors appear as one compact
  line with "Retry" (retry the same WAV) and "Settings".

The language panel opens centred on the current language: a **COMMON** block
first, then the remaining languages grouped by the first letter of their
English name, with an A–Z rail above the list that jumps to a letter. The panel
has no text field — an IME-internal query would need the QWERTY routed to it.

### Network security

`network_security_config.xml` permits cleartext only for `<nas-lan>`. The
Cloudflare endpoint is HTTPS, so it needs no cleartext exception. Android 17
blocks app traffic to LAN addresses unless the app holds the
`ACCESS_LOCAL_NETWORK` runtime permission; the app declares it and the settings
screen can request it. The public route is the fallback when the permission is
denied or the phone is away from the LAN. The bridge port is `8787` because
`8765` is already taken on the NAS.

## Decisions

- Language: Turkish by default, with a selectable list of supported dictation
  languages and `Auto-detect`. In a NAS test the same short Turkish sentence
  came back as English gibberish with `auto`, and correctly with `tr` in
  ~0.4 s; the pill segment shows the selected code and opens the language
  panel.
- Layout: compact dictation/prompt surface by default; the left-hand keyboard
  icon opens the full English QWERTY and returns. The top bar is keyboard
  icon, the Dictate pill carrying the language segment, clipboard and the
  compact delete key. Quick inserts come from Settings.
  No mode chip, no fixed starter row, vibration off, no bottom transcript
  strip.
- Connection: one token, two modes with editable URLs. The initial Local URL is
  `http://<nas-lan>:8787`, the initial Remote URL is
  `https://<nas-host>`; both are stored in Settings. The keyboard
  shows no mode chip. The UI is English and the mode is called **Remote**, not
  Cloudflare.
- Clipboard: text and images. Images are copied into the app cache and shared
  through a `FileProvider`; insertion uses `InputConnection.commitContent`, so
  fields that do not accept content show a clear error. The quick grid expands
  to a filterable full view; history is session-only.
- Package: `com.engin.warpandroidscribe`; minSdk 26, targetSdk 37.
- License: the NAS profile currently runs on Superwhisper's free 10 000-word
  cloud quota (`license status` says `unlicensed`). File transcription works;
  activating Pro on the NAS is a follow-up, not a code change.
- Superwhisper's language pin is global to the profile. The bridge serializes
  requests and sets it per request; the NAS bridge is a dedicated profile.
- Tone travels with each request (`?tone=`), like the language pin, so changing
  it in the phone's Settings does not depend on a second write endpoint. The
  bridge sets it inside the same session mutex.
- Vocabulary is the one server-wide setting the phone edits: terms and
  replacements are saved in the NAS profile, so every dictation there sees
  them. Replacements apply in Cloud and Offline; terms are matched by the
  Offline acoustic vocabulary, which is why the Offline models are worth the
  2.5 GB.
- Processing is server-wide. The phone starts the download and polls; the
  bridge keeps the old engine selected until every model for the target mode is
  installed, so a dictation during the download still works.
- The Enter key is editor-aware: a declared send action turns it into the send
  key (`↑`) and sends; otherwise it stays a newline Enter.
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
- Mode is only in Settings now; long-press the keyboard icon. The compact
  layout is restored on every keyboard show; the full layout is not remembered.
- Undo/cut/paste are best-effort: `performContextMenuAction` first, then
  Ctrl+Z/X; editors that support neither simply ignore them.
- The full clipboard view has filters but no search. Searching an IME-internal
  list needs the full QWERTY routed into the IME's own query instead of the
  app's input connection; that is a separate v2 change.
- Clipboard history is session-only. After the process dies, only the current
  system clip and the recent `RECENT` transcripts come back; image cache files
  expire after 24 hours.
- Slash-command shortcuts only insert text into the focused field; they do not
  call the harness API. A command that needs a running harness still depends on
  the app receiving the text.
- The system keyboard bar (switch/hide) is Android's, not part of the
  keyboard. The app measures the IME window against the display and pads the
  keys and the panel by the navigation-bar height, so nothing is hidden under
  it.
- The Android build is a debug APK; release signing is not set up yet.
- Offline + Offline Lite models occupy about 2.7 GB on the NAS. The first
  dictation after switching to Offline waits for the workers to warm (about
  20 s); the bridge retries the warming response instead of failing, but the
  request is slow once.
- Cloud mode does not correct vocabulary terms; replacements do. Offline terms
  work because the acoustic vocabulary model is installed. The vocabulary card
  says so.
- The phone's tone is sent on every request, so it overrides a tone set in the
  NAS Superwhisper window; the NAS is a dedicated profile.
