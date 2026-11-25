import java.io.FileInputStream
import java.util.Properties

import com.android.build.api.variant.FilterConfiguration
import org.gradle.api.tasks.testing.Test

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

val versionProps = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val appVersionName = providers.gradleProperty("account.versionName").getOrElse(versionProps.getProperty("versionName", "0.0.0"))
val appVersionCode = providers.gradleProperty("account.versionCode").getOrElse(versionProps.getProperty("versionCode", "0")).toInt()
val appBuildId = providers.gradleProperty("account.buildId").getOrElse(versionProps.getProperty("buildId", "0")).toInt()

// ---------------- Rust KDF ----------------
val rustTargets = mapOf(
    "arm64-v8a" to "aarch64-linux-android"
)
val hostTestTarget = "x86_64-unknown-linux-gnu"
val hostLibDir = layout.buildDirectory.dir("rust-kdf/host-test").get().asFile
val rustTargetDir = layout.buildDirectory.dir("rust-kdf/target").get().asFile
val rustDir = rootProject.file("rust")
val cargoBin = providers.environmentVariable("CARGO").orElse(providers.gradleProperty("account.cargo").orElse("cargo")).get()
val ndkToolchainBin: String? = run {
    val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (home == null) null else {
        val ndkBase = File(home, "ndk")
        val ndkDir = ndkBase.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ndkDir?.resolve("toolchains/llvm/prebuilt/linux-x86_64/bin")?.takeIf { it.isDirectory }?.absolutePath
    }
}

rustTargets.forEach { (abi, triple) ->
    val targetDir = rustTargetDir
    val libDir = File(layout.buildDirectory.dir("rust-kdf/jniLibs").get().asFile, abi)
    val builtSo = File(targetDir, "$triple/release/libaccount_kdf.so")
    val outSo = File(libDir, "libaccount_kdf.so")
    val manifest = rustDir.resolve("kdf/Cargo.toml")
    tasks.register<Exec>("buildRustKdf$abi") {
        group = "build"
        description = "cargo build rust/kdf ($abi)"
        inputs.dir(rustDir.resolve("kdf/src"))
        inputs.file(manifest)
        inputs.file(rustDir.resolve(".cargo/config.toml"))
        outputs.file(outSo)
        workingDir(rustDir)
        commandLine(
            cargoBin, "build", "--release",
            "--target", triple,
            "--manifest-path", manifest.absolutePath,
            "--target-dir", targetDir.absolutePath
        )
        val toolchain = ndkToolchainBin
        doFirst {
            require(toolchain != null) { "NDK toolchain not found under ANDROID_HOME/ndk/* (rust/kdf, $abi)" }
            environment("PATH", "$toolchain:${System.getenv("PATH")}")
            libDir.mkdirs()
        }
        doLast {
            builtSo.copyTo(outSo, overwrite = true)
        }
    }
}
android.sourceSets["main"].jniLibs.srcDir(
    File(layout.buildDirectory.get().asFile, "rust-kdf/jniLibs")
)
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(rustTargets.keys.map { "buildRustKdf$it" })
}

val buildRustKdfHost = run {
    val cargo = cargoBin
    val workDir = rustDir
    val manifest = rustDir.resolve("kdf/Cargo.toml")
    val hostTriple = hostTestTarget
    val hostLib = hostLibDir
    val hostTarget = rustTargetDir
    tasks.register<Exec>("buildRustKdfHost") {
        group = "build"
        description = "cargo build rust/kdf (host, unit tests only)"
        inputs.dir(workDir.resolve("kdf/src"))
        inputs.file(manifest)
        outputs.file(File(hostLib, "libaccount_kdf.so"))
        workingDir(workDir)
        commandLine(
            cargo, "build", "--release",
            "--target", hostTriple,
            "--manifest-path", manifest.absolutePath,
            "--target-dir", hostTarget.absolutePath
        )
        doLast {
            hostLib.mkdirs()
            File(hostTarget, "$hostTriple/release/libaccount_kdf.so")
                .copyTo(File(hostLib, "libaccount_kdf.so"), overwrite = true)
        }
    }
}
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest" || name == "testReleaseUnitTest") {
        dependsOn(buildRustKdfHost)
        jvmArgs("-Djava.library.path=$hostLibDir")
        setForkEvery(1)
        maxParallelForks = 2
    }
}

android {
    namespace = "com.copy.account"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.copy.account"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += setOf("zh")

        buildConfigField(
            "String",
            "DEFAULT_THEME_MODE",
            "\"${providers.gradleProperty("account.defaultThemeMode").getOrElse("light")}\""
        )
        buildConfigField("int", "BUILD_ID", "$appBuildId")
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
        debug {
            buildConfigField("long", "AUTO_LOCK_TEST_SECONDS", "5L")
        }
        release {
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("long", "AUTO_LOCK_TEST_SECONDS", "0L")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            val requested = (providers.gradleProperty("account.abis").getOrElse("arm64-v8a"))
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            include(*requested.toTypedArray())
            isUniversalApk = false
        }
    }
    packaging {
        jniLibs {
            excludes += "**/libdatastore_shared_counter.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("debug").kotlin.srcDirs(file("../shared/src/main/kotlin"))
    sourceSets["main"].kotlin.srcDirs(file("../shared/src/main/kotlin"))
    sourceSets["main"].java.srcDirs(file("../shared/src/main/kotlin"))
    sourceSets["main"].res.srcDirs(file("../shared/src/main/res"))
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("robolectric.dependency.repo.url", "https://mirrors.cloud.tencent.com/nexus/repository/maven-central") }
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier
            val abiPart = abi?.let { "-$it" } ?: ""
            val buildPart = if (variant.name == "release") "" else "-${variant.name}"
            output.outputFileName.set("account-$appVersionName$abiPart$buildPart.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
