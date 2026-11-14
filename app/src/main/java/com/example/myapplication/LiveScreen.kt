package com.example.myapplication

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveScreen(
    vm: MirrorViewModel, padding: PaddingValues, capture: () -> Unit, choose: () -> Unit,
    start: () -> Unit, photo: () -> Unit
) {
    val canChoose = !vm.photoBusy && !vm.loadingPhoto && (!vm.active || vm.phase == LivePhase.LIVE)
    StudioPage(padding) {
        item { PageTitle("GƯƠNG TRỰC TIẾP", "Chuyển động cùng bạn.", "Chọn trang phục, đứng đủ xa camera và ngắm phong cách mới theo từng chuyển động.") }
        item { LiveMirror(vm) }
        item {
            StudioCard {
                StepHeading("01", "Trang phục đang thử", vm.garment != null)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhotoTile("Trang phục", vm.garment, Modifier.width(90.dp).height(112.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (vm.garment == null) "Chọn món đồ đầu tiên" else "Sẵn sàng cho lần thử mới",
                            style = MaterialTheme.typography.titleMedium)
                        KindSelector(vm, canChoose)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = choose, enabled = canChoose, modifier = Modifier.weight(1f)) { Text("Thư viện ảnh") }
                    OutlinedButton(onClick = capture, enabled = canChoose, modifier = Modifier.weight(1f)) { Text("Chụp ảnh") }
                }
                if (vm.recent.size > 1) {
                    Text("Chạm để đổi trang phục", color = Muted, style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(vm.recent, key = { it.id }) { asset ->
                            OutlinedCard(onClick = { vm.selectGarment(asset) }, enabled = canChoose,
                                border = androidx.compose.foundation.BorderStroke(
                                    if (asset.id == vm.garment?.id) 2.dp else 1.dp,
                                    if (asset.id == vm.garment?.id) Wine else MaterialTheme.colorScheme.outlineVariant
                                ), shape = RoundedCornerShape(16.dp)) {
                                Image(asset.bitmap.asImageBitmap(), "Đổi sang trang phục đã chọn",
                                    Modifier.size(70.dp).padding(5.dp), contentScale = ContentScale.Fit)
                            }
                        }
                    }
                }
                if (vm.active) Text("Đổi ảnh ở đây để thay đồ trong phiên. Chụp bằng camera ngoài hoặc rời màn hình sẽ dừng gương.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (!vm.active) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = start, enabled = !vm.photoBusy && !vm.loadingPhoto && !vm.loginBusy &&
                    (!vm.connected || vm.garment != null), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    StudioIcon(Glyph.LIVE, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (vm.connected) "Mở gương thử đồ" else "Kết nối để mở gương")
                }
                Text("Camera trước · Không thu âm · Tự dừng theo giới hạn phiên. Video được xử lý bởi Decart và có thể phát sinh phí.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (vm.photoBusy) Text("Đang tạo ảnh. Chờ ảnh hoàn tất hoặc hủy trong màn Thử ảnh để mở gương.",
                    style = MaterialTheme.typography.bodySmall, color = Wine)
                OutlinedButton(onClick = photo, modifier = Modifier.fillMaxWidth()) { Text("Chuyển sang thử bằng ảnh") }
            }
        }
    }
}

@Composable
private fun LiveMirror(vm: MirrorViewModel) {
    var original by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm.phase) { if (vm.phase != LivePhase.LIVE) original = false }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 480.dp).aspectRatio(0.85f)
            .clip(RoundedCornerShape(28.dp)).background(Ink), contentAlignment = Alignment.Center) {
            val output = vm.remote
            if (output != null) key(vm.rendererEpoch) {
                val epoch = vm.rendererEpoch
                LiveVideo(output, Modifier.fillMaxSize().alpha(if (vm.phase == LivePhase.LIVE && !original) 1f else 0f)) {
                    vm.onRemoteRendered(epoch)
                }
            }
            val preview = vm.local
            val preparing = vm.phase == LivePhase.CONNECTING || vm.phase == LivePhase.WAITING
            if (preview != null && (preparing || original)) LiveVideo(preview, Modifier.fillMaxSize())
            if (vm.phase == LivePhase.IDLE) Column(
                Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StudioIcon(Glyph.LIVE, modifier = Modifier.size(42.dp), tint = Color(0xFFE6C8D1))
                Text("Gương của bạn", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Chọn một chiếc áo hoặc váy,\nrồi mở gương để bắt đầu.",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE6D7DE))
                StatusPill("Camera trước · Không thu âm", false)
            }
            androidx.compose.animation.AnimatedVisibility(vm.active && vm.phase != LivePhase.LIVE, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.74f)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(when (vm.phase) {
                        LivePhase.CONNECTING -> "Đang kết nối phòng thử"
                        LivePhase.WAITING -> "Đang chờ khung hình AI"
                        LivePhase.CHANGING -> "Đang thay trang phục"
                        else -> "Đang chuẩn bị gương"
                    }, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(if (preparing) "Camera gốc chỉ để chuẩn bị · chưa có kết quả"
                        else "Chờ ảnh mới được xác nhận và khung hình kết quả",
                        color = Color(0xFFE6D7DE), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (vm.phase == LivePhase.LIVE) Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                color = if (original) Ink.copy(alpha = 0.8f) else Sage, shape = RoundedCornerShape(50)
            ) {
                Text(if (original) "CAMERA GỐC" else "ĐANG MẶC THỬ",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        if (vm.phase == LivePhase.LIVE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !original, onClick = { original = false }, label = { Text("Mặc thử AI") })
            FilterChip(selected = original, onClick = { original = true }, label = { Text("Camera gốc") })
        }
    }
}

@Composable
internal fun ActiveSessionBar(vm: MirrorViewModel) {
    Surface(color = Ink, contentColor = Color.White) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Phiên trực tiếp", style = MaterialTheme.typography.labelLarge)
                Text("Còn ${vm.secondsLeft / 60}:${(vm.secondsLeft % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6D7DE))
            }
            Button(onClick = vm::stop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA1394E))) {
                Text("Dừng phiên")
            }
        }
    }
}
