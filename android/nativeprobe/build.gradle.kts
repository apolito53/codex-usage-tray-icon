plugins {
    id("com.android.application")
}

val nativeLibraryDirectory = providers.gradleProperty("nativeProbeLibDir")
val supportedAbis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "com.apolito.codexusage.nativeprobe"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.apolito.codexusage.nativeprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-lab"
        ndk.abiFilters.addAll(supportedAbis)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].jniLibs.srcDir(nativeLibraryDirectory.orElse("libs"))
}

tasks.named("preBuild") {
    doFirst {
        val directory = nativeLibraryDirectory.orNull?.let { file(it) }
        check(directory != null && supportedAbis.any { abi ->
            directory.resolve("$abi/libcodex_android_native_probe.so").isFile
        }) {
            "Build the native probe first, then pass -PnativeProbeLibDir=<directory containing arm64-v8a and/or x86_64 folders>."
        }
    }
}
