import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}



val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.qhana.siku"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qhana.siku"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["msalHash"] = "9iIMP+wSostNbzqfp/dHJ4SxhX8="
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystoreProps.isEmpty) signingConfigs.getByName("debug")
            else signingConfigs.getByName("release")
            manifestPlaceholders["msalHash"] = "h4IbsW2RdoWyIktegHmCXNbcKWQ="
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core module (dominio: modelos puros)
    implementation(project(":core"))

    // Core Android
    implementation("androidx.core:core-ktx:1.17.0")
    // Splash screen retenido en arranque hasta resolver la sesión MSAL (evita flash del Login)
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 1.5.0-alpha: APIs Expressive públicas (MotionScheme, ButtonGroup, ToggleButton,
    // SplitButton, MaterialShapes). Override explícito sobre el BOM (que mapea 1.4.0).
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    // Genera un ColorScheme M3 completo desde un color "seed" (acento del álbum). Arrastra
    // `com.materialkolor:material-color-utilities` como transitiva, y de ahí salen también el
    // quantizer, el Score y el HCT que ArtworkRepository usa para SACAR ese seed de la carátula
    // (`com.materialkolor.quantize` / `.score` / `.hct`). NO se declara explícita a propósito:
    // ya se intentó y dio problemas de resolución (ver más abajo); si algún día se separa,
    // fijarla con la MISMA versión que material-kolor.
    implementation("com.materialkolor:material-kolor:4.1.1")

    // Haze - backdrop blur (vidrio esmerilado) para contenedores sobre la carátula en NowPlaying
    implementation("dev.chrisbanes.haze:haze:1.7.2")

    // Glance - widgets de pantalla de inicio con sintaxis Compose
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.6")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-session:1.9.0")

    // JAudioTagger (fork compatible Android) - lectura de tags Vorbis (ReplayGain) en FLAC
    implementation("com.github.Adonai:jaudiotagger:2.3.15")

    // Coil 3 (Compose Multiplatform-ready)
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // Paging 3
    implementation("androidx.paging:paging-runtime:3.3.6")
    implementation("androidx.paging:paging-compose:3.3.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // DataStore (replaces SharedPreferences)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.room:room-paging:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Scrollbar nativo
    implementation("com.github.nanihadesuka:LazyColumnScrollbar:2.2.0")

    // Network (Retrofit)
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Material Color Utilities (MCU) - REMOVIDO por problemas de resolución
    // implementation("com.materialkolor:material-color-utilities-android:4.1.0")

    // Reorderable LazyColumn - ACTUALIZADO para Compose 1.7+
    implementation("sh.calvin.reorderable:reorderable:3.0.0")

    // Dependency Injection
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-android-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0")
    
    // WorkManager + Hilt
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // MSAL
    implementation("com.microsoft.identity.client:msal:8.2.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}