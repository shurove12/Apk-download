package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.db.GeneratedLink
import com.example.data.network.StorageProvider
import com.example.data.network.UploadProgress
import com.example.data.network.UploadResult
import com.example.data.network.UploadService
import com.example.util.ApkInfoExtractor
import com.example.util.DirectLinkConverter
import com.example.util.FileAnalysisResult
import com.example.util.InstalledAppItem
import com.example.util.LocalFileServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val linkDao = db.generatedLinkDao()
    private val uploadService = UploadService()
    private val localFileServer = LocalFileServer(application)

    val historyList: StateFlow<List<GeneratedLink>> = linkDao.getAllLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedFile = MutableStateFlow<FileAnalysisResult?>(null)
    val selectedFile: StateFlow<FileAnalysisResult?> = _selectedFile.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    private val _latestResult = MutableStateFlow<UploadResult.Success?>(null)
    val latestResult: StateFlow<UploadResult.Success?> = _latestResult.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _selectedProvider = MutableStateFlow(StorageProvider.CATBOX)
    val selectedProvider: StateFlow<StorageProvider> = _selectedProvider.asStateFlow()

    private val _customEndpoint = MutableStateFlow("")
    val customEndpoint: StateFlow<String> = _customEndpoint.asStateFlow()

    private val _customDomain = MutableStateFlow("")
    val customDomain: StateFlow<String> = _customDomain.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppItem>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _isLocalServerRunning = MutableStateFlow(false)
    val isLocalServerRunning: StateFlow<Boolean> = _isLocalServerRunning.asStateFlow()

    private val _localServerUrl = MutableStateFlow<String?>(null)
    val localServerUrl: StateFlow<String?> = _localServerUrl.asStateFlow()

    private val _qrModalUrl = MutableStateFlow<String?>(null)
    val qrModalUrl: StateFlow<String?> = _qrModalUrl.asStateFlow()

    private val _convertUrlInput = MutableStateFlow("")
    val convertUrlInput: StateFlow<String> = _convertUrlInput.asStateFlow()

    private val _convertResult = MutableStateFlow<DirectLinkConverter.DirectConvertResult?>(null)
    val convertResult: StateFlow<DirectLinkConverter.DirectConvertResult?> = _convertResult.asStateFlow()

    private var uploadJob: Job? = null

    fun selectProvider(provider: StorageProvider) {
        _selectedProvider.value = provider
    }

    fun setCustomEndpoint(value: String) {
        _customEndpoint.value = value
    }

    fun setCustomDomain(value: String) {
        _customDomain.value = value
    }

    fun setConvertUrlInput(value: String) {
        _convertUrlInput.value = value
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun showQr(url: String) {
        _qrModalUrl.value = url
    }

    fun dismissQr() {
        _qrModalUrl.value = null
    }

    fun selectFileUri(uri: Uri) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _errorMessage.value = null
            _latestResult.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    ApkInfoExtractor.analyzeUri(getApplication(), uri)
                }
                _selectedFile.value = result
            } catch (e: Exception) {
                _errorMessage.value = "Failed to process file: ${e.localizedMessage}"
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun loadInstalledApps() {
        if (_installedApps.value.isNotEmpty()) return
        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                val list = withContext(Dispatchers.IO) {
                    ApkInfoExtractor.getInstalledApps(getApplication())
                }
                _installedApps.value = list
            } catch (e: Exception) {
                _errorMessage.value = "Failed to list apps: ${e.localizedMessage}"
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun selectInstalledApp(app: InstalledAppItem) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            _errorMessage.value = null
            _latestResult.value = null
            try {
                val cachedFile = withContext(Dispatchers.IO) {
                    val src = File(app.sourceDir)
                    val tempDir = File(getApplication<Application>().cacheDir, "extracted_apks").apply { mkdirs() }
                    val cleanName = "${app.appName.replace(" ", "_")}_v${app.versionName ?: "1.0"}.apk"
                    val dest = File(tempDir, cleanName)
                    FileInputStream(src).use { inStream ->
                        FileOutputStream(dest).use { outStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                    dest
                }

                val analysis = FileAnalysisResult(
                    fileName = cachedFile.name,
                    fileSize = cachedFile.length(),
                    mimeType = "application/vnd.android.package-archive",
                    isApk = true,
                    appName = app.appName,
                    packageName = app.packageName,
                    versionName = app.versionName,
                    versionCode = null,
                    iconBitmap = null,
                    tempCachedFile = cachedFile,
                    sha256Checksum = null
                )
                _selectedFile.value = analysis
            } catch (e: Exception) {
                _errorMessage.value = "Failed to extract APK: ${e.localizedMessage}"
            } finally {
                _isAnalyzing.value = false
            }
        }
    }

    fun clearSelectedFile() {
        _selectedFile.value = null
        _latestResult.value = null
        _uploadProgress.value = null
        stopLocalServer()
    }

    fun startUpload() {
        val fileInfo = _selectedFile.value ?: return
        val targetFile = fileInfo.tempCachedFile
        if (targetFile == null || !targetFile.exists()) {
            _errorMessage.value = "File is not accessible for upload."
            return
        }

        uploadJob?.cancel()
        _isUploading.value = true
        _errorMessage.value = null
        _uploadProgress.value = UploadProgress(0, targetFile.length(), 0, 0)

        uploadJob = viewModelScope.launch {
            val result = uploadService.uploadFile(
                file = targetFile,
                provider = _selectedProvider.value,
                customEndpoint = _customEndpoint.value.takeIf { it.isNotBlank() },
                customDomainPrefix = _customDomain.value.takeIf { it.isNotBlank() },
                onProgress = { progress ->
                    _uploadProgress.value = progress
                }
            )

            _isUploading.value = false

            when (result) {
                is UploadResult.Success -> {
                    _latestResult.value = result

                    // Save to Room Database
                    val entity = GeneratedLink(
                        fileName = fileInfo.fileName,
                        fileSize = fileInfo.fileSize,
                        mimeType = fileInfo.mimeType,
                        isApk = fileInfo.isApk,
                        appName = fileInfo.appName,
                        packageName = fileInfo.packageName,
                        versionName = fileInfo.versionName,
                        downloadUrl = result.downloadUrl,
                        providerName = result.provider.displayName,
                        expiryInfo = result.expiryInfo,
                        customDomain = _customDomain.value.takeIf { it.isNotBlank() }
                    )
                    withContext(Dispatchers.IO) {
                        linkDao.insertLink(entity)
                    }
                }
                is UploadResult.Error -> {
                    _errorMessage.value = result.message
                }
            }
        }
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        _isUploading.value = false
        _uploadProgress.value = null
    }

    fun startLocalServer() {
        val fileInfo = _selectedFile.value ?: return
        val targetFile = fileInfo.tempCachedFile ?: return
        localFileServer.start(
            file = targetFile,
            fileName = fileInfo.fileName,
            onStarted = { url ->
                _isLocalServerRunning.value = true
                _localServerUrl.value = url
            },
            onError = { err ->
                _errorMessage.value = err
                _isLocalServerRunning.value = false
                _localServerUrl.value = null
            }
        )
    }

    fun stopLocalServer() {
        localFileServer.stop()
        _isLocalServerRunning.value = false
        _localServerUrl.value = null
    }

    fun convertExternalLink() {
        val input = _convertUrlInput.value.trim()
        if (input.isEmpty()) return
        val result = DirectLinkConverter.convertToDirectLink(input, _customDomain.value.takeIf { it.isNotBlank() })
        _convertResult.value = result

        if (result is DirectLinkConverter.DirectConvertResult.Success) {
            viewModelScope.launch(Dispatchers.IO) {
                val entity = GeneratedLink(
                    fileName = "Converted_${result.serviceName}_link",
                    fileSize = 0L,
                    mimeType = "application/octet-stream",
                    isApk = input.contains(".apk", ignoreCase = true),
                    appName = null,
                    packageName = null,
                    versionName = null,
                    downloadUrl = result.directUrl,
                    providerName = "${result.serviceName} Direct",
                    expiryInfo = "Permanent / Direct",
                    customDomain = _customDomain.value.takeIf { it.isNotBlank() }
                )
                linkDao.insertLink(entity)
            }
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            linkDao.deleteById(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            linkDao.deleteAll()
        }
    }

    fun toggleFavorite(id: Long, currentFav: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            linkDao.updateFavorite(id, !currentFav)
        }
    }

    override fun onCleared() {
        super.onCleared()
        localFileServer.stop()
    }
}
