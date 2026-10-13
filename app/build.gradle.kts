plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.ultraslim"
    compileSdk { version = release(37) }
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.ultraslim"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 18
        versionName = "1.1.6"

        // App 自身只有中文文案；剥掉 androidx/Compose 带入的其他语言资源。
        resourceConfigurations += setOf("zh-rCN", "zh-rTW", "zh-rHK", "en")

        // Filter transitive JNI libraries too, not only our CMake target.
        ndk { abiFilters += listOf("arm64-v8a") }

        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = providers.gradleProperty("ultraslimDebugSuffix").orElse(".debug").get()
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            providers.environmentVariable("ULTRASLIM_BUILD_ROOT").orNull?.let { buildStagingDirectory = file("$it/cxx") }
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }



    sourceSets.getByName("androidTest").assets.srcDir("../tests/fixtures/libultrahdr")

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    buildFeatures {
        compose = true
    }

    packaging {
        // Project-built JNI replacement takes precedence; verification checks the packaged bytes.
        jniLibs.pickFirsts += "lib/arm64-v8a/libandroidx.graphics.path.so"
        // 同步盘冲突副本（"xxx 2.so"）不得进 APK——曾把 3 份 20MB 的 libultraslim.so 打进包。
        jniLibs.excludes += setOf("**/* [0-9].so", "**/* [0-9].*[0-9].so")
        resources {
            // Keep AndroidX version metadata for Compose Layout Inspector / Android CLI.
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/INDEX.LIST",
                "META-INF/proguard/**", "META-INF/com.android.tools/**",
                "META-INF/gradle/incremental.annotation.processors",
            )
        }
    }
}

dependencies {
    constraints {
        implementation("androidx.graphics:graphics-path") {
            version { strictly("1.0.1") }
            because("The aligned JNI replacement is built from the matching 1.0.1 source")
        }
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.window.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.activity)
implementation(libs.compose.navigation)
implementation(libs.compose.adaptive)
implementation(libs.compose.adaptive.navigation)
implementation(libs.compose.navigation.suite)
implementation(libs.androidx.lifecycle.navigation)
implementation(libs.kotlinx.serialization.core)
screenshotTestImplementation(libs.screenshot.validation.api)
screenshotTestImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
