import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

group = "com.example.voicebrainlive"
version = "1.0.0"



kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.coroutines.get()}")
    implementation(libs.okhttp)
    implementation("com.github.kwhat:jnativehook:2.2.2")
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "com.example.voicebrainlive.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "VoiceBrainLive"
            packageVersion = "1.0.0"
            description = "VoiceBrainLive Windows Desktop Assistant"
            vendor = "VoiceBrainLive"
        }
    }
}
