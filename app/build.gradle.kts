import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    // Consume el perfil que genera `:baselineprofile` y lo empaqueta en el APK/AAB.
    id("androidx.baselineprofile")
}



val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.qhana.siku"
    // 37 lo EXIGE MaterialKolor 5.0.0: arrastra `compose.material3:1.11.0-alpha07`, que ya no
    // compila contra 36. `targetSdk` sigue en 35 a propósito — compileSdk solo dice contra qué
    // API se compila, no cambia el comportamiento en runtime de la app publicada.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qhana.siku"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.0"

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

    // Los esquemas exportados por Room son la ENTRADA de MusicDatabaseMigrationTest: el helper
    // abre la BD en una versión vieja leyendo su JSON de los assets del APK de test.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs("$projectDir/schemas")
        }
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
        // Para leer VERSION_NAME desde el código: el User-Agent que exige LrcLib debe llevar la
        // versión REAL, y escrita a mano se quedó desfasada (decía 1.0 con la app en 1.1.1).
        buildConfig = true
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

    // Baseline profile: el módulo que lo GENERA (no entra en el APK) y la librería que lo
    // INSTALA en el dispositivo al primer arranque. `profileinstaller` llega como transitiva de
    // Compose, pero se declara explícita porque sin ella el .prof empaquetado no se aplicaría en
    // instalaciones fuera de Play — y esa dependencia implícita es justo la que se rompe sola.
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

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
    //
    // El pin estaba en alpha18 porque de alpha20 en adelante exigían compileSdk 37; con el salto a
    // 37 esa restricción desaparece y se pasa a la última publicada (alpha24, comprobada en el
    // índice de maven.google.com el 28 jul 2026). OJO: en androidx, M3 Expressive TODAVÍA no es
    // estable — el "officially stable" del changelog de MaterialKolor 5 habla de Compose
    // Multiplatform (`org.jetbrains.compose.material3`), que no es lo que usa esta app. Si alguna
    // API cambió de nombre entre alpha18 y alpha24, volver a alpha18 es cambiar esta línea.
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    // Genera un ColorScheme M3 completo desde un color "seed" (acento del álbum). Arrastra
    // `com.materialkolor:material-color-utilities` como transitiva, y de ahí salen también el
    // quantizer, el Score y el HCT que ArtworkRepository usa para SACAR ese seed de la carátula
    // (`com.materialkolor.quantize` / `.score` / `.hct`). NO se declara explícita a propósito:
    // ya se intentó y dio problemas de resolución (ver más abajo); si algún día se separa,
    // fijarla con la MISMA versión que material-kolor.
    // 4.1.1, NO 5.0.0: la 5 exige Kotlin 2.4.0 y no hay KSP para 2.4 todavía (ver el comentario
    // del plugin de Kotlin en el build raíz). Y no se pierde nada de color: verificado sobre las
    // fuentes de la 5 — `SpecVersion.Default` sigue siendo SPEC_2021 y las paletas de cada
    // variante son idénticas a las de 4.1.1. Lo único nuevo es `DynamicMaterialExpressiveTheme`,
    // azúcar sobre lo que `MusicPlayerTheme` ya hace a mano.
    implementation("com.materialkolor:material-kolor:4.1.1")

    // (Haze — backdrop blur — se ELIMINÓ el 9 ago 2026: el vidrio esmerilado se había retirado
    // del NowPlaying al adoptar "nada de glassmorphism", pero solo se quitaron los usos y quedó
    // vivo el componente, la cadena de parámetros que lo alimentaba y esta dependencia.)

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
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
// ---------------------------------------------------------------------------------------------
// Verificación del BASELINE PROFILE antes de empaquetar una release.
//
// Este pipeline falla EN SILENCIO por diseño y ya costó dos veces: `assembleRelease` empaqueta el
// perfil si existe y compila igual si no (así se publicaron ocho días de releases sin perfil AOT
// para el código propio), y el generador puede escribir un `startup-prof.txt` idéntico al
// `baseline-prof.txt` (lo que ocurre con un único test marcado `includeInStartupProfile`), en cuyo
// caso el reparto de clases dentro del dex no ordena NADA. Las dos cosas se detectan en un segundo
// y ninguna avisa sola: de ahí esta tarea.
//
// Los tres criterios son exactamente los del bloque de comandos de CLAUDE.md:
//   1. el perfil EXISTE (si no, la release sale sin AOT para el código de la app),
//   2. el startup profile es MÁS CORTO que el baseline (si es igual, no hay nada que ordenar),
//   3. el baseline CUBRE el reproductor (su árbol es el composable más grande de la app y es el
//      coste que solo el AOT puede quitar de la primera apertura).
//
// Escape explícito para un build de emergencia: `-PskipBaselineProfileCheck`. Es a propósito una
// bandera que hay que escribir, y no un warning: un warning en la consola de Gradle es justo lo
// que nadie leyó las dos veces anteriores.
// Símbolo que el baseline profile TIENE que mencionar para que valga lo que promete: el árbol del
// reproductor. Se busca por nombre de clase Compose y no por una firma exacta porque los nombres
// generados cambian entre versiones del compilador; lo que se comprueba es que ESE recorrido corrió.
val PLAYER_PROFILE_MARKER = "NowPlaying"

