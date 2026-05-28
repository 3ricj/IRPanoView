plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val hikNativeUsb = (project.findProperty("hikNativeUsb") as String?)?.toBoolean() ?: false
val hikUvcEofFraming = (project.findProperty("hikUvcEofFraming") as String?)?.toBoolean() ?: true
val shutdownBuildStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
val buildArchiveDescriptionRaw = ((project.findProperty("buildDescription") as String?) ?: "debug").trim()
val buildArchiveDescription = buildArchiveDescriptionRaw
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .trim('-')
    .ifBlank { "debug" }
val buildsArchiveRoot = rootProject.layout.projectDirectory.dir("builds").asFile

android {
    namespace = "com.vilos.irpanoview"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.vilos.irpanoview"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        val hikEnhanceProfile = (project.findProperty("hikEnhanceProfile") as String?) ?: "baseline"
        buildConfigField("String", "HIK_ENHANCE_PROFILE", "\"$hikEnhanceProfile\"")
        buildConfigField("boolean", "HIK_NATIVE_USB", "$hikNativeUsb")
        buildConfigField("boolean", "HIK_UVC_EOF_FRAMING", "$hikUvcEofFraming")
        buildConfigField("String", "SHUTDOWN_BUILD_STAMP", "\"$shutdownBuildStamp\"")
        if (hikNativeUsb) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf("-DANDROID_STL=c++_shared")
                }
            }
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    if (hikNativeUsb) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Direct UVC when OEM has no Camera2 external provider (e.g. Lenovo TB-Q706F).
    implementation("com.herohan:UVCAndroid:1.0.12")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

tasks.register("archiveDebugBuild") {
    group = "build"
    description = "Archive debug APK to repo builds folder."
    dependsOn("assembleDebug")
    doLast {
        val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!debugApk.exists()) {
            throw GradleException("Debug APK not found at ${debugApk.absolutePath}")
        }

        val archiveDir = buildsArchiveRoot.resolve("$shutdownBuildStamp-$buildArchiveDescription")
        archiveDir.mkdirs()
        val archivedApk = archiveDir.resolve(debugApk.name)
        debugApk.copyTo(archivedApk, overwrite = true)

        val metadataFile = archiveDir.resolve("build-info.txt")
        metadataFile.writeText(
            """
            timestamp=$shutdownBuildStamp
            description=$buildArchiveDescriptionRaw
            archive_description_slug=$buildArchiveDescription
            module=:app
            artifact=${archivedApk.name}
            artifact_size_bytes=${archivedApk.length()}
            """.trimIndent() + System.lineSeparator()
        )

        println("Archived debug build to ${archiveDir.absolutePath}")
    }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        finalizedBy("archiveDebugBuild")
    }
}
