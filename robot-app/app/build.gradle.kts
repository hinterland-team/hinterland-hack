import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// backend url/token come from local.properties so they never land in git
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun local(key: String, default: String = "") = localProps.getProperty(key, default).trim()

android {
    namespace = "com.hinterland.clinic"
    compileSdk = 35

    defaultConfig {
        // kept from the first version so the install on the robot stays the same app
        applicationId = "com.hinterland.relay"
        minSdk = 26
        targetSdk = 34
        // ci passes -PversionCode=1000+N -PversionName=bN-<sha7> (docs/deploy.md)
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 3
        versionName = providers.gradleProperty("versionName").orNull ?: "0.3"

        // base url of team-inbox without a path, e.g. http://<laptop-ip>:3000
        buildConfigField("String", "BACKEND_URL", "\"${local("teamInbox.url")}\"")
        // several servers, comma separated, first = preferred (online, then the laptop). otto uses the
        // first one whose /api/health answers. empty = only teamInbox.url (docs/journeys.md 6.1)
        buildConfigField("String", "BACKEND_URLS", "\"${local("teamInbox.urls")}\"")
        // where phones open the walk companion (<base>/walk/<code>); empty = the server otto uses
        buildConfigField("String", "WALK_BASE_URL", "\"${local("walk.baseUrl")}\"")
        // radar point frame: auto (otto finds out), map or robot
        buildConfigField("String", "SCAN_FRAME", "\"${local("robot.scanFrame", "auto")}\"")
        // true = debug log lines go to the server too (they always show in the staff menu)
        buildConfigField("boolean", "LOG_DEBUG", local("robot.logDebug", "false").toBoolean().toString())
        buildConfigField("String", "BACKEND_TOKEN", "\"${local("teamInbox.token")}\"")
        buildConfigField("String", "ROBOT_NAME", "\"${local("robot.name", "nova")}\"")
        // map spot otto drives back to after a walk, used when /api/places has no reception place
        buildConfigField("String", "HOME_SPOT", "\"${local("robot.homeSpot")}\"")
        // languages otto calls a ticket in (BCP-47 tags, comma separated), until the patient speaks
        buildConfigField("String", "CALL_LANGUAGES", "\"${local("robot.callLanguages", "de,en")}\"")
        // otto turns towards the person he talks to (RobotApi focus following); false turns it off
        buildConfigField("boolean", "FOCUS_FOLLOW", local("robot.focusFollow", "true").toBoolean().toString())
        // the side the patient walks on, next to otto: left or right (docs/journeys.md 6.7)
        buildConfigField("String", "WALK_SIDE", "\"${local("robot.walkSide", "left")}\"")
        // nova deploy on the robot, gets the busy hold while otto walks (docs/deploy.md)
        buildConfigField("String", "NOVA_DEPLOY_PACKAGE", "\"${local("novaDeploy.package", "com.novadeploy.agent")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
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
    // SDK version has to match the robot ROM:
    // V12.6 -> 0.4.12-SNAPSHOT, V12.3 -> 0.4.11-SNAPSHOT, V11.7 -> 0.4.5
    // our Nova is on 12.1 (16.10.2026), so 0.4.5 - newest one that doesn't need 12.3+
    // it also brings RobotApi (navigation) since 0.4.4
    implementation("com.orionstar.agent:sdk:0.4.5-SNAPSHOT")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // QR code for the phone companion, drawn on the robot (pure java, no network)
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // android's org.json is only a stub in jvm tests
    testImplementation("org.json:json:20240303")
}
