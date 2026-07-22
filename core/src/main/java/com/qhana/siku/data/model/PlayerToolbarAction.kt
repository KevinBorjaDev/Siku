package com.qhana.siku.data.model

/**
 * Acciones configurables del toolbar del NowPlaying. El usuario decide, en Ajustes, cuáles van
 * en la barra flotante y cuáles en el menú overflow (⋮), y en qué orden ([ToolbarActionState]).
 *
 * El indicador de descarga EN CURSO NO es una acción de esta lista: es un estado transitorio que
 * la barra muestra por su cuenta mientras se descarga (ver `BottomActionBar`).
 */
enum class PlayerToolbarAction {
    REPEAT,
    LYRICS,
    QUEUE,
    KEEP_SCREEN_ON,
    EQUALIZER,
    SLEEP_TIMER,
    ADD_TO_PLAYLIST,
    DOWNLOAD
}

/** Una acción con su ubicación: [inBar] = en la barra flotante; si no, en el overflow. */
data class ToolbarActionState(
    val action: PlayerToolbarAction,
    val inBar: Boolean
)

/**
 * Config del toolbar: orden + ubicación de cada acción. Se persiste como string delimitado en
 * DataStore (`MusicPreferences`) y se reconcilia con el set completo al decodificar (acciones
 * nuevas de versiones futuras entran con su default; tokens desconocidos se descartan).
 */
object PlayerToolbarConfig {

    /** Config por defecto: replica el toolbar histórico (4 en barra, 3 en overflow). */
    val DEFAULT: List<ToolbarActionState> = listOf(
        ToolbarActionState(PlayerToolbarAction.REPEAT, inBar = true),
        ToolbarActionState(PlayerToolbarAction.LYRICS, inBar = true),
        ToolbarActionState(PlayerToolbarAction.QUEUE, inBar = true),
        ToolbarActionState(PlayerToolbarAction.KEEP_SCREEN_ON, inBar = true),
        ToolbarActionState(PlayerToolbarAction.EQUALIZER, inBar = true),
        ToolbarActionState(PlayerToolbarAction.ADD_TO_PLAYLIST, inBar = false),
        ToolbarActionState(PlayerToolbarAction.SLEEP_TIMER, inBar = false),
        ToolbarActionState(PlayerToolbarAction.DOWNLOAD, inBar = false)
    )

    /**
     * Máximo de acciones en la barra: el `HorizontalFloatingToolbar` no scrollea, así que más de
     * esto (más el overflow) desbordaría en pantallas angostas. La UI de Ajustes lo hace cumplir.
     */
    const val MAX_IN_BAR = 5

    fun encode(list: List<ToolbarActionState>): String =
        list.joinToString(",") { "${it.action.name}:${if (it.inBar) 1 else 0}" }

    fun decode(raw: String?): List<ToolbarActionState> {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.split(",").mapNotNull { token ->
            val parts = token.split(":")
            val action = parts.getOrNull(0)
                ?.let { name -> PlayerToolbarAction.entries.firstOrNull { it.name == name } }
                ?: return@mapNotNull null
            ToolbarActionState(action, inBar = parts.getOrNull(1) == "1")
        }
        // Reconciliación: cualquier acción del set completo que no estuviera guardada se agrega al
        // final con su ubicación por defecto (permite sumar acciones nuevas sin romper configs).
        val seen = parsed.map { it.action }.toSet()
        val missing = DEFAULT.filter { it.action !in seen }
        return (parsed + missing).distinctBy { it.action }
    }
}
