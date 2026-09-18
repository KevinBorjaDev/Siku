package com.qhana.siku.ui.components

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qhana.siku.R
import com.qhana.siku.player.MusicController
import com.qhana.siku.ui.theme.AppColors
import com.qhana.siku.ui.theme.AppSurface
import com.qhana.siku.ui.theme.appButtonColors
import com.qhana.siku.ui.theme.appFilledTonalIconButtonColors
import com.qhana.siku.ui.theme.appFilterChipColors
import com.qhana.siku.ui.theme.appTextFieldColors
import com.qhana.siku.ui.theme.appSwitchColors
import kotlinx.coroutines.delay

/**
 * Cadencia con la que se repinta la cuenta regresiva. Un segundo porque es la resolución de lo que
 * se lee (`mm:ss`): más rápido no cambiaría ningún dígito y más lento dejaría el número parado a la
 * vista. No sale de la constante del reloj de reproducción aunque coincida el valor — aquello mide
 * el avance de la canción y esto una cuenta atrás; que los dos quieran segundos es una coincidencia,
 * no un acoplamiento, y atarlos haría que tocar uno moviera el otro sin motivo.
 */
/**
 * Plazos habituales, los que ofrecen los chips. El 5 está para que [DEFAULT_TIMER_MINUTES] tenga su
 * chip: al abrir, el valor del campo y el chip marcado dicen lo mismo.
 */
private val TIMER_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90)

/** Valor con el que abre el campo. */
private const val DEFAULT_TIMER_MINUTES = 5

/**
 * Rango admitido. El mínimo es 1 porque por debajo de un minuto el temporizador no es un plazo sino
 * un botón de pausa; el máximo, 10 horas, es holgado para cualquier uso real y lo que impide que un
 * dedo apoyado en el campo arme un plazo de años.
 */
private const val TIMER_MIN_MINUTES = 1
private const val TIMER_MAX_MINUTES = 600

/** Dígitos que el campo acepta. Sale de [TIMER_MAX_MINUTES]: tres cifras. */
private const val TIMER_MAX_DIGITS = 3

/** Parte del ancho disponible que ocupa el campo de minutos, para que escale con la pantalla. */
private const val TIMER_FIELD_WIDTH_FRACTION = 0.25f

/**
 * Suelo de ese ancho, para que en una pantalla estrecha la fracción no deje el campo por debajo de
 * lo que tiene que alojar: tres cifras más el sufijo "min", con su aire.
 */
private val TimerFieldMinWidth = 88.dp

