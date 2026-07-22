package com.qhana.siku.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun rememberListItemShape(
    index: Int,
    count: Int,
    isActive: Boolean = false,
    activeRadius: Dp = 16.dp,
    inactiveRadius: Dp = 4.dp,
    pronouncedRadius: Dp = 24.dp
): androidx.compose.ui.graphics.Shape {
    return remember(index, count, isActive) {
        if (isActive) {
            RoundedCornerShape(activeRadius)
        } else if (count == 1) {
            RoundedCornerShape(pronouncedRadius)
        } else {
            when (index) {
                0 -> RoundedCornerShape(
                    topStart = pronouncedRadius,
                    topEnd = pronouncedRadius,
                    bottomStart = inactiveRadius,
                    bottomEnd = inactiveRadius
                )
                count - 1 -> RoundedCornerShape(
                    topStart = inactiveRadius,
                    topEnd = inactiveRadius,
                    bottomStart = pronouncedRadius,
                    bottomEnd = pronouncedRadius
                )
                else -> RoundedCornerShape(inactiveRadius)
            }
        }
    }
}
