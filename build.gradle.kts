// Versions verified against Maven Central on 2026-11-11 (latest stable at that date).
plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.flywaydb:flyway-database-postgresql:13.12.1")

    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("engine.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
