// SPDX-License-Identifier: AGPL-3.0-only
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
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
    includes.set(listOf("model/**", "isolation/**", "bot/BotProviderStore.kt", "bot/BotSettingsStore.kt", "bot/NativeProviderBroker.kt"))
    outputDir.set(layout.buildDirectory.dir("shared-native"))
}
val deviceTests by tasks.registering(SharedNativeSources::class) {
    inputDir.set(file("../../app/android/app/src/androidTest/kotlin/io/github/ccch1mneyyy/dsh_app"))
    includes.set(listOf("isolation/AgentIsolationClientTest.kt", "bot/ProviderStorageTest.kt"))
    outputDir.set(layout.buildDirectory.dir("shared-device-tests"))
}
val unitTests by tasks.registering(SharedNativeSources::class) {
    inputDir.set(file("../../app/android/app/src/test/kotlin/io/github/ccch1mneyyy/dsh_app"))
    includes.set(listOf("model/**", "isolation/**"))
    outputDir.set(layout.buildDirectory.dir("shared-unit-tests"))
}
android {
    namespace = "io.github.ccch1mneyyy.dsh_app"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ccch1mneyyy.dsh_app.brokerprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "verification-only"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(nativeSources) { it.outputDir }
    variant.deviceTests.values.forEach { test ->
        test.sources.kotlin?.addGeneratedSourceDirectory(deviceTests) { it.outputDir }
    }
    variant.hostTests.values.forEach { test ->
        test.sources.kotlin?.addGeneratedSourceDirectory(unitTests) { it.outputDir }
    }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