private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * Hoja del temporizador de apagado: presets de duración + opción de terminar la canción en
 * curso antes de pausar. Con un temporizador activo muestra la cuenta regresiva (tick de
 * [COUNTDOWN_TICK_MS] solo mientras la hoja está abierta) y el botón de cancelar; elegir otro
 * preset lo re-arma.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepTimerSheet(
    state: MusicController.SleepTimerState?,
    onStart: (minutes: Int, finishSong: Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    // **Ninguna acción de dentro cierra la hoja**: armar un temporizador la lleva a la cuenta atrás
    // y cancelarlo la devuelve a los plazos, así que en los dos casos el usuario VE el resultado de
    // lo que pulsó. Se cierra solo con el gesto o el toque fuera, que es lo que `AppModalSheet` ya
    // atiende — por eso aquí ya no hace falta `LocalSheetCloser`.
    // contentDescription izados: los lambdas de `semantics {}` no son @Composable.
    val minutesLabel = stringResource(R.string.sleep_timer_minutes_label)
    val minuteLessDesc = stringResource(R.string.sleep_timer_minute_less)
    val minuteMoreDesc = stringResource(R.string.sleep_timer_minute_more)
    AppModalSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleLarge
            )

            if (state != null) {
                // Cuenta regresiva viva: el tick existe solo mientras la hoja está en
                // composición — cerrarla lo mata, el timer real vive en MusicController.
                // El MISMO reloj con el que se armó el plazo (ver [MusicController.SleepTimerState.endAtMs]):
                // monótono, no wall-clock. Restar dos relojes distintos daría un restante que se
                // mueve solo cuando el sistema corrige la hora.
                var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                LaunchedEffect(state.endAtMs) {
                    while (true) {
                        now = SystemClock.elapsedRealtime()
                        delay(COUNTDOWN_TICK_MS)
                    }
                }
                val remainingMs = (state.endAtMs - now).coerceAtLeast(0L)
                AppSurface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = AppColors.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.awaitingSongEnd) {
                            Text(
                                text = stringResource(R.string.sleep_timer_awaiting_song_end),
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.onPrimaryContainer
                            )
                        } else {
                            Text(
                                text = formatTime(remainingMs),
                                style = MaterialTheme.typography.displaySmallEmphasized,
                                color = AppColors.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.sleep_timer_remaining),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.onPrimaryContainer
                            )
                            if (state.finishSong) {
                                Text(
                                    text = stringResource(R.string.sleep_timer_then_finish_song),
                                    // `onPrimaryContainer` como las otras dos líneas: la jerarquía
                                    // la hace la ESCALA —displaySmallEmphasized, bodyMedium,
                                    // bodySmall—, que ya son tres niveles en tres líneas. Estuvo
                                    // derivado con `rememberSecondaryContentColor`, que existe para
                                    // los contenedores de acento porque M3 no define ningún
                                    // `onXContainerVariant`; aquí no hacía falta ningún secundario.
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // **La hoja NO se cierra al cancelar** (12 sep 2026). `state` llega por
                        // parámetro desde el reproductor, así que al desaparecer el temporizador
                        // esta rama se cambia sola por la de los plazos: el usuario ve el resultado
                        // de lo que acaba de pulsar y puede armar otro sin volver a abrir.
                        // **El par INVERTIDO de la propia tarjeta**, no el tonal por defecto
                        // (device, 12 sep 2026). `appFilledTonalButtonColors` da
                        // `secondaryContainer`, y esta tarjeta es `primaryContainer`: con
                        // Equilibrado los dos comparten tono —medido en la captura, tarjeta y botón
                        // en el MISMO `#424F58`— así que el botón desaparecía y solo se leía por su
                        // texto. Un rol no se separa de sí mismo, y ese helper asume un botón sobre
                        // `surface`, no dentro de un contenedor de acento.
                        //
                        // Invertido es lo único garantizado aquí: el contraste del par lo tabula M3
                        // en los dos sentidos. Mismo criterio que el chip de origen del reproductor
                        // y que la píldora del ⋮ sobre la fila que suena.
                        FilledTonalButton(
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = AppColors.onPrimaryContainer,
                                contentColor = AppColors.primaryContainer
                            ),
                            shapes = ButtonDefaults.shapes(),
                            onClick = onCancel
                        ) {
                            Text(stringResource(R.string.sleep_timer_cancel))
                        }
                    }
                }
            } else {
                // **Las opciones SOLO existen sin temporizador puesto** (12 sep 2026). Estaban
                // fuera del `if`, así que con uno corriendo la hoja enseñaba la cuenta atrás y
                // debajo los seis plazos y el interruptor, o sea los mandos para armar OTRO encima
                // del que ya hay. Con un temporizador activo solo hay una acción posible —
                // cancelarlo—, y esa ya vive en la tarjeta.
                //
                // **Los plazos SELECCIONAN, no arman** (12 sep 2026). Cada uno llamaba a `onStart`
                // en su `onClick`, así que el temporizador empezaba en el mismo toque y el
                // interruptor de abajo solo servía si se marcaba ANTES: elegir 15 min y luego pedir
                // "terminar la canción" era imposible, porque para cuando llegabas al interruptor ya
                // estaba corriendo. Ahora el plazo es un estado, el interruptor lo acompaña y los
                // dos se confirman con el botón del final.
                //
                // `finishSong` vive en esta rama por lo mismo: es un ajuste de lo que se va a armar,
                // no un estado del que ya está armado (ese lo cuenta la tercera línea de la
                // tarjeta). El `state?.finishSong` del valor inicial se queda: al cancelar y volver
                // a abrir, la hoja recuerda cómo estaba puesto el anterior.
                var finishSong by rememberSaveable { mutableStateOf(state?.finishSong ?: false) }
                // **El plazo es un CAMPO y los presets son atajos que lo rellenan.** Con solo seis
                // botones no había forma de pedir 20 minutos; ahora el valor se escribe, se ajusta
                // de uno en uno o se toma de un chip, y los tres caminos escriben lo mismo.
                //
                // El estado es el TEXTO y no un `Int`: mientras se edita, el campo puede estar vacío
                // o traer algo que no es un número, y eso es un estado legítimo del campo que un
                // `Int` no sabe representar. El valor válido se deriva y es el que gobierna los
                // controles — de ahí que `minutes` sea nullable.
                var minutesText by rememberSaveable { mutableStateOf(DEFAULT_TIMER_MINUTES.toString()) }
                // Lo ESCRITO y lo VÁLIDO son dos cosas, y por eso hay dos valores. `typed` es el
                // número que hay en el campo, aunque esté fuera de rango —un 0 o un 900 son estados
                // legítimos mientras se teclea—; `minutes` es el que se puede armar, y es el único
                // que gobierna el botón de iniciar. Los pasos ±1 trabajan sobre `typed`, así que
                // desde un 0 se sale con el `+` en vez de teniendo que borrar.
                val typed = minutesText.toIntOrNull()
                val minutes = typed?.takeIf { it in TIMER_MIN_MINUTES..TIMER_MAX_MINUTES }

                // El campo NO se estira a toda la fila: lo que contiene son tres cifras como mucho,
                // y un cuadro de texto a todo el ancho prometía un dato largo. Ocupa
                // [TIMER_FIELD_WIDTH_FRACTION] del ancho y el grupo se centra, así que los pasos
                // quedan pegados al número en vez de en los extremos.
                //
                // La fracción se mide con `BoxWithConstraints` y no con `fillMaxWidth(f)` dentro de
                // la `Row`: ahí un hijo sin peso recibe como máximo lo que queda DESPUÉS de los
                // hermanos ya medidos, así que la fracción se calcularía sobre el ancho menos el
                // primer botón — un porcentaje de algo que no es la fila.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val fieldWidth = (maxWidth * TIMER_FIELD_WIDTH_FRACTION).coerceAtLeast(TimerFieldMinWidth)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalIconButton(
                            onClick = {
                                typed?.let {
                                    minutesText = (it - 1).coerceIn(TIMER_MIN_MINUTES, TIMER_MAX_MINUTES).toString()
                                }
                            },
                            enabled = typed != null && typed > TIMER_MIN_MINUTES,
                            colors = appFilledTonalIconButtonColors(),
                            shapes = IconButtonDefaults.shapes()
                        ) {
                            MaterialSymbol(
                                "remove",
                                modifier = Modifier.semantics {
                                    contentDescription = minuteLessDesc
                                }
                            )
                        }
                        TextField(
                            value = minutesText,
                            // **El campo solo filtra la FORMA, no el valor**: dígitos y un tope de
                            // largo. Lo dígitos porque el teclado numérico no impide pegar texto, y el
                            // largo porque sin él se pueden teclear cifras que no significan nada.
                            //
                            // Que el número sirva o no —un 0, un 900— lo decide el botón de iniciar, que
                            // se apaga. Bloquear el 0 en la tecla se probó y es peor: rechaza algo que
                            // el usuario está a mitad de escribir y no explica por qué no entra.
                            onValueChange = { new ->
                                if (new.all { it.isDigit() } && new.length <= TIMER_MAX_DIGITS) {
                                    minutesText = new
                                }
                            },
                            // **La unidad va DENTRO como sufijo, no como etiqueta flotante.** Una etiqueta
                            // encima de tres cifras añadía alto y dejaba el campo diciendo su unidad solo
                            // mientras estaba vacío; el sufijo se lee junto al número — "5 min" — y no ocupa
                            // una línea propia.
                            //
                            // La etiqueta era además el NOMBRE ACCESIBLE del campo, y eso el sufijo no lo
                            // sustituye: se conserva como `contentDescription`, así que el lector anuncia el
                            // nombre y luego el valor.
                            // **Con la tipografía del texto de entrada, como manda el spec, y NO más chico.**
                            // Se probó en `bodySmall` (12sp contra 16) y se descartó en device: M3 coloca el
                            // sufijo en la misma `y` que el texto —arriba, no por LÍNEA BASE
                            // (`TextFieldImpl.kt`, `placeRelativeWithLayer(..., yOffset + textPosition)`)—, así
                            // que dos cuerpos distintos dan dos bases distintas y la unidad queda flotando
                            // sobre el número. Compensarlo pedía un desplazamiento a ojo que depende de las
                            // métricas de la fuente.
                            //
                            // Lo que subordina la unidad al dato es el COLOR: el sufijo va en
                            // `onSurfaceVariant` y el número en `onSurface` (ver `appTextFieldColors`), que es
                            // justo el par que M3 ordena.
                            suffix = { Text(stringResource(R.string.sleep_timer_minutes_suffix)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            // **El número CENTRADO en su área y el sufijo donde empieza la suya.** El área de
                            // entrada se estira para ocupar lo que queda del campo, así que la alineación del
                            // número decide cuánto aire queda entre él y la unidad: al inicio lo mandaba al
                            // borde izquierdo y abría un hueco enorme, y al final lo pegaba a "min" sin
                            // respiro. Centrado reparte ese aire a los dos lados, que es lo que hace que se
                            // lean como un valor con su unidad y no como dos palabras.
                            //
                            // Por eso el ancho del campo es AJUSTADO ([TIMER_FIELD_WIDTH_FRACTION]): cuanto más
                            // ancho, más aire hay que repartir y más se separan.
                            textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center),
                            colors = appTextFieldColors(),
                            modifier = Modifier
                                .width(fieldWidth)
                                .semantics { contentDescription = minutesLabel }
                        )
                        FilledTonalIconButton(
                            onClick = {
                                typed?.let {
                                    minutesText = (it + 1).coerceIn(TIMER_MIN_MINUTES, TIMER_MAX_MINUTES).toString()
                                }
                            },
                            enabled = typed != null && typed < TIMER_MAX_MINUTES,
                            colors = appFilledTonalIconButtonColors(),
                            shapes = IconButtonDefaults.shapes()
                        ) {
                            MaterialSymbol(
                                "add",
                                modifier = Modifier.semantics {
                                    contentDescription = minuteMoreDesc
                                }
                            )
                        }
                    }
                }

                // Atajos. **`FilterChip` y no un botón**: no ejecutan nada, marcan cuál de los
                // valores habituales tiene el campo — y por eso el que coincide sale seleccionado
                // aunque el número se haya escrito a mano.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TIMER_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = minutes == preset,
                            onClick = { minutesText = preset.toString() },
                            colors = appFilterChipColors(),
                            label = { Text(stringResource(R.string.sleep_timer_minutes, preset)) }
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.sleep_timer_finish_song),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(end = 16.dp)
                    )
                    Switch(
                        colors = appSwitchColors(),
                        checked = finishSong,
                        onCheckedChange = { finishSong = it }
                    )
                }

                // La ACCIÓN de la hoja, y por eso es el botón lleno de `primary`: los plazos y el
                // interruptor solo preparan. Deshabilitado sin plazo elegido —no hay nada que armar—
                // y no se cierra al pulsarlo: la hoja pasa a la cuenta atrás, que es la confirmación
                // de que quedó puesto.
                Button(
                    onClick = { minutes?.let { onStart(it, finishSong) } },
                    enabled = minutes != null,
                    colors = appButtonColors(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.sleep_timer_start))
                }
            }
        }
    }
}
