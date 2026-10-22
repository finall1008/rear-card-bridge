import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

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
        versionCode = 18
        versionName = "1.3.0"
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
    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
    }
    // AGP 不接受 Provider；生成目录由 copyRootAgentDex 填充并用 preBuild 挂好依赖。
    sourceSets.getByName("main").assets.srcDir("${layout.buildDirectory.get()}/generated/assets")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
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

interface RootAgentExec {
    @get:Inject
    val execOperations: ExecOperations
}

val rootAgentSdk = extensions.getByType(ApplicationAndroidComponentsExtension::class.java).sdkComponents
val rootAgentExec = objects.newInstance(RootAgentExec::class.java)

val generateRootAgentDex = tasks.register("generateRootAgentDex") {
    group = "build"
    description = "Compile RelayInstaller.java with javac and convert it to classes.dex."
    val source = layout.projectDirectory.file("src/main/rootagent/RelayInstaller.java")
    val classesDir = layout.buildDirectory.dir("generated/rootagent/classes")
    val dexDir = layout.buildDirectory.dir("generated/rootagent")
    val sdkDirProvider = rootAgentSdk.sdkDirectory
    val bootJars = rootAgentSdk.bootClasspath
    inputs.file(source)
    inputs.files(bootJars)
    outputs.file(dexDir.map { it.file("classes.dex") })
    doLast {
        val classes = classesDir.get().asFile
        val dexOut = dexDir.get().asFile
        classes.mkdirs()
        dexOut.mkdirs()
        classes.listFiles()?.forEach { if (it.isFile) it.delete() }
        val sdkDir = sdkDirProvider.get().asFile
        val androidJar = bootJars.get().map { it.asFile }.firstOrNull { it.name == "android.jar" }
            ?: File(sdkDir, "platforms/android-37.0/android.jar")
        check(androidJar.isFile) { "android.jar not found: $androidJar" }
        val d8 = File(sdkDir, "build-tools/36.0.0/d8")
        check(d8.isFile) { "d8 not found: $d8" }
        // /usr/libexec/java_home is macOS-only; CI sets JAVA_HOME instead.
        val javaHome = ByteArrayOutputStream()
        val javaHomeTool = File("/usr/libexec/java_home")
        val home = if (javaHomeTool.isFile) {
            rootAgentExec.execOperations.exec {
                setCommandLine(listOf(javaHomeTool.absolutePath))
                standardOutput = javaHome
                isIgnoreExitValue = true
            }
        } else null
        val javac = when {
            home != null && home.exitValue == 0 ->
                File(javaHome.toString(Charsets.UTF_8).trim(), "bin/javac")
            !System.getenv("JAVA_HOME").isNullOrBlank() ->
                File(System.getenv("JAVA_HOME"), "bin/javac")
            else -> File(System.getProperty("java.home"), "bin/javac")
        }
        check(javac.isFile) { "javac not found: $javac" }
        rootAgentExec.execOperations.exec {
            setCommandLine(listOf(
                javac.absolutePath,
                "--release", "8",
                "-classpath", androidJar.absolutePath,
                "-d", classes.absolutePath,
                source.asFile.absolutePath,
            ))
        }
        val classFiles = classes.listFiles { file -> file.extension == "class" }?.sortedBy { it.name }.orEmpty()
        check(classFiles.any { it.name == "RelayInstaller.class" }) { "javac did not produce RelayInstaller.class" }
        val dexFile = File(dexOut, "classes.dex")
        if (dexFile.exists()) dexFile.delete()
        rootAgentExec.execOperations.exec {
            setCommandLine(
                listOf(
                    d8.absolutePath,
                    "--min-api", "26",
                    "--lib", androidJar.absolutePath,
                    "--output", dexOut.absolutePath,
                ) + classFiles.map { it.absolutePath },
            )
        }
        check(dexFile.isFile) { "d8 did not produce classes.dex" }
    }
}

tasks.register<Copy>("copyRootAgentDex") {
    group = "build"
    description = "Copy the root agent dex into generated assets as rootagent.dex."
    dependsOn(generateRootAgentDex)
    from(layout.buildDirectory.dir("generated/rootagent")) {
        include("classes.dex")
    }
    into(layout.buildDirectory.dir("generated/assets"))
    rename("classes.dex", "rootagent.dex")
}

tasks.named("preBuild") {
    dependsOn("generateRootAgentDex")
    dependsOn("copyRootAgentDex")
}

tasks.configureEach {
    if ((name.startsWith("merge") || name.startsWith("package")) && name.contains("Assets")) {
        dependsOn("copyRootAgentDex")
    }
}
