package com.example.expensenotificationlistener

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class DeliveryException(
    val safeMessage: String,
    val retryable: Boolean,
) : Exception(safeMessage)

object AppsScriptClient {
    fun ping(config: EndpointConfig) {
        post(config, mapOf("action" to "ping"))
    }

    fun post(config: EndpointConfig, fields: Map<String, Any>) {
        validateDeploymentUrl(config.url)
        val request = JSONObject(fields)
            .put("token", config.secret)
            .toString()
        var currentUrl = URL(config.url)
        var method = "POST"
        var body: String? = request
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            validateGoogleUrl(currentUrl)
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = method
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (body != null) {
                    connection.outputStream.use {
                        it.write(body.toByteArray(Charsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw DeliveryException("Pengalihan Apps Script tidak valid.", false)
                    currentUrl = URL(currentUrl, location)
                    validateGoogleUrl(currentUrl)
                    when (code) {
                        HttpURLConnection.HTTP_SEE_OTHER,
                        HttpURLConnection.HTTP_MOVED_PERM,
                        HttpURLConnection.HTTP_MOVED_TEMP -> {
                            method = "GET"
                            body = null
                        }
                        307,
                        308 -> Unit
                        else -> throw DeliveryException("Pengalihan Apps Script tidak terduga.", false)
                    }
                    redirects++
                    continue
                }
                val responseText = readLimited((if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }))
                if (code == 429 || code >= 500) {
                    throw DeliveryException("Apps Script sementara tidak tersedia (HTTP $code).", true)
                }
                if (code !in 200..299) {
                    throw DeliveryException("Apps Script menolak permintaan (HTTP $code).", false)
                }
                val response = try {
                    JSONObject(responseText)
                } catch (_: Exception) {
                    throw DeliveryException("Respons Apps Script tidak valid.", true)
                }
                if (response.optBoolean("ok").not()) {
                    val errorCode = response.optString("code", "request_failed")
                        .lowercase(Locale.ROOT)
                    throw DeliveryException(
                        "Apps Script menolak permintaan ($errorCode).",
                        errorCode == "temporary_error" || errorCode == "busy",
                    )
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        throw DeliveryException("Apps Script terlalu banyak melakukan pengalihan.", false)
    }

    private fun validateDeploymentUrl(value: String) {
        val url = URL(value)
        require(url.protocol == "https" && url.host == "script.google.com" &&
            url.path.startsWith("/macros/s/") && url.path.endsWith("/exec")
        ) { "Gunakan URL HTTPS web app Apps Script yang berakhir dengan /exec." }
    }

    private fun validateGoogleUrl(url: URL) {
        val host = url.host.lowercase(Locale.ROOT)
        val isGoogleHost = host == "script.google.com" ||
            host.endsWith(".googleusercontent.com")
        if (url.protocol != "https" || !isGoogleHost ||
            url.port != -1 && url.port != 443 || url.userInfo != null
        ) {
            throw DeliveryException("Apps Script mengalihkan ke alamat yang tidak tepercaya.", false)
        }
    }

    private fun readLimited(input: InputStream?): String {
        if (input == null) return ""
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var remaining = MAX_RESPONSE_BYTES
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
            return String(output.toByteArray(), Charsets.UTF_8)
        }
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_RESPONSE_BYTES = 8_192
}
