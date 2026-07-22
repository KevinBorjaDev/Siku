package com.qhana.siku.ui.components

import android.content.res.AssetManager
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Componente para renderizar iconos de Material Symbols (Variable Font).
 * Permite personalizar peso, relleno, grado y tamaño óptico.
 *
 * @param icon El nombre del icono (ligadura) ej. "search", "play_arrow", "home".
 * @param fill Si el icono debe estar relleno (true) o delineado (false).
 * @param weight El peso de la fuente (100 a 700). Default 400.
 * @param grade El grado (-25 a 200). Ajuste fino del grosor. Default 0.
 * @param opticalSize El tamaño óptico (20 a 48). Debe coincidir aprox con el tamaño visual. Default 24.
 */
@Composable
fun MaterialSymbol(
    icon: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 24.sp,
    color: Color = LocalContentColor.current,
    fill: Boolean = false,
    weight: Int = 400,
    grade: Int = 0,
    opticalSize: Int = 24
) {
    val context = LocalContext.current
    val fillValue = if (fill) 1f else 0f

    // Cache del font con una key estable (context no cambia durante la sesión)
    val font = remember(fillValue, weight, grade, opticalSize) {
        createMaterialSymbolFontFamily(context.assets, fillValue, weight, grade, opticalSize)
    }

    // Cache del TextStyle base para evitar recrearlo
    val textStyle = remember(size) {
        TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeight = size,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None
            )
        )
    }

    Text(
        text = icon,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontFamily = font,
        textAlign = TextAlign.Center,
        style = textStyle
    )
}

private fun createMaterialSymbolFontFamily(
    assetManager: AssetManager,
    fill: Float,
    weight: Int,
    grade: Int,
    opticalSize: Int
): FontFamily {
    return FontFamily(
        Font(
            path = "fonts/material_symbols_rounded.ttf",
            assetManager = assetManager,
            variationSettings = FontVariation.Settings(
                FontVariation.Setting("FILL", fill),
                FontVariation.Setting("wght", weight.toFloat()),
                FontVariation.Setting("GRAD", grade.toFloat()),
                FontVariation.Setting("opsz", opticalSize.toFloat())
            )
        )
    )
}
