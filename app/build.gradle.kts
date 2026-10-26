plugins {
    id("com.android.application")
}

android {
    namespace = "com.naiwa.androidpet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.naiwa.androidpet"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0-personal"
        testInstrumentationRunner = "com.naiwa.androidpet.LifecycleProbe"
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

dependencies {
    testImplementation("junit:junit:4.13.2")
}
