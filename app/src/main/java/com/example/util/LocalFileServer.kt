package com.example.util

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

class LocalFileServer(
    private val context: Context,
    private val port: Int = 8080
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var servingFile: File? = null
    private var servingFileName: String = "app.apk"

    fun start(file: File, fileName: String, onStarted: (url: String) -> Unit, onError: (String) -> Unit) {
        if (isRunning.get()) {
            stop()
        }
        servingFile = file
        servingFileName = fileName

        Thread {
            try {
                val socket = ServerSocket(port)
                serverSocket = socket
                isRunning.set(true)
                val ip = getLocalIpAddress()
                val url = "http://$ip:$port/download/${URLEncoder.encode(fileName, "UTF-8")}"
                onStarted(url)

                while (isRunning.get()) {
                    try {
                        val client = socket.accept()
                        handleClient(client)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            } catch (e: Exception) {
                onError("Failed to start local server: ${e.localizedMessage ?: "Port in use"}")
                stop()
            }
        }.start()
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    fun isServerRunning(): Boolean = isRunning.get()

    private fun handleClient(client: Socket) {
        Thread {
            try {
                client.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val line = reader.readLine() ?: return@use
                    val parts = line.split(" ")
                    if (parts.size < 2) return@use
                    val method = parts[0]
                    val path = parts[1]

                    val out = BufferedOutputStream(s.getOutputStream())

                    val file = servingFile
                    if (file == null || !file.exists()) {
                        val notFound = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 14\r\n\r\nFile Not Found"
                        out.write(notFound.toByteArray())
                        out.flush()
                        return@use
                    }

                    if (path.startsWith("/download")) {
                        // Serve direct file download
                        val fileSize = file.length()
                        val header = StringBuilder()
                            .append("HTTP/1.1 200 OK\r\n")
                            .append("Content-Type: application/vnd.android.package-archive\r\n")
                            .append("Content-Length: $fileSize\r\n")
                            .append("Content-Disposition: attachment; filename=\"$servingFileName\"\r\n")
                            .append("Connection: close\r\n")
                            .append("Access-Control-Allow-Origin: *\r\n")
                            .append("\r\n")

                        out.write(header.toString().toByteArray())

                        val buffer = ByteArray(64 * 1024)
                        BufferedInputStream(FileInputStream(file)).use { fis ->
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                        }
                        out.flush()
                    } else {
                        // Landing page with download button
                        val html = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta charset="utf-8">
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <title>Download $servingFileName</title>
                                <style>
                                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; padding: 20px; }
                                    .card { background: #1e293b; border-radius: 16px; padding: 32px; max-width: 440px; width: 100%; text-align: center; box-shadow: 0 10px 25px rgba(0,0,0,0.5); border: 1px solid #334155; }
                                    .badge { background: #3b82f6; color: white; padding: 4px 12px; border-radius: 999px; font-size: 12px; font-weight: bold; text-transform: uppercase; }
                                    h1 { margin-top: 16px; font-size: 20px; word-break: break-all; }
                                    p { color: #94a3b8; font-size: 14px; margin-bottom: 24px; }
                                    .btn { display: block; background: #2563eb; color: white; text-decoration: none; padding: 14px 24px; border-radius: 12px; font-weight: bold; font-size: 16px; transition: background 0.2s; box-shadow: 0 4px 12px rgba(37,99,235,0.4); }
                                    .btn:hover { background: #1d4ed8; }
                                </style>
                            </head>
                            <body>
                                <div class="card">
                                    <span class="badge">Direct File Share</span>
                                    <h1>$servingFileName</h1>
                                    <p>File Size: ${ApkInfoExtractor.formatBytes(file.length())}</p>
                                    <a class="btn" href="/download/${URLEncoder.encode(servingFileName, "UTF-8")}">Direct Download APK</a>
                                </div>
                            </body>
                            </html>
                        """.trimIndent()

                        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(html.toByteArray())
                        out.flush()
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
