package com.example.scan2pc

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.tasks.Tasks
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Выбор фото из галереи, поиск на них кодов, визуальный выбор нужных кодов
 * (порядок нажатий = порядок вставки на ПК) и отправка.
 */
class PhotoSelectActivity : AppCompatActivity() {

    private val uris = ArrayList<Uri>()
    private val dets = ArrayList<List<Det>>()   // коды по фото, в порядке чтения
    private val selected = ArrayList<Det>()     // выбранные, в порядке нажатия
    private var page = 0
    private var loadGen = 0

    private lateinit var overlay: OverlayView
    private lateinit var progress: View
    private lateinit var pageText: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var allBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var sendBtn: Button

    private val scanner = BarcodeScanning.getClient()
    private val io = Executors.newSingleThreadExecutor()

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { list ->
        if (list.isEmpty()) finish() else {
            uris.addAll(list)
            detectAll()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photos)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        overlay = findViewById(R.id.overlay)
        progress = findViewById(R.id.progress)
        pageText = findViewById(R.id.pageText)
        prevBtn = findViewById(R.id.prevBtn)
        nextBtn = findViewById(R.id.nextBtn)
        allBtn = findViewById(R.id.allBtn)
        clearBtn = findViewById(R.id.clearBtn)
        sendBtn = findViewById(R.id.sendBtn)

        overlay.orderOf = { selected.indexOf(it) + 1 }
        overlay.onTap = { d ->
            if (!selected.remove(d)) selected.add(d)
            refresh()
        }
        prevBtn.setOnClickListener { showPage(page - 1) }
        nextBtn.setOnClickListener { showPage(page + 1) }
        allBtn.setOnClickListener {
            dets.getOrNull(page)?.forEach { if (it !in selected) selected.add(it) }
            refresh()
        }
        clearBtn.setOnClickListener { selected.clear(); refresh() }
        sendBtn.setOnClickListener { send() }

        refresh()
        if (savedInstanceState == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } else finish()
    }

    private fun detectAll() {
        progress.visibility = View.VISIBLE
        val photos = ArrayList(uris)
        io.execute {
            val result = ArrayList<List<Det>>()
            photos.forEachIndexed { index, uri ->
                val list = try {
                    val bmp = decodeBitmap(this, uri, 3000)
                    if (bmp == null) emptyList() else {
                        val w = bmp.width.toFloat()
                        val h = bmp.height.toFloat()
                        val found = Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0)))
                        bmp.recycle()
                        readingOrder(found.mapNotNull { b ->
                            val v = b.rawValue
                            val r = b.boundingBox
                            if (v == null || r == null) null
                            else Det(index, v, r.left / w, r.top / h, r.right / w, r.bottom / h)
                        })
                    }
                } catch (e: Exception) {
                    emptyList()
                }
                result += list
            }
            runOnUiThread {
                progress.visibility = View.GONE
                dets.addAll(result)
                if (result.all { it.isEmpty() }) {
                    Toast.makeText(this, "Коды на фото не найдены", Toast.LENGTH_LONG).show()
                }
                showPage(0)
            }
        }
    }

    // Порядок чтения: строки сверху вниз, внутри строки слева направо.
    private fun readingOrder(list: List<Det>): List<Det> {
        val sorted = list.sortedBy { it.t }
        val rows = ArrayList<MutableList<Det>>()
        var rowTop = 0f
        var rowH = 0f
        for (d in sorted) {
            if (rows.isEmpty() || d.t > rowTop + rowH / 2) {
                rows += mutableListOf(d)
                rowTop = d.t
                rowH = d.b - d.t
            } else rows.last() += d
        }
        return rows.flatMap { row -> row.sortedBy { it.l } }
    }

    private fun showPage(p: Int) {
        if (dets.isEmpty()) return
        page = p.coerceIn(0, dets.size - 1)
        val gen = ++loadGen
        val uri = uris[page]
        val items = dets[page]
        overlay.setContent(null, items)
        io.execute {
            val bmp = decodeBitmap(this, uri, 1600)
            runOnUiThread { if (gen == loadGen) overlay.setContent(bmp, items) }
        }
        refresh()
    }

    private fun refresh() {
        overlay.invalidate()
        val n = dets.getOrNull(page)?.size ?: 0
        pageText.text = if (dets.isEmpty()) "" else "Фото ${page + 1} из ${dets.size} · кодов: $n"
        prevBtn.isEnabled = page > 0
        nextBtn.isEnabled = page < dets.size - 1
        allBtn.isEnabled = n > 0
        clearBtn.isEnabled = selected.isNotEmpty()
        sendBtn.text = "Отправить (${selected.size})"
        sendBtn.isEnabled = selected.isNotEmpty()
    }

    private fun send() {
        val prefs = getSharedPreferences("s2pc", MODE_PRIVATE)
        val host = prefs.getString("host", "").orEmpty()
        val token = prefs.getString("token", "").orEmpty()
        if (token.isEmpty()) {
            Toast.makeText(this, "Укажите токен на главном экране", Toast.LENGTH_LONG).show()
            return
        }
        val text = selected.joinToString("\n") { Sender.oneLine(it.value) }
        val count = selected.size
        sendBtn.isEnabled = false
        io.execute {
            val (usedHost, err) = Sender.postAuto(host, token, text)
            if (err == null && usedHost != host) prefs.edit().putString("host", usedHost).apply()
            runOnUiThread {
                if (err == null) {
                    Toast.makeText(this, "Отправлено кодов: $count", Toast.LENGTH_SHORT).show()
                    selected.clear()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        io.shutdown()
    }
}
