plugins {
    id("com.android.application") version "8.7.2"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.weartak.plugin.handler"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weartak.plugin.handler"
        minSdk = 30
        targetSdk = 34
        val baseVersionCode = 584
        val baseVersionName = "5.8.4"
        val pipeline = System.getenv("CI_PIPELINE_IID")?.let {
            it.toIntOrNull()?.takeIf { id -> id in 1..9999 }
                ?: throw GradleException("CI_PIPELINE_IID must be in 1..9999")
        }
        versionCode = if (pipeline == null) baseVersionCode else baseVersionCode * 10_000 + pipeline
        versionName = if (pipeline == null) baseVersionName
            else "$baseVersionName-p$pipeline+${System.getenv("CI_COMMIT_SHORT_SHA") ?: "local"}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

apply(from = rootProject.file("gradle/weartak-sideload-debug.gradle"))
apply(from = rootProject.file("gradle/weartak-sideload-production.gradle"))

dependencies {
    debugImplementation(files("libs/weartak-plugin-sdk-debug.aar"))
    releaseImplementation(files("libs/weartak-plugin-sdk-release.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
