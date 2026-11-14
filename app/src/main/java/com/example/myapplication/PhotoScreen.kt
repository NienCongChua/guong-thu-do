package com.example.myapplication

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun PhotoScreen(
    vm: MirrorViewModel, padding: PaddingValues,
    captureMe: () -> Unit, chooseMe: () -> Unit, captureGarment: () -> Unit, chooseGarment: () -> Unit,
    connect: () -> Unit, openResult: (PhotoResult) -> Unit
) {
    val enabled = !vm.photoBusy && !vm.loadingPhoto && !vm.pendingCloudRequest
    var confirmNewAttempt by rememberSaveable { mutableStateOf(false) }
    var showTips by rememberSaveable { mutableStateOf(false) }
    StudioPage(padding) {
        item { PageTitle("THỬ BẰNG ẢNH", "Một diện mạo mới.", "Chọn ảnh của bạn và món đồ yêu thích. Để AI giúp bạn hình dung phần còn lại.") }
        if (vm.pendingCloudRequest && !vm.photoBusy) item {
            StudioCard {
                Text("Lần thử đã gửi", style = MaterialTheme.typography.titleMedium)
                Text("Kiểm tra tác vụ trên cloud để tiếp tục mà không gửi tạo ảnh lần nữa.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = vm::resumeCloudPhoto) { Text("Tiếp tục theo dõi") }
                OutlinedButton(onClick = vm::cancelPhoto) { Text("Hủy tác vụ") }
                if (vm.cloudJob == null) TextButton(onClick = { confirmNewAttempt = true }) { Text("Bỏ yêu cầu chưa xác nhận") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                StepHeading("01", "Chuẩn bị hai bức ảnh", vm.person != null && vm.garment != null)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhotoInput("Ảnh của tôi", vm.person, enabled, captureMe, chooseMe, Modifier.weight(1f))
                    PhotoInput("Trang phục", vm.garment, enabled, captureGarment, chooseGarment, Modifier.weight(1f))
                }
                TextButton(onClick = { showTips = !showTips }) { Text(if (showTips) "Ẩn mẹo chọn ảnh" else "Ảnh thế nào cho đẹp?") }
                AnimatedVisibility(showTips) {
                    Text("• Ảnh người chụp thẳng, thấy rõ thân trên hoặc toàn thân.\n• Ảnh trang phục đủ sáng, ít nếp gấp và không bị vật khác che.\n• Chọn đúng Áo hoặc Váy để AI xử lý đúng vùng.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            StudioCard {
                StepHeading("02", "Tùy chỉnh lần thử")
                Text("Loại trang phục", style = MaterialTheme.typography.labelLarge)
                KindSelector(vm, enabled)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Bạn ưu tiên điều gì?", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoQuality.entries.forEach { quality ->
                        FilterChip(selected = vm.quality == quality, onClick = { vm.selectQuality(quality) },
                            enabled = enabled, label = { Text(quality.label) },
                            leadingIcon = if (vm.quality == quality) ({ StudioIcon(Glyph.CHECK, modifier = Modifier.size(16.dp)) }) else null)
                    }
                }
                Text(if (vm.quality == PhotoQuality.FAST) "Tạo bản xem nhanh. Chi tiết vải và họa tiết có thể đơn giản hơn."
                    else "Ưu tiên chi tiết trang phục. Thời gian tạo có thể lâu hơn.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if (vm.capabilities?.fallbackAvailable == true) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Dùng API dự phòng", style = MaterialTheme.typography.labelLarge)
                            Text("Cho phép gửi hai ảnh đến FASHN khi OpenAI lỗi dịch vụ hoặc hết hạn mức.",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                        Switch(checked = vm.allowFallback, onCheckedChange = vm::setFallback, enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = "Cho phép API dự phòng FASHN" })
                    }
                }
            }
        }
        item {
            if (vm.photoBusy) GenerationStatus(
                vm.photoSeconds, vm.allowFallback && vm.capabilities?.fallbackAvailable == true,
                cloudStatus = if (vm.usingCloud) when (vm.cloudJob?.status) {
                    "queued" -> "Đang xếp hàng xử lý"
                    "processing" -> "AI đang tạo diện mạo mới"
                    "completed" -> "Đang tải ảnh kết quả"
                    else -> "Đang tải ảnh lên cloud"
                } else null,
                onCancel = vm::cancelPhoto
            )
            else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val provider = vm.capabilities?.imageProvider?.takeIf { it.isNotBlank() } ?: "dịch vụ AI"
                Text("Bấm Tạo ảnh sẽ gửi hai ảnh đến dịch vụ và $provider${if (vm.allowFallback && vm.capabilities?.fallbackAvailable == true) ", có thể chuyển sang FASHN" else ""}. Mỗi lần gửi dùng một lượt trong hạn mức.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if (vm.usingCloud) Text("Ảnh đầu vào được lưu riêng tư tạm thời và xóa sau xử lý. Kết quả được lưu riêng theo tài khoản trong 7 ngày; bạn có thể xóa sớm ở Tủ đồ.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (!vm.connected) connect() else vm.createPhoto() },
                    enabled = !vm.loadingPhoto && !vm.loginBusy && (!vm.connected || vm.pendingCloudRequest || (vm.person != null && vm.garment != null)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (!vm.connected) "Đăng nhập để bắt đầu" else if (vm.pendingCloudRequest) "Theo dõi lần thử đã gửi" else "Tạo ảnh mặc thử")
                    Spacer(Modifier.width(12.dp)); StudioIcon(Glyph.ARROW, modifier = Modifier.size(18.dp))
                }
                if (vm.connected && (vm.person == null || vm.garment == null))
                    Text("Chọn đủ ảnh người và trang phục để tiếp tục.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.result?.let { output -> item {
            OutlinedButton(onClick = { openResult(output) }, modifier = Modifier.fillMaxWidth()) { Text("Xem kết quả gần nhất") }
        } }
        item { Text("Kết quả AI mang tính tham khảo. Hãy đối chiếu cổ áo, tay áo, họa tiết và bàn tay.",
            color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmNewAttempt) AlertDialog(
        onDismissRequest = { confirmNewAttempt = false },
        title = { Text("Bắt đầu một lần thử mới?") },
        text = { Text("Chưa xác nhận được trạng thái yêu cầu trước. Hãy kiểm tra Tủ đồ trước khi bỏ yêu cầu; nếu cloud đã nhận, lần thử cũ vẫn có thể xử lý và dùng lượt.") },
        confirmButton = { TextButton(onClick = { confirmNewAttempt = false; vm.forgetUnconfirmedCloudRequest() }) { Text("Bỏ yêu cầu trên máy") } },
        dismissButton = { TextButton(onClick = { confirmNewAttempt = false }) { Text("Quay lại kiểm tra") } }
    )
}

