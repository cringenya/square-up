import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "squareup"
version = "0.1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":application"))
    implementation(project(":api"))
    implementation(project(":persistence"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.status.pages)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("squareup.MainKt")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("squareup.runtime.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
