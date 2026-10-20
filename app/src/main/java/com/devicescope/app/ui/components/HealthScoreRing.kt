package com.devicescope.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HealthScoreRing(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 116.dp,
) {
    val safeScore = score.coerceIn(0, 100)
    val trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)
    val progressColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 9.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * safeScore / 100f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = safeScore.toString(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 31.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "/ 100",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
