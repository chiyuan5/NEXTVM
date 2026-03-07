plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nextvm.core.virtualization"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:hook"))
    implementation(project(":core:binder"))
    implementation(project(":core:apk"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:framework"))
    implementation(project(":core:services"))

    implementation(libs.core.ktx)
    implementation(libs.timber)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
    implementation(libs.datastore.preferences)
    implementation(libs.hiddenapibypass)

    // Compose runtime (required by kotlin.compose plugin)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
}
