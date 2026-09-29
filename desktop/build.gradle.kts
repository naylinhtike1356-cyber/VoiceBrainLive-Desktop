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
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    // Phone-as-mic: embedded HTTPS+WebSocket server for phone browser client.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.tls.certificates)
    // QR code generation for phone pairing.
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

compose.desktop {
    application {
        mainClass = "com.example.voicebrainlive.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "NilarAI"
            packageVersion = "1.0.0"
            description = "Nilar AI — Burmese Voice Assistant"
            vendor = "Nilar AI"
            windows {
                iconFile.set(project.file("src/main/resources/nilar_ai_logo.ico"))
            }
        }
    }
}
