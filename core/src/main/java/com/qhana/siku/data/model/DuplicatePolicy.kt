package com.qhana.siku.data.model

/**
 * Política del usuario para canciones DUPLICADAS entre fuentes (mismo archivo visible por
 * la nube y por la carpeta local — caso típico: la carpeta local es un espejo sincronizado
 * de la nube). Se decide UNA vez (diálogo al detectar) y se persiste; el pipeline genérico
 * del sync la re-aplica idempotentemente en cada scan.
 *
 * null (sin persistir) = aún no se preguntó: el sync detecta y dispara el diálogo.
 */
enum class DuplicatePolicy {
    /** Conservar ambas copias (también se persiste: no volver a preguntar). */
    KEEP_BOTH,

    /** Conservar la copia de NUBE; las filas locales duplicadas se retiran. */
    PREFER_CLOUD,

    /** Conservar la copia LOCAL; las filas de nube duplicadas se retiran. */
    PREFER_LOCAL
}

/**
 * Normalización canónica de una ruta relativa para COMPARAR entre fuentes: separadores a
 * `/`, sin bordes, case-insensitive (OneDrive y FAT/exFAT lo son). La columna
 * `songs.relativePath` se persiste YA normalizada — el JOIN de duplicados compara igualdad
 * directa.
 */
fun normalizeRelativePath(raw: String): String =
    raw.replace('\\', '/').trim('/').lowercase()
