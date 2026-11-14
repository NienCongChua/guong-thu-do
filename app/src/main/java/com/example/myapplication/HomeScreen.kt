package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(
    vm: MirrorViewModel, padding: PaddingValues, navigate: (Destination) -> Unit, openResult: (PhotoResult) -> Unit
) {
    StudioPage(padding) {
        item { HomeHero { navigate(Destination.PHOTO) } }
        item {
            Text("Phòng thử của bạn", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureCard("Thử bằng ảnh", "Hai bức ảnh. Một diện mạo mới.", "Bắt đầu thử", Glyph.PHOTO,
                    Modifier.weight(1f).widthIn(min = 150.dp), { navigate(Destination.PHOTO) })
                FeatureCard("Gương trực tiếp", "Ngắm trang phục khi bạn chuyển động.", "Mở phòng thử", Glyph.LIVE,
                    Modifier.weight(1f).widthIn(min = 150.dp), { navigate(Destination.LIVE) })
            }
        }
        if (!vm.connected) item {
            Surface(onClick = { navigate(Destination.CONNECTION) }, shape = RoundedCornerShape(20.dp), color = Blush) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudioIcon(Glyph.SETTINGS, tint = Wine)
                    Column(Modifier.weight(1f)) {
                        Text("Chuẩn bị cho lần thử đầu tiên", style = MaterialTheme.typography.titleMedium)
                        Text("Kết nối dịch vụ để bắt đầu tạo ảnh.", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    StudioIcon(Glyph.ARROW, tint = Wine)
                }
            }
        }
        if (vm.photoBusy) item {
            Surface(onClick = { navigate(Destination.PHOTO) }, shape = RoundedCornerShape(20.dp), color = Blush) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Ảnh của bạn đang được tạo · xem tiến trình", Modifier.weight(1f))
                    StudioIcon(Glyph.ARROW)
                }
            }
        }
        if (vm.history.isNotEmpty()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Những lần thử gần đây", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { navigate(Destination.WARDROBE) }) { Text("Xem tất cả") }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(vm.history, key = { it.id }) { output ->
                    OutlinedCard(onClick = { openResult(output) }, shape = RoundedCornerShape(20.dp)) {
                        Image(output.bitmap.asImageBitmap(), "Xem kết quả mặc thử từ ${output.provider}",
                            Modifier.width(126.dp).height(168.dp).background(Color.White), contentScale = ContentScale.Fit)
                        Text(output.provider, Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        item {
            StudioCard {
                Text("Bắt đầu thật đơn giản", style = MaterialTheme.typography.titleMedium)
                GuideStep("01", "Chọn ảnh rõ nét", "Ảnh người nhìn thẳng, trang phục đủ sáng và ít bị che.")
                GuideStep("02", "Tìm phong cách mới", "Chọn áo hoặc váy, sau đó tạo ảnh hay mở gương.")
                GuideStep("03", "So sánh & lưu lại", "Đối chiếu ảnh trước và sau, lưu diện mạo bạn thích.")
            }
        }
        item { Text("Ảnh AI mang tính tham khảo. Màu sắc, họa tiết và độ vừa vặn có thể khác sản phẩm thực tế.",
            color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun HomeHero(onStart: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(
        Brush.linearGradient(listOf(Color(0xFF593244), Color(0xFF825568))))) {
        val titleStyle = if (maxWidth < 350.dp) MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp)
            else MaterialTheme.typography.headlineLarge
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Color.White.copy(alpha = 0.055f), size.width * 0.44f, Offset(size.width, size.height * 0.4f))
            drawCircle(Color(0xFFEBD2B8).copy(alpha = 0.12f), size.width * 0.32f,
                Offset(size.width * 0.96f, size.height * 0.44f), style = Stroke(1.2.dp.toPx()))
            val w = size.width
            val h = size.height
            val seam = Path().apply {
                moveTo(w * 0.77f, h * 0.08f)
                cubicTo(w * 0.60f, h * 0.30f, w * 1.10f, h * 0.51f, w * 0.83f, h * 0.83f)
            }
            drawPath(seam, Color(0xFFEBD2B8).copy(alpha = 0.27f), style = Stroke(1.dp.toPx()))
        }
        Column(Modifier.padding(26.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PHÒNG THỬ CÁ NHÂN / AI", color = Color(0xFFE5C8CD), style = MaterialTheme.typography.labelSmall)
            Text("Mặc thử.\nThấy khác biệt.", style = titleStyle, color = Color.White)
            Text("Khám phá cách một món đồ\ntrở thành phong cách của bạn.",
                color = Color(0xFFF0DDE2), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Cream, contentColor = Wine),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
                Text("Thử phong cách mới"); Spacer(Modifier.width(14.dp)); StudioIcon(Glyph.ARROW, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, description: String, action: String, glyph: Glyph, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp), color = Color.White) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(42.dp).background(Blush, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                StudioIcon(glyph, tint = Wine)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, minLines = 2)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted, minLines = 3)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action, color = Wine, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                StudioIcon(Glyph.ARROW, modifier = Modifier.size(18.dp), tint = Wine)
            }
        }
    }
}

@Composable
private fun GuideStep(number: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = CircleShape, color = Cream) {
            Text(number, Modifier.padding(9.dp), color = Wine, style = MaterialTheme.typography.labelMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Preview(name = "Compact phone", widthDp = 360, showBackground = true)
@Preview(name = "Phone", widthDp = 390, showBackground = true)
@Preview(name = "Tablet", widthDp = 800, showBackground = true)
@Composable
private fun HomeHeroPreview() {
    MirrorTheme { Box(Modifier.background(Cream).padding(20.dp)) { HomeHero {} } }
}
