# warp-android-scribe — agent instructions

Read [`../../AGENTS.md`](../../AGENTS.md) first. This file adds only the
app-specific facts. The user-facing description is in [`README.md`](README.md).

## Shape

Two halves, two toolchains:

- `bridge/` is a normal member of the suite Cargo workspace
  (`warp-android-scribe-bridge`). It is selected by `-p` like every other
  member and is in the CI clippy/test selectors. It needs no GPUI and no
  Android SDK.
- `android/` is a standalone Gradle project (AGP 9.5.1, built-in Kotlin,
  Compose). It is **not** a Cargo member, **not** in `apps.toml` and **not**
  part of `scripts/build.sh` or the suite release. Do not add an Android
  dependency to the root workspace.

## Bridge

- Wire contract: `POST /transcribe?lang=…&tone=…` with raw `audio/wav` and
  `Authorization: Bearer <token>`; `GET /health` for the phone's server test;
  `GET /settings`, `PUT /settings/vocabulary` and `POST /settings/processing`
  for the profile. Changing the status codes, the JSON field names or the
  bearer scheme means the Android client changes in the same commit.
- `SuperwhisperCli` serializes the whole set-language/transcribe/poll sequence
  with a tokio mutex: the daemon has one active session. Keep it that way.
- The language pin is global in the Superwhisper profile; set it before every
  request, not once at startup.
- The tone is the same kind of global profile pin; set it under the same mutex
  right after the language. A `transcribe` that answers "Not ready: warming"
  (exit 1) is retried until the request budget runs out: switching the
  processing mode restarts the workers and takes about 20 s.
- Poll `superwhisper status` until `phase=idle` **and** `last.session_id` is
  the id the transcribe command returned. Do not accept an idle with an older
  session id.
- `settings.rs` is the only module that runs `general show`, `vocab` and
  `models`; it parses the CLI's camelCase model JSON by hand. Vocabulary PUTs
  diff against the current list and run only the needed `vocab` commands.
  Processing downloads run in a spawned task, one model at a time, and the
  current engine stays selected until the target mode has no missing models.
- The request WAV is a `NamedTempFile` in the configured work dir; it must be
  dropped on success, failure and timeout. `tests/api_probe.rs` asserts the
  directory is empty afterwards.
- Configuration is env-only and the token is mandatory (>= 16 chars). Never
  log the token or the transcript text.

## Android

- The IME is a `ComposeView` with a hand-made owner stack:
  `ImeLifecycleOwner` provides lifecycle, view-model and saved-state owners and
  is attached with the `setViewTree*` extension functions. A plain service has
  none of these; a ComposeView in an IME will not compose without them.
- State lives in `ScribeUiState` (Compose `mutableStateOf`) owned by the
  service; the UI is a pure function of it and `ScribeActions`.
- The keyboard opens compact (`KeyboardMode.COMPACT`) and resets to compact on
  every `onWindowShown`; the full layout is a temporary switch, not a
  preference. The top bar is the keyboard icon (tap toggles full QWERTY,
  long-press opens Settings), the language code, **Dictate**, clipboard and —
  in compact only — delete (hold repeats); the full layout carries its own
  backspace in the letter row, so the bar hands that slot to Dictate. The
  language and clipboard controls go inert and dim while a recording or a
  transcription runs. Under it, `ScribePrefs.shortcuts` renders the
  configurable slash commands (`/goal`, `/compact` by default). The compact
  body is cursor arrows (hold repeats), space/enter, icon buttons for
  `Undo`/`Cut`/`Copy`/`Paste`, and a single horizontal clipboard strip of
  boxes; no punctuation row, no fixed starter row, no backspace beside the
  arrows, and vibration is intentionally off. There is no Local/Remote chip and
  no bottom transcript strip; do not bring them back.
- The clipboard button opens a panel instead of the keyboard-switch key: one
  grid of cards for every clip (text, `RECENT` transcript, image), with no
  separate text/image sections. Text commits with `commitText`, an image
  copies to `cacheDir/clipboard` and goes through the
  `${applicationId}.files` `FileProvider` with
  `InputConnectionCompat.commitContent(connection, editorInfo, …)`. Some fields
  reject content; show the error and keep the panel state sane.
- The quick grid expands to `ClipboardView.FULL`: `ALL`/`TEXT`/`IMAGES`/`RECENT`
  filters, relative timestamps, long-press `Insert`/`Copy`/`Delete` (the picked
  card keeps a green outline and the rest dim; Delete is the error colour), and
  a two-tap `Clear all` that removes clips but keeps `RECENT` transcripts.
  Search is deliberately v2: an IME-internal query needs the QWERTY routed to
  it instead of the app's `InputConnection`. Clipboard history is session-only;
  do not claim it survives process death.
