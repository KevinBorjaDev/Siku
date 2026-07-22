package com.qhana.siku.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.qhana.siku.ui.components.shimmerEffect

@Composable
fun NowPlayingSkeleton(
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean
) {
    val baseColor = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFF5F5F5)
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(baseColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Album Art Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20.dp))
                .shimmerEffect()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Info Skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .width(200.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .width(150.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .shimmerEffect()
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Slider Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .shimmerEffect()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.width(40.dp).height(12.dp).clip(RoundedCornerShape(2.dp)).shimmerEffect())
            Box(modifier = Modifier.width(40.dp).height(12.dp).clip(RoundedCornerShape(2.dp)).shimmerEffect())
        }

        Spacer(modifier = Modifier.weight(2f))

        // Controls Skeleton
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(68.dp).clip(CircleShape).shimmerEffect())
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(32.dp)).shimmerEffect())
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.size(68.dp).clip(CircleShape).shimmerEffect())
        }

        Spacer(modifier = Modifier.weight(2f))

        // Bottom Actions Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(50.dp))
                .shimmerEffect()
        )
    }
}