// SPDX-License-Identifier: AGPL-3.0-only
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import javax.inject.Inject
import org.gradle.work.DisableCachingByDefault
import groovy.json.JsonSlurper

plugins { id("com.android.application") version "9.1.0" }

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
    includes.set(listOf("model/**", "bot/BotProviderStore.kt", "bot/BotSettingsStore.kt", "bot/NativeModelChannel.kt", "bot/NativeModelRequestHost.kt", "isolation/ModelRequestClient.kt", "isolation/RuntimeDatabaseFiles.kt", "isolation/RuntimePrivateDirectory.kt", "isolation/RuntimeAtomicPublisher.kt"))
    outputDir.set(layout.buildDirectory.dir("shared-model"))
}
val productionResources by tasks.registering(SharedModelSources::class) {
    inputDir.set(file("../../app/android/app/src/main/res"))
    includes.set(listOf("xml/network_security_config.xml"))
    outputDir.set(layout.buildDirectory.dir("shared-resources"))
}
val verifyPreparedInputs by tasks.registering {
    doLast {
        for (name in listOf("libnode.so", "libc++_shared.so", "libmbot_node_probe.so", "libmbot_file_publication_probe.so")) {
            check(file("build/verified-jni/x86_64/$name").isFile) { "Run prepare-probe.py with the pinned Node archive and NDK first" }
        }
        check(file("build/verified-assets/node-fd-probe.cjs").isFile)
        check(file("build/verified-assets/node-validation.json").isFile)
        val verification = JsonSlurper().parse(file("build/verified-assets/node-validation.json")) as Map<*, *>
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
    }
}
android {
    namespace = "io.github.ccch1mneyyy.dsh_app"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ccch1mneyyy.dsh_app.nodeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "verification-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk.abiFilters.add("x86_64")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").apply {
        jniLibs.srcDir("build/verified-jni")
        assets.srcDir("build/verified-assets")
    }
    packaging.jniLibs.useLegacyPackaging = true
    // The release libnode is already stripped. This target compiles its JNI
    // outside AGP; do not let AGP fetch an unrelated default NDK to re-strip.
    packaging.jniLibs.keepDebugSymbols.add("**/*.so")
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(productionSources) { it.outputDir }
    variant.sources.res?.addGeneratedSourceDirectory(productionResources) { it.outputDir }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyPreparedInputs) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
