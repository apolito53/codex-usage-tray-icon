plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val nativeAccountDirectory = providers.gradleProperty("accountNativeDir")
    .map { rootProject.file(it) }
    .orElse(rootProject.file("native/out"))

// A signed-in 0.2.0 installation can still receive an update under its original ID.
val updatePreviewPackage = providers.gradleProperty("updatePreviewPackage")
    .map { it.toBooleanStrict() }.orElse(false)
val playDistribution = providers.gradleProperty("playDistribution")
    .map { it.toBooleanStrict() }.orElse(false)
require(!playDistribution.get() || !updatePreviewPackage.get()) { "Choose either the Play edition or the preview update." }

android {
    namespace = "com.apolito.codexusage.preview"
    compileSdk = 36

    defaultConfig {
        applicationId = when {
            playDistribution.get() -> "com.apolito.allowancemeter"
            updatePreviewPackage.get() -> "com.apolito.codexusage.preview"
            else -> "com.apolito.codexusage.meter"
        }
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 5
        versionName = "0.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].jniLibs.srcDir(nativeAccountDirectory)
    sourceSets["main"].assets.srcDir(rootProject.file("native/notices"))
    sourceSets["main"].assets.srcDir(nativeAccountDirectory.map { it.resolve("notices") })
    // Play delivers aligned, uncompressed libraries in device-specific ABI splits.
    // Keep existing sideload APKs compressed, as in 0.2.1.
    packaging.jniLibs.useLegacyPackaging = !playDistribution.get()

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric's documented Java 17+ module access requirements.
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

val verifyAccountLibraries by tasks.registering {
    doLast {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            val lib = nativeAccountDirectory.get().resolve("$abi/libcodex_android_native_probe.so")
            check(lib.isFile && lib.length() > 0) { "Missing account library for $abi. See android/README.md." }
        }
    }
}
tasks.matching { it.name == "mergeDebugNativeLibs" || it.name == "mergeReleaseNativeLibs" }.configureEach {
    dependsOn(verifyAccountLibraries)
}
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach {
    doFirst {
        check(listOf("native-notices.zip", "notice-report.json").all {
            nativeAccountDirectory.get().resolve("notices/$it").isFile
        }) {
            "Release APKs and bundles require the matching account-notices archive and report. See android/README.md."
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
