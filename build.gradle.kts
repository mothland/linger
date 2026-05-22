// Root build file. Only declares plugins that sub-projects may use — it does not apply them here.
// "apply false" means: make the plugin available but don't apply it to the root project itself.
plugins {
    id("com.android.application")        version "8.7.3"  apply false
    id("org.jetbrains.kotlin.android")   version "2.1.0"  apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp")        version "2.1.0-1.0.29" apply false
}
