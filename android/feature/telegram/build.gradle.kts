import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Telegram 收件匣（規格第 5 節、階段 0）：純 Kotlin，可在 JVM 單元測試；Android 的部分在 app 的 telegram 套件。
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.test { useJUnitPlatform() }
