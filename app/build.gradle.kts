plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.rikka.savecopy"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "app.rikka.savecopy.revived"
        minSdk = 29
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 11
        versionName = "26.1.0611"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    //noinspection UseTomlInstead
    implementation("androidx.annotation:annotation:1.10.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    // SAF
    //noinspection UseTomlInstead
    implementation("androidx.documentfile:documentfile:1.1.0")
    // MTDataFilesProvider
    //noinspection UseTomlInstead
    implementation("com.github.L-JINBIN:MTDataFilesProvider:v1.0.0")
}