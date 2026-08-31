plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

/**
 * VM Manager, drawn natively inside JCode's own process.
 *
 * What ships is `classes.dex`, not the APK the build produces around it: this plugin owns no
 * resources — every icon it draws is a vector built in code — so there is no resource table for
 * JCode's `addAssetPath` to attach.
 *
 * **The dependency rules are the ABI.** Anything JCode already ships is `compileOnly`: the plugin
 * must resolve those classes from JCode at runtime, because the composition it returns is spliced
 * into JCode's own and two Compose runtimes in one process do not interoperate. Nothing here is
 * bundled, which is why this dex is small — it is this plugin's code and nothing else.
 */
android {
    namespace = "dev.blamspot.jcode.ext.vm"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // Never installed as an app; this only names the archive the dex comes out of.
        applicationId = "dev.blamspot.jcode.ext.vm"
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            // JCode does not minify either, and an obfuscated entry class cannot be found by name.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JCode's, resolved from JCode at runtime. Versions must match what JCode ships.
    compileOnly(files("libs/jcode-ext-api-abi3.jar"))
    // JCode's design system — the spacing scale, the compact buttons, the notice card. compileOnly
    // like Compose: these classes come from JCode at runtime, so this panel is drawn out of the same
    // parts the rest of the IDE is, and a change to the app's density or palette moves it too.
    compileOnly(files("libs/jcode-core-design.jar"))
    // Pinned to the versions JCode resolves, not to a BOM. These classes are not bundled — they are
    // looked up in JCode at runtime — so the signature this compiles against has to be the signature
    // that is there. Measured the hard way: built against the BOM's Compose 1.7.6, this called the
    // `FlowRow` overload taking a `FlowRowOverflow`, and JCode ships foundation 1.9.0, where that
    // overload is gone. It compiled, installed, and died with NoSuchMethodError on first draw.
    //
    // Keep in step with the app's `gradle/libs.versions.toml` and what it resolves — a BOM version is
    // not the same claim as a resolved one.
    compileOnly("androidx.compose.ui:ui:1.9.0")
    compileOnly("androidx.compose.foundation:foundation:1.9.0")
    compileOnly("androidx.compose.runtime:runtime:1.9.0")
    compileOnly("androidx.compose.material3:material3:1.3.1")
    compileOnly("androidx.compose.material:material-icons-extended:1.7.6")
    compileOnly("androidx.core:core-ktx:1.15.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