val baselineProfileDir = layout.projectDirectory.dir("src/release/generated/baselineProfiles")
val skipBaselineProfileCheck = providers.gradleProperty("skipBaselineProfileCheck").isPresent

val verifyBaselineProfile = tasks.register("verifyBaselineProfile") {
    group = "verification"
    description = "Comprueba que el baseline profile existe, cubre el reproductor y no es idéntico al startup profile."

    val baselineFile = baselineProfileDir.file("baseline-prof.txt").asFile
    val startupFile = baselineProfileDir.file("startup-prof.txt").asFile
    val skip = skipBaselineProfileCheck
    val playerMarker = PLAYER_PROFILE_MARKER

    // Declarados como entradas para que la tarea sea cacheable y no corra en cada build.
    inputs.files(baselineFile, startupFile).withPropertyName("baselineProfiles").optional()

    doLast {
        if (skip) return@doLast

        check(baselineFile.exists()) {
            "No hay baseline profile en ${baselineFile.parentFile}. La release saldría SIN AOT para el " +
                "código de la app. Generarlo con `./gradlew :app:generateBaselineProfile` (⚠️ es un test " +
                "instrumentado: DESINSTALA la app y se lleva la biblioteca — exportar antes las playlists) " +
                "y COMMITEARLO. Para saltarse esta comprobación: -PskipBaselineProfileCheck"
        }

        val baselineText = baselineFile.readText()

        check(!startupFile.exists() || startupFile.length() < baselineFile.length()) {
            "El startup profile no es más corto que el baseline (${startupFile.length()} vs ${baselineFile.length()} bytes): " +
                "el recorrido completo entró en el startup profile, así que el reparto de clases dentro del dex " +
                "no ordena nada. Regenerar con el generador de DOS tests (solo `startup()` lleva " +
                "`includeInStartupProfile = true`). Para saltarse esta comprobación: -PskipBaselineProfileCheck"
        }

        check(baselineText.contains(playerMarker)) {
            "El baseline profile no menciona '$playerMarker': el recorrido del reproductor no llegó a " +
                "correr (device sin biblioteca = la app se queda en el onboarding), así que el perfil solo cubre " +
                "el arranque. Regenerar en un device CON música. Para saltarse esta comprobación: -PskipBaselineProfileCheck"
        }
    }
}

/**
 * La fuente de iconos que se EMPAQUETA es un subset: 117 glifos de los 4174 que trae Material
 * Symbols (0,24 MB contra 14,9). La completa vive en `tools/fonts/` y no entra en el APK.
 *
 * El modo de fallo de esto es el peor que hay: un icono que no esté en el subset no falla en
 * compilación ni en ejecución — se dibuja el NOMBRE del icono como texto, o un tofu, y solo se ve
 * abriendo esa pantalla. Por eso el build cruza en cada release lo que el código pide contra lo
 * que el manifiesto declara. Al añadir un icono nuevo hay que regenerar:
 *
 *     python tools/subset_icon_font.py
 *
 * Escape de emergencia: -PskipIconFontCheck
 */
val iconSubsetManifest = rootProject.layout.projectDirectory.file("tools/icon_subset_manifest.txt")
val skipIconFontCheck = providers.gradleProperty("skipIconFontCheck").isPresent

