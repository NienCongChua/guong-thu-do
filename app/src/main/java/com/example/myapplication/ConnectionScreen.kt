package com.example.myapplication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectionScreen(vm: MirrorViewModel, padding: PaddingValues) {
    var url by rememberSaveable(vm.backendUrl) { mutableStateOf(vm.backendUrl) }
    // Passwords stay out of saved instance state and are cleared after submission.
    var password by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val editable = !vm.loginBusy && !vm.photoBusy && !vm.checkingConnection
    fun submit() {
        if (editable && password.isNotBlank() && url.isNotBlank()) {
            vm.connectBackend(url, password)
            password = ""
            keyboard?.hide()
        }
    }
    StudioPage(padding) {
        item { PageTitle("KẾT NỐI", "Sẵn sàng cho lần thử.", "Kết nối backend của bạn một lần để sử dụng các dịch vụ mặc thử.") }
        item {
            StudioCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Dịch vụ của bạn", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    StatusPill(if (vm.connected) "Đã kết nối" else "Chưa kết nối", vm.connected)
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Địa chỉ backend") },
                    placeholder = { Text("http://127.0.0.1:8787") }, singleLine = true, enabled = editable,
                    modifier = Modifier.fillMaxWidth().testTag("backend_url"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, label = { Text("Mật khẩu kết nối") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = editable,
                    modifier = Modifier.fillMaxWidth().testTag("backend_password"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
                Text("Ứng dụng chỉ lưu địa chỉ backend. Mật khẩu và khóa API không được lưu trong APK.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                if (vm.loginBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Đang xác thực và kiểm tra dịch vụ…", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Button(onClick = ::submit, enabled = editable && password.isNotBlank() && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (vm.loginBusy) "Đang kết nối…" else if (vm.connected) "Cập nhật kết nối" else "Kết nối dịch vụ")
                }
                if (vm.photoBusy) Text("Chờ ảnh tạo xong hoặc hủy yêu cầu trước khi đổi kết nối.",
                    color = Wine, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (vm.connected) item {
            StudioCard {
                Text("Trạng thái cấu hình", style = MaterialTheme.typography.titleMedium)
                ProviderStatus("Gương trực tiếp", "Decart", vm.capabilities?.decart == true)
                HorizontalDivider()
                ProviderStatus("Ảnh mặc thử", "OpenAI", vm.capabilities?.openai == true)
                HorizontalDivider()
                ProviderStatus(if (vm.capabilities?.openai == true) "Ảnh dự phòng" else "Ảnh mặc thử", "FASHN", vm.capabilities?.fashn == true)
                Text("Đã cấu hình nghĩa là server có key. Quyền sử dụng, số dư và chất lượng kết quả được xác định khi tạo thật.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Phản hồi backend", style = MaterialTheme.typography.labelLarge)
                        Text(vm.connectionMs?.let { "$it ms · lần kiểm tra gần nhất" } ?: "Chưa đo được",
                            style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    OutlinedButton(onClick = vm::refreshConnection, enabled = !vm.checkingConnection && !vm.loginBusy) {
                        if (vm.checkingConnection) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Kiểm tra")
                    }
                }
                TextButton(onClick = vm::disconnect, enabled = editable) { Text("Ngắt kết nối") }
            }
        }
        item {
            StudioCard {
                Text("Nếu kết nối chưa thành công", style = MaterialTheme.typography.titleMedium)
                Text("1. Kiểm tra backend đã chạy và địa chỉ đúng.\n2. Nếu dùng USB, kiểm tra chuyển tiếp cổng. Nếu dùng Wi-Fi, đặt hai thiết bị cùng mạng.\n3. Nhập mật khẩu demo đã đặt trên server.",
                    style = MaterialTheme.typography.bodyMedium, color = Muted)
                Text("Ảnh tĩnh cần OpenAI hoặc FASHN. Gương trực tiếp cần Decart. API dự phòng chỉ gửi ảnh khi bạn bật tùy chọn ở màn Thử ảnh.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun ProviderStatus(feature: String, provider: String, configured: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(provider, style = MaterialTheme.typography.labelLarge)
            Text(feature, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(if (configured) "Đã cấu hình" else "Chưa cấu hình",
            style = MaterialTheme.typography.labelMedium, color = if (configured) Sage else Muted)
    }
}
