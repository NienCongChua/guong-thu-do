package com.example.myapplication

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Capabilities(
    val decart: Boolean, val openai: Boolean, val maxSeconds: Int,
    val fashn: Boolean = false, val photo: Boolean = openai,
    val imageProvider: String = if (openai) "OpenAI" else "",
    val fallbackAvailable: Boolean = false, val asyncPhotos: Boolean = false
)
data class MemberQuota(val imagesLimit: Int, val imagesUsed: Int, val liveLimit: Int, val liveUsed: Int)
data class Member(val uid: String, val email: String, val quota: MemberQuota, val activeJob: CloudJob?)
data class CloudJob(
    val id: String, val status: String, val provider: String,
    val fallbackUsed: Boolean, val elapsedMs: Long, val errorCode: String, val expiresAt: Long
) {
    val pending get() = status in setOf("uploading", "queued", "processing")
    val completed get() = status == "completed"
}
data class LiveToken(val key: String, val maxSeconds: Int)
class EditedPhoto(val png: ByteArray, val provider: String, val fallbackUsed: Boolean)
enum class PhotoQuality(val label: String, val apiName: String) {
    FAST("Nhanh", "fast"), DETAIL("Chi tiết", "detail")
}
class BackendException(message: String, val code: String = "", val status: Int = 0) : IOException(message)

