//! Build facts for `/health`: the bridge answers with the suite version, build
//! number, commit and channel the desktop apps show. There is no Windows
//! resource block; this binary runs on the NAS.

fn main() {
    warpui_kit_build::emit(warpui_kit_build::AppInfo::new(
        "Warp Android Scribe Bridge",
        "LAN HTTP bridge for the Android dictation keyboard",
        "warp-android-scribe-bridge",
    ));
}
