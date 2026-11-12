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
  Compose). It is **not** a Cargo member of the desktop workspace and **not**
  part of the desktop suite build or release. Do not add an Android
  dependency to the bridge's workspace.

## Bridge

- Wire contract: `POST /transcribe?engine=…&lang=…&tone=…` with raw `audio/wav`
  and `Authorization: Bearer <token>`; `GET /health` for the phone's server test;
  `GET /engines` for the engine picker; `GET /settings`,
  `PUT /settings/vocabulary` and `POST /settings/processing` for the profile.
  Changing the status codes, the JSON field names or the bearer scheme means the
  Android client changes in the same commit.
- Two engines share the bridge: Superwhisper (`superwhisper-cli`) and Hex
  (`hex-cli`, `hex model transcribe` with `--model whisper_large_v3_turbo`).
  The phone sends `?engine=`; `WARP_SCRIBE_TRANSCRIBER` only picks the default.
  An unknown engine is a `400 unknown_engine`; a single-engine bridge (the mock)
  answers every name with that engine. `/engines` reports `ready` and a `detail`
  line per engine, and the Android Settings screen hides the Superwhisper-only
  cards (tone, vocabulary, processing) when Hex is selected.
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
  preference. The error line is the keyboard's first row; under it the
  clipboard bar, then the top bar. The clipboard bar never disappears with the
  keys, so its button stays reachable while a panel is open. In every layout the
  top bar is the **Dictate** pill, and its far right belongs to the one
  transient action of a dictation: `Cancel` while a recording or transcription
  runs, then `Undo` for four seconds after a transcript (deleting the code
  points `handleTranscript` inserted). The keyboard icon (tap switches layouts,
  long-press opens Settings) sits left of enter, and delete (hold repeats) ends
  the row above: the quick-insert row in compact, the shift row in full, the
  last symbol row in symbols. No letter or symbol row carries a second
  backspace. The dictation language is the pill's leading segment, not a
  separate button: tapping it opens the language panel. Under it,
  `ScribePrefs.shortcuts` renders the configurable slash commands (`/goal`,
  `/compact` by default) in a row at the same key height as every other row,
  scrolling sideways, with delete pinned at its right end so a long command
  never pushes it off. Below that, one key row: emoji, comma, space, period,
  keyboard toggle, enter. Every layout keeps the comma directly left of space
  and the period directly right of it: the full QWERTY swaps `?123` in for the
  emoji slot with the symbols key (`ic_symbols`, a `=\<` mark) and puts delete
  at the end of the shift row, symbols swap `ABC` in and put delete at the end
  of the last symbol row. A numeric or phone editor
  (`TYPE_CLASS_NUMBER` / `TYPE_CLASS_PHONE`) replaces the key area with a number
  pad: four rows of digits, `,`/`.` on a number field and `+`/`#` on a phone
  field, with delete and a half-height enter down a wider (64 dp) right rail;
  the clipboard bar and the pill stay. No cursor arrows, no undo/cut/copy/paste row, no punctuation row, no
  fixed starter row, and vibration is intentionally off. The clipboard bar (above the top bar) is the clipboard button at the far
  left,
  then the single horizontal strip of clips (`No clips yet` when empty). There
  is no Local/Remote chip and no bottom transcript strip; do not bring them
  back.
- The clipboard button opens a panel: one grid of cards for every clip (text,
  `DICTATION` transcript, image), with no separate text/image sections. It is
  the clipboard bar's first control, above the top bar, and it goes inert and
  dim while a recording or a transcription runs. Text commits with
  `commitText`, an image copies to `cacheDir/clipboard` and goes through the
  `${applicationId}.files` `FileProvider` with
  `InputConnectionCompat.commitContent(connection, editorInfo, …)`. Some fields
  reject content; show the error and keep the panel state sane.
- The quick grid expands to `ClipboardView.FULL`: `ALL`/`TEXT`/`IMAGES`/`DICTATION`
  filters, relative timestamps, long-press `Insert`/`Copy`/`Delete` (the picked
  card keeps an accent outline and the rest dim; Delete is the error colour), and
  a two-tap `Clear all` that removes clips but keeps `RECENT` transcripts.
  Cards are labelled `TEXT`, `IMAGE` or `DICTATION` from `clipKindLabel`; the
  panel has no "System keyboard" action (Settings owns the picker) and an empty
  filter names the kind that is missing.
  Search is deliberately v2: an IME-internal query needs the QWERTY routed to
  it instead of the app's `InputConnection`. Clipboard history is session-only;
  do not claim it survives process death.
