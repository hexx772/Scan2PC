package com.example.scan2pc

import java.net.HttpURLConnection
import java.net.URL

object Sender {
    /**
     * Отправляет текст на ПК (один код на строку; приёмник нажимает Enter после каждого).
     * Блокирующий вызов, только из фонового потока.
     * Возвращает null при успехе, иначе текст ошибки.
     */
    fun post(host: String, token: String, text: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$host/scan").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("X-Token", token)
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            when (val c = conn.responseCode) {
                200 -> null
                401 -> "Неверный токен"
                else -> "Ошибка сервера: $c"
            }
        } catch (e: Exception) {
            "Нет связи с ПК: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    private const val PORT = 8765

    /** true, если по адресу отвечает наш приёмник и принимает этот токен. */
    private fun ping(host: String, token: String, timeoutMs: Int): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL("http://$host/ping").openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 1500
            conn.setRequestProperty("X-Token", token)
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    /** Ищет в локальной подсети (/24) ПК с приёмником, принимающим этот токен. */
    fun discover(token: String): String? {
        val myIps = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .map { it.address }
                .filterIsInstance<java.net.Inet4Address>()
                .map { it.hostAddress.orEmpty() }
        } catch (e: Exception) { emptyList() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(64)
        try {
            for (mine in myIps) {
                val parts = mine.split(".")
                if (parts.size != 4) continue
                val prefix = parts.take(3).joinToString(".")
                val tasks = (1..254).map { i ->
                    val h = "$prefix.$i:$PORT"
                    pool.submit<String?> { if ("$prefix.$i" != mine && ping(h, token, 500)) h else null }
                }
                tasks.forEach { f -> f.get()?.let { return it } }
            }
        } finally { pool.shutdownNow() }
        return null
    }

    /**
     * Отправка с автопоиском: если сохранённый адрес не отвечает (сменился IP) или пуст,
     * ищем ПК в сети по токену. Возвращает (рабочий адрес, ошибка или null).
     */
    fun postAuto(host: String, token: String, text: String): Pair<String, String?> {
        if (host.isNotEmpty()) {
            val err = post(host, token, text)
            if (err == null || err == "Неверный токен") return host to err
        }
        val found = discover(token) ?: return host to
            (if (host.isEmpty()) "ПК не найден в сети: проверьте, что приёмник запущен и телефон в той же Wi-Fi"
            else "Нет связи с ПК, и в сети он не найден")
        return found to post(found, token, text)
    }

    /** Один код = одна строка: переводы строк внутри кода заменяем пробелом. */
    fun oneLine(code: String): String = code.replace(Regex("\\s*[\\r\\n]+\\s*"), " ")
}
