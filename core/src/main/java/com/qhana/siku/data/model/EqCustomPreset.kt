package com.qhana.siku.data.model

/**
 * Preset de ecualizador creado por el usuario. Guarda la curva CRUDA tal como estaba al
 * capturarla ([gains]) junto al modo de bandas de ese momento ([bandCount], 5 o 10): al
 * aplicarlo en el otro modo se interpola en espacio log-frecuencia (igual que los presets
 * de fábrica), así el preset suena consistente en 5 y 10 bandas.
 *
 * [id] es un identificador estable (para borrar/seleccionar sin depender del nombre, que el
 * usuario puede repetir).
 */
data class EqCustomPreset(
    val id: String,
    val name: String,
    val bandCount: Int,
    val gains: FloatArray
) {
    // equals/hashCode explícitos: FloatArray usa identidad por defecto y rompería el diffing
    // de Compose y los remember por preset.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqCustomPreset) return false
        return id == other.id &&
            name == other.name &&
            bandCount == other.bandCount &&
            gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + bandCount
        result = 31 * result + gains.contentHashCode()
        return result
    }
}
