import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// all robot apps are signed with one key, so the robot installs every new build on top of the
// old one (docs/deploy.md). ci builds debug apks, so the key goes on the debug build type.
// without signing.storeFile in local.properties a module keeps the normal debug key.
subprojects {
    pluginManager.withPlugin("com.android.application") {
        val props = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        fun local(key: String) = props.getProperty(key, "").trim()

        val store = local("signing.storeFile").takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }
        if (store != null && store.exists()) {
            extensions.configure<ApplicationExtension> {
                val otto = signingConfigs.create("nova") {
                    storeFile = store
                    storePassword = local("signing.storePassword")
                    keyAlias = local("signing.keyAlias")
                    keyPassword = local("signing.keyPassword")
                }
                buildTypes.matching { it.name == "debug" }.configureEach { signingConfig = otto }
            }
        }
    }
}
