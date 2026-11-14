package com.example.myapplication

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class Glyph { HOME, PHOTO, LIVE, WARDROBE, SETTINGS, ADD, ARROW, BACK, CHECK, CLOSE, SAVE, REFRESH }

@Composable
internal fun StudioIcon(
    glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current
) {
    Canvas(modifier.size(24.dp).then(if (description == null) Modifier else Modifier.semantics {
        contentDescription = description
    })) {
        val line = Stroke(width = 1.7f, cap = StrokeCap.Round)
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun segment(x: Float, y: Float, x2: Float, y2: Float) =
                drawLine(tint, Offset(x, y), Offset(x2, y2), 1.7f, StrokeCap.Round)
            when (glyph) {
                Glyph.HOME -> {
                    val path = Path().apply {
                        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
                        moveTo(5f, 9f); lineTo(5f, 21f); lineTo(10f, 21f); lineTo(10f, 14f)
                        lineTo(14f, 14f); lineTo(14f, 21f); lineTo(19f, 21f); lineTo(19f, 9f)
                    }
                    drawPath(path, tint, style = line)
                }
                Glyph.PHOTO -> {
                    drawRoundRect(tint, Offset(3f, 3f), Size(18f, 18f), CornerRadius(3f), style = line)
                    drawCircle(tint, 2f, Offset(8f, 8f), style = line)
                    val path = Path().apply { moveTo(4f, 18f); lineTo(10f, 12f); lineTo(14f, 16f); lineTo(18f, 11f); lineTo(21f, 14f) }
                    drawPath(path, tint, style = line)
                }
                Glyph.LIVE -> {
                    drawRoundRect(tint, Offset(3f, 5f), Size(13f, 14f), CornerRadius(3f), style = line)
                    val path = Path().apply { moveTo(16f, 10f); lineTo(21f, 7f); lineTo(21f, 17f); lineTo(16f, 14f) }
                    drawPath(path, tint, style = line)
                }
                Glyph.WARDROBE -> {
                    val path = Path().apply {
                        moveTo(9f, 7f); cubicTo(9f, 3f, 15f, 3f, 15f, 7f)
                        cubicTo(15f, 9f, 12f, 9f, 12f, 11f)
                        lineTo(21f, 17f); quadraticTo(22f, 19f, 20f, 19f); lineTo(4f, 19f)
                        quadraticTo(2f, 19f, 3f, 17f); lineTo(12f, 11f)
                    }
                    drawPath(path, tint, style = line)
                }
                Glyph.SETTINGS -> {
                    segment(4f, 6f, 20f, 6f); segment(4f, 12f, 20f, 12f); segment(4f, 18f, 20f, 18f)
                    listOf(Offset(9f, 6f), Offset(15f, 12f), Offset(9f, 18f)).forEach {
                        drawCircle(Cream, 2.5f, it); drawCircle(tint, 2.5f, it, style = line)
                    }
                }
                Glyph.ADD -> { segment(12f, 5f, 12f, 19f); segment(5f, 12f, 19f, 12f) }
                Glyph.ARROW -> { segment(4f, 12f, 20f, 12f); segment(14f, 6f, 20f, 12f); segment(14f, 18f, 20f, 12f) }
                Glyph.BACK -> { segment(20f, 12f, 4f, 12f); segment(10f, 6f, 4f, 12f); segment(10f, 18f, 4f, 12f) }
                Glyph.CHECK -> { segment(4f, 12f, 9f, 17f); segment(9f, 17f, 20f, 6f) }
                Glyph.CLOSE -> { segment(6f, 6f, 18f, 18f); segment(18f, 6f, 6f, 18f) }
                Glyph.SAVE -> {
                    segment(12f, 3f, 12f, 15f); segment(7f, 10f, 12f, 15f); segment(17f, 10f, 12f, 15f)
                    val path = Path().apply { moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f) }
                    drawPath(path, tint, style = line)
                }
                Glyph.REFRESH -> {
                    drawArc(tint, 35f, 290f, false, Offset(4f, 4f), Size(16f, 16f), style = line)
                    segment(20f, 3f, 20f, 9f); segment(14f, 9f, 20f, 9f)
                }
            }
        }
    }
}

@Composable
internal fun StudioPage(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 880.dp).fillMaxSize().consumeWindowInsets(padding),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 20.dp,
                end = padding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) + 20.dp,
                top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            content = content
        )
    }
}

@Composable
internal fun PageTitle(eyebrow: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = Wine)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}

@Composable
internal fun StudioCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth().animateContentSize(), color = Color.White,
        shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Color(0xFFEDE5E5))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
internal fun StatusPill(label: String, ready: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, color = if (ready) Color(0xFFE7EEE5) else Blush, shape = CircleShape) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).background(if (ready) Sage else Wine, CircleShape))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (ready) Sage else Wine)
        }
    }
}

@Composable
internal fun PhotoTile(label: String, photo: PhotoAsset?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFFF1EBE7)), contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo.bitmap.asImageBitmap(), label, Modifier.fillMaxSize().padding(6.dp),
            contentScale = ContentScale.Fit)
        else Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StudioIcon(if (label == "Ảnh của tôi") Glyph.PHOTO else Glyph.WARDROBE, tint = Wine)
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
        }
    }
}

@Composable
internal fun EmptyState(glyph: Glyph, title: String, description: String, action: String, onAction: () -> Unit) {
    StudioCard {
        Box(Modifier.size(52.dp).background(Blush, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            StudioIcon(glyph, tint = Wine)
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun KindSelector(vm: MirrorViewModel, enabled: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GarmentKind.entries.forEach { kind ->
            FilterChip(selected = vm.kind == kind, onClick = { vm.selectKind(kind) }, enabled = enabled,
                label = { Text(kind.label) },
                leadingIcon = if (vm.kind == kind) ({ StudioIcon(Glyph.CHECK, modifier = Modifier.size(16.dp)) }) else null)
        }
    }
}

@Composable
internal fun StepHeading(number: String, title: String, complete: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(color = if (complete) Color(0xFFE7EEE5) else Blush, shape = CircleShape) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                if (complete) StudioIcon(Glyph.CHECK, modifier = Modifier.size(16.dp), tint = Sage)
                else Text(number, style = MaterialTheme.typography.labelMedium, color = Wine)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
}