- The system's two-button keyboard bar (switch/hide) is drawn over the IME
  window, and the navigation-bar inset is reported as zero for
  `INPUT_METHOD` windows. Measure whether the IME window reaches the display
  bottom and pad the whole keyboard by `navigation_bar_height`; do not go back
  to a fixed 20 dp guess.
- Audio: `WavRecorder` (16 kHz mono PCM16, `VOICE_RECOGNITION`), max 120 s.
  Delete the cache WAV on success/cancel and sweep stale files on service
  creation. Never leave a recording behind.
- The language button opens the in-keyboard language panel (the full list,
  auto-scrolled to the current code) and sends the selected code as `?lang=…`;
  `SUPPORTED_LANGUAGES` is the 103 entries from Superwhisper 1.2.0,
  `prefs.language` persists the choice, and Settings uses a filterable dialog
  over the same list. Do not put an editable search field in the IME panel: an
  IME-internal query would need the QWERTY routed to it instead of the app's
  `InputConnection`.
- Settings also edits the NAS profile: the tone is stored in `prefs.tone` and
  sent as `?tone=…` on every request; word chips and `heard → write`
  replacements are saved with one PUT; processing is a Cloud/Offline/Offline
  Lite card that polls `GET /settings` while the bridge downloads models. The
  server's language list, vocabulary and processing status load from
  `GET /settings`; a failed load leaves the bundled list in place.
- The Enter key is editor-aware: `ui.sendKey` is set from the focused
  `EditorInfo` action, the key shows `↑` for `IME_ACTION_SEND`, and `enter()`
  calls `sendDefaultEditorAction(true)` only for a declared action. Plain and
  multiline fields keep the Enter key event.
- Cleartext is limited to `<nas-lan>` in
  `res/xml/network_security_config.xml`. The public route is HTTPS and needs no
  exception.
- targetSdk 37: Android 17 blocks sockets to LAN addresses without the
  `ACCESS_LOCAL_NETWORK` runtime permission. It is declared in the manifest
  and requested from the settings screen; `ScribePrefs.usesLocalNetwork` is
  the gate, and the IME shows an error instead of a silent timeout. The
  Remote mode does not need this permission.
- `ScribePrefs.mode` is `LOCAL` or `REMOTE`; `localUrl` and `remoteUrl` are
  editable, initialised to the owner's bridge (`http://<nas-lan>:8787` and
  `https://<nas-host>`), and both share one bearer token. The
  settings screen writes them; the keyboard has no mode chip. Remote must be
  HTTPS. Do not add a third host/port form.
- All app UI strings are English and the full keyboard layout is English
  QWERTY. The dictation language is separate from the layout. Do not
  reintroduce Turkish UI labels or Turkish letter keys.
- Public path: Cloudflare Tunnel public hostname `<nas-host>` ->
  Nginx Proxy Manager -> `<nas-lan>:8787`. The NPM proxy host is
  `<nas-host>`, and the wildcard `*.engin.page` certificate covers
  it. The tunnel route itself lives in the Cloudflare dashboard.
- `SettingsActivity` reads `Settings.Secure.ENABLED_INPUT_METHODS`? Do not: it
  throws `SecurityException` for targetSdk > 33. Use
  `InputMethodManager.enabledInputMethodList`.
- Compose in an IME: use `DisposeOnDetachedFromWindowOrReleasedFromPool` and
  stop recording when `onFinishInputView(finishingInput=true)` arrives.

## Commands

```sh
cargo test -p warp-android-scribe-bridge
cargo clippy --locked --all-targets -p warp-android-scribe-bridge -- -D warnings

cd android-apps/warp-android-scribe/android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk \
ANDROID_HOME=$HOME/tools/android-sdk \
./gradlew assembleDebug
```

Device access from the laptop is through the NAS because the phone and laptop
are isolated on Wi-Fi:

```sh
# On the NAS, discover the phone's current wireless-debugging port:
avahi-browse -rt _adb-tls-connect._tcp
ssh -f -N -L 127.0.0.1:39441:<phone-lan>:<port> <nas-lan>
adb connect 127.0.0.1:39441
```

The port changes when wireless debugging restarts; do not assume `39441`.

## Do not

- Add the Android project to the Cargo workspace or `apps.toml`, or make the
  desktop build depend on it.
- Commit `local.properties`, a keystore, the bridge token or an APK.
- Expose `/transcribe` without the 64-hex bearer token and HTTPS. The public
  route is `https://<nas-host>`; keep the token out of logs and the
  repo, and add Cloudflare Access or a rate limit before sharing it further.
- Change `superwhisper`'s global language outside the bridge mutex.
- Use `Settings.Secure` for IME state on targetSdk > 33.
- Add a second palette/theme; the keyboard palette is the app's own and lives
  in `ui/Theme.kt`.
- Add a per-app build script or installer; the suite's `scripts/` owns desktop
  packaging, and this app is sideloaded.