@Composable
private fun PhotoInput(
    label: String, photo: PhotoAsset?, enabled: Boolean, capture: () -> Unit, choose: () -> Unit, modifier: Modifier
) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = Color.White) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            PhotoTile(label, photo, Modifier.fillMaxWidth().aspectRatio(0.85f))
            FilledTonalButton(onClick = choose, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                Text(if (photo == null) "Chọn ảnh" else "Đổi ảnh")
            }
            TextButton(onClick = capture, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Chụp ảnh") }
        }
    }
}

@Composable
internal fun GenerationStatus(seconds: Int, fallbackAllowed: Boolean, cloudStatus: String? = null, onCancel: () -> Unit) {
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
            Column {
                Text(cloudStatus ?: "Đang tạo diện mạo mới", style = MaterialTheme.typography.titleMedium)
                Text("Đã chờ ${seconds}s", style = MaterialTheme.typography.labelMedium, color = Wine)
            }
        }
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (seconds < 45) "Đang gửi ảnh và chờ dịch vụ xử lý. Bạn có thể xem tủ đồ trong lúc chờ."
            else "Dịch vụ vẫn đang xử lý. Chưa có kết quả trả về; bạn có thể tiếp tục chờ hoặc hủy.",
            style = MaterialTheme.typography.bodyMedium, color = Muted)
        if (fallbackAllowed) Text("Đã cho phép OpenAI → FASHN khi có lỗi dịch vụ xác định.",
            style = MaterialTheme.typography.bodySmall, color = Muted)
        TextButton(onClick = onCancel) { Text("Hủy chờ kết quả") }
        Text(if (cloudStatus == null) "Hủy sẽ ngắt chờ trong ứng dụng; dịch vụ có thể đã nhận và tính phí yêu cầu."
            else "Cloud lưu tiến trình để bạn có thể quay lại. Tác vụ đã gửi đến AI vẫn có thể dùng lượt khi hủy.",
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
internal fun ResultScreen(vm: MirrorViewModel, padding: PaddingValues, back: () -> Unit) {
    val output = vm.result
    if (output == null) {
        StudioPage(padding) {
            item { EmptyState(Glyph.PHOTO, "Chưa có kết quả", "Chọn hai ảnh để bắt đầu một lần thử mới.", "Về phòng thử", back) }
        }
        return
    }
    val original = output.before
    var split by rememberSaveable(output.id) { mutableFloatStateOf(0.5f) }
    StudioPage(padding) {
        item { PageTitle("KẾT QUẢ CỦA BẠN", "Phong cách, phiên bản mới.",
            if (original != null) "Kéo thanh so sánh để xem ảnh gốc và ảnh mặc thử."
            else "Kết quả đã được tải từ cloud. Ảnh gốc của lần thử này không còn trong phiên máy.") }
        item {
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(max = 650.dp).aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(26.dp)).background(Color(0xFFECE5E1))) {
                    Image(output.bitmap.asImageBitmap(), "Ảnh mặc thử từ ${output.provider}",
                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (original != null) {
                        Image(original.bitmap.asImageBitmap(), "Ảnh gốc, chiếm ${(split * 100).roundToInt()} phần trăm vùng so sánh",
                            Modifier.fillMaxSize().drawWithContent {
                                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
                            }, contentScale = ContentScale.Fit)
                        Box(Modifier.fillMaxWidth(split).fillMaxHeight()) {
                            Box(Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                        }
                    }
                    Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        if (original != null) CompareLabel("Ảnh gốc")
                        CompareLabel("Mặc thử")
                    }
                }
                if (original != null) {
                    Slider(value = split, onValueChange = { split = it }, valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Tỷ lệ ảnh gốc trong vùng so sánh" })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = split == 1f, onClick = { split = 1f }, label = { Text("Ảnh gốc") })
                        FilterChip(selected = split == 0.5f, onClick = { split = 0.5f }, label = { Text("So sánh") })
                        FilterChip(selected = split == 0f, onClick = { split = 0f }, label = { Text("Kết quả") })
                    }
                }
            }
        }
        item {
            StudioCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Tạo bởi ${output.provider}", style = MaterialTheme.typography.titleMedium)
                    Text("${output.elapsedMs / 1000}s", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                if (output.fallbackUsed) Text("Đã sử dụng FASHN dự phòng do OpenAI tạm thời không khả dụng.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                val saved = output.id in vm.savedResultIds
                Button(onClick = vm::saveResult, enabled = !vm.savingPhoto && !saved,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                    StudioIcon(if (saved) Glyph.CHECK else Glyph.SAVE, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (saved) "Đã lưu vào thư viện" else if (vm.savingPhoto) "Đang lưu…" else "Lưu ảnh mặc thử")
                }
                OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth()) { Text("Tiếp tục thử đồ") }
                Text("Ảnh được lưu là toàn bộ kết quả AI, không bao gồm đường chia so sánh.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun CompareLabel(label: String) {
    Surface(color = Ink.copy(alpha = 0.72f), shape = RoundedCornerShape(10.dp)) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color.White,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Preview(name = "Creating", widthDp = 390, showBackground = true)
@Composable
private fun GenerationStatusPreview() {
    MirrorTheme { Box(Modifier.padding(20.dp)) { GenerationStatus(12, false) {} } }
}
