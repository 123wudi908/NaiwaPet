plugins {
    id("com.android.application")
}

val releaseKeys = listOf("NAIWA_RELEASE_KEYSTORE", "NAIWA_RELEASE_STORE_PASSWORD", "NAIWA_RELEASE_KEY_ALIAS", "NAIWA_RELEASE_KEY_PASSWORD")
val releaseValues = releaseKeys.associateWith { providers.environmentVariable(it).orNull }
val releaseSigningConfigured = releaseValues.values.all { !it.isNullOrBlank() }
require(releaseSigningConfigured || releaseValues.values.all { it.isNullOrBlank() }) {
    "Release signing requires all four NAIWA_RELEASE_* environment variables."
}

android {
    namespace = "com.naiwa.androidpet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.naiwa.androidpet"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6.0-personal"
        testInstrumentationRunner = "com.naiwa.androidpet.LifecycleProbe"
    }
    signingConfigs {
        if (releaseSigningConfigured) create("production") {
            storeFile = file(checkNotNull(releaseValues["NAIWA_RELEASE_KEYSTORE"]))
            storePassword = checkNotNull(releaseValues["NAIWA_RELEASE_STORE_PASSWORD"])
            keyAlias = checkNotNull(releaseValues["NAIWA_RELEASE_KEY_ALIAS"])
            keyPassword = checkNotNull(releaseValues["NAIWA_RELEASE_KEY_PASSWORD"])
        }
    }
    buildTypes {
        release { if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("production") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

val requireReleaseSigning = tasks.register("requireReleaseSigning") {
    doLast { check(releaseSigningConfigured) { "Release signing is not configured; no release package was produced." } }
}
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" || it.name == "packageReleaseBundle" }.configureEach {
    dependsOn(requireReleaseSigning)
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0") {
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
    testImplementation("junit:junit:4.13.2")
}
