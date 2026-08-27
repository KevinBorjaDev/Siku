package com.qhana.siku.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Log de escuchas (v28). Una fila por escucha CONTADA — el mismo hecho que incrementa
 * `songs.playCount`, pero con su fecha y con lo que de verdad sonó.
 *
 * Existe porque los agregados de la v22 no pueden fechar el pasado: de N reproducciones solo se
 * conoce el instante de UNA, la última. Sin este log no hay "cómo fue julio", ni tiempo escuchado,
 * ni racha.
 *
 * **`songs.playCount` NO se sustituye** y sigue siendo el que alimenta el inicio, los sorts
 * `MOST_PLAYED`/`RECENTLY_PLAYED`, el periodo "Siempre" de las estadísticas y el LRU de desalojo
 * (`SongDao.getEvictionCandidates`). Son dos resoluciones del mismo hecho, escritas en la MISMA
 * transacción (ver `SongRepository.recordPlay`) para que no puedan divergir.
 *
 * **SIN `foreignKeys` A PROPÓSITO, y esto es la decisión de diseño de la tabla.** Con
 * `onDelete = CASCADE`, `MusicRepository.clearSourceData(ONEDRIVE)` —el logout— borraría en
 * silencio el historial de toda la música de nube, justo lo que prohíbe la convención 8
 * ("desconectar una fuente ≠ borrar la biblioteca"). Y el dedupe entre fuentes
 * (`SyncManager.applyDuplicatePolicy`) hace `mergePlayStats` y LUEGO `deleteSongs(losers)`: con
 * CASCADE, los eventos recién fusionados se borrarían acto seguido, dos mecanismos
 * contradiciéndose. La advertencia sobre `PlaylistSongCrossRef` sin CASCADE no aplica aquí y
 * conviene decirlo: un crossref colgado es una lista ROTA porque es una REFERENCIA; un evento
 * colgado sigue diciendo la verdad ("el 3 de marzo escuchaste esto") porque es un HECHO.
 *
 * Consecuencia buscada: las escuchas de una canción borrada siguen contando en los TOTALES
 * (reproducciones, tiempo, distintas), pero no aparecen en los rankings — que salen de un JOIN con
 * `songs` y por tanto solo ven lo que sigue en la biblioteca.
 */
@Entity(
    tableName = "play_events",
    // Un único índice, sobre `playedAt`: TODA consulta de esta tabla acota una ventana temporal.
    // Uno sobre `songId` solo serviría a `repointEvents` (que corre en un dedupe, no en el uso
    // normal) y duplicaría el id namespaced en cada fila para un GROUP BY que ya está recorriendo
    // un rango de este mismo índice.
    indices = [Index(value = ["playedAt"])]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Id namespaced de la canción. Puede quedar COLGADO si la canción se borra, y es correcto. */
    val songId: String,

    /** Epoch ms del instante en que la escucha se contó (el mismo `at` de `incrementPlayStats`). */
    val playedAt: Long,

    /**
     * Lo que sonó, acotado a la duración de la pista al insertar. Es el valor que
     * `MusicController.maybeRecordPlay` recibe y, hasta la v28, tiraba.
     *
     * Es la POSICIÓN alcanzada cuando la escucha se contó, no tiempo de reloj: un salto hacia
     * adelante lo inflaría (de ahí el acote) y rebobinar para repetir el estribillo lo sub-cuenta.
     * Es la misma aproximación que hace cualquier scrobbler; no leerlo como un cronómetro.
     */
    val playedMs: Long,

    /**
     * ÚNICO campo de snapshot, y no está por simetría: es el único dato del pasado que el resumen
     * necesita y que la biblioteca puede perder — "artistas distintos" cuenta también los de
     * canciones ya borradas. Título, álbum y género NO se copian porque los rankings los sacan del
     * JOIN con `songs`, o sea de la fila VIVA (que además arregla solo el caso de un re-etiquetado),
     * así que una copia aquí no la leería nadie.
     */
    val artist: String
)
