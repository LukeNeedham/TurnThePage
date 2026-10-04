plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.avstaim.listai.benchmark"
    compileSdk = 36
    targetProjectPath = ":app"
    // Macrobenchmark must survive killing/recompiling the app under measurement.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Reuse the tiny, licensed format fixtures in the external release smoke test.
    sourceSets.named("main") {
        assets.srcDir("../app/src/androidTest/assets")
    }

    // Measure the non-debuggable application, while allowing test instrumentation.
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.core.ktx)
}
