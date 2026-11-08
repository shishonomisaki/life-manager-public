import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.shishonomisaki.lifemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = (findProperty("lifemanager.applicationId") as String?) ?: "com.shishonomisaki.lifemanager"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // 簽章金鑰：第一次執行 GitHub Actions「1. 初始化（產生簽章金鑰）」會產生 release.jks 和
    // keystore.properties 並提交到你自己的 private repo。金鑰固定，Firebase 的 SHA-1 只要登記一次。
    // 沒有這兩個檔時改用 Android 預設的 debug 金鑰（每台電腦不同，只適合本機試跑）。
    val ksProps = Properties().apply {
        val f = file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasKeystore = ksProps.getProperty("storeFile")?.let { file(it).exists() } == true
    if (hasKeystore) {
        signingConfigs {
            create("own") {
                storeFile = file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasKeystore) signingConfig = signingConfigs.getByName("own")
        }
        release {
            isMinifyEnabled = false
            if (hasKeystore) signingConfig = signingConfigs.getByName("own")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":feature:telegram"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Telegram 截圖在沒有 Gemini 時離線讀字（透過 Play 服務下載中文模型，APK 不變大）
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
