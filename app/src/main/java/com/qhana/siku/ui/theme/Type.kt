package com.qhana.siku.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun rememberAppTypography(): Typography {
    val context = LocalContext.current
    val assets = context.assets

    val googleSansFlex = remember(assets) {
        FontFamily(
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(400),
                    FontVariation.Setting("ROND", 100f) // Adjusted roundness to 75
                )
            ),
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                weight = FontWeight.Medium,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(500),
                    FontVariation.Setting("ROND", 100f)
                )
            ),
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                weight = FontWeight.SemiBold,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(600),
                    FontVariation.Setting("ROND", 100f)
                )
            ),
            Font(
                path = "fonts/GoogleSansFlex.ttf",
                assetManager = assets,
                weight = FontWeight.Bold,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(700),
                    FontVariation.Setting("ROND", 100f)
                )
            )
        )
    }

    return remember(googleSansFlex) {
        Typography(
            displayLarge = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium, // Changed from Bold
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.25).sp
            ),
            displayMedium = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium, // Changed from Bold
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = 0.sp
            ),
            displaySmall = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = 0.sp
            ),
            headlineLarge = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium, // Changed from Bold
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp
            ),
            headlineMedium = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.sp
            ),
            headlineSmall = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                letterSpacing = 0.sp
            ),
            titleLarge = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.sp
            ),
            titleMedium = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp
            ),
            titleSmall = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp
            ),
            bodyLarge = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp
            ),
            bodyMedium = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp
            ),
            bodySmall = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.4.sp
            ),
            labelLarge = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp
            ),
            labelMedium = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            ),
            labelSmall = TextStyle(
                fontFamily = googleSansFlex,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}

