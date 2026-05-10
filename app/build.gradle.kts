import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Room 어노테이션 처리 — annotation processor 가 Dao / Database / Entity 메타 코드 생성.
    alias(libs.plugins.ksp)
}

// ── Release signing ─────────────────────────────────────────────────────────
// keystore.properties (project root, gitignored) 가 존재하면 자동 로드해 서명.
// 없으면 release 빌드는 unsigned 로 떨어짐 → Play Console 업로드 불가.
//
// keystore.properties 예시:
//   storeFile=/Users/johndoe/hrmonitor.jks
//   storePassword=********
//   keyAlias=********
//   keyPassword=********
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
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
        versionCode = 6
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // keystore.properties 있을 때만 release signing 활성.
            // 없으면 Gradle 이 default debug key 로 fallback (Play 업로드 X).
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // BuildConfig 클래스 생성 활성 — SessionLogger 가 versionName/Code 자동 채우는 데 사용.
    // AGP 9 부터는 default false 라 명시적 활성 필요.
    buildFeatures {
        buildConfig = true
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
    // RecyclerView — 기록 화면 세션 카드 목록
    implementation(libs.androidx.recyclerview)
    // Room — 세션 / HR 샘플 / 이벤트 영구 저장 (기록 기능)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
}