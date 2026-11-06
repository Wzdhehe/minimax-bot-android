import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keyProps = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// The Bot product never launches the same-UID DSH/PRoot runtime. Its Node VM
// and code assets are assembled explicitly for the selected Android ABI.
val mobileTooling = rootProject.file("../../tooling/mobile-runtime-bundle")
val mobileProducts = File(mobileTooling, "build/product")

// versionCode is the build minute, same encoding as tooling/build_number.py:
// (year - 2026) * 10^8 + MMDDhhmm at UTC+8. tooling/build-apk.sh passes it as
// --build-number; this covers builds that skip the script (flutter run, the
// IDE), where pubspec's `+1` placeholder would be a downgrade and make
// `flutter run` uninstall the installed app, data included.
fun buildMinuteVersionCode(): Int {
    val t = OffsetDateTime.now(ZoneOffset.ofHours(8))
    return (t.year - 2026) * 100_000_000 + t.monthValue * 1_000_000 + t.dayOfMonth * 10_000 + t.hour * 100 + t.minute
}

android {
    namespace = "io.github.ccch1mneyyy.dsh_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "27.3.13750724"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // Install the Bot product alongside upstream DSH without overwriting
        // its app data. The namespace stays stable for existing JNI bindings.
        applicationId = "io.github.ccch1mneyyy.dsh_app.mbot"
        // java.nio.file (symlinks while unpacking the rootfs) needs API 26.
        minSdk = 26
        // Bot-only fork: comply with the current Play target API requirement.
        // Do not bypass Android's exec restrictions with the legacy DSH target.
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_static"
        versionCode = flutter.versionCode.takeIf { it > 1 } ?: buildMinuteVersionCode()
        versionName = flutter.versionName
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    sourceSets {
        getByName("arm64") {
            assets.srcDir(File(mobileProducts, "arm64-v8a/assets"))
            jniLibs.srcDir(File(mobileProducts, "arm64-v8a/jniLibs"))
        }
        getByName("x64") {
            assets.srcDir(File(mobileProducts, "x86_64/assets"))
            jniLibs.srcDir(File(mobileProducts, "x86_64/jniLibs"))
        }
    }

    packaging {
        // Independent Android UIDs cannot read Main's extracted native
        // directory. Map uncompressed libraries directly from the signed APK.
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += listOf("**/libnode.so", "**/libmbot_node_runtime.so", "**/libmbot_sqlite_probe.so", "**/libmbot_sqlite_backup.so", "**/libc++_shared.so")
        }
    }

    androidResources {
        noCompress += listOf("gz", "xz", "zst", "tar")
    }

    signingConfigs {
        if (!keyProps.isEmpty) {
            create("release") {
                storeFile = keyProps.getProperty("storeFile")?.let { file(it) }
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
                // All three schemes: AGP drops v1 for minSdk 24+, but some installers and
                // security scanners on Chinese ROMs still read the JAR signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Use the release key when configured; local builds without key.properties fall back to debug.
            signingConfig = signingConfigs.getByName(if (keyProps.isEmpty) "debug" else "release")
            // Ours on top of Flutter's: phone control's privileged process and
            // its TLS libraries are reached by class name (see the file).
            proguardFiles("proguard-rules.pro")
        }
    }
}

// A missing or stale product assembly is a build failure, not an apparently
// successful APK which can only display an unavailable-engine screen.
for ((flavor, abi) in listOf("X64" to "x86_64", "Arm64" to "arm64-v8a")) {
    val verifier = tasks.register<Exec>("verify${flavor}BotProduct") {
        workingDir(mobileTooling)
        val python = providers.gradleProperty("mbotPython").orElse(
            if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3")
        commandLine(python.get(), File(mobileTooling, "prepare-product.py").absolutePath,
            "--verify-only", "--abi", abi)
    }
    tasks.configureEach {
        if (name.startsWith("pre${flavor}") && name.endsWith("Build")) dependsOn(verifier)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // JVM broker tests use real JSON; production uses Android's org.json.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")

    // .tar.xz support for the rootfs bundle.
    implementation("org.tukaani:xz:1.10")
    // Apache-2.0: native-only streaming HTTPS with socket-level cancellation.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 不打扰模式 (phone/background): a shell-uid privileged display via Shizuku or self-ADB.
    implementation("dev.rikka.shizuku:api:13.1.5")       // MIT
    implementation("dev.rikka.shizuku:provider:13.1.5")  // MIT
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") // used under its Apache-2.0 option; pulls in spake2-android (LGPL-3.0)
    implementation("org.conscrypt:conscrypt-android:2.8.0")         // Apache-2.0: libadb TLS/pairing
    // MIT: self-signed adb client cert. The same jdk15to18 family libadb-android
    // pulls in (bcprov-jdk15to18:1.81); adding jdk18on next to it duplicates every class.
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("androidx.webkit:webkit:1.15.0") // Apache-2.0: web islands' force dark (webview_flutter's own version)
}
