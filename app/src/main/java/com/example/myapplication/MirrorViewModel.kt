package com.example.myapplication

import ai.decart.sdk.ConnectionState
import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.LogLevel
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.realtime.MirrorMode
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import livekit.org.webrtc.VideoSink
import java.util.UUID

enum class GarmentKind(val label: String, val apiName: String) { TOP("Áo", "top"), DRESS("Váy", "dress") }
enum class LivePhase { IDLE, CONNECTING, WAITING, CHANGING, LIVE }
class PhotoResult(
    val before: PhotoAsset?, val png: ByteArray, val bitmap: Bitmap,
    val provider: String, val elapsedMs: Long, val fallbackUsed: Boolean,
    val id: String = UUID.randomUUID().toString()
)

const val LIVE_PROMPT = "Dress the person in the exact garment shown in the reference image. Preserve its neckline, sleeve length, color, print placement and fabric texture. Preserve the person’s face, hair, body shape, hands, pose, background and lighting. As the person moves, keep the garment aligned with the torso and let hands naturally pass in front of the fabric."

class MirrorViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("connection", 0)
    var backendUrl by mutableStateOf(prefs.getString("url", BuildConfig.BACKEND_URL)!!)
        private set
    var capabilities by mutableStateOf<Capabilities?>(null)
        private set
    var connected by mutableStateOf(false)
        private set
    var loginBusy by mutableStateOf(false)
        private set
    var garment by mutableStateOf<PhotoAsset?>(null)
        private set
    var recent by mutableStateOf<List<PhotoAsset>>(emptyList())
        private set
    var person by mutableStateOf<PhotoAsset?>(null)
        private set
    var kind by mutableStateOf(GarmentKind.TOP)
        private set
    var phase by mutableStateOf(LivePhase.IDLE)
        private set
    var local by mutableStateOf<RealtimeMediaStream?>(null)
        private set
    var remote by mutableStateOf<RealtimeMediaStream?>(null)
        private set
    var rendererEpoch by mutableStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var loadingPhoto by mutableStateOf(false)
        private set
    var photoBusy by mutableStateOf(false)
        private set
    var result by mutableStateOf<PhotoResult?>(null)
        private set
    var secondsLeft by mutableStateOf(0)
        private set
    var history by mutableStateOf<List<PhotoResult>>(emptyList())
        private set
    var quality by mutableStateOf(PhotoQuality.FAST)
        private set
    var allowFallback by mutableStateOf(false)
        private set
    var photoSeconds by mutableStateOf(0)
        private set
    var connectionMs by mutableStateOf<Long?>(null)
        private set
    var checkingConnection by mutableStateOf(false)
        private set
    var savingPhoto by mutableStateOf(false)
        private set
    var savedResultIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var member by mutableStateOf<Member?>(null)
        private set
    var cloudJobs by mutableStateOf<List<CloudJob>>(emptyList())
        private set
    var cloudJob by mutableStateOf<CloudJob?>(null)
        private set
    var pendingCloudRequest by mutableStateOf(false)
        private set
    var cloudLibraryBusy by mutableStateOf(false)
        private set
    val active get() = phase != LivePhase.IDLE
    val usingCloud get() = capabilities?.asyncPhotos == true
    private val accountAuth = GoogleAccountAuth(app)
    val googleConfigured get() = accountAuth.configured
    var signedInEmail by mutableStateOf(accountAuth.email)
        private set
    var deletingAccount by mutableStateOf(false)
        private set
    private var pendingBefore: PhotoAsset? = null
    private var foreground = true
    private fun pendingKey(uid: String) = "pending_cloud_request_$uid"

    private var api: BackendApi? = null
    private var client: DecartClient? = null
    private var liveJob: Job? = null
    private var swapJob: Job? = null
    private var watchJob: Job? = null
    private var errorJob: Job? = null
    private var stateJob: Job? = null
    private var localStreamJob: Job? = null
    private var endedJob: Job? = null
    private var loginJob: Job? = null
    private var photoJob: Job? = null
    private var connectionJob: Job? = null
    private var photoGeneration = 0
    private var generation = 0
    private var updateAckAt = Long.MAX_VALUE
    private var renderedEpoch = -1
    private var monitoredTrack: VideoTrack? = null
    @Volatile private var lastFrameAt = 0L
    private val frameMonitor = VideoSink { lastFrameAt = SystemClock.elapsedRealtime() }

    init {
        if (signedInEmail != null) connectCloud(accountAuth::idToken)
    }

    fun report(message: String) { error = message }
    fun clearError() { error = null }
    fun clearNotice() { notice = null }
    fun selectQuality(value: PhotoQuality) { if (!photoBusy) quality = value }
    fun setFallback(value: Boolean) { if (!photoBusy) allowFallback = value }
    fun connectBackend(url: String, password: String) {
        if (!BuildConfig.DEBUG) return report("Bản phát hành yêu cầu đăng nhập Google.")
        if (loginBusy || photoBusy) return
        connectionJob?.cancel()
        checkingConnection = false
        stop()
        loginBusy = true
        loginJob = viewModelScope.launch {
            val candidate = runCatching { BackendApi(url) }.getOrElse {
                error = it.message ?: "Địa chỉ backend không hợp lệ."; loginBusy = false; return@launch
            }
            try {
                candidate.login(password)
                val started = SystemClock.elapsedRealtime()
                val caps = candidate.capabilities()
                connectionMs = SystemClock.elapsedRealtime() - started
                api?.close(); api = candidate
                backendUrl = candidate.baseUrl
                prefs.edit().putString("url", backendUrl).apply()
                capabilities = caps; connected = true
                member = null; cloudJobs = emptyList(); cloudJob = null; pendingCloudRequest = false
                error = null; notice = "Đã kết nối backend phát triển."
            } catch (e: CancellationException) { candidate.close(); throw e }
            catch (e: Exception) { candidate.close(); error = e.message ?: "Không đăng nhập được." }
            finally { loginBusy = false }
        }
    }

    fun connectCloud(tokenProvider: suspend () -> String) {
        if (loginBusy || photoBusy) return
        if (BuildConfig.CLOUD_BACKEND_URL.isBlank()) return report("Chưa cấu hình địa chỉ dịch vụ cloud.")
        connectionJob?.cancel(); checkingConnection = false; stop()
        loginBusy = true; error = null
        loginJob = viewModelScope.launch {
            var candidate: BackendApi? = null
            try {
                val backend = BackendApi(BuildConfig.CLOUD_BACKEND_URL, tokenProvider)
                candidate = backend
                val started = SystemClock.elapsedRealtime()
                val profile = backend.member()
                val caps = backend.capabilities()
                val jobs = backend.jobs()
                if (member?.uid != profile.uid) {
                    garment = null; person = null; recent = emptyList(); result = null; history = emptyList()
                    savedResultIds = emptySet(); pendingBefore = null
                }
                api?.close(); api = backend
                backendUrl = backend.baseUrl; member = profile; capabilities = caps
                cloudJobs = jobs; cloudJob = profile.activeJob
                pendingCloudRequest = profile.activeJob != null || prefs.contains(pendingKey(profile.uid))
                connected = true; connectionMs = SystemClock.elapsedRealtime() - started
                notice = "Đã đăng nhập. Chào mừng bạn đến phòng thử riêng."
                if (pendingCloudRequest) resumeCloudPhoto()
            } catch (e: CancellationException) { candidate?.close(); throw e }
            catch (e: Exception) { candidate?.close(); handleFailure(e, "Không đăng nhập được vào dịch vụ.") }
            finally { loginBusy = false }
        }
    }

    fun signInGoogle(context: Context) {
        if (loginBusy || photoBusy || deletingAccount) return
        if (!googleConfigured) return report("Bản ứng dụng này chưa có cấu hình đăng nhập Google.")
        loginBusy = true; error = null
        loginJob = viewModelScope.launch {
            try {
                accountAuth.signIn(context)
                signedInEmail = accountAuth.email
                loginBusy = false
                connectCloud(accountAuth::idToken)
            } catch (_: GetCredentialCancellationException) {
                loginBusy = false
            } catch (_: NoCredentialException) {
                loginBusy = false
                report("Chưa tìm thấy tài khoản Google. Hãy thêm tài khoản Google vào thiết bị rồi thử lại.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                loginBusy = false
                report("Chưa đăng nhập được Google. Kiểm tra mạng và thử lại.")
            }
        }
    }

    fun retryCloudConnection() = connectCloud(accountAuth::idToken)

    fun signOut() {
        if (loginBusy || deletingAccount) return
        disconnect()
        signedInEmail = null
        loginBusy = true
        loginJob = viewModelScope.launch {
            try { accountAuth.signOut() }
            finally { loginBusy = false }
        }
    }

    fun deleteAccount(context: Context) {
        if (loginBusy || photoBusy || deletingAccount || signedInEmail == null) return
        deletingAccount = true; error = null; stop(); pausePhotoPolling()
        viewModelScope.launch {
            var removedFromCloud = false
            try {
                accountAuth.reauthenticate(context)
                val backend = BackendApi(BuildConfig.CLOUD_BACKEND_URL, accountAuth::idToken)
                try { backend.deleteAccount(); removedFromCloud = true }
                finally { backend.close() }
                accountAuth.deleteFirebaseUser()
                accountAuth.signOut()
                signedInEmail = null
                disconnect()
                notice = "Đã xóa tài khoản và dữ liệu trên cloud."
            } catch (_: GetCredentialCancellationException) {
                notice = "Đã dừng xóa tài khoản."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = if (removedFromCloud)
                    "Dữ liệu cloud đã được xóa. Chưa xóa được hồ sơ đăng nhập Google; hãy thử Xóa tài khoản lần nữa."
                else if (e is BackendException) e.message
                else "Chưa xóa được tài khoản. Kiểm tra mạng, xác nhận lại Google và thử lần nữa."
            } finally { deletingAccount = false }
        }
    }

    fun refreshCloudLibrary() {
        val backend = api ?: return
        if (!usingCloud || cloudLibraryBusy) return
        cloudLibraryBusy = true
        viewModelScope.launch {
            try {
                cloudJobs = backend.jobs()
                member = backend.member()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleFailure(e, "Không tải được lịch sử.") }
            finally { cloudLibraryBusy = false }
        }
    }

    fun refreshConnection() {
        val backend = api ?: return report("Hãy kết nối backend trước.")
        if (checkingConnection || loginBusy) return
        checkingConnection = true
        connectionJob = viewModelScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                capabilities = backend.capabilities()
                if (usingCloud) { member = backend.member(); cloudJobs = backend.jobs() }
                connectionMs = SystemClock.elapsedRealtime() - started
                notice = "Backend phản hồi sau ${connectionMs} ms."
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { connectionMs = null; handleFailure(e, "Không kiểm tra được kết nối.") }
            finally { checkingConnection = false }
        }
    }

    fun disconnect() {
        loginJob?.cancel(); connectionJob?.cancel()
        loginBusy = false; checkingConnection = false
        stop(); pausePhotoPolling()
        api?.close(); api = null
        connected = false; capabilities = null; connectionMs = null; allowFallback = false
        member = null; cloudJobs = emptyList(); cloudJob = null; pendingCloudRequest = false
        pendingBefore = null
        garment = null; person = null; recent = emptyList(); result = null; history = emptyList()
        savedResultIds = emptySet()
        error = null; notice = "Đã đăng xuất và xóa ảnh khỏi bộ nhớ phiên."
    }

    private fun handleFailure(cause: Exception, fallback: String) {
        if (cause is BackendException && cause.code in setOf("AUTH_EXPIRED", "AUTH_REQUIRED", "INVITE_REQUIRED")) {
            connected = false; capabilities = null; connectionMs = null
            stop()
        }
        error = cause.message ?: fallback
    }

    fun loadPhoto(uri: Uri, forPerson: Boolean) {
        if (loadingPhoto || photoBusy) return
        loadingPhoto = true
        viewModelScope.launch {
            try {
                val asset = Photos.load(getApplication(), uri)
                result = null
                if (forPerson) person = asset else selectGarment(asset)
                error = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Không đọc được ảnh: ${e.message ?: "Hãy chọn ảnh khác."}" }
            finally { loadingPhoto = false }
        }
    }

    fun selectGarment(asset: PhotoAsset) {
        if (photoBusy || phase == LivePhase.CONNECTING || phase == LivePhase.CHANGING || phase == LivePhase.WAITING) return
        garment = asset
        recent = (listOf(asset) + recent.filter { it.id != asset.id }).take(6)
        result = null
        if (phase == LivePhase.LIVE) changeGarment()
    }

    fun selectKind(value: GarmentKind) {
        if (photoBusy || (active && phase != LivePhase.LIVE)) return
        if (kind == value) return
        kind = value; result = null
        if (phase == LivePhase.LIVE) changeGarment()
    }

    fun start() {
        if (active || photoBusy || loadingPhoto || loginBusy || !connected) return
        val selected = garment ?: return report("Hãy chụp hoặc chọn trang phục trước.")
        val backend = api ?: return report("Hãy đăng nhập trong mục Tài khoản.")
        if (capabilities?.decart != true) return report("Cần cấu hình DECART_API_KEY trên server để mở gương trực tiếp.")
        phase = LivePhase.CONNECTING; error = null; notice = null
        val run = ++generation
        liveJob = viewModelScope.launch {
            var step = LiveStep.TOKEN
            try {
                Log.i("GuongThuDo", "live stage=TOKEN")
                val token = backend.liveToken()
                if (run != generation) return@launch
                // SDK 0.7.3 predates the named 3.6 registry constant. Use the exact
                // official model ID and its documented 1280x720/30fps specification.
                val model = RealtimeModel("lucy-vton-3.6", "/v1/stream", 30, 1280, 720)
                step = LiveStep.CAMERA
                Log.i("GuongThuDo", "live stage=CAMERA; token received (value omitted)")
                val sdk = DecartClient(getApplication(), DecartClientConfig(apiKey = token.key, logLevel = LogLevel.WARN))
                client = sdk
                // connect() owns the camera and Room so SDK retries can recreate both.
                // Passing a caller-owned Room prevents SDK 0.7.3 from reconnecting it.
                // Keep only a borrowed reference for the local preview.
                localStreamJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sdk.realtime.localStreamUpdates.collect { stream ->
                        if (run == generation) local = stream
                    }
                }
                secondsLeft = token.maxSeconds
                watch(run, token.maxSeconds)
                errorJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sdk.realtime.errors.collect {
                        if (run != generation) return@collect
                        if (phase == LivePhase.CONNECTING &&
                            DecartCompatibility.isLegacySessionMetadataError(it.code, it.cause)) {
                            Log.w("GuongThuDo", "SDK 0.7.3: ignored obsolete session_id address fields; awaiting LiveKit room info")
                        } else {
                            failLive(LiveStep.CONNECT,
                                IllegalStateException("${it.code}: ${it.message}", it.cause))
                        }
                    }
                }
                endedJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    sdk.realtime.generationEnded.collect {
                        if (run == generation) fail("Decart đã kết thúc phiên. Kiểm tra hạn mức hoặc mở phiên mới.")
                    }
                }
                val image = withContext(Dispatchers.Default) { Base64.encodeToString(selected.imageBytes, Base64.NO_WRAP) }
                step = LiveStep.CONNECT
                Log.i("GuongThuDo", "live stage=CONNECT; model=lucy-vton-3.6; video only")
                withTimeout(45000) {
                    sdk.realtime.connect(
                        ConnectOptions(
                            model = model, initialImage = image,
                            initialPrompt = InitialPrompt(prompt(), enhance = false),
                            publishCamera = true, facing = FacingMode.FRONT, mirror = MirrorMode.OFF,
                            onRemoteStream = { output ->
                                viewModelScope.launch {
                                    if (run == generation) attachRemote(output)
                                }
                            }
                        )
                    )
                }
                if (run != generation) return@launch
                // connect() returns only after the initial image/prompt server ACK.
                updateAckAt = SystemClock.elapsedRealtime()
                phase = LivePhase.WAITING
                Log.i("GuongThuDo", "live stage=WAITING; initial garment acknowledged")
                stateJob = viewModelScope.launch {
                    sdk.realtime.connectionState.collect { state ->
                        if (run != generation) return@collect
                        if (state == ConnectionState.DISCONNECTED || state == ConnectionState.RECONNECTING)
                            fail("Kết nối video Decart bị ngắt. Phiên đã dừng; hãy thử lại.")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                if (run == generation) failLive(step, e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (run == generation) failLive(step, e)
            }
        }
    }

    private fun prompt() = LIVE_PROMPT + if (kind == GarmentKind.DRESS)
        " The reference garment is a dress; replace the current dress or outfit in the corresponding area."
    else " The reference garment is a top; replace the current top."

    private fun attachRemote(stream: RealtimeMediaStream) {
        if (monitoredTrack !== stream.videoTrack) {
            monitoredTrack?.removeRenderer(frameMonitor)
            monitoredTrack = stream.videoTrack
            lastFrameAt = 0
            monitoredTrack?.addRenderer(frameMonitor)
        }
        // Only this remote stream supplies the main try-on renderer.
        remote = stream
    }

    fun onRemoteRendered(epoch: Int) {
        if (active && epoch == rendererEpoch) renderedEpoch = epoch
    }

    private fun changeGarment() {
        val sdk = client ?: return
        val selected = garment ?: return
        val run = generation
        phase = LivePhase.CHANGING
        updateAckAt = Long.MAX_VALUE
        renderedEpoch = -1
        swapJob = viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.Default) { Base64.encodeToString(selected.imageBytes, Base64.NO_WRAP) }
                sdk.realtime.setImage(imageBase64 = image, prompt = prompt(), enhance = false, timeout = 30000)
                if (run != generation) return@launch
                updateAckAt = SystemClock.elapsedRealtime()
                rendererEpoch++ // New renderer; its first frame must follow the image ACK.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (run == generation) failLive(LiveStep.GARMENT, e) }
        }
    }

    private fun watch(run: Int, limit: Int) {
        val started = SystemClock.elapsedRealtime()
        watchJob = viewModelScope.launch {
            while (run == generation && active) {
                delay(250)
                val now = SystemClock.elapsedRealtime()
                secondsLeft = (limit - (now - started) / 1000).toInt().coerceAtLeast(0)
                if (secondsLeft == 0) {
                    stop(); notice = "Đã kết thúc phiên sau ${limit} giây theo thời lượng được cấp."; return@launch
                }
                if ((phase == LivePhase.WAITING || phase == LivePhase.CHANGING) && updateAckAt != Long.MAX_VALUE) {
                    if (lastFrameAt > updateAckAt + 500 && renderedEpoch == rendererEpoch) phase = LivePhase.LIVE
                    else if (now - updateAckAt > 20000) {
                        fail("Không nhận được khung hình kết quả Decart sau xác nhận. Phiên đã dừng. Kiểm tra mạng, hạn mức rồi thử lại.")
                        return@launch
                    }
                }
                if (phase == LivePhase.LIVE && now - lastFrameAt > 8000) {
                    fail("Video kết quả Decart đã đứng quá 8 giây. Phiên đã dừng; hãy thử lại.")
                    return@launch
                }
            }
        }
    }

    fun stop() {
        generation++
        liveJob?.cancel(); swapJob?.cancel(); watchJob?.cancel()
        errorJob?.cancel(); stateJob?.cancel(); endedJob?.cancel(); localStreamJob?.cancel()
        runCatching { monitoredTrack?.removeRenderer(frameMonitor) }
        monitoredTrack = null
        remote = null
        local = null // SDK owns this stream; release() disposes its camera and Room.
        phase = LivePhase.IDLE; secondsLeft = 0
        updateAckAt = Long.MAX_VALUE; renderedEpoch = -1; lastFrameAt = 0
        rendererEpoch++
        client?.release(); client = null
    }

    fun onBackground() {
        if (active) { stop(); notice = "Đã dừng gương và giải phóng camera khi ứng dụng vào nền." }
        foreground = false
        if (usingCloud && photoBusy && cloudJob?.pending == true) {
            pausePhotoPolling()
            notice = "Tác vụ tiếp tục trên cloud. Mở lại ứng dụng để theo dõi."
        }
    }
    fun onForeground() {
        foreground = true
        if (usingCloud && connected && pendingCloudRequest && !photoBusy) resumeCloudPhoto()
    }
    private fun fail(message: String) { stop(); error = message }
    private fun failLive(step: LiveStep, cause: Throwable) {
        if (cause is BackendException && cause.code in setOf("AUTH_EXPIRED", "AUTH_REQUIRED", "INVITE_REQUIRED")) {
            connected = false; capabilities = null; connectionMs = null
        }
        Log.e("GuongThuDo", LiveFailureMessages.diagnostic(step, cause))
        fail("Decart: " + LiveFailureMessages.describe(step, cause))
    }

    fun createPhoto() {
        val backend = api ?: return report("Hãy đăng nhập để tiếp tục.")
        if (usingCloud && pendingCloudRequest) { resumeCloudPhoto(); return }
        val me = person ?: return report("Hãy chọn Ảnh của tôi.")
        val item = garment ?: return report("Hãy chọn Trang phục.")
        if (!connected) return report("Hãy đăng nhập lại để tiếp tục.")
        if (capabilities?.photo != true) return report("Dịch vụ ảnh hiện chưa được bật.")
        if (photoBusy || loadingPhoto || loginBusy || deletingAccount) return
        val selectedKind = kind
        val selectedQuality = quality
        val fallbackAllowed = allowFallback && capabilities?.fallbackAvailable == true
        pendingBefore = me
        cloudJob = null
        val cloud = usingCloud
        val requestId = UUID.randomUUID().toString()
        val uid = member?.uid
        if (cloud && uid != null) {
            prefs.edit().putString(pendingKey(uid), requestId).apply()
            pendingCloudRequest = true
        }
        startPhotoWork {
            if (cloud) {
                val created = backend.createJob(me, item, selectedKind, selectedQuality, fallbackAllowed, requestId)
                cloudJob = created
                awaitCloudPhoto(backend, created, me)
            } else {
                val started = SystemClock.elapsedRealtime()
                val edited = backend.edit(me, item, selectedKind, selectedQuality, fallbackAllowed)
                installPhoto(me, edited, SystemClock.elapsedRealtime() - started)
            }
        }
    }

    private fun startPhotoWork(work: suspend () -> Unit) {
        if (photoBusy) return
        stop(); photoBusy = true; photoSeconds = 0; error = null; notice = null
        val run = ++photoGeneration
        val started = SystemClock.elapsedRealtime()
        photoJob = viewModelScope.launch {
            val ticker = launch {
                while (true) {
                    delay(1000)
                    photoSeconds = ((SystemClock.elapsedRealtime() - started) / 1000).toInt()
                }
            }
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (run == photoGeneration) {
                    // A lost response is not permission to create again. Keep the
                    // request key so the same server job can be found on reconnect.
                    if (e is BackendException && e.status in 400..499 && e.code != "IMAGE_BUSY" &&
                        e.code !in setOf("AUTH_EXPIRED", "AUTH_REQUIRED", "INVITE_REQUIRED", "JOB_NOT_FOUND")) clearPendingCloud()
                    handleFailure(e, "Không tải được kết quả. Hãy kiểm tra tiến trình.")
                }
            } finally {
                ticker.cancel()
                if (run == photoGeneration) photoBusy = false
            }
        }
    }

    private suspend fun installPhoto(
        before: PhotoAsset?, edited: EditedPhoto, elapsedMs: Long, id: String = UUID.randomUUID().toString()
    ) {
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(edited.png, 0, edited.png.size) ?: error("Không nhận được ảnh hợp lệ.")
        }
        val output = PhotoResult(before, edited.png, bitmap, edited.provider, elapsedMs, edited.fallbackUsed, id)
        result = output
        history = (listOf(output) + history.filter { it.id != id }).take(3)
    }

    private suspend fun awaitCloudPhoto(backend: BackendApi, initial: CloudJob, before: PhotoAsset?) {
        var current = initial
        cloudJob = current
        if (!foreground && current.pending) return
        withTimeout(20 * 60 * 1000L) {
            while (current.pending) {
                cloudJob = current
                delay(2000)
                current = backend.job(current.id)
            }
        }
        cloudJob = current
        if (!current.completed) {
            clearPendingCloud()
            throw BackendException(CloudFailureMessages.describe(current.errorCode, current.status),
                current.errorCode.ifBlank { "JOB_ENDED" })
        }
        val edited = backend.downloadJob(current.id)
        installPhoto(before, edited, current.elapsedMs, current.id)
        clearPendingCloud()
        refreshCloudLibrary()
    }

    fun resumeCloudPhoto() {
        val backend = api ?: return
        if (!usingCloud || !connected || photoBusy) return
        val uid = member?.uid ?: return
        startPhotoWork {
            val key = prefs.getString(pendingKey(uid), null)
            val current = when {
                cloudJob?.pending == true -> backend.job(cloudJob!!.id)
                key != null -> try { backend.jobByRequest(key) }
                    catch (e: BackendException) {
                        if (e.status != 404) throw e
                        backend.member().activeJob ?: throw BackendException(
                            "Chưa tìm thấy tác vụ đã gửi. Kiểm tra lịch sử trước khi tạo lại.", "JOB_NOT_FOUND", 404)
                    }
                else -> backend.member().activeJob
            }
            if (current == null) { clearPendingCloud(); return@startPhotoWork }
            pendingCloudRequest = true
            awaitCloudPhoto(backend, current, pendingBefore)
        }
    }

    fun openCloudResult(job: CloudJob) {
        if (job.pending) {
            if (photoBusy) { notice = "Tác vụ đang được theo dõi trong màn Thử ảnh."; return }
            cloudJob = job; pendingCloudRequest = true; pendingBefore = null
            resumeCloudPhoto()
            return
        }
        val backend = api ?: return
        if (!job.completed || photoBusy) return
        result = null
        cloudJob = job
        startPhotoWork {
            installPhoto(history.firstOrNull { it.id == job.id }?.before,
                backend.downloadJob(job.id), job.elapsedMs, job.id)
        }
    }

    fun deleteCloudResult(job: CloudJob) {
        val backend = api ?: return
        viewModelScope.launch {
            try {
                if (cloudJob?.id == job.id) pausePhotoPolling()
                backend.deleteJob(job.id)
                if (cloudJob?.id == job.id) { cloudJob = null; clearPendingCloud() }
                history = history.filter { it.id != job.id }
                if (result?.id == job.id) result = null
                refreshCloudLibrary()
                notice = "Đã xóa ảnh trên cloud. Ảnh đã lưu trong thư viện máy vẫn được giữ."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleFailure(e, "Chưa xóa được ảnh trên cloud.") }
        }
    }

    fun forgetUnconfirmedCloudRequest() {
        if (photoBusy || cloudJob != null) return
        clearPendingCloud()
        pendingBefore = null
        error = null
    }

    private fun clearPendingCloud() {
        member?.uid?.let { prefs.edit().remove(pendingKey(it)).apply() }
        pendingCloudRequest = false
    }

    private fun pausePhotoPolling() {
        photoGeneration++
        photoJob?.cancel(); photoJob = null; photoBusy = false
    }

    fun cancelPhoto() {
        if (!photoBusy && !pendingCloudRequest) return
        val backend = api
        val cloud = usingCloud
        pausePhotoPolling()
        if (!cloud || backend == null) {
            notice = "Đã hủy chờ. Dịch vụ có thể vẫn xử lý yêu cầu đã nhận."
            return
        }
        viewModelScope.launch {
            try {
                val uid = member?.uid ?: return@launch
                val id = cloudJob?.id ?: prefs.getString(pendingKey(uid), null)?.let { backend.jobByRequest(it).id }
                if (id == null) throw BackendException("Chưa xác định được tác vụ. Hãy kiểm tra lại lịch sử.")
                backend.cancelJob(id)
                cloudJob = backend.job(id)
                clearPendingCloud(); refreshCloudLibrary()
                notice = "Đã gửi lệnh hủy. Yêu cầu đã được dịch vụ ảnh nhận vẫn có thể tính vào hạn mức."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleFailure(e, "Chưa xác nhận được lệnh hủy. Kiểm tra lại tiến trình.") }
        }
    }

    fun showResult(output: PhotoResult) { result = output }

    fun forgetSessionPhotos() {
        if (photoBusy || loadingPhoto || savingPhoto) return
        stop()
        garment = null; person = null; recent = emptyList(); result = null; history = emptyList()
        pendingBefore = null
        savedResultIds = emptySet()
        notice = "Đã xóa ảnh khỏi phiên này. Ảnh đã lưu trong thư viện vẫn được giữ."
    }

    fun saveResult() {
        val output = result ?: return
        if (savingPhoto || output.id in savedResultIds) return
        savingPhoto = true
        viewModelScope.launch {
            try {
                Photos.save(getApplication(), output.png)
                savedResultIds = savedResultIds + output.id
                notice = "Đã lưu vào Pictures/GuongThuDo."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Không lưu được ảnh: ${e.message}" }
            finally { savingPhoto = false }
        }
    }
    override fun onCleared() { stop(); api?.close(); super.onCleared() }
}
