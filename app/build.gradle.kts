plugins {
    id("com.android.application") version "9.1.1"
}

android {
    namespace = "com.harshxdev.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.harshxdev.jarvis"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets.getByName("main") {
        assets.directories.add(rootProject.file(".gradle/jarvis-sherpa/assets").absolutePath)
        jniLibs.directories.add(rootProject.file(".gradle/jarvis-sherpa/jniLibs").absolutePath)
        kotlin.directories.add(rootProject.file(".gradle/jarvis-sherpa/kotlin").absolutePath)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
