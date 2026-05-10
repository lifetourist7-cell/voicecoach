import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Phone 모듈과 동일한 keystore.properties 사용 — 같은 applicationId 라 키도 같아야 함.
// Play Console 은 같은 listing 내 두 form factor 의 서명 키가 일치해야 받아줌.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
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
        // Form factor 별 versionCode 분리 — Play Console 은 같은 applicationId 의 모든
        // form factor (mobile / wear / tv 등) versionCode 가 unique 해야 함.
        // 스킴: wear = 1_000_000 + phone.versionCode  → phone 99,999 까지 충돌 불가능.
        // 사용자 표기용 versionName 은 phone 과 동일.
        versionCode = 1_000_006
        versionName = "1.5"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
