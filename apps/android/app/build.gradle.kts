import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "org.echoscript.app"
    compileSdk = 36
    defaultConfig { applicationId = "org.echoscript.app"; minSdk = 29; targetSdk = 36; versionCode = 3; versionName = "0.3.0-dev"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true }
    sourceSets.getByName("androidTest").assets.srcDirs("../../../specs/fixtures", "build/codec-fixtures")
    sourceSets.getByName("main").assets.srcDir("../../../specs/fixtures")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
        release { isMinifyEnabled = false }
    }
}
dependencies {
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
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

abstract class PrepareOfflineModels : DefaultTask() {
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val license: RegularFileProperty
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
    val cache = providers.environmentVariable("ECHOSCRIPT_MODEL_CACHE")
    archiveCache.set(layout.dir(cache.map { file(it) }).orElse(rootProject.layout.projectDirectory.dir(".gradle/echoscript-models")))
    assetsDirectory.set(layout.buildDirectory.dir("generated/offline-model-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareOfflineModels, PrepareOfflineModels::assetsDirectory)
}
