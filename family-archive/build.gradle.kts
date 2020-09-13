import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.*

plugins {
    java
    kotlin("jvm")
    id("com.github.johnrengelman.shadow") version "6.0.0"
    application
}

application {
    mainClassName = "net.swarmshine.familty.archive.FamilyArchiveKt"
}

tasks.withType<ShadowJar>{
    archiveVersion.set("")
    archiveClassifier.set("")
}

dependencies {
    // Kotlin
    implementation(Libs.kotlin_jdk8)
    implementation(Libs.kotlin_stdlib)
    implementation(Libs.kotlin_reflect)

    implementation(Libs.selenium_java)
    implementation(Libs.selenium_chrome_driver)
    implementation(Libs.webdrivermanager)

    // Logging
    implementation(Libs.log4j_kotlin)
    implementation(Libs.log4j_core)
    implementation(Libs.slf4j_over_log4j)

    implementation(Libs.http_client)



    // Testing
    //  Junit
    testImplementation(Libs.junit_api)
    testRuntimeOnly(Libs.junit_engine)
    //  Kotest
    testImplementation(Libs.kotest_assertions_core_jvm)
    //  Mocking
    testImplementation(Libs.mockk)

}

