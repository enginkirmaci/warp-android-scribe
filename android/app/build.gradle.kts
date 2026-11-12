import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The suite version lives in version.toml at the repository root. The APK
// derives its own versionName/versionCode from it, so the phone never carries
// a second copy that drifts from the desktop apps.
val suiteToml = rootProject.file("../version.toml").readText()
val suiteVersion = Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""")
    .find(suiteToml)
    ?.groupValues
    ?.get(1)
    ?: error("version.toml has no version line")
val suiteParts = suiteVersion.split(".")
check(suiteParts.size == 3) { "version.toml version is not MAJOR.MINOR.PATCH: $suiteVersion" }
val (suiteMajor, suiteMinor, suitePatch) = suiteParts.map {
    it.toIntOrNull() ?: error("version.toml version is not numeric: $suiteVersion")
}
val suiteVersionCode = suiteMajor * 10_000_000 + suiteMinor * 100_000 + suitePatch

android {
    namespace = "com.engin.warpandroidscribe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.engin.warpandroidscribe"
        minSdk = 26
        targetSdk = 37
        versionCode = suiteVersionCode
        versionName = suiteVersion
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.savedstate:savedstate-ktx:1.5.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
