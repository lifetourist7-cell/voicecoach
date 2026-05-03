plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jecheon.voicecoach.wear"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // 폰 앱과 동일한 applicationId — Wear OS 가 자동 페어링 인식
        applicationId = "com.jecheon.voicecoach"
        minSdk = 30          // Wear OS 3 (Galaxy Watch 4+, Pixel Watch 1+)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.wear:wear:1.3.0")
    // Wearable Data Layer — 폰과 메시지 통신
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    // 워치 자체 HR 측정
    implementation("androidx.health:health-services-client:1.1.0-alpha04")
    // health-services 의 ListenableFuture 반환을 다루기 위한 Guava
    implementation("com.google.guava:guava:31.1-android")
}
