plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.deskstride.wear"
    compileSdk = 34
    defaultConfig {
        // same applicationId and signing key as the phone app: that's how the Data Layer pairs them
        applicationId = "io.github.deskstride"
        minSdk = 30          // Wear OS 3+
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // launcher icon shared by phone and watch
    sourceSets["main"].res.srcDir("../icon/res")
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    // "DeskStride" Tile
    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    testImplementation("junit:junit:4.13.2")
}
