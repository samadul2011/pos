plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.compose") version "1.6.11"
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    // Ktor server for local sync API
    implementation("io.ktor:ktor-server-core:2.3.5")
    implementation("io.ktor:ktor-server-netty:2.3.5")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.5")
    implementation("io.ktor:ktor-serialization-jackson:2.3.5")
    // PDF generation
    implementation("org.apache.pdfbox:pdfbox:3.0.0")
}

compose.desktop {
    application {
        mainClass = "pos.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "PosApp"
            packageVersion = "1.0.0"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
