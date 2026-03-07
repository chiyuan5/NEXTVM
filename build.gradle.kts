// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

// Workaround for KSP2 multi-round duplicate class issue with Hilt
subprojects {
    afterEvaluate {
        tasks.withType<JavaCompile>().configureEach {
            exclude("**/byRounds/**")
        }
    }
}