val verifyIconFontSubset = tasks.register("verifyIconFontSubset") {
    group = "verification"
    description = "Comprueba que todo icono usado en el código está en el subset de la fuente empaquetada."

    val manifestFile = iconSubsetManifest.asFile
    val sourceDir = layout.projectDirectory.dir("src/main/java").asFile
    val skip = skipIconFontCheck

    inputs.file(manifestFile).withPropertyName("iconManifest").optional()
    inputs.dir(sourceDir).withPropertyName("kotlinSources")

    doLast {
        if (skip) return@doLast

        check(manifestFile.exists()) {
            "No está $manifestFile. Generarlo con `python tools/subset_icon_font.py`. " +
                "Para saltarse esta comprobación: -PskipIconFontCheck"
        }

        val bundled = manifestFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        // Solo los usos EXPLÍCITOS de icono, no todo literal: aquí se busca el fallo real (pedir un
        // icono que no está empaquetado), mientras que el script genera con un barrido amplio a
        // propósito. Que el generador incluya de más es barato; que la verificación avise de más
        // sería ruido que enseña a ignorarla.
        val iconCall = Regex("(?:MaterialSymbol|MenuItemIcon)\\(\\s*(?:icon\\s*=\\s*)?\"([a-z0-9_]{2,40})\"")
        val namedIcon = Regex("\\bicon\\s*=\\s*\"([a-z0-9_]{2,40})\"")

        val requested = sortedSetOf<String>()
        sourceDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            iconCall.findAll(text).forEach { requested.add(it.groupValues[1]) }
            namedIcon.findAll(text).forEach { requested.add(it.groupValues[1]) }
        }

        val missing = requested - bundled
        check(missing.isEmpty()) {
            "Estos iconos se usan en el código pero NO están en la fuente empaquetada: " +
                "${missing.joinToString(", ")}. Se dibujarían como texto o como tofu, sin fallar en " +
                "ejecución. Regenerar con `python tools/subset_icon_font.py` y commitear la fuente y el " +
                "manifiesto. Para saltarse esta comprobación: -PskipIconFontCheck"
        }
    }
}

/**
 * Comprueba que no se llama a ninguna API por encima de `minSdk` sin guarda de versión.
 *
 * **Existe porque el proyecto no tiene NADA más que lo compruebe**: no hay
 * `isCoreLibraryDesugaringEnabled` y `assembleRelease` no corre la tarea `lint`, así que una API
 * nueva compila, pasa la release y solo revienta con `NoSuchMethodError` en el dispositivo viejo —
 * el mismo modo de fallo silencioso que ya obligó a escribir las dos verificaciones de arriba. Pasó
 * el 29 ago 2026 con `LocalDate.ofInstant` (API **34** aunque `LocalDate` sea de la 26), que
 * crasheaba la pantalla de estadísticas en todo Android 8–13.
 *
 * **Por qué esto y no desugaring**: el desugaring resolvería `java.time` a cambio de ~1 MB de APK
 * —en una app cuyo APK se bajó de 17 a 3 MB a base de pelear cada KB— y **no cubriría `android.*`**,
 * que es la otra mitad del riesgo. Esta tarea cubre las dos y no pesa nada en el binario.
 *
 * **Cómo evita el ruido** (la lección de `verifyIconFontSubset`: una verificación que avisa de más
 * enseña a ignorarla). Solo mira lo que puede resolver con CERTEZA:
 *  - clases del framework IMPORTADAS cuya `since` supera minSdk;
 *  - llamadas con receptor de TIPO (`LocalDate.ofInstant(...)`), resolviendo el nombre simple por
 *    los imports del propio archivo, más las clases implícitas de `java.lang`.
 *
 * Entre sobrecargas del mismo nombre se toma la `since` **MÍNIMA**: `Math.floorMod(int,int)` es API
 * 24 y `(long,int)` es 31, y sin esa regla el uso legítimo del primero saltaría como falso positivo.
 *
 * Lo que NO ve, y hay que saberlo: llamadas sobre una INSTANCIA (`layout.getLineBottom()`), tipos
 * escritos con nombre completo inline, y campos. Para eso está `./gradlew :app:lint`, que hace
 * análisis de flujo de verdad; esto es la red que corre sola en cada release.
 *
 * Se considera guardado si hay `SDK_INT`/`@RequiresApi` en el archivo (para una clase) o en las 15
 * líneas anteriores (para una llamada).
 *
 * Escape de emergencia: -PskipMinSdkApiCheck
 */
val skipMinSdkApiCheck = providers.gradleProperty("skipMinSdkApiCheck").isPresent

