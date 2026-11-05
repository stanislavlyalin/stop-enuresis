import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.io.ByteArrayOutputStream
import java.util.Properties

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
val signingPropertiesFile = rootProject.file("signing.properties")
val releaseSigningProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}
val requiredSigningFields = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

android {
    namespace = "com.stanislavlyalin.stopenuresis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stanislavlyalin.stopenuresis"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_COMMIT", "\"$currentGitCommit\"")
    }

    signingConfigs {
        create("release") {
            storeFile = releaseSigningProperties.getProperty("storeFile")
                ?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation(libs.rustore.review)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.mpandroidchart)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Ошибка относится только к релизным задачам: отладочная сборка не требует ключа.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(signingPropertiesFile.isFile && requiredSigningFields.all {
            !releaseSigningProperties.getProperty(it).isNullOrBlank()
        }) { "Для релизной сборки заполните storeFile, storePassword, keyAlias и keyPassword в signing.properties" }
    }
}
