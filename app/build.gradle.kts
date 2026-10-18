plugins {
    id("com.android.application")
}

android {
    namespace = "com.ecc.camera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ecc.camera"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

tasks.register<Copy>("copyVersionedDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/versioned-apk"))
    rename { "ECC_V${android.defaultConfig.versionName}.apk" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
