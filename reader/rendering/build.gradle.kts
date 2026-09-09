plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.avstaim.listai.reader.rendering"
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
    implementation(project(":core:model"))
    implementation(project(":reader:pagination"))
    testImplementation(libs.junit)
}
