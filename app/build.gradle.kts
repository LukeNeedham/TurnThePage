plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.avstaim.listai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.avstaim.listai"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("listai.versionCode").get().toInt()
        versionName = providers.gradleProperty("listai.versionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is opt-in. Credentials may be supplied as Gradle properties
    // (for example from ~/.gradle/gradle.properties or CI secrets) or environment
    // variables; never put a keystore or passwords in the repository.
    val releaseStoreFile = providers.gradleProperty("listai.release.storeFile")
        .orElse(providers.environmentVariable("LISTAI_RELEASE_STORE_FILE")).orNull
    val releaseStorePassword = providers.gradleProperty("listai.release.storePassword")
        .orElse(providers.environmentVariable("LISTAI_RELEASE_STORE_PASSWORD")).orNull
    val releaseKeyAlias = providers.gradleProperty("listai.release.keyAlias")
        .orElse(providers.environmentVariable("LISTAI_RELEASE_KEY_ALIAS")).orNull
    val releaseKeyPassword = providers.gradleProperty("listai.release.keyPassword")
        .orElse(providers.environmentVariable("LISTAI_RELEASE_KEY_PASSWORD")).orNull

    val releaseSigningValues = listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    )
    check(releaseSigningValues.all { it == null } || releaseSigningValues.all { !it.isNullOrBlank() }) {
        "Release signing requires LISTAI_RELEASE_STORE_FILE, LISTAI_RELEASE_STORE_PASSWORD, " +
            "LISTAI_RELEASE_KEY_ALIAS, and LISTAI_RELEASE_KEY_PASSWORD (or their Gradle properties)."
    }

    signingConfigs {
        if (releaseSigningValues.all { !it.isNullOrBlank() }) {
            create("releaseExternal") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningValues.all { !it.isNullOrBlank() }) {
                signingConfig = signingConfigs.getByName("releaseExternal")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.getByName("test").resources.srcDir("src/main/res/raw")

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        enable += setOf("ExtraTranslation", "MissingTranslation")
        htmlReport = true
        sarifReport = true
        xmlReport = true
    }
}

dependencies {
    implementation(project(":core:adaptive"))
    implementation(project(":core:database"))
    implementation(project(":core:files"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":format:fb2"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
