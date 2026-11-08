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

- Wire contract: `POST /transcribe?lang=…` with raw `audio/wav` and
  `Authorization: Bearer <token>`; `GET /health` for the phone's server test.
  Changing the status codes, the JSON field names or the bearer scheme means
  the Android client changes in the same commit.
- `SuperwhisperCli` serializes the whole set-language/transcribe/poll sequence
  with a tokio mutex: the daemon has one active session. Keep it that way.
- The language pin is global in the Superwhisper profile; set it before every
  request, not once at startup.
- Poll `superwhisper status` until `phase=idle` **and** `last.session_id` is
  the id the transcribe command returned. Do not accept an idle with an older
  session id.
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
- The keyboard is a full Turkish QWERTY (`ScribeKeyboard.kt`): number row,
  letter rows, shift/backspace, a `?123` symbol page, and the bottom
  space/enter row. The top bar holds the Local/Remote chip, the green dictate
  pill (`TR`/`OTO`) and the clipboard button. There is no chips row and no
  bottom transcript strip; do not bring them back.
- The clipboard button opens a panel instead of the keyboard-switch key: one
  grid of cards for every clip (text, `SON` transcript, image), with no
  separate text/image sections. Text commits with `commitText`, an image
  copies to `cacheDir/clipboard` and goes through the
  `${applicationId}.files` `FileProvider` with
  `InputConnectionCompat.commitContent(connection, editorInfo, …)`. Some fields
  reject content; show the error and keep the panel state sane.
- The system's two-button keyboard bar (switch/hide) is drawn over the IME
  window, and the navigation-bar inset is reported as zero for
  `INPUT_METHOD` windows. Measure whether the IME window reaches the display
  bottom and pad the whole keyboard by `navigation_bar_height`; do not go back
  to a fixed 20 dp guess.
- Audio: `WavRecorder` (16 kHz mono PCM16, `VOICE_RECOGNITION`), max 120 s.
  Delete the cache WAV on success/cancel and sweep stale files on service
  creation. Never leave a recording behind.
- The language toggle sends `?lang=tr` or `?lang=auto`; `prefs.language`
  persists the choice.
- Cleartext is limited to `<nas-lan>` in
  `res/xml/network_security_config.xml`. The public route is HTTPS and needs no
  exception.
- targetSdk 37: Android 17 blocks sockets to LAN addresses without the
  `ACCESS_LOCAL_NETWORK` runtime permission. It is declared in the manifest
  and requested from the settings screen; `ScribePrefs.usesLocalNetwork` is
  the gate, and the IME shows an error instead of a silent timeout. The
  Remote mode does not need this permission.
- `ScribePrefs.mode` is `LOCAL` (`http://<nas-lan>:8787`) or `REMOTE`
  (`https://<nas-host>`); both share one bearer token. The settings
  screen and the keyboard's mode chip write the same preference. Do not add a
  third host/port form.
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
ssh -f -N -L 127.0.0.1:39441:<phone-lan>:39441 <nas-lan>
adb connect 127.0.0.1:39441
```

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
