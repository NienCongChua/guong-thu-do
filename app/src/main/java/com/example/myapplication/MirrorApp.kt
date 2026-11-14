package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
internal enum class Destination(val label: String, val glyph: Glyph) : NavKey {
    HOME("Khám phá", Glyph.HOME), PHOTO("Thử ảnh", Glyph.PHOTO), LIVE("Gương", Glyph.LIVE),
    WARDROBE("Tủ đồ", Glyph.WARDROBE), CONNECTION("Kết nối", Glyph.SETTINGS)
}
@Serializable
private data object ResultDestination : NavKey

@Composable
fun MirrorApp(vm: MirrorViewModel = viewModel()) {
    val context = LocalContext.current
    var currentTab by rememberSaveable { mutableStateOf(Destination.HOME) }
    val stacks = Destination.entries.associateWith { destination -> rememberNavBackStack(destination) }
    var liveConsent by rememberSaveable { mutableStateOf(false) }
    var forPerson by rememberSaveable { mutableStateOf(false) }
    var captureUri by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.loadPhoto(it, forPerson) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) vm.loadPhoto(Uri.parse(uri), forPerson)
        captureUri = null
    }
    fun launchCamera(person: Boolean) {
        forPerson = person
        vm.stop()
        try {
            val dir = File(context.cacheDir, "captures").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File.createTempFile("capture_", ".jpg", dir)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            captureUri = uri.toString()
            camera.launch(uri)
        } catch (_: Exception) { vm.report("Không mở được máy ảnh. Hãy chọn ảnh từ thư viện.") }
    }
    fun choosePhoto(person: Boolean) {
        forPerson = person
        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { vm.report("Không mở được thư viện ảnh. Hãy thử chụp ảnh.") }
    }
    val capturePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera(forPerson) else vm.report("Cần quyền camera để chụp ảnh. Bạn có thể chọn từ thư viện.")
    }
    fun takePhoto(person: Boolean) {
        forPerson = person
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            launchCamera(person)
        else capturePermission.launch(Manifest.permission.CAMERA)
    }
    val livePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && currentTab == Destination.LIVE) vm.start()
        else if (!granted) vm.report("Cần quyền camera để mở gương. Bạn vẫn có thể thử bằng ảnh.")
    }
    fun startLive() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            vm.start()
        else livePermission.launch(Manifest.permission.CAMERA)
    }
    fun navigate(destination: Destination) {
        if (destination != Destination.LIVE && vm.active) vm.stop()
        currentTab = destination
    }
    fun showResult(output: PhotoResult) {
        vm.showResult(output)
        navigate(Destination.PHOTO)
        val stack = stacks.getValue(Destination.PHOTO)
        if (stack.lastOrNull() != ResultDestination) stack.add(ResultDestination)
    }
    fun goBack() {
        if (vm.active) { vm.stop(); return }
        val stack = stacks.getValue(currentTab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else currentTab = Destination.HOME
    }
    // Do not steal focus when a generation finishes on another tab.
    LaunchedEffect(vm.result?.id) {
        val output = vm.result
        if (output != null) {
            if (currentTab == Destination.PHOTO) showResult(output)
        } else {
            val stack = stacks.getValue(Destination.PHOTO)
            if (stack.lastOrNull() == ResultDestination) stack.removeAt(stack.lastIndex)
        }
    }
    LaunchedEffect(vm.notice) {
        val message = vm.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true)
        if (vm.notice == message) vm.clearNotice()
    }
    val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
        NavEntry(key) {
            when (key) {
                Destination.HOME -> HomeScreen(vm, navigate = ::navigate, openResult = ::showResult, padding = LocalPagePadding.current)
                Destination.PHOTO -> PhotoScreen(vm, LocalPagePadding.current,
                    { takePhoto(true) }, { choosePhoto(true) }, { takePhoto(false) }, { choosePhoto(false) },
                    { navigate(Destination.CONNECTION) }, ::showResult)
                Destination.LIVE -> LiveScreen(vm, LocalPagePadding.current,
                    { takePhoto(false) }, { choosePhoto(false) },
                    {
                        when {
                            !vm.connected -> navigate(Destination.CONNECTION)
                            vm.capabilities?.decart != true -> vm.report("Gương chưa sẵn sàng. Kết nối dịch vụ trong mục Kết nối hoặc thử bằng ảnh.")
                            else -> liveConsent = true
                        }
                    }, { navigate(Destination.PHOTO) })
                Destination.WARDROBE -> WardrobeScreen(vm, LocalPagePadding.current,
                    { choosePhoto(false) }, ::navigate, ::showResult)
                Destination.CONNECTION -> ConnectionScreen(vm, LocalPagePadding.current)
                ResultDestination -> ResultScreen(vm, LocalPagePadding.current, ::goBack)
                else -> error("Unknown destination")
            }
        }
    }
    // Retain each tab's entry state even while another stack is displayed.
    val entriesByTab = stacks.mapValues { (_, stack) ->
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>()),
            entryProvider = entryProvider
        )
    }
    val displayed = if (currentTab == Destination.HOME) entriesByTab.getValue(Destination.HOME)
        else entriesByTab.getValue(Destination.HOME) + entriesByTab.getValue(currentTab)
    NavigationSuiteScaffold(
        navigationSuiteItems = {
            Destination.entries.forEach { destination ->
                item(selected = destination == currentTab, onClick = { navigate(destination) },
                    modifier = Modifier.testTag("nav_${destination.name}"),
                    icon = { StudioIcon(destination.glyph) }, label = { Text(destination.label) })
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = Cream, navigationRailContainerColor = Cream
        )
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                Surface(color = Cream) {
                    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (stacks.getValue(currentTab).size > 1)
                                IconButton(onClick = ::goBack) { StudioIcon(Glyph.BACK, "Quay lại") }
                            else Box(Modifier.padding(end = 12.dp)) { StudioIcon(Glyph.WARDROBE, tint = Wine) }
                            Column(Modifier.weight(1f)) {
                                Text("GƯƠNG THỬ ĐỒ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                Text("Phong cách của riêng bạn", style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                            TextButton(onClick = { navigate(Destination.CONNECTION) }) {
                                Text(if (vm.connected) "Đã kết nối" else "Kết nối", color = if (vm.connected) Sage else Wine)
                            }
                        }
                        if (vm.loadingPhoto) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (vm.active) ActiveSessionBar(vm) }
        ) { padding ->
            val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
            val fixedPadding = PaddingValues(
                start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction),
                top = padding.calculateTopPadding()
            )
            Column(Modifier.fillMaxSize().padding(fixedPadding).consumeWindowInsets(fixedPadding)) {
                AnimatedVisibility(vm.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    vm.error?.let { message ->
                        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                                Text(message, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                Row {
                                    TextButton(onClick = vm::clearError) { Text("Đóng") }
                                    if (!vm.connected) TextButton(onClick = { navigate(Destination.CONNECTION) }) { Text("Kết nối lại") }
                                    if (currentTab == Destination.LIVE) TextButton(onClick = { navigate(Destination.PHOTO) }) { Text("Thử bằng ảnh") }
                                }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalPagePadding provides PaddingValues(bottom = padding.calculateBottomPadding())) {
                    NavDisplay(
                        entries = displayed, onBack = ::goBack, modifier = Modifier.weight(1f),
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { it / 18 }) togetherWith fadeOut(tween(140))
                        },
                        popTransitionSpec = {
                            fadeIn(tween(200)) togetherWith (fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 18 })
                        },
                        predictivePopTransitionSpec = {
                            fadeIn(tween(200)) togetherWith (fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { it / 18 })
                        }
                    )
                }
            }
        }
    }
    BackHandler(vm.active) { vm.stop() }
    if (liveConsent) AlertDialog(
        onDismissRequest = { liveConsent = false },
        title = { Text("Bắt đầu phiên thử trực tiếp?") },
        text = { Text("Ảnh trang phục và video camera trước sẽ được gửi đến Decart để mặc thử. Không thu âm. Phiên có tính phí và tự dừng sau ${vm.capabilities?.maxSeconds ?: 120} giây. Rời màn hình hoặc đưa ứng dụng vào nền sẽ dừng phiên.") },
        confirmButton = { TextButton(onClick = { liveConsent = false; startLive() }) { Text("Đồng ý và mở gương") } },
        dismissButton = { TextButton(onClick = { liveConsent = false }) { Text("Để sau") } }
    )
}

private val LocalPagePadding = compositionLocalOf { PaddingValues() }
