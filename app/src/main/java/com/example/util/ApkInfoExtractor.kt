package com.example.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

data class FileAnalysisResult(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isApk: Boolean,
    val appName: String? = null,
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val iconBitmap: Bitmap? = null,
    val tempCachedFile: File? = null,
    val sha256Checksum: String? = null
)

data class InstalledAppItem(
    val appName: String,
    val packageName: String,
    val versionName: String?,
    val sourceDir: String,
    val icon: Drawable? = null,
    val sizeBytes: Long = 0L
)

object ApkInfoExtractor {

    fun analyzeUri(context: Context, uri: Uri): FileAnalysisResult {
        val contentResolver = context.contentResolver
        var fileName = "file"
        var fileSize = 0L

        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: "file"
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val mimeType = contentResolver.getType(uri) ?: when {
            fileName.endsWith(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
            fileName.endsWith(".zip", ignoreCase = true) -> "application/zip"
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".jpg", ignoreCase = true) || fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            else -> "application/octet-stream"
        }

        val isApk = fileName.endsWith(".apk", ignoreCase = true) ||
                mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)

        // Cache a small copy or full temp file to analyze if it's an APK or for uploading
        val tempDir = File(context.cacheDir, "picked_files").apply { mkdirs() }
        val tempFile = File(tempDir, fileName)

        var sha256: String? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }
            if (fileSize <= 0) {
                fileSize = tempFile.length()
            }
            val hashBytes = digest.digest()
            sha256 = hashBytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // fallback
        }

        var appName: String? = null
        var packageName: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var iconBitmap: Bitmap? = null

        if (isApk && tempFile.exists()) {
            try {
                val pm = context.packageManager
                val pkgInfo: PackageInfo? = pm.getPackageArchiveInfo(tempFile.absolutePath, PackageManager.GET_META_DATA)
                val appInfo = pkgInfo?.applicationInfo
                if (pkgInfo != null && appInfo != null) {
                    appInfo.sourceDir = tempFile.absolutePath
                    appInfo.publicSourceDir = tempFile.absolutePath

                    packageName = pkgInfo.packageName
                    versionName = pkgInfo.versionName
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pkgInfo.longVersionCode
                    } else {
                        @Suppress("DEPRECATION")
                        pkgInfo.versionCode.toLong()
                    }
                    appName = pm.getApplicationLabel(appInfo).toString()
                    val iconDrawable = pm.getApplicationIcon(appInfo)
                    iconBitmap = drawableToBitmap(iconDrawable)
                }
            } catch (_: Exception) {
                // Not standard APK archive or corrupted
            }
        }

        return FileAnalysisResult(
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            isApk = isApk,
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            iconBitmap = iconBitmap,
            tempCachedFile = tempFile,
            sha256Checksum = sha256
        )
    }

    fun getInstalledApps(context: Context): List<InstalledAppItem> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        val list = mutableListOf<InstalledAppItem>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            // Exclude system apps with flags unless updated
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem) continue

            val label = pm.getApplicationLabel(appInfo).toString()
            val sourceDir = appInfo.sourceDir ?: continue
            val file = File(sourceDir)
            if (!file.exists()) continue

            list.add(
                InstalledAppItem(
                    appName = label,
                    packageName = pkg.packageName,
                    versionName = pkg.versionName,
                    sourceDir = sourceDir,
                    icon = pm.getApplicationIcon(appInfo),
                    sizeBytes = file.length()
                )
            )
        }
        return list.sortedBy { it.appName.lowercase(Locale.ROOT) }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val clampedIndex = digitGroups.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, clampedIndex.toDouble())
        return String.format(Locale.US, "%.2f %s", value, units[clampedIndex])
    }
}
