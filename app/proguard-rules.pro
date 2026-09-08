# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\Users\the_m\AppData\Local\Android\Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Coil
-keep class coil.** { *; }

# --- Gson / Retrofit ---
# Los DTOs se deserializan por reflexión (nombres de campos y @SerializedName como
# "@microsoft.graph.downloadUrl"). Sin estos keeps R8 renombra/elimina campos y la
# deserialización devuelve nulls en runtime (no falla en compilación).
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Todos los modelos de respuesta de red (OneDrive/Graph, LrcLib, Deezer) viven en este paquete.
-keep class com.qhana.siku.data.remote.** { *; }

# Modelos del backup de playlists: Gson los serializa por reflexión, y los nombres de los campos
# SON el formato del archivo. Si R8 los renombra, el JSON exportado sale con claves ofuscadas
# (`a`, `b`, …) y ningún backup vuelve a restaurarse — incluidos los ya guardados en la nube.
-keep class com.qhana.siku.data.backup.** { *; }

# --- JAudioTagger (fork Adonai, muy reflexivo para tags Vorbis/FLAC) ---
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# --- MSAL (autenticación Microsoft) ---
-keep class com.microsoft.identity.** { *; }
-dontwarn com.microsoft.identity.**
-dontwarn com.microsoft.device.display.**
# MSAL depende de nimbus-jose, que referencia clases opcionales de Google Tink
# (curvas Ed25519/X25519/XChaCha20) que no se empaquetan. Solo se usan en flujos JWE/JWS
# que MSAL no ejercita en estos flujos (scopes Files.Read / Files.ReadWrite.AppFolder), así que
# silenciamos los warnings.
-dontwarn com.google.crypto.tink.**
-dontwarn com.nimbusds.**

# --- Logging de diagnóstico fuera de la release ---
# `AppLogger` ya gatea con BuildConfig.DEBUG lo que de verdad cuesta —el consumidor del canal, el
# writer y toda la I/O de archivo—, pero su llamada a logcat quedaba fuera del gate, y con ella la
# construcción de cada string. `MusicController` la invoca en cada evento de reproducción.
#
# Se quitan solo los tres niveles informativos. `w` y `e` SE QUEDAN: son los que sirven cuando llega
# el informe de fallo de un usuario, que es la única ventana de diagnóstico que hay en una app
# distribuida por GitHub y sin telemetría.
#
# `assumenosideeffects` deja además que R8 elimine el cálculo de los argumentos, así que las
# concatenaciones que solo alimentaban un `Log.d` desaparecen con él.
#
# ⚠️ ESTA REGLA NO DISTINGUE QUIÉN LLAMA, y por eso se llevó por delante `AppLogger` entero: su
# emisión usaba `Log.i`, así que en release el logger no escribía NADA —ni archivo (gateado por
# BuildConfig.DEBUG) ni logcat— y la app distribuida quedaba sin ninguna ventana de diagnóstico.
# Costó una sesión entera de depuración el 7 sep 2026, y el KDoc de la clase afirmaba lo contrario.
# `AppLogger` emite ahora por `Log.println`, que NO está en esta lista. Si algún día se añade
# `println` aquí, se vuelve a romper EN SILENCIO: la app compila, corre, y deja de contar lo que
# hace.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# Media3, Compose, Room, Hilt, Coil y OkHttp traen consumer rules propias.
