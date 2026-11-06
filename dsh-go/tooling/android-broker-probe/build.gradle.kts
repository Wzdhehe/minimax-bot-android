// SPDX-License-Identifier: AGPL-3.0-only
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault

plugins { id("com.android.application") version "9.1.0" }

// Compile generated copies of the real app sources. AGP's variant API records
// these task dependencies and treats generated files as read-only in the IDE.
@DisableCachingByDefault(because = "Small deterministic verification-source copy")
abstract class SharedNativeSources : DefaultTask() {
    @get:InputDirectory abstract val inputDir: DirectoryProperty
    @get:Input abstract val includes: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations
    @TaskAction fun copySources() {
        fileSystem.sync {
            from(inputDir)
            include(this@SharedNativeSources.includes.get())
            into(outputDir)
        }
    }
}
val nativeSources by tasks.registering(SharedNativeSources::class) {
    inputDir.set(file("../../app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app"))
    includes.set(listOf("model/**", "isolation/**", "runtime/RuntimeDescriptor.kt", "runtime/RuntimeDescriptorPayload.kt", "bot/BotProviderStore.kt", "bot/BotSettingsStore.kt", "bot/GuestProviderCatalog.kt", "bot/NativeProviderBroker.kt", "bot/NativeModelChannel.kt", "bot/NativeModelRequestHost.kt"))
    outputDir.set(layout.buildDirectory.dir("shared-native"))
}
val deviceTests by tasks.registering(SharedNativeSources::class) {
    inputDir.set(file("../../app/android/app/src/androidTest/kotlin/io/github/ccch1mneyyy/dsh_app"))
    includes.set(listOf("isolation/AgentIsolationClientTest.kt", "isolation/RuntimeSnapshotOwnerTest.kt", "bot/ProviderStorageTest.kt", "runtime/RuntimeDescriptorTest.kt"))
    outputDir.set(layout.buildDirectory.dir("shared-device-tests"))
}
val unitTests by tasks.registering(SharedNativeSources::class) {
    inputDir.set(file("../../app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app"))
    includes.set(listOf("model/**", "isolation/**", "bot/GuestProviderCatalogTest.kt", "runtime/RuntimeDescriptorPayloadTest.kt"))
    outputDir.set(layout.buildDirectory.dir("shared-unit-tests"))
}
val sharedResources by tasks.registering(SharedNativeSources::class) {
    inputDir.set(file("../../app/android/app/src/main/res"))
    includes.set(listOf("xml/network_security_config.xml"))
    outputDir.set(layout.buildDirectory.dir("shared-resources"))
}

// Refuse to build against stale inputs or a stale library. prepare-backup.py
// records the digest of every compiled byte and of every packaged library;
// this check compares them with the current tree, so a source change or a
// replaced library fails the build instead of shipping an old product.
@DisableCachingByDefault(because = "Verification of the prepared product, not a cacheable product")
abstract class VerifyPreparedBackup : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @get:InputDirectory abstract val jniLibs: DirectoryProperty
    @get:Input abstract val repositoryRootPath: Property<String>
    @get:InputFiles abstract val repositorySources: ConfigurableFileCollection

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    @TaskAction fun verify() {
        val summary = groovy.json.JsonSlurper()
            .parseText(manifest.get().asFile.readText(Charsets.UTF_8)) as Map<*, *>
        val root = File(repositoryRootPath.get())
        for ((name, value) in summary["inputs"] as Map<*, *>) {
            val entry = value as Map<*, *>
            val source = File(root, entry["repo_path"] as String)
            if (!source.isFile) throw GradleException("Prepared backup input is missing: $name (${source.path})")
            val actual = sha256(source)
            if (actual != entry["sha256"]) {
                throw GradleException(
                    "Prepared backup input is stale: $name changed after prepare-backup.py ran " +
                        "(expected ${entry["sha256"]}, found $actual). Run prepare-backup.py again."
                )
            }
        }
        for ((abi, value) in summary["libraries"] as Map<*, *>) {
            for ((name, digest) in value as Map<*, *>) {
                val library = File(jniLibs.get().asFile, "$abi/$name")
                if (!library.isFile) throw GradleException("Prepared backup library is missing: $abi/$name")
                val actual = sha256(library)
                if (actual != digest) {
                    throw GradleException(
                        "Prepared backup library is stale: $abi/$name does not match the recorded digest. " +
                            "Run prepare-backup.py again."
                    )
                }
            }
        }
    }
}
val preparedBackup by tasks.registering(VerifyPreparedBackup::class) {
    manifest.set(file("../../tooling/android-node-probe/build/verified-backup/backup-inputs.json"))
    jniLibs.set(file("../../tooling/android-node-probe/build/verified-backup/jniLibs"))
    repositoryRootPath.set(file("../../").absolutePath)
    repositorySources.from(fileTree("../../runtime/native-sqlite") { include("*.cpp", "*.h") })
    repositorySources.from(file("../../tooling/android-node-probe/src/main/cpp/fd_vfs.cpp"))
    repositorySources.from(file("../../tooling/android-node-probe/sqlite-runtime-pin.json"))
}
android {
    namespace = "io.github.ccch1mneyyy.dsh_app"
    compileSdk = 36
    ndkVersion = "27.3.13750724"
    defaultConfig {
        applicationId = "io.github.ccch1mneyyy.dsh_app.brokerprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "verification-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The same static STL the production app builds its native library with.
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_static"
        ndk {
            abiFilters += listOf("x86_64", "arm64-v8a")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake {
            // The production CMake source of the app, unchanged.
            path = file("../../app/android/app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(file("../../tooling/android-node-probe/build/verified-backup/jniLibs").absolutePath)
        }
    }
    packaging {
        jniLibs {
            // The isolated backup worker runs under its own UID and must map
            // the JNI library from the world-readable APK. An extracted,
            // app-private nativeLibraryDir is not readable by that UID.
            useLegacyPackaging = false
        }
    }
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(nativeSources) { it.outputDir }
    variant.sources.res?.addGeneratedSourceDirectory(sharedResources) { it.outputDir }
    variant.deviceTests.values.forEach { test ->
        test.sources.kotlin?.addGeneratedSourceDirectory(deviceTests) { it.outputDir }
    }
    variant.hostTests.values.forEach { test ->
        test.sources.kotlin?.addGeneratedSourceDirectory(unitTests) { it.outputDir }
    }
}
tasks.named("preBuild").configure { dependsOn(preparedBackup) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
