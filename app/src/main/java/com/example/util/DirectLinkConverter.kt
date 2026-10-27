package com.example.util

import java.util.regex.Pattern

object DirectLinkConverter {

    fun convertToDirectLink(inputUrl: String, customDomain: String? = null): DirectConvertResult {
        val trimmed = inputUrl.trim()
        if (trimmed.isEmpty()) {
            return DirectConvertResult.Error("URL cannot be empty")
        }

        // 1. Google Drive
        // Formats:
        // https://drive.google.com/file/d/FILE_ID/view...
        // https://drive.google.com/open?id=FILE_ID
        // https://docs.google.com/uc?id=FILE_ID
        val gDrivePattern = Pattern.compile("https?://(?:drive|docs)\\.google\\.com/(?:file/d/|open\\?id=|uc\\?id=)([a-zA-Z0-9_-]+)")
        val gDriveMatcher = gDrivePattern.matcher(trimmed)
        if (gDriveMatcher.find()) {
            val fileId = gDriveMatcher.group(1)
            val direct = "https://drive.google.com/uc?export=download&id=$fileId"
            return DirectConvertResult.Success(
                directUrl = applyCustomDomain(direct, customDomain),
                originalUrl = trimmed,
                serviceName = "Google Drive",
                notes = "Direct 1-click download link generated. Large files may show a Google virus scan warning on download."
            )
        }

        // 2. Dropbox
        // Formats:
        // https://www.dropbox.com/s/abcdef12345/app.apk?dl=0
        // https://www.dropbox.com/scl/fi/...
        if (trimmed.contains("dropbox.com/")) {
            var direct = trimmed
            if (direct.contains("?dl=0")) {
                direct = direct.replace("?dl=0", "?dl=1")
            } else if (direct.contains("&dl=0")) {
                direct = direct.replace("&dl=0", "&dl=1")
            } else if (!direct.contains("dl=1")) {
                direct = if (direct.contains("?")) "$direct&dl=1" else "$direct?dl=1"
            }
            direct = direct.replace("www.dropbox.com", "dl.dropboxusercontent.com")
            return DirectConvertResult.Success(
                directUrl = applyCustomDomain(direct, customDomain),
                originalUrl = trimmed,
                serviceName = "Dropbox",
                notes = "Direct download link created. Clicks will trigger instant file download."
            )
        }

        // 3. GitHub Blob to Raw
        // https://github.com/owner/repo/blob/branch/path/app.apk
        if (trimmed.contains("github.com/") && trimmed.contains("/blob/")) {
            val direct = trimmed.replace("github.com/", "raw.githubusercontent.com/").replace("/blob/", "/")
            return DirectConvertResult.Success(
                directUrl = applyCustomDomain(direct, customDomain),
                originalUrl = trimmed,
                serviceName = "GitHub",
                notes = "Direct raw link generated from GitHub repository."
            )
        }

        // 4. Mediafire
        if (trimmed.contains("mediafire.com/file/")) {
            return DirectConvertResult.Success(
                directUrl = applyCustomDomain(trimmed, customDomain),
                originalUrl = trimmed,
                serviceName = "MediaFire",
                notes = "MediaFire file recognized."
            )
        }

        // 5. Generic direct link check
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val direct = applyCustomDomain(trimmed, customDomain)
            return DirectConvertResult.Success(
                directUrl = direct,
                originalUrl = trimmed,
                serviceName = "Direct / Web Link",
                notes = "Direct link formatted and ready for sharing."
            )
        }

        return DirectConvertResult.Error("Invalid or unsupported URL format. Please enter a valid HTTP/HTTPS link.")
    }

    private fun applyCustomDomain(url: String, customDomain: String?): String {
        if (customDomain.isNullOrBlank()) return url
        val cleanDomain = customDomain.trim().trimEnd('/')
        return "$cleanDomain?dl=${java.net.URLEncoder.encode(url, "UTF-8")}"
    }

    sealed class DirectConvertResult {
        data class Success(
            val directUrl: String,
            val originalUrl: String,
            val serviceName: String,
            val notes: String
        ) : DirectConvertResult()

        data class Error(val message: String) : DirectConvertResult()
    }
}
