package com.vclient.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vclient.android.data.Announcement
import com.vclient.android.ui.theme.Critical
import com.vclient.android.ui.theme.Gold
import com.vclient.android.ui.theme.Info
import com.vclient.android.ui.theme.Warning

/** The VYRN mark: a gold tile with a dark V, matching the logo on the website and Windows launcher. */
@Composable
fun VLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            color = Gold,
            size = Size(s, s),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.2f),
        )
        val v = Path().apply {
            moveTo(s * 0.22f, s * 0.26f)
            lineTo(s * 0.42f, s * 0.26f)
            lineTo(s * 0.5f, s * 0.58f)
            lineTo(s * 0.58f, s * 0.26f)
            lineTo(s * 0.78f, s * 0.26f)
            lineTo(s * 0.6f, s * 0.78f)
            lineTo(s * 0.4f, s * 0.78f)
            close()
        }
        drawPath(v, Color(0xFF101321))
        drawRect(Color(0xFFFFF0BD), topLeft = Offset(s * 0.72f, s * 0.08f), size = Size(s * 0.18f, s * 0.18f))
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AnnouncementBanner(announcement: Announcement) {
    val background = when (announcement.level) {
        "critical" -> Critical
        "warning" -> Warning
        else -> Info
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(announcement.message, style = MaterialTheme.typography.bodyMedium, color = Color.White)
    }
}
