import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val ownerSigningNames = listOf("ANDROID_SIGNING_STORE_FILE", "ANDROID_SIGNING_STORE_PASSWORD", "ANDROID_SIGNING_KEY_ALIAS", "ANDROID_SIGNING_KEY_PASSWORD")
val ownerSigning = ownerSigningNames.associateWith { providers.environmentVariable(it).orNull }
check(ownerSigning.values.all { it == null } || ownerSigning.values.all { !it.isNullOrBlank() }) {
    "Provide all owner signing variables, or none for an unsigned development release build"
}
android {
    namespace = "org.echoscript.app"
    compileSdk = 36
    defaultConfig { applicationId = "org.echoscript.app"; minSdk = 29; targetSdk = 36; versionCode = 9; versionName = "0.6.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; manifestPlaceholders["appLabel"] = "EchoScript" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("androidTest").assets.srcDir("../../../specs/fixtures")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    if (ownerSigning.values.all { !it.isNullOrBlank() }) {
        signingConfigs.create("ownerRelease") {
            storeFile = file(checkNotNull(ownerSigning["ANDROID_SIGNING_STORE_FILE"]))
            storePassword = ownerSigning["ANDROID_SIGNING_STORE_PASSWORD"]
            keyAlias = ownerSigning["ANDROID_SIGNING_KEY_ALIAS"]
            keyPassword = ownerSigning["ANDROID_SIGNING_KEY_PASSWORD"]
        }
    }
    buildTypes {
        debug {
            // A new candidate has a separate vault from prior test packages.
            // Ephemeral development keys cannot establish upgrade continuity.
            applicationIdSuffix = ".candidate06"
            versionNameSuffix = "-candidate.1"
            manifestPlaceholders["appLabel"] = "EchoScript 0.6 Candidate"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("ownerRelease")
        }
    }
}
dependencies {
    implementation(project(":case-security"))
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("androidx.media3:media3-exoplayer:1.8.1")
    implementation("androidx.media3:media3-ui:1.8.1")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

abstract class PrepareOfflineModels : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val license: RegularFileProperty
    @get:InputFile abstract val syntheticSample: RegularFileProperty
    @get:InputFile abstract val clearVoiceBinary: RegularFileProperty
    @get:InputFile abstract val clearVoiceInventory: RegularFileProperty
    @get:Internal abstract val archiveCache: DirectoryProperty
    @get:OutputDirectory abstract val assetsDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun generate() {
        execOperations.exec {
            commandLine("python3", script.get().asFile.absolutePath, assetsDirectory.get().asFile.absolutePath, archiveCache.get().asFile.absolutePath)
        }
    }
}

val prepareOfflineModels = tasks.register<PrepareOfflineModels>("prepareOfflineModels") {
    script.set(rootProject.layout.projectDirectory.file("../../scripts/bundle-android-models.py"))
    license.set(rootProject.layout.projectDirectory.file("../../LICENSE"))
    syntheticSample.set(rootProject.layout.projectDirectory.file("../../specs/fixtures/synthetic-speech.wav"))
    clearVoiceBinary.set(rootProject.layout.projectDirectory.file("../../assets/clearvoice/frcrn-16k-2s.onnx"))
    clearVoiceInventory.set(rootProject.layout.projectDirectory.file("../../assets/clearvoice/inventory.json"))
    val cache = providers.environmentVariable("ECHOSCRIPT_MODEL_CACHE")
    archiveCache.set(layout.dir(cache.map { file(it) }).orElse(rootProject.layout.projectDirectory.dir(".gradle/echoscript-models")))
    assetsDirectory.set(layout.buildDirectory.dir("generated/offline-model-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareOfflineModels, PrepareOfflineModels::assetsDirectory)
}
