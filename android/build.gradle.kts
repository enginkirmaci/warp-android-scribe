plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin); the Kotlin Android plugin
    // must not be applied. The Compose compiler plugin is still separate.
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
