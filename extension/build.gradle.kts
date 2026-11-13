import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.copy.account.sync"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.copy.account.sync"
        minSdk = 28
        targetSdk = 37
        versionCode = 22
        versionName = "2.2.0"
        buildConfigField("int", "BUILD_ID", "22")
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].kotlin.srcDirs(
        file("../shared/src/main/kotlin/com/copy/account/platform"),
        file("../shared/src/main/kotlin/com/copy/account/ui"),
        file("../shared/src/main/kotlin/com/copy/account/data/config"),
        file("../shared/src/main/kotlin/com/copy/account/data/model"),
    )
    sourceSets["main"].java.srcDirs(
        file("../shared/src/main/kotlin/com/copy/account/platform"),
        file("../shared/src/main/kotlin/com/copy/account/ui"),
        file("../shared/src/main/kotlin/com/copy/account/data/config"),
        file("../shared/src/main/kotlin/com/copy/account/data/model"),
    )
    sourceSets["main"].res.srcDirs(file("../shared/src/main/res"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    testImplementation(libs.junit)
}
