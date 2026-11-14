package com.example.myapplication

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun WardrobeScreen(
    vm: MirrorViewModel, padding: PaddingValues, add: () -> Unit,
    navigate: (Destination) -> Unit, openResult: (PhotoResult) -> Unit
) {
    var showResults by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val editable = !vm.photoBusy && !vm.loadingPhoto && !vm.savingPhoto && !vm.deletingAccount
    LaunchedEffect(showResults, vm.connected, vm.usingCloud) {
        if (showResults && vm.connected && vm.usingCloud) vm.refreshCloudLibrary()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val horizontal = ((maxWidth - 880.dp) / 2).coerceAtLeast(20.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(start = horizontal, end = horizontal,
                top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 24.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PageTitle("TỦ ĐỒ CÁ NHÂN", "Lưu cảm hứng. Thử lại.", if (vm.usingCloud) "Kết quả của bạn được giữ trên cloud trong 7 ngày. Trang phục chỉ được giữ trong phiên trên máy này." else "Trang phục và ba kết quả gần nhất được giữ trong phiên này. Đăng nhập để xem lịch sử cloud.")
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showResults, onClick = { showResults = false }, label = { Text("Trang phục · ${vm.recent.size}") })
                    FilterChip(selected = showResults, onClick = { showResults = true }, label = { Text("Kết quả · ${if (vm.usingCloud) vm.cloudJobs.size else vm.history.size}") })
                }
            }
            if (!showResults) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FilledTonalButton(onClick = add, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                        StudioIcon(Glyph.ADD, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Thêm trang phục")
                    }
                }
                if (vm.recent.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Glyph.WARDROBE, "Tủ đồ đang chờ bạn", "Thêm ảnh chiếc áo hoặc váy đầu tiên để bắt đầu phối phong cách.", "Chọn trang phục", add)
                }
                items(vm.recent, key = { it.id }) { asset ->
                    val selected = asset.id == vm.garment?.id
                    OutlinedCard(onClick = { vm.selectGarment(asset) }, enabled = editable,
                        modifier = Modifier.semantics { this.selected = selected },
                        shape = RoundedCornerShape(22.dp),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Wine else MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Image(asset.bitmap.asImageBitmap(), "Trang phục trong tủ đồ",
                            Modifier.fillMaxWidth().aspectRatio(0.85f).background(Color.White).padding(10.dp), contentScale = ContentScale.Fit)
                        Text(if (selected) "✓ Đang chọn" else "Chạm để chọn", Modifier.padding(14.dp),
                            color = if (selected) Wine else Muted, style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (vm.garment != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { navigate(Destination.PHOTO) }, modifier = Modifier.weight(1f)) { Text("Thử bằng ảnh") }
                        OutlinedButton(onClick = { navigate(Destination.LIVE) }, modifier = Modifier.weight(1f)) { Text("Mở gương") }
                    }
                }
            } else if (vm.usingCloud) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Lịch sử trên cloud", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = vm::refreshCloudLibrary, enabled = !vm.cloudLibraryBusy) { Text("Làm mới") }
                    }
                    if (vm.cloudLibraryBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (vm.cloudJobs.isEmpty() && !vm.cloudLibraryBusy) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Glyph.PHOTO, "Diện mạo mới sắp xuất hiện", "Các lần thử của bạn sẽ xuất hiện ở đây sau khi gửi ảnh.",
                        "Thử phong cách đầu tiên", { navigate(Destination.PHOTO) })
                }
                items(vm.cloudJobs, key = { it.id }) { job ->
                    val cached = vm.history.firstOrNull { it.id == job.id }
                    OutlinedCard(shape = RoundedCornerShape(22.dp)) {
                        if (cached != null) Image(cached.bitmap.asImageBitmap(), "Kết quả mặc thử",
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color.White), contentScale = ContentScale.Fit)
                        else Box(Modifier.fillMaxWidth().height(130.dp).background(Blush), contentAlignment = Alignment.Center) {
                            StudioIcon(if (job.completed) Glyph.PHOTO else Glyph.REFRESH, modifier = Modifier.size(32.dp), tint = Wine)
                        }
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when (job.status) {
                                "completed" -> "Đã hoàn tất"
                                "queued", "uploading" -> "Đang chờ xử lý"
                                "processing" -> "Đang tạo ảnh"
                                "cancelled" -> "Đã hủy"
                                "expired" -> "Đã hết hạn"
                                else -> "Chưa tạo được ảnh"
                            }, style = MaterialTheme.typography.titleSmall)
                            if (job.provider.isNotBlank()) Text(job.provider, style = MaterialTheme.typography.labelMedium, color = Muted)
                            if (job.completed) {
                                val expiry = remember(job.expiresAt) {
                                    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                                        .format(java.util.Date(job.expiresAt * 1000))
                                }
                                Text("Giữ đến $expiry", style = MaterialTheme.typography.bodySmall, color = Muted)
                            } else if (!job.pending) Text(CloudFailureMessages.describe(job.errorCode, job.status),
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                            if (job.completed || job.pending) Button(
                                onClick = { navigate(Destination.PHOTO); vm.openCloudResult(job) },
                                enabled = editable, modifier = Modifier.fillMaxWidth()
                            ) { Text(if (job.pending) "Theo dõi" else "Xem & lưu") }
                            TextButton(onClick = { deleteId = job.id }, enabled = editable && !vm.cloudLibraryBusy,
                                modifier = Modifier.fillMaxWidth()) { Text("Xóa khỏi cloud") }
                        }
                    }
                }
            } else {
                if (vm.history.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Glyph.PHOTO, "Diện mạo mới sắp xuất hiện", "Kết quả mặc thử của bạn sẽ có mặt ở đây.", "Thử phong cách đầu tiên",
                        { navigate(Destination.PHOTO) })
                }
                items(vm.history, key = { it.id }) { output ->
                    OutlinedCard(onClick = { openResult(output) }, shape = RoundedCornerShape(22.dp)) {
                        Image(output.bitmap.asImageBitmap(), "Xem kết quả từ ${output.provider}",
                            Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Color.White), contentScale = ContentScale.Fit)
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(output.provider, style = MaterialTheme.typography.labelLarge)
                            Text(if (output.id in vm.savedResultIds) "Đã lưu vào thư viện" else "Chạm để so sánh & lưu",
                                style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
            }
            if (vm.recent.isNotEmpty() || vm.history.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { confirmClear = true }, enabled = editable) { Text("Xóa ảnh khỏi phiên này") }
            }
        }
    }
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text("Xóa lần thử này?") },
        text = { Text("Ảnh đầu vào và kết quả trên cloud sẽ được xóa. Yêu cầu AI đã nhận vẫn tính vào hạn mức. Ảnh đã tải về máy vẫn được giữ.") },
        confirmButton = { TextButton(onClick = {
            vm.cloudJobs.firstOrNull { it.id == deleteId }?.let(vm::deleteCloudResult)
            deleteId = null
        }) { Text("Xóa khỏi cloud") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Giữ lại") } }
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Xóa ảnh của phiên này?") },
        text = { Text("Ảnh người, trang phục và kết quả đang giữ trong ứng dụng sẽ được xóa khỏi phiên. Ảnh đã lưu trong thư viện điện thoại vẫn được giữ.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.forgetSessionPhotos() }) { Text("Xóa ảnh phiên") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Giữ lại") } }
    )
}