val verifyMinSdkApis = tasks.register("verifyMinSdkApis") {
    group = "verification"
    description = "Comprueba que no se usa ninguna API por encima de minSdk sin guarda de versión."

    val minSdkLevel = android.defaultConfig.minSdk
        ?: error("minSdk sin declarar: verifyMinSdkApis no sabría contra qué comparar")
    val platformsDir = File(android.sdkDirectory, "platforms")
    val sourceDirs = listOf(
        layout.projectDirectory.dir("src/main/java").asFile,
        rootProject.layout.projectDirectory.dir("core/src/main/java").asFile
    )
    val skip = skipMinSdkApiCheck

    sourceDirs.forEachIndexed { i, dir -> inputs.dir(dir).withPropertyName("kotlinSources$i") }

    doLast {
        if (skip) return@doLast

        // El índice oficial de niveles de API que trae cada plataforma del SDK. Se toma el de la
        // plataforma MÁS ALTA instalada: conoce todo lo que conocen las anteriores y además las
        // APIs nuevas, que son justo las que hay que detectar.
        val apiFile = platformsDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("android-") }
            .sortedBy { it.name.removePrefix("android-").toDoubleOrNull() ?: 0.0 }
            .map { File(it, "data/api-versions.xml") }
            .lastOrNull { it.isFile }

        check(apiFile != null) {
            "No se encontró ningún `data/api-versions.xml` en $platformsDir. Se instala con " +
                "cualquier plataforma del SDK. Para saltarse esta comprobación: -PskipMinSdkApiCheck"
        }

        val classSince = HashMap<String, Int>()
        // "clase#metodo" -> since MÍNIMA entre sus sobrecargas (ver KDoc).
        val memberSince = HashMap<String, Int>()
        val nameRx = Regex("""name="([^"]+)"""")
        val sinceRx = Regex("""since="(\d+)"""")
        var currentClass: String? = null

        apiFile.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("<class ") -> {
                    val name = nameRx.find(line)?.groupValues?.get(1)
                    currentClass = name
                    if (name != null) classSince[name] = sinceRx.find(line)?.groupValues?.get(1)?.toInt() ?: 1
                }
                line.startsWith("</class>") -> currentClass = null
                line.startsWith("<method ") -> {
                    val cls = currentClass ?: return@forEachLine
                    val method = nameRx.find(line)?.groupValues?.get(1)?.substringBefore('(')
                        ?: return@forEachLine
                    // Sin `since` propia, el método es tan viejo como su clase.
                    val since = sinceRx.find(line)?.groupValues?.get(1)?.toInt()
                        ?: classSince[cls] ?: 1
                    val key = "$cls#$method"
                    val previous = memberSince[key]
                    if (previous == null || since < previous) memberSince[key] = since
                }
            }
        }

        // Las de `java.lang` no se importan, así que no habría forma de resolver su nombre simple.
        val implicitLang = listOf(
            "Math", "StrictMath", "System", "String", "Integer", "Long", "Double", "Float",
            "Character", "Boolean", "Byte", "Short", "Thread", "Runtime", "StringBuilder", "Process"
        ).associateWith { "java/lang/$it" }

        val importRx = Regex("""^import\s+((?:android|java|javax)(?:\.[A-Za-z0-9_]+)+)""")
        val callRx = Regex("""\b([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)\s*\(""")
        val problems = sortedSetOf<String>()

        sourceDirs.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val lines = file.readLines()
                val text = lines.joinToString("\n")
                val fileGuarded = text.contains("SDK_INT") || text.contains("@RequiresApi")

                val simpleToFqn = HashMap(implicitLang)
                lines.forEach { line ->
                    importRx.find(line.trim())?.let { match ->
                        val fq = match.groupValues[1]
                        simpleToFqn[fq.substringAfterLast('.')] = fq.replace('.', '/')
                    }
                }

                // (A) Clases del framework más nuevas que minSdk.
                if (!fileGuarded) {
                    simpleToFqn.forEach { (simple, fqn) ->
                        val since = classSince[fqn] ?: return@forEach
                        if (since > minSdkLevel && Regex("""\b$simple\b""").containsMatchIn(text)) {
                            problems += "${file.name}: la clase $fqn es API $since"
                        }
                    }
                }

                // (B) Llamadas con receptor de TIPO, que es como se coló `LocalDate.ofInstant`.
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) return@forEachIndexed
                    callRx.findAll(line).forEach { match ->
                        val (receiver, method) = match.destructured
                        val fqn = simpleToFqn[receiver] ?: return@forEach
                        val since = memberSince["$fqn#$method"] ?: return@forEach
                        if (since <= minSdkLevel) return@forEach
                        val context = lines.subList(maxOf(0, index - 15), index + 1).joinToString("\n")
                        if (context.contains("SDK_INT") || context.contains("@RequiresApi")) return@forEach
                        problems += "${file.name}:${index + 1}  $receiver.$method() es API $since"
                    }
                }
            }
        }

        check(problems.isEmpty()) {
            "APIs por encima de minSdk $minSdkLevel sin guarda de versión. Compilan y pasan la " +
                "release, pero lanzan NoSuchMethodError en un dispositivo viejo:\n" +
                problems.joinToString("\n") { "  - $it" } +
                "\nArreglo: usar una API disponible en minSdk, o guardar con " +
                "`if (Build.VERSION.SDK_INT >= …)`. Para saltarse esta comprobación: -PskipMinSdkApiCheck"
        }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyBaselineProfile)
    dependsOn(verifyIconFontSubset)
    dependsOn(verifyMinSdkApis)
}
