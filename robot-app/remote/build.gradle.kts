import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// optional settings from local.properties (ci injects NOVA_APP_PROPERTIES the same way)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun local(key: String, default: String = "") = localProps.getProperty(key, default).trim()

android {
    namespace = "com.novaremote.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novaremote.agent"
        minSdk = 26
        targetSdk = 34
        // ci passes -PversionCode=1000+N -PversionName=bN-<sha7> (docs/deploy.md / release.sh)
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1"

        // the lan server port the robot opens; the control page lives at http://<robot-ip>:<port>
        buildConfigField("int", "PORT", local("remote.port", "8080"))
        // only these laptop MACs may pair, comma separated (aa:bb:..). empty = any device on the
        // same wifi that also knows the 9-digit code. a MAC is easy to spoof, so the code stays
        // the real lock; this is just a second, convenient gate for the dev's own laptop.
        buildConfigField("String", "MAC_ALLOW", "\"${local("remote.macAllow")}\"")
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // same SDK as the clinic app - it matches the robot ROM (12.1 -> 0.4.5) and brings RobotApi
    // (chassis motion for the arrow-key driving). see app/build.gradle.kts for the version table.
    implementation("com.orionstar.agent:sdk:0.4.5-SNAPSHOT")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // tiny embedded http + websocket server, runs on the robot over the local wifi only
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
