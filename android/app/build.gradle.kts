plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val nativeAccountDirectory = providers.gradleProperty("accountNativeDir")
    .map { rootProject.file(it) }
    .orElse(rootProject.file("native/out"))

android {
    namespace = "com.apolito.codexusage.preview"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apolito.codexusage.preview"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 3
        versionName = "0.2.0"
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
    // This is a universal sideload APK. Android extracts only the device's ABI.
    packaging.jniLibs.useLegacyPackaging = true

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
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(nativeAccountDirectory.get().resolve("notices/native-notices.zip").isFile) {
            "The release requires the matching account-notices artifact. See android/README.md."
        }
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