class BackendApi(rawUrl: String, private val tokenProvider: (suspend () -> String)? = null) {
    val baseUrl: String
    var bearer: String = ""
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val tokenHttp = http.newBuilder().readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val imageHttp = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).readTimeout(190, TimeUnit.SECONDS)
        .callTimeout(195, TimeUnit.SECONDS).build()
    private val uploadHttp = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    init {
        val url = rawUrl.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
            "Địa chỉ backend không được chứa mật khẩu, query hay fragment."
        }
        require(url.encodedPath == "/") { "Nhập địa chỉ gốc của backend." }
        require((BuildConfig.DEBUG && tokenProvider == null) || url.isHttps) { "Dịch vụ cloud yêu cầu HTTPS." }
        baseUrl = url.toString().trimEnd('/')
    }

    suspend fun login(password: String) {
        check(BuildConfig.DEBUG && tokenProvider == null) { "Mật khẩu demo chỉ dùng trong môi trường phát triển." }
        val body = JSONObject().put("password", password).toString().toRequestBody("application/json".toMediaType())
        bearer = JSONObject(request("/api/login", body, auth = false).bytes.decodeToString()).getString("token")
    }

    suspend fun capabilities(): Capabilities {
        val json = JSONObject(request("/api/capabilities").bytes.decodeToString())
        val openai = json.getBoolean("openai")
        return Capabilities(
            decart = json.getBoolean("decart"), openai = openai, maxSeconds = json.getInt("maxSessionSeconds"),
            fashn = json.optBoolean("fashn"), photo = json.optBoolean("photo", openai),
            imageProvider = json.optString("imageProvider", if (openai) "OpenAI" else ""),
            fallbackAvailable = json.optBoolean("fallbackAvailable"), asyncPhotos = json.optBoolean("asyncPhotos")
        )
    }

    suspend fun member(): Member {
        val json = JSONObject(request("/api/me").bytes.decodeToString())
        val quota = json.getJSONObject("quota")
        return Member(json.getString("uid"), json.getString("email"),
            MemberQuota(quota.getInt("imagesLimit"), quota.getInt("imagesUsed"), quota.getInt("liveLimit"), quota.getInt("liveUsed")),
            json.optJSONObject("activeJob")?.let(::parseJob))
    }

    suspend fun liveToken(): LiveToken {
        val json = JSONObject(request("/api/realtime-token", ByteArray(0).toRequestBody(), client = tokenHttp,
            headers = mapOf("Idempotency-Key" to UUID.randomUUID().toString())).bytes.decodeToString())
        return LiveToken(json.getString("apiKey"), json.getInt("maxSessionSeconds"))
    }

    suspend fun edit(
        person: PhotoAsset, garment: PhotoAsset, kind: GarmentKind,
        quality: PhotoQuality, allowFallback: Boolean
    ): EditedPhoto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("person", "person.png", person.imageBytes.toRequestBody("image/png".toMediaType()))
            .addFormDataPart("garment", "garment.png", garment.imageBytes.toRequestBody("image/png".toMediaType()))
            .addFormDataPart("kind", kind.apiName).addFormDataPart("quality", quality.apiName)
            .addFormDataPart("allowFallback", allowFallback.toString()).build()
        return image(request("/api/try-on-photo", body, client = imageHttp))
    }

    suspend fun createJob(
        person: PhotoAsset, garment: PhotoAsset, kind: GarmentKind,
        quality: PhotoQuality, allowFallback: Boolean, requestKey: String
    ): CloudJob {
        // Upload a bounded JPEG copy. Keep original sRGB bitmap/PNG for local
        // comparison and live garment input. Encoding stays off the UI thread.
        val body = withContext(Dispatchers.Default) {
            fun jpeg(asset: PhotoAsset): ByteArray = ByteArrayOutputStream().use {
                check(asset.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) { "Không chuẩn bị được ảnh tải lên." }
                it.toByteArray().also { bytes ->
                    require(bytes.size <= 6 * 1024 * 1024) { "Ảnh quá lớn. Hãy chọn ảnh nhỏ hơn 6 MB." }
                }
            }
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("person", "person.jpg", jpeg(person).toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("garment", "garment.jpg", jpeg(garment).toRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("kind", kind.apiName).addFormDataPart("quality", quality.apiName)
                .addFormDataPart("allowFallback", allowFallback.toString()).build()
        }
        val json = JSONObject(request("/api/jobs", body, client = uploadHttp,
            headers = mapOf("Idempotency-Key" to requestKey)).bytes.decodeToString())
        return parseJob(json.getJSONObject("job"))
    }

    suspend fun jobs(): List<CloudJob> {
        val json = JSONObject(request("/api/jobs").bytes.decodeToString()).getJSONArray("jobs")
        return (0 until json.length()).map { parseJob(json.getJSONObject(it)) }
    }

    suspend fun job(id: String): CloudJob =
        parseJob(JSONObject(request(jobPath(id)).bytes.decodeToString()).getJSONObject("job"))

    suspend fun jobByRequest(key: String): CloudJob {
        require(UUID.fromString(key).toString() == key.lowercase()) { "Mã thao tác không hợp lệ." }
        return parseJob(JSONObject(request("/api/jobs/by-request/$key").bytes.decodeToString()).getJSONObject("job"))
    }

    suspend fun downloadJob(id: String): EditedPhoto = image(request(jobPath(id) + "/result", client = imageHttp))

    suspend fun cancelJob(id: String) {
        request(jobPath(id) + "/cancel", ByteArray(0).toRequestBody())
    }

    suspend fun deleteJob(id: String) { request(jobPath(id), method = "DELETE") }
    suspend fun deleteAccount() { request("/api/account", method = "DELETE") }

    private fun jobPath(id: String): String {
        require(runCatching { UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)) { "Mã tác vụ không hợp lệ." }
        return "/api/jobs/$id"
    }

    private fun parseJob(json: JSONObject) = CloudJob(
        id = json.getString("id"), status = json.getString("status"),
        provider = json.optString("provider").takeUnless { it == "null" }.orEmpty(),
        fallbackUsed = json.optBoolean("fallbackUsed"), elapsedMs = json.optLong("elapsedMs"),
        errorCode = json.optString("errorCode").takeUnless { it == "null" }.orEmpty(),
        expiresAt = json.optLong("expiresAt")
    )

    private fun image(response: ApiResponse): EditedPhoto {
        if (response.contentType?.substringBefore(';') != "image/png")
            throw BackendException("Dịch vụ không trả về ảnh PNG hợp lệ.")
        return EditedPhoto(response.bytes, response.provider, response.fallbackUsed)
    }

    private data class ApiResponse(
        val bytes: ByteArray, val contentType: String?, val provider: String, val fallbackUsed: Boolean
    )

    private suspend fun request(
        path: String, body: RequestBody? = null, auth: Boolean = true, client: OkHttpClient = http,
        method: String = if (body == null) "GET" else "POST", headers: Map<String, String> = emptyMap()
    ): ApiResponse {
        // Firebase refreshes an expired ID token before the request. Never replay
        // an image/token POST on a 401 or ambiguous transport failure.
        val token = if (auth) tokenProvider?.invoke() ?: bearer else ""
        return suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(baseUrl + path).method(method, body).apply {
                if (auth) header("Authorization", "Bearer $token")
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BackendException(
                        "Không kết nối được dịch vụ. Kiểm tra mạng; tác vụ cloud đã nhận vẫn tiếp tục trong nền.",
                        code = "CONNECTION"
                    ))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            val source = it.body?.source() ?: throw BackendException("Dịch vụ trả về dữ liệu trống.")
                            val limit = if (it.isSuccessful) 30L * 1024 * 1024 else 64L * 1024
                            source.request(limit + 1)
                            if (source.buffer.size > limit) throw BackendException("Phản hồi dịch vụ quá lớn.")
                            val bytes = source.readByteArray()
                            if (!it.isSuccessful) {
                                val json = runCatching { JSONObject(bytes.decodeToString()) }.getOrNull()
                                throw BackendException(
                                    json?.optString("error")?.takeIf(String::isNotBlank) ?: "Dịch vụ trả lỗi HTTP ${it.code}.",
                                    json?.optString("code").orEmpty(), it.code
                                )
                            }
                            ApiResponse(bytes, it.header("Content-Type"), it.header("X-Image-Provider") ?: "OpenAI",
                                it.header("X-Image-Fallback") == "true")
                        }
                        if (continuation.isActive) continuation.resume(data)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    fun close() {
        bearer = ""
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }
}
