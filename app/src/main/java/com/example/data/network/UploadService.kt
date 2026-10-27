package com.example.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class StorageProvider(val displayName: String, val maxSizeDesc: String, val retentionDesc: String) {
    CATBOX("Catbox.moe", "Max 200 MB", "Permanent Direct Link"),
    LITTERBOX("Litterbox (Large)", "Max 1 GB", "Direct Link (72 Hours)"),
    FILE_IO("File.io", "Max 500 MB", "Direct 1-time Link"),
    CUSTOM_ENDPOINT("Custom Server", "Configurable", "Your Private Endpoint")
}

data class UploadProgress(
    val bytesWritten: Long,
    val totalBytes: Long,
    val percent: Int,
    val speedBytesPerSec: Long
)

sealed class UploadResult {
    data class Success(
        val downloadUrl: String,
        val provider: StorageProvider,
        val originalFileName: String,
        val fileSize: Long,
        val expiryInfo: String
    ) : UploadResult()

    data class Error(val message: String) : UploadResult()
}

class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long {
        return try {
            delegate.contentLength()
        } catch (_: IOException) {
            -1L
        }
    }

    override fun writeTo(sink: BufferedSink) {
        val totalLength = contentLength()
        val countingSink = object : ForwardingSink(sink) {
            var bytesWritten = 0L
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                bytesWritten += byteCount
                onProgress(bytesWritten, totalLength)
            }
        }
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
}

