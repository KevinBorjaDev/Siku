plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qhana.siku.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose runtime — para @Immutable y @Stable en modelos de dominio.
    // Solo runtime, no UI (los modelos no pintan nada).
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.runtime:runtime")
}
