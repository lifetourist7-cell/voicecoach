plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.jecheon.voicecoach"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.jecheon.voicecoach"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Wear OS Data Layer — 갤럭시워치 / Pixel Watch 컴패니언 앱과 메시지 통신
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    // Android 12+ SplashScreen API (구버전에서도 호환 동작)
    implementation("androidx.core:core-splashscreen:1.0.1")
    // ViewPager2 — 온보딩 carousel
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}