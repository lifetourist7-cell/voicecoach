// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // KSP — Room 어노테이션 처리. 실제 적용은 :app 모듈에서.
    alias(libs.plugins.ksp) apply false
}