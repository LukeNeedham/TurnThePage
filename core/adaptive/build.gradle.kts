plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.avstaim.listai.core.adaptive"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.window)

    testImplementation(libs.junit)
}
