package com.example.scan2pc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.slider.Slider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var hostInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var scanFrame: ScanFrameView
    private lateinit var placeholder: View
    private lateinit var areaControls: View
    private lateinit var scanBtn: MaterialButton

    private val scanner = BarcodeScanning.getClient()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraOn = false

    // true после нажатия «Сканировать»: ближайший код в рамке будет отправлен один раз
    @Volatile
    private var armed = false

    private val prefs by lazy { getSharedPreferences("s2pc", Context.MODE_PRIVATE) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status.text = "Нужен доступ к камере (Настройки → Приложения → Scan2PC)"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostInput = findViewById(R.id.hostInput)
        tokenInput = findViewById(R.id.tokenInput)
        status = findViewById(R.id.status)
        previewView = findViewById(R.id.previewView)
        scanFrame = findViewById(R.id.scanFrame)
        placeholder = findViewById(R.id.placeholder)
        areaControls = findViewById(R.id.areaControls)
        scanBtn = findViewById(R.id.scanBtn)

        hostInput.setText(prefs.getString("host", ""))
        tokenInput.setText(prefs.getString("token", ""))

        // Превью целиком (без обрезки), чтобы рамка области совпадала с кадром.
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        val widthSlider = findViewById<Slider>(R.id.widthSlider)
        val heightSlider = findViewById<Slider>(R.id.heightSlider)
        widthSlider.value = prefs.getFloat("areaW", 0.7f).coerceIn(0.2f, 1f)
        heightSlider.value = prefs.getFloat("areaH", 0.4f).coerceIn(0.2f, 1f)
        scanFrame.fracW = widthSlider.value
        scanFrame.fracH = heightSlider.value
        widthSlider.addOnChangeListener { _, v, _ ->
            scanFrame.fracW = v
            prefs.edit().putFloat("areaW", v).apply()
        }
        heightSlider.addOnChangeListener { _, v, _ ->
            scanFrame.fracH = v
            prefs.edit().putFloat("areaH", v).apply()
        }

        scanBtn.setOnClickListener {
            saveSettings()
            if (!cameraOn) {
                ensureCamera()
            } else {
                armed = !armed
                status.text = if (armed) "Наведите рамку на код" else "Сканирование отменено"
                updateUi()
            }
        }
        findViewById<View>(R.id.photoBtn).setOnClickListener {
            saveSettings()
            startActivity(Intent(this, PhotoSelectActivity::class.java))
        }
        updateUi()
    }

    private fun saveSettings(): Pair<String, String>? {
        val host = hostInput.text.toString().trim()
        val token = tokenInput.text.toString().trim()
        prefs.edit().putString("host", host).putString("token", token).apply()
        return if (token.isEmpty()) null else host to token
    }

    private fun ensureCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        // превью включается само, а отправка кода только по нажатию кнопки
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera()
    }

    private fun updateUi() {
        scanBtn.text = when {
            !cameraOn -> "Включить камеру"
            armed -> "Отмена"
            else -> "Сканировать"
        }
        placeholder.visibility = if (cameraOn) View.GONE else View.VISIBLE
        scanFrame.visibility = if (cameraOn) View.VISIBLE else View.GONE
        areaControls.visibility = if (cameraOn) View.VISIBLE else View.GONE
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                val media = proxy.image
                if (!armed || media == null) { proxy.close(); return@setAnalyzer }
                val rot = proxy.imageInfo.rotationDegrees
                val sideways = rot == 90 || rot == 270
                val iw = (if (sideways) proxy.height else proxy.width).toFloat()
                val ih = (if (sideways) proxy.width else proxy.height).toFloat()
                val image = InputImage.fromMediaImage(media, rot)
                scanner.process(image)
                    .addOnSuccessListener { list ->
                        scanFrame.imageAspect = iw / ih
                        val area = scanFrame.normalizedFrame()
                        // берём код, центр которого внутри рамки и ближе всего к её центру
                        val hit = list
                            .filter { it.rawValue != null && it.boundingBox != null }
                            .filter {
                                val b = it.boundingBox!!
                                area.contains(b.centerX() / iw, b.centerY() / ih)
                            }
                            .minByOrNull {
                                val b = it.boundingBox!!
                                val dx = b.centerX() / iw - 0.5f
                                val dy = b.centerY() / ih - 0.5f
                                dx * dx + dy * dy
                            }
                        if (armed) hit?.rawValue?.let { onCode(it) }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            cameraOn = true
            if (!armed) status.text = "Нажмите «Сканировать», код должен быть в рамке"
            updateUi()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraOn = false
        armed = false
        updateUi()
    }

    private fun onCode(code: String) {
        armed = false   // один код на одно нажатие
        updateUi()

        val creds = saveSettings()
        if (creds == null) {
            status.text = "Укажите токен из окна приёмника (один раз)"
            return
        }
        vibrate()
        status.text = "Отправка: $code"
        netExecutor.execute {
            val (usedHost, err) = Sender.postAuto(creds.first, creds.second, Sender.oneLine(code))
            if (err == null && usedHost != creds.first) prefs.edit().putString("host", usedHost).apply()
            runOnUiThread {
                if (err == null && usedHost != creds.first) hostInput.setText(usedHost)
                status.text = err ?: "Отправлено: $code"
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        analysisExecutor.shutdown()
        netExecutor.shutdown()
    }
}
