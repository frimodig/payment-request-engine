// Versions not verified (written offline). Update to current releases before building.
plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.flywaydb:flyway-database-postgresql:10.20.1")

    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:postgresql:1.20.4")
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
