package com.qhana.siku.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qhana.siku.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qhana.siku.data.model.Song

// ============== MINI PLAYER ==============

@Immutable
private data class MiniPlayerColors(
    val titleColor: Color,
    val contentColor: Color
)

private fun calculateMiniPlayerTextColors(
    isDarkTheme: Boolean
): MiniPlayerColors {
    val titleColor: Color
    val contentColor: Color

    if (isDarkTheme) {
        titleColor = Color.White
        contentColor = Color.White.copy(alpha = 0.7f)
    } else {
        titleColor = Color(0xFF1A1A1A)
        contentColor = Color(0xFF1A1A1A).copy(alpha = 0.6f)
    }

    return MiniPlayerColors(
        titleColor = titleColor,
        contentColor = contentColor
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    currentPositionFlow: kotlinx.coroutines.flow.StateFlow<Long>,
    duration: Long,
    albumColors: com.qhana.siku.data.model.AlbumColors?,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    if (song == null) return

    // collectAsStateWithLifecycle: detiene la recolección cuando la app está en background
    // para evitar trabajo innecesario (MiniPlayer no debe actualizar progreso sin UI visible).
    val currentPosition by currentPositionFlow.collectAsStateWithLifecycle()
    val progress = if (duration > 0) currentPosition.toFloat() / duration.toFloat() else 0f

    val isDarkTheme = isSystemInDarkTheme()

    val primaryColor = MaterialTheme.colorScheme.primary
    // Acento del álbum para la barra de progreso (fallback a primary del sistema), con la misma
    // guarda de contraste que el botón (canciones sin carátula → acento visible).
    val progressAccent = remember(albumColors, isDarkTheme, primaryColor) {
        albumAccent(albumColors, isDarkTheme, fallback = primaryColor)
    }

    // Colores del texto (título/artista) según el tema.
    val textColors = remember(isDarkTheme) {
        calculateMiniPlayerTextColors(isDarkTheme)
    }

    // Background SÓLIDO surfaceContainer (el mismo del ajuste "fondo sólido" del NowPlaying):
    // superficie plana estable, sin tinte de acento. Es un rol del scheme sembrado con el álbum,
    // así que el tema lo tiñe al cambiar de canción sin animarlo a mano.
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer

    // Píldora completa: el MiniPlayer FLOTA sobre la navbar del home (ya no va edge-to-edge
    // con fondo plano hasta abajo).
    val shape = RoundedCornerShape(50)

    // contentDescription se resuelve aquí (contexto @Composable) porque el lambda de semantics {} no lo es.
    val miniPlayerDesc = stringResource(R.string.mini_player_desc, song.title)

    // Card: contenedor OFICIAL de M3 para "contenido de un solo tema que se toca para abrir".
    // M3 no tiene componente de mini-player; un toolbar no aplica (no es una fila de acciones y
    // necesita tap en toda la superficie). Card da ese onClick nativo (expandir al NowPlaying) y
    // la elevación por su propia API, sin fingir que es un toolbar. Es `Surface` + defaults de card.
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // Alto fijo al spec del floating toolbar M3 Expressive (64.dp).
            .height(ComponentConfig.MiniPlayerHeight)
            .semantics { contentDescription = miniPlayerDesc },
        shape = shape,
        // Contenedor TRANSPARENTE: el tinte animado del álbum se dibuja en el Box interno para que
        // NO reciba el overlay tonal del Card (que teñiría el color según la elevación).
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // Elevación por la API del Card. 6dp = nivel 3 del spec (el mismo del FAB): un bar flotante
        // debe LEERSE flotante; el default de Card (1dp) quedaría casi plano y desharía la sombra.
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
            // Contenido
            MiniPlayerContent(
                song,
                isPlaying,
                onPlayPause,
                onNextClick,
                textColors,
                isBuffering,
                sharedTransitionScope,
                animatedVisibilityScope
            )

            // Barra de progreso inferior (fina, siempre visible). Va INSET y con puntas
            // redondeadas. Un único Box con drawBehind (track + progreso) en vez de un Box
            // hijo con fillMaxWidth(progress): así el tick de posición solo re-DIBUJA, sin
            // disparar una pasada de layout cada segundo.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // El inset NO es estético: la barra vive pegada al borde inferior de una
                    // píldora de radio = alto/2, así que a esa altura el contorno curvo ya se
                    // metió ~26dp hacia adentro (r - √(r² - (r-y)²), con r = 36 e y = 3). Con
                    // menos que eso las puntas se saldrían del recorte del Card.
                    .padding(horizontal = 34.dp)
                    .height(3.dp)
                    .align(Alignment.BottomCenter)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(
                            color = progressAccent.copy(alpha = 0.15f),
                            cornerRadius = radius
                        )
                        val p = progress.coerceIn(0f, 1f)
                        if (p > 0f) {
                            drawRoundRect(
                                color = progressAccent,
                                size = Size(size.width * p, size.height),
                                cornerRadius = radius
                            )
                        }
                    }
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniPlayerContent(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNextClick: () -> Unit,
    textColors: MiniPlayerColors,
    isBuffering: Boolean,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    // contentDescription hoisteados (los lambdas de semantics {} no son @Composable).
    val playPauseDesc = if (isPlaying) stringResource(R.string.common_pause) else stringResource(R.string.common_play)
    val nextDesc = stringResource(R.string.common_next)
    // El icono muestra "pausa" también durante el buffering (transición de canción,
    // re-buffer): es el mismo criterio que usa NowPlaying (PLAYING || BUFFERING) y evita
    // el parpadeo del flapping de isPlaying sin retrasar el estado con un reloj.
    val showAsPlaying = isPlaying || isBuffering

    // Transporte alineado con el NowPlaying: laterales tonales (secondaryContainer) y play
    // resaltado (primary). Son roles del scheme sembrado con el álbum, así que ya vienen
    // teñidos por la canción sin animar colores a mano.
    val sideContainer = MaterialTheme.colorScheme.secondaryContainer
    val sideContent = MaterialTheme.colorScheme.onSecondaryContainer
    val playContainer = MaterialTheme.colorScheme.primary
    val playContent = MaterialTheme.colorScheme.onPrimary

    Row(
        // fillMaxHeight + CenterVertically: el contenido se centra en los 64.dp fijos del Surface
        // sin depender de un padding vertical calculado a mano. Padding interno = spec del
        // floating toolbar (8dp).
        modifier = Modifier.fillMaxHeight().padding(horizontal = ComponentConfig.FloatingBarInnerPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val sharedElementModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "album_art_${song.id}"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        } else Modifier

        // En el mini la carátula es SIEMPRE un círculo, reproduzca o no (decisión de diseño:
        // a este tamaño el morph a squircle no se leía y solo agregaba ruido). Se usa
        // AlbumArtMorphShape(1f) —el mismo path de círculo que el NowPlaying en pausa— en vez de
        // CircleShape para que ese extremo del shared element coincida exacto al pausar.
        // El tamaño sale del alto del container (deja 8dp de aire arriba/abajo), no de una
        // constante suelta: si la barra cambia de alto, la carátula lo sigue.
        AlbumArt(
            albumArtUri = song.albumArtUriString,
            size = ComponentConfig.MiniPlayerArtSize,
            shape = AlbumArtMorphShape(progress = 1f),
            modifier = sharedElementModifier,
            cacheKey = song.id
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = textColors.titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(
                    // Marquee finito: el MiniPlayer es persistente, un scroll infinito
                    // mantendría una animación viva recomponiéndose siempre. 3 pasadas bastan.
                    iterations = 3,
                    velocity = 30.dp
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = textColors.contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Solo play + siguiente. El "anterior" salió del mini a propósito: con tres controles
        // el texto se quedaba en ~155dp (marquee permanente) y los botones no llegaban al
        // mínimo táctil de 48dp. Sigue disponible en el NowPlaying, la notificación, Android
        // Auto y Wear — que es donde lo ponen YT Music, Spotify y Apple Music.
        //
        // Botón Play/Pause — círculo resaltado (primary).
        MiniRoundButton(
            onClick = onPlayPause,
            containerColor = playContainer,
            contentColor = playContent,
            desc = playPauseDesc
        ) {
            AnimatedContent(
                targetState = isBuffering,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "bufferingAnimation"
            ) { buffering: Boolean ->
                if (buffering) {
                    // LoadingIndicator expressive (morfea entre MaterialShapes), igual que
                    // el buffering del NowPlaying/Lyrics.
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = playContent
                    )
                } else {
                    AnimatedContent(
                        targetState = showAsPlaying,
                        transitionSpec = {
                            scaleIn(
                                animationSpec = tween(200, easing = FastOutSlowInEasing)
                            ) togetherWith scaleOut(
                                animationSpec = tween(150, easing = FastOutLinearInEasing)
                            )
                        },
                        label = "playPauseAnimation"
                    ) { playing: Boolean ->
                        if (playing)
                            MaterialSymbol("pause", color = playContent, size = 24.sp, fill = true)
                        else
                            MaterialSymbol("play_arrow", color = playContent, size = 24.sp, fill = true)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(ComponentConfig.FloatingBarItemGap))

        // Botón Siguiente — mismo círculo, en tonal: el par se lee como transporte y el color
        // (primary vs secondaryContainer) es el que marca cuál es la acción principal.
        MiniRoundButton(
            onClick = onNextClick,
            containerColor = sideContainer,
            contentColor = sideContent,
            desc = nextDesc
        ) {
            // Icono skip RELLENO (variante fill del símbolo, no el outline).
            MaterialSymbol("skip_next", color = sideContent, size = 24.sp, fill = true)
        }
    }
}

/**
 * Botón REDONDO del MiniPlayer (círculo de [ComponentConfig.MiniPlayerButtonSize]):
 * `FilledIconButton` REAL de M3 Expressive con shape circular FIJA (sin morph). El color del
 * contenedor puede venir animado (play/pause con el acento del álbum). Trae ripple/state-layer,
 * touch target y semántica del componente.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MiniRoundButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    desc: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(shape = CircleShape, pressedShape = CircleShape),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        modifier = modifier
            .size(ComponentConfig.MiniPlayerButtonSize)
            .semantics { contentDescription = desc }
    ) {
        content()
    }
}
