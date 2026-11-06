// SPDX-License-Identifier: AGPL-3.0-only
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { id("com.android.application") version "9.1.0" }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

@DisableCachingByDefault(because = "Small deterministic verification-source copy")
abstract class SharedModelSources : DefaultTask() {
    @get:InputDirectory abstract val inputDir: DirectoryProperty
    @get:Input abstract val includes: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations
    @TaskAction fun copySources() {
        fileSystem.sync {
            from(inputDir)
            include(this@SharedModelSources.includes.get())
            into(outputDir)
        }
    }
}
val productionSources by tasks.registering(SharedModelSources::class) {
    inputDir.set(file("../../app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app"))
    includes.set(listOf("model/**", "bot/BotProviderStore.kt", "bot/BotSettingsStore.kt", "bot/GuestProviderCatalog.kt", "bot/NativeModelChannel.kt", "bot/NativeModelRequestHost.kt", "runtime/RuntimeDescriptor.kt", "runtime/RuntimeDescriptorPayload.kt", "runtime/BotLoopbackBridge.kt", "runtime/BotSocketConnection.kt", "runtime/BotConnectionGrants.kt", "runtime/BotRuntimeCoordinator.kt", "runtime/BotRuntimeBinder.kt", "runtime/BotRuntimeEndpoint.kt", "isolation/**"))
    outputDir.set(layout.buildDirectory.dir("shared-model"))
}
val productionResources by tasks.registering(SharedModelSources::class) {
    inputDir.set(file("../../app/android/app/src/main/res"))
    includes.set(listOf("xml/network_security_config.xml"))
    outputDir.set(layout.buildDirectory.dir("shared-resources"))
}
val verifyPreparedInputs by tasks.registering {
    doLast {
        val verification = JsonSlurper().parse(file("build/verified-assets/node-validation.json")) as Map<*, *>
        for (name in listOf("libnode.so", "libc++_shared.so", "libmbot_node_probe.so")) {
            check(file("build/verified-jni/x86_64/$name").isFile) { "Run prepare-probe.py with the pinned Node archive and NDK first" }
        }
        val bridge = JsonSlurper().parse(file("build/node-bridge-inputs.json")) as Map<*, *>
        val bridgeInputs = bridge["inputs"] as Map<*, *>
        check(bridgeInputs.keys == setOf("node_probe.cpp", "node_snapshot_bridge.inc")) { "Node JNI input set changed" }
        bridgeInputs.forEach { (name, digest) -> check(sha256(file("src/main/cpp/$name")) == digest) { "Node JNI input is stale: $name" } }
        check(sha256(file("build/verified-jni/x86_64/libmbot_node_probe.so")) == bridge["librarySha256"]) { "Node JNI library is stale" }
        if (verification["requiresFdVfs"] == true) {
            val backup = JsonSlurper().parse(file("build/verified-backup/backup-inputs.json")) as Map<*, *>
            val inputs = backup["inputs"] as Map<*, *>
            val paths = mapOf(
                "backup.cpp" to "runtime/native-sqlite/backup.cpp", "backup.h" to "runtime/native-sqlite/backup.h",
                "fd_backup.cpp" to "runtime/native-sqlite/fd_backup.cpp", "fd_backup.h" to "runtime/native-sqlite/fd_backup.h",
                "fd_vfs.cpp" to "tooling/android-node-probe/src/main/cpp/fd_vfs.cpp",
                "jni_backup.cpp" to "runtime/native-sqlite/jni_backup.cpp",
                "sqlite-runtime-pin.json" to "tooling/android-node-probe/sqlite-runtime-pin.json")
            check(inputs.keys == paths.keys) { "Backup input set changed" }
            paths.forEach { (name, path) ->
                val entry = inputs[name] as Map<*, *>
                check(entry["repo_path"] == path && sha256(file("../../$path")) == entry["sha256"]) { "Backup input is stale: $name" }
            }
            val libraries = (backup["libraries"] as Map<*, *>)["x86_64"] as Map<*, *>
            check(libraries.keys == setOf("libmbot_sqlite_backup.so", "libc++_shared.so")) { "Backup library set changed" }
            libraries.forEach { (name, digest) -> check(sha256(file("build/verified-backup/jniLibs/x86_64/$name")) == digest) { "Backup library is stale: $name" } }
            check(sha256(file("build/verified-jni/x86_64/libc++_shared.so")) == libraries["libc++_shared.so"]) { "Node and backup STL differ" }
        }
        check(file("build/verified-assets/node-fd-probe.cjs").isFile)
        check(file("build/verified-assets/node-validation.json").isFile)
        if (verification["requiresSqlite"] == true) {
            check(file("build/verified-jni/x86_64/libmbot_sqlite_probe.so").isFile)
            check(file("build/verified-assets/third_party/better-sqlite3/LICENSE").isFile)
            check(file("build/verified-assets/third_party/better-sqlite3/SQLITE-PUBLIC-DOMAIN.txt").isFile)
            if (verification["requiresFdVfs"] == true) check(file("build/verified-assets/third_party/better-sqlite3/FD-VFS-AGPL-LICENSE").isFile)
            check(!file("build/verified-assets/node-fd-probe.cjs").readText().contains("\"__MBOT_SQLITE_WRAPPER__\""))
        }
        check(file("build/verified-assets/third_party/nodejs-mobile/LICENSE").isFile)
        check(file("build/verified-assets/third_party/nodejs-mobile/LICENSE-nodejs-mobile").isFile)
        check(file("build/verified-assets/third_party/ndk/NOTICE").isFile)
        check(file("build/verified-assets/third_party/minimax-bot/LICENSE").isFile)
        check(file("build/verified-assets/third_party/mbot-workspace-guard/LICENSE").isFile)
    }
}
@DisableCachingByDefault(because = "Small verified native-library and notice projection")
abstract class SnapshotComponents : DefaultTask() {
    @get:InputFile abstract val verification: RegularFileProperty
    @get:InputFiles abstract val backupFiles: ConfigurableFileCollection
    @get:Internal abstract val backupRoot: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val fileSystem: FileSystemOperations
    @TaskAction fun copyComponents() {
        val metadata = JsonSlurper().parse(verification.get().asFile) as Map<*, *>
        // sync executes even for an empty source selection, retiring stale
        // components on a real no-SQLite preparation. Do not change its flags.
        fileSystem.sync {
            if (metadata["requiresFdVfs"] == true) {
                from(backupRoot.dir("jniLibs")) { include("x86_64/libmbot_sqlite_backup.so"); into("jni") }
                from(backupRoot.dir("third_party")) { into("assets/third_party/sqlite-backup") }
            }
            into(outputDir)
        }
    }
}
val snapshotComponents by tasks.registering(SnapshotComponents::class) {
    dependsOn(verifyPreparedInputs)
    verification.set(file("build/verified-assets/node-validation.json"))
    backupRoot.set(layout.buildDirectory.dir("verified-backup"))
    backupFiles.from(fileTree("build/verified-backup") { include("jniLibs/x86_64/libmbot_sqlite_backup.so", "third_party/**") })
    outputDir.set(layout.buildDirectory.dir("snapshot-components"))
}
android {
    namespace = "io.github.ccch1mneyyy.dsh_app"
    compileSdk = 36
    ndkVersion = "27.3.13750724"
    defaultConfig {
        applicationId = "io.github.ccch1mneyyy.dsh_app.nodeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "verification-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters.add("x86_64")
        externalNativeBuild.cmake.arguments += "-DANDROID_STL=c++_static"
    }
    externalNativeBuild {
        cmake {
            path = file("../../app/android/app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").apply {
        jniLibs.directories.add(file("build/verified-jni").absolutePath)
        jniLibs.directories.add(file("build/snapshot-components/jni").absolutePath)
        assets.directories.add(file("build/verified-assets").absolutePath)
        assets.directories.add(file("build/snapshot-components/assets").absolutePath)
    }
    packaging.jniLibs.useLegacyPackaging = false
    // Preserve prepared Node bytes. The production file kernel is built by
    // AGP from the App's exact CMake source with an explicit pinned NDK.
    packaging.jniLibs.keepDebugSymbols.add("**/*.so")
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(productionSources) { it.outputDir }
    variant.sources.res?.addGeneratedSourceDirectory(productionResources) { it.outputDir }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(snapshotComponents) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
