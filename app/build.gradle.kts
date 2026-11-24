import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.deskstride"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.deskstride"
        minSdk = 31          // BLUETOOTH_SCAN/CONNECT permissions
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        // PitPat account id sent in control commands, kept out of git (local.properties: pitpat.userId=<decimal>)
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val userId = localProps.getProperty("pitpat.userId", "0").trim()
        require(userId.toLongOrNull()?.let { it >= 0 } == true) { "pitpat.userId in local.properties must be a non-negative decimal number" }
        buildConfigField("long", "PITPAT_USER_ID", "${userId}L")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // launcher icon shared by phone and watch
    sourceSets["main"].res.srcDir("../icon/res")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    testImplementation("junit:junit:4.13.2")
}
