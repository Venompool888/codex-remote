plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.codexremote.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.codexremote.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        testInstrumentationRunnerArguments["class"] = "app.codexremote.android.TaskNotificationDeviceTest"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    kotlin {
        jvmToolchain(21)
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "OldTargetApi")
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    androidTestCompileOnly(files(
        "${androidComponents.sdkComponents.sdkDirectory.get().asFile}/platforms/android-36/optional/android.test.base.jar",
        "${androidComponents.sdkComponents.sdkDirectory.get().asFile}/platforms/android-36/optional/android.test.runner.jar",
    ))
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
