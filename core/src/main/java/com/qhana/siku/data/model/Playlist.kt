package com.qhana.siku.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Playlist(
    val id: Long,
    val name: String,
    val dateCreated: Long
)
