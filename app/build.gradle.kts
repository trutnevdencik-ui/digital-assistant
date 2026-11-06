plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.digitalassistant.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitalassistant.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")

    // HTTPS-запросы к OpenAI
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Проверка подписи OAuth-токенов
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
}
