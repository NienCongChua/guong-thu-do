package com.example.myapplication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionScreen(vm: MirrorViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var developerTools by rememberSaveable { mutableStateOf(false) }
    val editable = !vm.loginBusy && !vm.photoBusy && !vm.deletingAccount
    StudioPage(padding) {
        item { PageTitle("TÀI KHOẢN", "Phòng thử riêng của bạn.", "Đăng nhập bằng Google để lưu kết quả và tiếp tục lần thử trên cloud.") }
        item {
            StudioCard {
                StatusPill(if (vm.connected && vm.usingCloud) "Thành viên thử nghiệm" else "Dành cho khách được mời",
                    vm.connected && vm.usingCloud)
                Text(vm.signedInEmail ?: "Chào mừng đến với Gương Thử Đồ",
                    style = MaterialTheme.typography.titleMedium)
                if (vm.signedInEmail == null) {
                    Text("Dùng đúng email đã nhận quyền truy cập. Bạn có thể xem giao diện trước khi đăng nhập.",
                        color = Muted, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { vm.signInGoogle(context) },
                        enabled = editable && vm.googleConfigured,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("google_sign_in")) {
                        Text(if (vm.loginBusy) "Đang đăng nhập…" else "Tiếp tục với Google")
                    }
                    if (!vm.googleConfigured) Text("Bản cài đặt chưa có cấu hình Google. Hãy liên hệ người quản lý ứng dụng.",
                        color = Wine, style = MaterialTheme.typography.bodySmall)
                } else {
                    if (!vm.connected) {
                        Text("Nếu email chưa có quyền, hãy nhờ người quản lý thêm bạn vào danh sách mời.",
                            color = Muted, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = vm::retryCloudConnection, enabled = editable,
                            modifier = Modifier.fillMaxWidth()) { Text("Kiểm tra quyền truy cập") }
                    }
                    OutlinedButton(onClick = { confirmSignOut = true }, enabled = editable,
                        modifier = Modifier.fillMaxWidth()) { Text("Đăng xuất") }
                }
                if (vm.loginBusy || vm.deletingAccount) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (vm.deletingAccount) "Đang xóa tài khoản và dữ liệu…" else "Đang xác thực tài khoản…",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                if (vm.photoBusy) Text("Chờ tác vụ hoặc dừng theo dõi trong màn Thử ảnh trước khi đổi tài khoản.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.member?.takeIf { vm.connected && vm.usingCloud }?.let { member ->
            item {
                StudioCard {
                    Text("Lượt sử dụng hôm nay", style = MaterialTheme.typography.titleMedium)
                    UsageRow("Ảnh mặc thử", member.quota.imagesUsed, member.quota.imagesLimit)
                    HorizontalDivider()
                    UsageRow("Phiên gương trực tiếp", member.quota.liveUsed, member.quota.liveLimit)
                    Text("Hạn mức tính theo yêu cầu đã nhận, kể cả yêu cầu lỗi hoặc hủy. Đặt lại lúc 07:00 hằng ngày (giờ Việt Nam).",
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.connectionMs?.let { "Phản hồi gần nhất: ${it} ms" } ?: "Dịch vụ cloud",
                            Modifier.weight(1f), color = Muted, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::refreshConnection, enabled = editable && !vm.checkingConnection) {
                            Text(if (vm.checkingConnection) "Đang kiểm tra…" else "Làm mới")
                        }
                    }
                }
            }
        }
        item {
            StudioCard {
                Text("Ảnh và quyền riêng tư", style = MaterialTheme.typography.titleMedium)
                Text("Ảnh đầu vào được dùng để tạo kết quả rồi xóa khỏi cloud. Kết quả được giữ trong 7 ngày; bạn có thể xóa sớm trong Tủ đồ hoặc lưu về điện thoại.",
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
                Text("Ảnh được gửi tới dịch vụ AI để xử lý. Dịch vụ dự phòng chỉ được dùng khi bạn bật lựa chọn đó ở màn Thử ảnh.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
                if (vm.signedInEmail != null) TextButton(onClick = { confirmDelete = true }, enabled = editable) {
                    Text("Xóa tài khoản và dữ liệu", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (BuildConfig.DEBUG) {
            item { TextButton(onClick = { developerTools = !developerTools }, modifier = Modifier.testTag("developer_tools")) {
                Text(if (developerTools) "Ẩn công cụ phát triển" else "Công cụ phát triển")
            } }
            if (developerTools) item { DeveloperConnectionCard(vm) }
        }
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Đăng xuất tài khoản?") },
        text = { Text("Ảnh trong bộ nhớ phiên sẽ được xóa. Tác vụ đã gửi tiếp tục trên cloud; đăng nhập lại cùng email để xem kết quả.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Đăng xuất") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Ở lại") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Xóa tài khoản và dữ liệu?") },
        text = { Text("Bạn sẽ xác nhận lại Google. Ứng dụng sẽ xóa lịch sử, ảnh trên cloud, hồ sơ đăng nhập và thu hồi quyền truy cập. Ảnh đã tải về máy vẫn được giữ. Thao tác này không thể hoàn tác.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteAccount(context) }) { Text("Xác nhận xóa") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Giữ tài khoản") } }
    )
}

@Composable
private fun UsageRow(label: String, used: Int, limit: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${(limit - used).coerceAtLeast(0)} / $limit lượt còn lại",
            color = if (used >= limit) Wine else Sage, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun DeveloperConnectionCard(vm: MirrorViewModel) {
    var url by rememberSaveable { mutableStateOf(vm.backendUrl) }
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val editable = !vm.loginBusy && !vm.photoBusy && !vm.checkingConnection && !vm.deletingAccount
    fun submit() {
        if (editable && password.isNotBlank() && url.isNotBlank()) {
            vm.connectBackend(url, password)
            password = ""
            keyboard?.hide()
        }
    }
    StudioCard {
        Text("Backend phát triển", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Địa chỉ backend") },
            placeholder = { Text("http://127.0.0.1:8787") }, singleLine = true, enabled = editable,
            modifier = Modifier.fillMaxWidth().testTag("backend_url"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Mật khẩu phát triển") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = editable,
            modifier = Modifier.fillMaxWidth().testTag("backend_password"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() })
        )
        Button(onClick = ::submit, enabled = editable && password.isNotBlank() && url.isNotBlank()) {
            Text("Kết nối backend phát triển")
        }
        Text("Công cụ này chỉ có trong bản debug. Mật khẩu không được lưu.",
            style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
