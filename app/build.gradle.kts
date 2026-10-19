import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (!file.exists()) throw GradleException("keystore.properties is missing. Run scripts/make-signing-key.sh once.")
    file.inputStream().use { load(it) }
}

android {
    namespace = "dev.matthes.privatemeetingnotes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.matthes.privatemeetingnotes"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("personal") {
            storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("personal")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Unit tests run on the JVM, where android.util.Log and the like are stubs; let them return defaults.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            excludes += listOf("lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**")
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

abstract class CheckManifestPrivacy : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val checkedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val networkPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
        )
        // Libraries can start code at launch through manifest components; only these may.
        val componentPrefixes = listOf("dev.matthes.privatemeetingnotes.", "androidx.")
        val manifest = mergedManifest.get().asFile
        val text = manifest.readText()
        val permissions = Regex("""<uses-permission[^>]*?android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it in networkPermissions }
            .toList()
        val components = Regex("""<(activity|activity-alias|service|receiver|provider)\b[^>]*?android:name="([^"]+)"""")
            .findAll(text)
            .map { it.groupValues[2] }
            .filter { name -> componentPrefixes.none { name.startsWith(it) } }
            .toList()
        if (permissions.isNotEmpty() || components.isNotEmpty()) {
            throw GradleException(
                "The merged manifest has network permissions [${permissions.joinToString()}] or library components " +
                    "[${components.joinToString()}]. Remove them in AndroidManifest.xml with tools:node=\"remove\".",
            )
        }
        manifest.copyTo(checkedManifest.get().asFile, overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val check = tasks.register<CheckManifestPrivacy>(
            "check${variant.name.replaceFirstChar { it.uppercase() }}ManifestPrivacy",
        )
        variant.artifacts.use(check)
            .wiredWithFiles(CheckManifestPrivacy::mergedManifest, CheckManifestPrivacy::checkedManifest)
            .toTransform(SingleArtifact.MERGED_MANIFEST)
    }
}

tasks.withType<Test>().configureEach {
    // Desktop ONNX Runtime otherwise sends usage telemetry to Microsoft.
    environment("ORT_DISABLE_TELEMETRY", "1")
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

configurations.configureEach {
    // JVM unit tests run the desktop ONNX Runtime; the Android build's native libraries can't load on the Mac.
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

dependencies {
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))
    implementation(libs.onnxruntime.android)
    implementation(libs.litertlm.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