class UploadService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun uploadFile(
        file: File,
        provider: StorageProvider,
        customEndpoint: String? = null,
        customDomainPrefix: String? = null,
        onProgress: (UploadProgress) -> Unit
    ): UploadResult = withContext(Dispatchers.IO) {
        var startTime = System.currentTimeMillis()
        var lastBytes = 0L

        val progressCallback: (Long, Long) -> Unit = { written, total ->
            val now = System.currentTimeMillis()
            val timeDiff = (now - startTime).coerceAtLeast(1)
            val speed = if (timeDiff > 0) ((written - lastBytes) * 1000) / timeDiff else 0L
            val percent = if (total > 0) ((written * 100) / total).toInt().coerceIn(0, 100) else 0

            onProgress(
                UploadProgress(
                    bytesWritten = written,
                    totalBytes = total,
                    percent = percent,
                    speedBytesPerSec = speed
                )
            )
        }

        try {
            when (provider) {
                StorageProvider.CATBOX -> uploadToCatbox(file, progressCallback, customDomainPrefix)
                StorageProvider.LITTERBOX -> uploadToLitterbox(file, progressCallback, customDomainPrefix)
                StorageProvider.FILE_IO -> uploadToFileIo(file, progressCallback, customDomainPrefix)
                StorageProvider.CUSTOM_ENDPOINT -> uploadToCustomEndpoint(
                    file,
                    customEndpoint ?: "",
                    progressCallback,
                    customDomainPrefix
                )
            }
        } catch (e: Exception) {
            UploadResult.Error("Upload failed: ${e.localizedMessage ?: "Network connection error"}")
        }
    }

    private fun uploadToCatbox(
        file: File,
        onProgress: (Long, Long) -> Unit,
        customDomainPrefix: String?
    ): UploadResult {
        val fileBody = RequestBody.create("application/octet-stream".toMediaTypeOrNull(), file)
        val countingBody = CountingRequestBody(fileBody, onProgress)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", file.name, countingBody)
            .build()

        val request = Request.Builder()
            .url("https://catbox.moe/user/api.php")
            .header("User-Agent", "ApkLinkGen/1.0")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()?.trim() ?: ""
            if (response.isSuccessful && responseBody.startsWith("http")) {
                val finalUrl = applyCustomDomain(responseBody, customDomainPrefix)
                return UploadResult.Success(
                    downloadUrl = finalUrl,
                    provider = StorageProvider.CATBOX,
                    originalFileName = file.name,
                    fileSize = file.length(),
                    expiryInfo = "Permanent Direct Link"
                )
            } else {
                return UploadResult.Error("Catbox error: $responseBody (HTTP ${response.code})")
            }
        }
    }

    private fun uploadToLitterbox(
        file: File,
        onProgress: (Long, Long) -> Unit,
        customDomainPrefix: String?
    ): UploadResult {
        val fileBody = RequestBody.create("application/octet-stream".toMediaTypeOrNull(), file)
        val countingBody = CountingRequestBody(fileBody, onProgress)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", "72h")
            .addFormDataPart("fileToUpload", file.name, countingBody)
            .build()

        val request = Request.Builder()
            .url("https://litterbox.catbox.moe/resources/internals/api.php")
            .header("User-Agent", "ApkLinkGen/1.0")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()?.trim() ?: ""
            if (response.isSuccessful && responseBody.startsWith("http")) {
                val finalUrl = applyCustomDomain(responseBody, customDomainPrefix)
                return UploadResult.Success(
                    downloadUrl = finalUrl,
                    provider = StorageProvider.LITTERBOX,
                    originalFileName = file.name,
                    fileSize = file.length(),
                    expiryInfo = "Active for 72 Hours (Direct Link)"
                )
            } else {
                return UploadResult.Error("Litterbox error: $responseBody (HTTP ${response.code})")
            }
        }
    }

    private fun uploadToFileIo(
        file: File,
        onProgress: (Long, Long) -> Unit,
        customDomainPrefix: String?
    ): UploadResult {
        val fileBody = RequestBody.create("application/octet-stream".toMediaTypeOrNull(), file)
        val countingBody = CountingRequestBody(fileBody, onProgress)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, countingBody)
            .build()

        val request = Request.Builder()
            .url("https://file.io")
            .header("User-Agent", "ApkLinkGen/1.0")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                try {
                    val json = JSONObject(responseBody)
                    val link = json.optString("link", "")
                    if (link.isNotBlank()) {
                        val finalUrl = applyCustomDomain(link, customDomainPrefix)
                        return UploadResult.Success(
                            downloadUrl = finalUrl,
                            provider = StorageProvider.FILE_IO,
                            originalFileName = file.name,
                            fileSize = file.length(),
                            expiryInfo = "Direct Link (Expires after download or 14 days)"
                        )
                    }
                } catch (_: Exception) {}
            }
            return UploadResult.Error("File.io error: $responseBody (HTTP ${response.code})")
        }
    }

    private fun uploadToCustomEndpoint(
        file: File,
        endpoint: String,
        onProgress: (Long, Long) -> Unit,
        customDomainPrefix: String?
    ): UploadResult {
        if (endpoint.isBlank() || !endpoint.startsWith("http")) {
            return UploadResult.Error("Please provide a valid custom server URL starting with http:// or https://")
        }

        val fileBody = RequestBody.create("application/octet-stream".toMediaTypeOrNull(), file)
        val countingBody = CountingRequestBody(fileBody, onProgress)

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, countingBody)
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()?.trim() ?: ""
            if (response.isSuccessful) {
                // If it returned a URL or JSON with link/url
                var downloadUrl = responseBody
                if (responseBody.startsWith("{")) {
                    try {
                        val json = JSONObject(responseBody)
                        downloadUrl = json.optString("url", json.optString("link", json.optString("download_url", "")))
                    } catch (_: Exception) {}
                }
                if (downloadUrl.startsWith("http")) {
                    val finalUrl = applyCustomDomain(downloadUrl, customDomainPrefix)
                    return UploadResult.Success(
                        downloadUrl = finalUrl,
                        provider = StorageProvider.CUSTOM_ENDPOINT,
                        originalFileName = file.name,
                        fileSize = file.length(),
                        expiryInfo = "Custom Server Link"
                    )
                }
            }
            return UploadResult.Error("Custom Server response error: $responseBody")
        }
    }

    private fun applyCustomDomain(url: String, domainPrefix: String?): String {
        if (domainPrefix.isNullOrBlank()) return url
        val cleanDomain = domainPrefix.trim().trimEnd('/')
        return "$cleanDomain/dl?target=${java.net.URLEncoder.encode(url, "UTF-8")}"
    }
}
