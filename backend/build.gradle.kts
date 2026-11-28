plugins {
    kotlin("jvm") version "2.2.10"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("com.example.stocktrace.backend.ServerKt")
}
