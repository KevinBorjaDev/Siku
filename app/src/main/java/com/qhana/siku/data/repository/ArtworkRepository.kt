package com.qhana.siku.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import com.qhana.siku.data.model.AlbumColors
import com.qhana.siku.data.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ArtworkRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: IMusicRepository,
    private val musicPreferences: com.qhana.siku.data.preferences.MusicPreferences,
    private val appLogger: com.qhana.siku.data.util.AppLogger
) {

    // Optimización: Cache aumentado para cubrir bibliotecas medianas-grandes (aprox. 16KB de RAM)
    private val memoryCache = LruCache<String, AlbumColors>(COLOR_CACHE_ENTRIES)

    // Mutexes para evitar extracción duplicada de la misma canción
    private val extractionMutexes = ConcurrentHashMap<String, Mutex>()


    /**
     * Invalida el caché de colores para una canción específica (RAM y DB).
     * Útil cuando el artwork se actualiza en background.
     *
     * Los colores elegidos A MANO por el usuario ([saveManualColor]) son inmunes salvo
     * [force]: la invalidación automática (descarga completada con carátula embebida mejor,
     * healing de artwork) no debe pisar una elección explícita — era el bug de "elijo un color
     * y al relanzar vuelve el anterior": el auto-download terminaba, esto ponía las columnas
     * en NULL y la re-extracción restauraba el color natural de la carátula.
     *
     * @param force también borra colores manuales Y su marca (acciones explícitas del usuario,
     *   como "regenerar colores" en Ajustes).
     */
    suspend fun invalidateCache(songId: String, force: Boolean = false) {
        if (!force && songId in musicPreferences.loadManualColorIds()) return
        if (force) musicPreferences.removeManualColorId(songId)
        memoryCache.remove(songId)
        musicRepository.saveColors(songId, null, null)
    }

    /**
     * Limpia todo el caché de colores en memoria.
     * Usar al cerrar sesión.
     */
    fun clearCache() {
        memoryCache.evictAll()
    }

    /**
     * Guarda un color seleccionado manualmente por el usuario y lo PROPAGA a todo el álbum:
     * el color sigue a la carátula y la carátula es por-álbum, así que todas las canciones
     * con el mismo tag `album` (mismo criterio de agrupado que el browse) reciben el override.
     * Con [album] vacío/null no se propaga (canciones sueltas sin tag no forman un álbum real).
     */
    suspend fun saveManualColor(songId: String, album: String?, color: Int, isDarkTheme: Boolean) {
        val targetIds = if (album.isNullOrBlank()) {
            listOf(songId)
        } else {
            val albumIds = musicRepository.getSongIdsByAlbum(album)
            if (songId in albumIds) albumIds else albumIds + songId
        }
        // Marca de "elegido a mano" ANTES de escribir: protege estos colores de las
        // invalidaciones automáticas (fin de descarga, redescarga, healing de artwork)
        // desde el primer instante. Ver invalidateCache.
        musicPreferences.addManualColorIds(targetIds)
        targetIds.forEach { id -> applyManualColor(id, color, isDarkTheme) }
    }

    /**
     * Aplica el override a UNA canción, conservando su color del otro tema (granular).
     * Genera automáticamente las variantes light/dark a partir del color elegido.
     */
    private suspend fun applyManualColor(songId: String, color: Int, isDarkTheme: Boolean) {
        // MISMO mutex que la extracción de getAlbumColors: sin él, una extracción EN VUELO
        // (canción recién abierta, colores aún resolviéndose) termina después de este guardado
        // y pisa el override con el color natural — "elijo un color y vuelve el anterior".
        // Serializado: si la extracción va primero, este write gana al esperar el lock; si el
        // manual va primero, la extracción ve el override en RAM (double-check) y ni extrae.
        val mutex = extractionMutexes.getOrPut(songId) { Mutex() }
        mutex.withLock {
            val existing = musicRepository.getColors(songId)
            val primary: Int
            val secondary: Int

            if (isDarkTheme) {
                secondary = color
                primary = existing?.primary ?: color
            } else {
                primary = color
                secondary = existing?.secondary ?: color
            }

            musicRepository.saveColors(songId, primary, secondary)
            memoryCache.put(songId, AlbumColors(primary, secondary))
        }
    }

    /**
     * Obtiene los colores de una canción con estrategia de 3 niveles:
     * 1. Memoria RAM (Instantáneo)
     * 2. Base de Datos (Muy rápido)
     * 3. Extracción Optimizada (Rápido - Miniatura 144px)
     *
     * Devuelve `null` cuando NO hay carátula (o la extracción falla): "sin carátula" es un
     * estado distinto de "una carátula que da un color oscuro/claro". El caller decide el
     * neutro a usar (ver [albumAccent] fallback). NUNCA se devuelve un gris centinela: eso
     * hacía indistinguible el caso sin-arte de una lectura real oscura y forzaba parches de
     * contraste sobre colores legítimos.
     */
    suspend fun getAlbumColors(song: Song): AlbumColors? {
        // NIVEL 1: Memoria RAM (Fast path sin lock)
        memoryCache.get(song.id)?.let { return it }

        val mutex = extractionMutexes.getOrPut(song.id) { Mutex() }

        return mutex.withLock {
            // Double-check después de adquirir lock
            memoryCache.get(song.id)?.let { return it }

            return withContext(Dispatchers.IO) {
                // NIVEL 2: Base de Datos (Persistencia)
                val dbColors = musicRepository.getColors(song.id)
                if (dbColors != null) {
                    memoryCache.put(song.id, dbColors)
                    return@withContext dbColors
                }

                // NIVEL 3: Extracción bajo demanda (Si no existe)
                val uri = song.albumArtUriString
                if (uri != null) {
                    val extracted = extractColorsOptimized(song.id, uri)
                    if (extracted != null) {
                        // Guardar en DB y RAM solo resultados REALES. Un fallo de extracción
                        // (imagen aún no descargada, error de carga) NO se persiste: persistirlo
                        // con colores fallback lo hacía indistinguible de una carátula
                        // legítimamente oscura/monocroma y bloqueaba el reintento natural
                        // (columnas NULL en BD = se re-extrae cuando el artwork esté).
                        musicRepository.saveColors(song.id, extracted.primary, extracted.secondary)
                        memoryCache.put(song.id, extracted)
                        return@withContext extracted
                    }
                }

                // Sin carátula (o extracción fallida): null, NO un gris centinela. Ver KDoc.
                return@withContext null
            }
        }.also {
            // Cleanup: remove mutex only if not locked (no contention).
            // Use remove(key, value) to avoid removing a different mutex.
            val m = extractionMutexes[song.id]
            if (m != null && !m.isLocked) {
                extractionMutexes.remove(song.id, m)
            }
        }
    }

    /**
     * Extrae colores usando una versión reducida pero detallada (256px) y "Democracia".
     * Ejecuta el procesamiento de imagen en Dispatchers.Default (CPU bound).
     *
     * @return null si la extracción FALLÓ (imagen no cargable, error de procesamiento).
     *         Los callers no deben persistir el fallo: se reintenta cuando haya artwork.
     */
    suspend fun extractColorsOptimized(id: String, uri: String): AlbumColors? {
        // Paso 1: Carga de Imagen (I/O Bound)
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(256) // 256px basta para el histograma cuantizado y cuesta 4x menos que 512
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()

                val result = context.imageLoader.execute(request)
                (result as? SuccessResult)?.image?.toBitmap()
            } catch (e: Exception) {
                Log.e("ArtworkRepo", "Error loading image for $id", e)
                null
            }
        } ?: return null

        // Paso 2: Procesamiento de Color (CPU Bound)
        return withContext(Dispatchers.Default) {
            try {
                val (primary, secondary) = processBitmapColors(bitmap, logId = id)
                AlbumColors(primary, secondary)
            } catch (e: Exception) {
                Log.e("ArtworkRepo", "Error processing colors for $id", e)
                null
            }
        }
    }

    // --- DEBUGGING ---
    
    data class DebugColorInfo(
        val bitmap: Bitmap,
        val candidates: List<ColorCandidate>,
        val winnerColor: Int
    )
    
    data class ColorCandidate(
        val color: Int,
        val weight: Int,
        val r: Int,
        val g: Int, 
        val b: Int,
        val isWinner: Boolean
    )

    suspend fun debugExtractColors(
        uri: String,
        isDarkTheme: Boolean = false,
        savedColors: AlbumColors? = null
    ): DebugColorInfo? {
        return withContext(Dispatchers.Default) {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(256) // Usar MISMA resolución que la lógica real (extractColorsOptimized)
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.DISABLED) // Forzar carga fresca
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()

                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.image?.toBitmap()

                if (bitmap != null) {
                    val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    analyzeBitmapForDebug(mutableBitmap, isDarkTheme, savedColors)
                } else {
                    null
                }
            } catch (e: Exception) {
                appLogger.error("Error in debugExtractColors: ${e.message}")
                null
            }
        }
    }

    // --- CORE LOGIC (UNIFICADA) ---

    /**
     * Candidatos de color de la carátula, ya rankeados por idoneidad para UI.
     *
     * Pipeline ESTÁNDAR de Material You (el mismo que usa Android para los colores del fondo de
     * pantalla), no uno propio: [QuantizerCelebi] agrupa los píxeles con Wu + k-means en espacio
     * **CAM16** —perceptualmente uniforme— y [Score] los rankea por proporción del hue y croma,
     * descartando lo que no sirve como acento.
     *
     * Sustituye al histograma propio de cubos RGB de paso fijo, que tenía dos defectos de raíz:
     * (a) los bordes de cubo son arbitrarios, así que un degradado —fondo de portada habitual—
     * se partía en bandas y el peso del color dominante se repartía entre varios cubos vecinos,
     * pudiendo perder contra un color plano menos representativo; y (b) mezclaba cuatro métricas
     * de claridad distintas (saturación HSV, luma Rec.601, lightness HSL y luminancia WCAG) cuyos
     * umbrales NO son comparables: con el mismo 0.5 de lightness, un amarillo tiene luminancia
     * 0.93 y un azul 0.07, de modo que un mismo umbral aceptaba uno y descartaba el otro.
     */
    private fun extractCandidates(bitmap: Bitmap): List<ColorCandidate> =
        rankCandidates(quantizeOpaquePixels(bitmap))

    /** Rankea con [Score] un mapa ya cuantizado (color ARGB → nº de píxeles). */
    private fun rankCandidates(populationByColor: Map<Int, Int>): List<ColorCandidate> {
        if (populationByColor.isEmpty()) return emptyList()

        // filter = true descarta acromáticos (croma < 5) y hues con presencia < 1%: es el filtro
        // de "sirve como acento" que al algoritmo anterior le faltaba (solo tenía tope de
        // saturación, nunca mínimo, y por eso un gris sucio masivo podía ganar).
        //
        // fallbackColorArgb = null es DELIBERADO: por defecto Score devuelve un azul de relleno
        // cuando nada pasa el filtro, y una carátula en blanco y negro saldría azul. Con null la
        // lista vuelve VACÍA, que es justo la señal de "carátula acromática".
        val ranked = Score.score(
            populationByColor,
            desired = CANDIDATE_COUNT,
            fallbackColorArgb = null,
            filter = true
        )

        return ranked.map { argb ->
            ColorCandidate(
                color = argb,
                // Score devuelve las MISMAS claves del mapa (`Hct.toInt()` conserva el argb con
                // el que se construyó), así que la población se lee directo.
                weight = populationByColor[argb] ?: 0,
                r = android.graphics.Color.red(argb),
                g = android.graphics.Color.green(argb),
                b = android.graphics.Color.blue(argb),
                isWinner = false
            )
        }
    }

    /**
     * Distancia angular entre dos matices HCT (0..180), tratando el cierre del círculo: 350° y
     * 10° están a 20°, no a 340°. Se calcula aquí en vez de usar el `MathUtils` de la librería
     * porque ese es interno.
     */
    private fun hueDistance(a: Double, b: Double): Double {
        val diff = kotlin.math.abs(a - b) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /** Píxeles OPACOS de la carátula agrupados por color (ARGB → nº de píxeles). */
    private fun quantizeOpaquePixels(bitmap: Bitmap): Map<Int, Int> {
        val width = bitmap.width
        val height = bitmap.height
        // Una sola llamada JNI para toda la imagen: `bitmap[x, y]` por píxel cruza a nativo
        // ~65.000 veces y ese cruce dominaba el coste.
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // El quantizer NO mira el alpha (su QuantizerMap cuenta el int crudo), así que los
        // píxeles transparentes entrarían como negro y arrastrarían el resultado. Se descartan
        // aquí, y a los que quedan se les fuerza alpha 255: sin eso, un mismo color con dos
        // alphas distintos serían dos claves separadas y su población quedaría dividida.
        val opaque = IntArray(pixels.size)
        var opaqueCount = 0
        for (pixel in pixels) {
            if (android.graphics.Color.alpha(pixel) < MIN_OPAQUE_ALPHA) continue
            opaque[opaqueCount++] = pixel or OPAQUE_ALPHA_MASK
        }
        if (opaqueCount == 0) return emptyMap()

        return QuantizerCelebi.quantize(opaque.copyOf(opaqueCount), MAX_QUANTIZED_COLORS)
    }

    private fun analyzeBitmapForDebug(
        bitmap: Bitmap,
        isDarkTheme: Boolean = false,
        savedColors: AlbumColors? = null
    ): DebugColorInfo {
        // Debug: Ejecutar la lógica REAL de producción para ver qué sale
        val (extractedPrimary, extractedSecondary) = processBitmapColors(bitmap)

        // El "ganador" depende del tema actual y de si hay colores guardados
        // (que reflejan elecciones manuales del usuario):
        // - Tema light: usa primary
        // - Tema dark:  usa secondary
        val winnerColor = if (savedColors != null) {
            if (isDarkTheme) savedColors.secondary else savedColors.primary
        } else {
            if (isDarkTheme) extractedSecondary else extractedPrimary
        }

        // La lista NO se toca: los candidatos salen siempre en su orden de ranking, ni se
        // reordena ni se añaden filas. Lo único que cambia es cuál va resaltado — que el color
        // elegido saltara de posición entre aperturas del diálogo era justo lo que confundía.
        val candidates = extractCandidates(bitmap).take(CANDIDATE_COUNT)

        // Cuál resaltar. Si el usuario eligió un color a mano, el actual ES uno de la lista y
        // coincide exacto. Si viene del análisis, el aplicado es el seed re-tonalizado a T40/T80,
        // así que no coincide en RGB pero sí conserva el MATIZ: se resalta el candidato del que
        // proviene, que es el de hue más cercano.
        val current = Hct.fromInt(winnerColor)
        val exactIndex = candidates.indexOfFirst {
            (it.color or OPAQUE_ALPHA_MASK) == (winnerColor or OPAQUE_ALPHA_MASK)
        }
        val highlighted = when {
            exactIndex >= 0 -> exactIndex
            // Un actual sin croma (carátula acromática → negro/blanco) no proviene de ningún
            // candidato; emparejarlo por matiz daría un resultado arbitrario. No se marca nada.
            current.chroma < MIN_ACCENT_CHROMA -> NO_HIGHLIGHT
            else -> candidates.indices.minByOrNull { index ->
                hueDistance(Hct.fromInt(candidates[index].color).hue, current.hue)
            } ?: NO_HIGHLIGHT
        }

        return DebugColorInfo(
            bitmap,
            candidates.mapIndexed { index, candidate ->
                candidate.copy(isWinner = index == highlighted)
            },
            winnerColor
        )
    }

    /**
     * Los dos acentos persistidos de la carátula: `primary` (tema claro) y `secondary` (oscuro).
     *
     * El ganador de [Score] es el **seed**, y de él solo viajan hue y croma: el `ColorScheme` lo
     * genera MaterialKolor, que reencuadra el TONO por rol (primary = T40 en claro, T80 en
     * oscuro). Por eso ya no se buscan dos colores distintos con dos objetivos de luminancia y
     * dos rescates de contraste, como hacía la versión anterior: era trabajo que el consumidor
     * descartaba y que además SESGABA la elección del matiz — descartaba por rango el color más
     * representativo del álbum si resultaba muy oscuro o muy claro, aunque su tono fuese perfecto.
     *
     * El par se conserva (no es un solo color) porque la app lo usa 1:1 en sitios puntuales
     * —`albumAccent` en el MiniPlayer— y porque el override manual guarda uno u otro según el
     * tema. Se derivan por tono HCT, que es como M3 construye el rol `primary`: eso da el
     * contraste POR CONSTRUCCIÓN (40 puntos de diferencia en tono HCT ya garantizan ratio ≥ 3.0),
     * sin validar ni rescatar nada a posteriori.
     */
    private fun processBitmapColors(bitmap: Bitmap, logId: String? = null): Pair<Int, Int> {
        val populationByColor = quantizeOpaquePixels(bitmap)
        // Acento neutro para carátulas sin color: GRIS PURO (croma 0) en los mismos tonos que el
        // camino normal, no negro/blanco. Negro puro se veía como "#000000" en el selector —
        // parecía un fallo, no una decisión— y como acento 1:1 (albumAccent del MiniPlayer) es
        // ilegible. El croma 0 sigue disparando el esquema Monochrome del tema igual de bien.
        val neutral = Pair(
            Hct.from(0.0, 0.0, LIGHT_THEME_ACCENT_TONE).toInt(),
            Hct.from(0.0, 0.0, DARK_THEME_ACCENT_TONE).toInt()
        )

        val candidates = rankCandidates(populationByColor)

        // Única condición de "sin color": que NINGÚN matiz pase el filtro de Score (croma ≥ 5 y
        // ≥1% de presencia). Es el caso del blanco y negro de verdad, y ahí no hay nada que
        // inventar. Hubo un intento de ir más allá —exigir que un % mínimo de la portada fuese
        // cromático para evitar que unas líneas de color tiñeran una foto en B&N— y se
        // DESCARTÓ (21 jul 2026): mandaba a gris portadas que sí tienen color, como el sepia
        // verdoso de Parasomnia, cuyos candidatos el algoritmo extrae correctamente. No
        // reintroducirlo sin un caso real que lo justifique.
        if (candidates.isEmpty()) return neutral

        val seed = Hct.fromInt(candidates.first().color)
        // El croma del seed es el otro número que hace falta para calibrar: es lo que decide si
        // el PaletteStyle del tema tiene material con el que trabajar o va a inventarse el color.
        // El hex se formatea APARTE: el id puede traer un '%' (rutas locales) y aplicar format()
        // sobre la cadena ya interpolada reventaría con IllegalFormatException.
        val seedHex = "#%06X".format(0xFFFFFF and candidates.first().color)
        appLogger.log(
            COLOR_LOG_CATEGORY,
            "${logId ?: "debug"}: seed $seedHex croma ${seed.chroma.toInt()}"
        )
        return Pair(
            seed.withTone(LIGHT_THEME_ACCENT_TONE).toInt(),
            seed.withTone(DARK_THEME_ACCENT_TONE).toInt()
        )
    }

    private companion object {
        // Entradas del caché de colores en RAM (~16KB): cubre bibliotecas medianas-grandes
        // sin re-extraer al scrollear.
        private const val COLOR_CACHE_ENTRIES = 2000

        // Colores que el quantizer produce antes de rankear. 128 es el valor que usa Android
        // para los colores del fondo de pantalla con este mismo pipeline.
        private const val MAX_QUANTIZED_COLORS = 128

        // Candidatos rankeados que se conservan: el primero es el seed y el resto alimenta el
        // color lab. Score ya devuelve como mucho uno por familia de hue, así que no hace falta
        // deduplicar a mano.
        private const val CANDIDATE_COUNT = 8

        // Un píxel por debajo de este alpha no cuenta (bordes suavizados, PNG con transparencia).
        private const val MIN_OPAQUE_ALPHA = 128
        private const val OPAQUE_ALPHA_MASK = 0xFF000000.toInt()

        // Croma HCT mínimo para considerar que un color TIENE matiz. Solo se usa para decidir si
        // el color actual puede emparejarse con un candidato en el selector: un neutro (croma 0)
        // no proviene de ninguno y emparejarlo por hue daría un resultado arbitrario.
        private const val MIN_ACCENT_CHROMA = 5.0

        // Categoría propia en AppLogger: el diagnóstico de color no es un error ni pertenece a
        // reproducción.
        private const val COLOR_LOG_CATEGORY = "COLOR"

        /** Ningún candidato corresponde al color actual (índice imposible). */
        private const val NO_HIGHLIGHT = -1

        // Tonos HCT del rol `primary` de M3: T40 sobre fondo claro y T80 sobre fondo oscuro.
        // No son estéticos: 40 puntos de diferencia en tono HCT garantizan un contraste ≥ 3.0
        // contra el fondo del tema (ver KDoc de Hct), que es lo que antes se intentaba asegurar
        // a posteriori con umbrales de luminancia WCAG y colores de rescate hardcodeados.
        private const val LIGHT_THEME_ACCENT_TONE = 40.0
        private const val DARK_THEME_ACCENT_TONE = 80.0
    }
}
