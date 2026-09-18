package com.qhana.siku.data.model

import androidx.compose.runtime.Immutable

/**
 * Lo que un criterio de orden tiene que saber decir de sí mismo: hacia dónde ordena cuando se
 * ELIGE por primera vez.
 *
 * Existe porque el criterio y la dirección son **dos ejes**, y el chip necesita los dos: al tocar
 * un criterio distinto aplica el que aquí se declare (reproducciones empieza de mayor a menor,
 * título de la A a la Z), y al tocar el que ya está activo invierte. Sin este dato, estrenar
 * "Reproducciones" listaría primero las que nunca se han oído.
 */
interface SortCriterion {
    /** Dirección con la que se estrena este criterio. `true` = ascendente. */
    val defaultAscending: Boolean
}

/**
 * Un orden completo: QUÉ se ordena ([criterion]) y HACIA DÓNDE ([ascending]).
 *
 * **Los dos ejes van separados a propósito** (16 sep 2026). Antes cada enum fundía los dos en un
 * solo valor (`TITLE_ASC`, `TITLE_DESC`, `MOST_PLAYED`…) y eso tenía dos consecuencias: el menú
 * crecía por multiplicación —cada criterio nuevo pedía dos entradas— y, como nadie quería un menú
 * de ocho, la mitad de los criterios se quedaba con una sola dirección. La asimetría se veía en el
 * propio enum: `title` y `dateAdded` tenían sus dos sentidos y `playCount` solo uno, no por
 * decisión sino por presupuesto de menú. Separados, invertir es un gesto (segundo toque sobre el
 * criterio activo) y "menos reproducidas" o "hace más que no la escucho" salen gratis.
 *
 * La capa de datos ya hablaba así: `SongDao.songOrderClause(sortColumn, sortAsc)` recibe columna y
 * dirección desde siempre, y `SongRepository` tenía un `when` cuyo único trabajo era volver a
 * PARTIR el enum en ese par. Ese `when` ya no existe.
 */
@Immutable
data class SortSpec<T>(
    val criterion: T,
    val ascending: Boolean
) where T : Enum<T>, T : SortCriterion {

    /**
     * El gesto del chip, en una función: elegir [next] cuando ya se está ordenando por él INVIERTE
     * la dirección; elegir otro distinto lo estrena con la suya ([SortCriterion.defaultAscending]).
     *
     * Vive aquí y no en cada pantalla porque las seis que tienen chip hacen exactamente esto.
     */
    fun selecting(next: T): SortSpec<T> =
        if (next == criterion) copy(ascending = !ascending) else of(next)

    /**
     * Formato de persistencia: `CRITERIO:asc|desc` en UNA sola clave de DataStore.
     *
     * Una clave y no dos porque cada volcado reescribe el archivo entero (ver el KDoc de
     * `MusicPreferences`): guardar criterio y dirección por separado serían dos escrituras para un
     * solo cambio del usuario, y ya hubo que fusionar un caso así con las carpetas locales.
     */
    fun serialize(): String = "$criterion$SEPARATOR${if (ascending) ASC else DESC}"

    companion object {
        const val SEPARATOR = ":"
        const val ASC = "asc"
        const val DESC = "desc"

        /** El orden con el que se ESTRENA un criterio: su dirección natural. */
        fun <T> of(criterion: T): SortSpec<T> where T : Enum<T>, T : SortCriterion =
            SortSpec(criterion, criterion.defaultAscending)
    }
}

/**
 * Lee lo que dejó [SortSpec.serialize], tolerando lo que se guardó ANTES de que la dirección fuera
 * un eje aparte.
 *
 * [legacy] traduce un nombre del formato viejo —donde la dirección iba dentro del propio valor
 * (`TITLE_DESC`, `RECENTLY_ADDED`)— al par nuevo. Sin él, la preferencia de orden de todos los
 * usuarios actuales se perdería en silencio al actualizar: `enumValueOf` no encontraría el nombre y
 * el `runCatching` caería al default sin que nada fallara.
 */
inline fun <reified T> parseSortSpec(
    raw: String?,
    fallback: SortSpec<T>,
    legacy: (String) -> SortSpec<T>? = { null }
): SortSpec<T> where T : Enum<T>, T : SortCriterion {
    val stored = raw?.takeIf { it.isNotBlank() } ?: return fallback
    val separator = stored.indexOf(SortSpec.SEPARATOR)
    // Sin separador es el formato viejo: el nombre llevaba la dirección dentro.
    if (separator < 0) return legacy(stored) ?: fallback
    val criterion = runCatching {
        enumValueOf<T>(stored.substring(0, separator))
    }.getOrNull() ?: return fallback
    return SortSpec(criterion, stored.substring(separator + 1) == SortSpec.ASC)
}