- The type, corner and row-height scale lives in `ui/Theme.kt`
  (`ScribeText`, `ScribeShape`, `ScribeSize`): 28/20/17/14/12/10 sp,
  8/12/21 dp, 40/44/48/52/96 dp. App code holds no other font-size, corner or
  row-height literal; add a token instead.
- The system's two-button keyboard bar (switch/hide) is drawn over the IME
  window, and the navigation-bar inset is reported as zero for
  `INPUT_METHOD` windows. Measure whether the IME window reaches the display
  bottom and pad the whole keyboard by `navigation_bar_height`; do not go back
  to a fixed 20 dp guess.
- Audio: `WavRecorder` (16 kHz mono PCM16, `VOICE_RECOGNITION`), max 120 s.
  Delete the cache WAV on success/cancel and sweep stale files on service
  creation. Never leave a recording behind.
- The language segment opens the in-keyboard language panel, centred on the
  current code: a `COMMON` block from `COMMON_LANGUAGE_CODES`, then the rest
  grouped by the first letter of the English name (Superwhisper's own order is
  not alphabetical), with an A–Z rail. It sends the selected code as `?lang=…`;
  `SUPPORTED_LANGUAGES` is the 103 entries from Superwhisper 1.2.0,
  `prefs.language` persists the choice, and Settings uses a filterable dialog
  over `displayLanguages()`. Do not put an editable search field in the IME
  panel: an IME-internal query would need the QWERTY routed to it instead of
  the app's `InputConnection`.
- The emoji key left of Space opens the emoji panel, the language panel's
  sibling. It opens with `MOST USED` — the emojis with the most taps, at most
  `MOST_USED_EMOJI_LIMIT` (20), ties broken by recency — then the full
  `res/raw/emoji.txt` catalogue: `group:` lines with one fully-qualified emoji
  each, generated from Unicode `emoji-test.txt` 16.0 (the system font's set;
  skin-tone variants and the Component group are left out). The grid is lazy,
  seven to a row at the 28 sp title step, so the 1,906 emoji cost nothing until
  scrolled. One tap commits with `commitText` and leaves the panel up for the
  next one; `ScribePrefs.recordEmojiUse` persists the counts, so the row
  survives a keyboard show. `showEmoji` closes on the panel's `Close` or BACK.
  It has no search field, for the same routing reason.
- Settings also edits the NAS profile: the tone is stored in `prefs.tone` and
  sent as `?tone=…` on every request; word chips and `heard → write`
  replacements are saved with one PUT; processing is a Cloud/Offline/Offline
  Lite card that polls `GET /settings` while the bridge downloads models. The
  server's language list, vocabulary and processing status load from
  `GET /settings`; a failed load leaves the bundled list in place.
- `ScribePrefs.engine` is `SUPERWHISPER` or `HEX` and the IME sends it as
  `?engine=` on every request. Settings renders the picker from `GET /engines`
  and hides the tone, vocabulary and processing cards while Hex is selected;
  the keyboard never shows which engine is active, so the Settings screen is
  the one place to change it.
- The Enter key is editor-aware: `ui.sendKey` is set from the focused
  `EditorInfo` action, the key shows `↑` for `IME_ACTION_SEND`, and `enter()`
  calls `sendDefaultEditorAction(true)` only for a declared action. Plain and
  multiline fields keep the Enter key event.
- Every key is one flat fill (`ScribeColors.Key`); no key is outlined and none
  sits on a raised tone. The phone's own wallpaper accent —
  `theme_customization_overlay_packages.accent_color`, then
  `system_accent1_500`, then the app green — is what a dictation hands off to
  and what is switched on: the Dictate pill (one flat fill, never a gradient),
  the Enter key (accent glyph, filled accent when the editor declares a send
  action), shift while it is on, the clipboard button while its panel is open,
  the picked clip card's outline, the clipboard filters and the selected
  language. Delete's glyph is text-coloured, not the error colour. Do not give
  a letter key the accent.
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

## Public history and secrets

- Never commit a token, PAT, password, private IP, Wi-Fi SSID, home path or
  personal email in file content. Use placeholders in docs and examples.
- A commit's author and committer are public. Use a public identity (the
  GitHub noreply address), never a private address.
- Pass credentials through GitHub Actions secrets only; never echo or log
  them.
- Before publishing, force-pushing or adding a remote, run this over the
  current tree and every ref:

  ```sh
  git grep -n -i -E '192\.168\.[0-9]+\.[0-9]+|10\.[0-9]+\.[0-9]+\.[0-9]+|/home/[a-z]+|ghp_|gho_|github_pat_|ghs_' -- .
  ```
