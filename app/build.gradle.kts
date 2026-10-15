import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "dev.finall.rearcardbridge"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.finall.rearcardbridge"
        minSdk = 35
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.0"
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    // Local signing files are ignored by Git; never fall back to another signing key.
    val finallKeystore = rootProject.file("signing/release.jks")
    check(finallKeystore.isFile) {
        "Missing finall signing keystore: $finallKeystore. Do not replace it with the default debug key."
    }
    val isCi = providers.environmentVariable("CI").map(String::toBoolean).getOrElse(false)
    if (isCi) {
        // CI: the workflow restores the same signing/release.jks from secrets and
        // passes the same alias/password via environment. Anything missing fails the build.
        fun ciProperty(name: String): String =
            requireNotNull(providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }) {
                "Missing $name environment variable for CI signing; refusing to use a different signing key."
            }
        val finall = signingConfigs.create("finall") {
            storeFile = finallKeystore
            storePassword = ciProperty("RELEASE_STORE_PASSWORD")
            keyAlias = ciProperty("RELEASE_KEY_ALIAS")
            keyPassword = ciProperty("RELEASE_KEY_PASSWORD")
        }
        buildTypes {
            debug { signingConfig = finall }
            release { signingConfig = finall; isMinifyEnabled = false }
        }
    } else {
        val keychainConfigFile = rootProject.file("signing/keychain.properties")
        check(keychainConfigFile.isFile) { "Missing signing/keychain.properties." }
        val keychainConfig = Properties().apply {
            keychainConfigFile.inputStream().use { load(it) }
        }
        fun signingProperty(name: String): String =
            requireNotNull(keychainConfig.getProperty(name)?.takeIf { it.isNotBlank() }) {
                "Missing $name in signing/keychain.properties."
            }
        run {
            val keychain = providers.exec {
                commandLine("/usr/bin/security", "find-generic-password",
                    "-s", signingProperty("keychainService"),
                    "-a", signingProperty("keychainAccount"), "-w")
                isIgnoreExitValue = true
            }
            check(keychain.result.get().exitValue == 0) {
                "Cannot read finall signing password from macOS Keychain; refusing to use a different signing key."
            }
            val password = keychain.standardOutput.asText.get().trimEnd('\r', '\n')
            check(password.isNotEmpty()) { "The finall signing password is empty." }
            val finall = signingConfigs.create("finall") {
                storeFile = finallKeystore
                storePassword = password
                keyAlias = signingProperty("keyAlias")
                keyPassword = password
            }
            buildTypes {
                debug { signingConfig = finall }
                release { signingConfig = finall; isMinifyEnabled = false }
            }
        }
    }
    // The debug variant installs side by side with the release app; providers and
    // module storage all follow BuildConfig.APPLICATION_ID, so nothing is shared.
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation("junit:junit:4.13.2")
}
