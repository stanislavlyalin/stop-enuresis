import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
}

fun gitCommitHash(): String {
    val output = ByteArrayOutputStream()
    return runCatching {
        exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = output
        }
        output.toString().trim()
    }.getOrDefault("unknown")
}

val currentGitCommit = gitCommitHash()

android {
    namespace = "com.stanislavlyalin.stopenuresis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stanislavlyalin.stopenuresis"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"$currentGitCommit\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

android.applicationVariants.all {
    outputs.all {
        (this as BaseVariantOutputImpl).outputFileName =
            "stop-enuresis-$currentGitCommit.apk"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.mpandroidchart)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
