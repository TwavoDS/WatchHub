package com.twavods.watchhub

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaActionSound
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.annotation.SuppressLint
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import org.json.JSONObject
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Main screen. Two modes:
 *  - WatchCam camera: this screen shows the camera itself (CameraX).
 *  - Xiaomi camera: [ScreenStreamService] streams the Xiaomi camera app from the screen.
 */
class CameraActivity : ComponentActivity(), WatchLink.Source {


    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var xiaomiBtn: Button
    private lateinit var tapBtn: Button

    private val main = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    @Volatile private var lastFrameMs = 0L
    private val sound = MediaActionSound()
    private lateinit var orientation: DeviceOrientation
    private var analysisUseCase: ImageAnalysis? = null
    private var lastRotation = -1

    // Extra controls from the watch
    private var camera: Camera? = null
    private var videoMode = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var zoom = 1f
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recStartedAt = 0L

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else WatchLink.lastError = "Camera-toestemming is nodig."
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                // Release the camera so the Xiaomi camera app can use it
                cameraProvider?.unbindAll()
                val i = Intent(this, ScreenStreamService::class.java)
                    .putExtra(ScreenStreamService.EXTRA_CODE, res.resultCode)
                    .putExtra(ScreenStreamService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
            } else {
                WatchLink.lastError = "Schermopname geweigerd"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WatchLink.init(this)
        orientation = DeviceOrientation(this)
        setContentView(buildUi())
        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (!WatchLink.permissionOk) WatchLink.connect()
        WatchLink.gotoOnWatch(org.json.JSONObject().put("t", "goto").put("p", "camera"), appWasClosed = true)
        tick()
    }

    override fun onResume() {
        super.onResume()
        orientation.start()
        if (!WatchLink.permissionOk || WatchLink.lastError.startsWith("versturen")) WatchLink.connect()
        useOwnCamera()
    }

    override fun onPause() {
        orientation.stop()
        super.onPause()
    }

    private fun useOwnCamera() {
        if (ScreenStreamService.runningFor(ScreenStreamService.TARGET_CAMERA)) return
        WatchLink.source = this
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        stopRecording()
        if (WatchLink.source === this) WatchLink.source = null
        analysisExecutor.shutdown()
        sound.release()
        super.onDestroy()
    }

    // ---- UI ------------------------------------------------------------------

    private fun buildUi(): ViewGroup {
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        fun row(vararg views: android.view.View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        fun slider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit): LinearLayout {
            val text = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }
            val bar = SeekBar(this).apply {
                this.max = 100
                progress = (((value - min) / (max - min)) * 100).toInt()
            }
            fun show(v: Float) { text.text = "$label: ${(v * 100).toInt()}%" }
            show(value)
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    show(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text)
                addView(bar)
            }
        }

        xiaomiBtn = button("Xiaomi-camera") { toggleXiaomiMode() }
        tapBtn = button("Sluiter-toegang") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 24, 32, 24)
            addView(statusView)
            addView(row(button("Verbinden") { WatchLink.connect() }, button("Open op horloge") { WatchLink.gotoOnWatch(org.json.JSONObject().put("t", "goto").put("p", "camera"), appWasClosed = true) }))
            addView(row(xiaomiBtn, tapBtn))
            addView(slider("Uitsnede Xiaomi-camera (hoogte)", Prefs.cropTop(this@CameraActivity), 0f, 0.6f) {
                Prefs.setCropTop(this@CameraActivity, it)
            })
            addView(slider("Positie sluiterknop", Prefs.shutterY(this@CameraActivity), 0.6f, 0.98f) {
                Prefs.setShutterY(this@CameraActivity, it)
            })
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
    }

    private fun toggleXiaomiMode() {
        if (ScreenStreamService.runningFor(ScreenStreamService.TARGET_CAMERA)) {
            startService(Intent(this, ScreenStreamService::class.java).setAction(ScreenStreamService.ACTION_STOP))
            main.postDelayed({ useOwnCamera() }, 300)
            return
        }
        if (CameraTapService.instance == null) {
            WatchLink.lastError = "Tip: zet eerst 'Sluiter-toegang' aan, anders werkt de sluiter niet"
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionConsent.launch(mpm.createScreenCaptureIntent())
    }

    private fun tick() {
        // Keep the watch picture and saved photos upright however the phone is held
        val rot = orientation.surfaceRotation
        if (rot != lastRotation) {
            lastRotation = rot
            analysisUseCase?.targetRotation = rot
            imageCapture?.targetRotation = rot
        }
        val xiaomi = ScreenStreamService.runningFor(ScreenStreamService.TARGET_CAMERA)
        xiaomiBtn.text = if (xiaomi) "Stop Xiaomi-camera" else "Xiaomi-camera"
        tapBtn.text = if (CameraTapService.instance != null) "Sluiter-toegang ✓" else "Sluiter-toegang"
        statusView.text = buildString {
            append("Horloge: ").append(WatchLink.linkState).append('\n')
            append("Modus: ").append(if (xiaomi) "Xiaomi-camera" else "WatchHub-camera").append('\n')
            append("Livebeeld: ").append(if (WatchLink.streaming) "aan" else "uit")
            append("  ·  beelden: ").append(WatchLink.framesSent)
            append("  ·  ").append(String.format(Locale.US, "%.1f", WatchLink.fps)).append(" fps")
            append("  ·  ").append(WatchLink.lastFrameBytes / 1024).append(" kB/beeld\n")
            append("Berichten van horloge: ").append(WatchLink.messagesIn)
            append("  ·  foto's: ").append(WatchLink.photosTaken)
            if (CameraTapService.lastAction.isNotEmpty()) append("\nSluiter: ").append(CameraTapService.lastAction)
            val err = ScreenStreamService.lastError.ifEmpty { WatchLink.lastError }
            if (err.isNotEmpty()) append("\nLaatste melding: ").append(err)
        }
        main.postDelayed({ tick() }, 500)
    }

    // ---- WatchCam camera (CameraX) -------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        if (ScreenStreamService.runningFor(ScreenStreamService.TARGET_CAMERA)) return
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()
        val video = VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
        )
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    ).build()
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image -> processFrame(image) }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        try {
            provider.unbindAll()
            // Most phones can't do photo + video + live picture at once: photo OR video
            camera = if (videoMode) provider.bindToLifecycle(this, selector, preview, video, analysis)
                     else provider.bindToLifecycle(this, selector, preview, capture, analysis)
            imageCapture = if (videoMode) null else capture
            videoCapture = if (videoMode) video else null
            camera?.cameraControl?.setZoomRatio(zoom)
            if (videoMode) camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
            analysisUseCase = analysis
            lastRotation = -1
            WatchLink.kick()
        } catch (e: Exception) {
            WatchLink.lastError = "camera: ${e.message}"
        }
    }

    /** Turns a camera frame into a small square JPEG for the round watch screen. */
    private fun processFrame(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameMs < 50) return
            lastFrameMs = now

            val src = image.toBitmap()
            // Upright, mirrored for the selfie camera, about twice the watch size
            val matrix = Matrix().apply {
                val scale = (WATCH_SIZE * 1.5f) / minOf(src.width, src.height)
                postScale(scale, scale)
                postRotate(image.imageInfo.rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            latestJpeg.set(frameToJpeg(upright, WatchLink.fillMode))
            if (upright !== src) upright.recycle()
            src.recycle()
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    // ---- WatchLink.Source ----------------------------------------------------

    override fun latestJpeg(): ByteArray? = latestJpeg.get()

    override fun shutter(done: (String?) -> Unit) {
        val capture = imageCapture ?: return done("camera niet klaar")
        val name = "WatchHub_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatchHub")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        main.post { sound.play(MediaActionSound.SHUTTER_CLICK) }
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(null)
                override fun onError(exc: ImageCaptureException) = done(exc.message ?: "fout")
            })
    }

    /** Flash, zoom, photo/video and recording, asked from the watch. Returns the new state. */
    override fun camCmd(a: String): JSONObject {
        when (a) {
            "flash" -> {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> if (videoMode) ImageCapture.FLASH_MODE_OFF else ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                imageCapture?.flashMode = flashMode
                if (videoMode) camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
            }
            "zoom" -> {
                val max = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                val steps = listOf(1f, 2f, 3f, 5f, 10f).filter { it <= max + 0.01f }
                val i = steps.indexOfFirst { it > zoom + 0.01f }
                zoom = if (i < 0) 1f else steps[i]
                camera?.cameraControl?.setZoomRatio(zoom)
            }
            "mode" -> {
                stopRecording()
                videoMode = !videoMode
                if (videoMode && flashMode == ImageCapture.FLASH_MODE_AUTO) flashMode = ImageCapture.FLASH_MODE_OFF
                if (videoMode && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                bindCamera()
                HubLog.i("camera", if (videoMode) "videomodus" else "fotomodus")
            }
            "rec" -> if (recording != null) stopRecording() else startRecording()
        }
        return camState()
    }

    private fun camState(): JSONObject = JSONObject()
        .put("flash", when (flashMode) { ImageCapture.FLASH_MODE_ON -> "on"; ImageCapture.FLASH_MODE_AUTO -> "auto"; else -> "off" })
        .put("zoom", zoom)
        .put("video", videoMode)
        .put("rec", recording != null)
        .put("recMs", if (recording != null) SystemClock.elapsedRealtime() - recStartedAt else 0L)

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        val name = "WatchHub_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WatchHub")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            pending = pending.withAudioEnabled()
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recStartedAt = SystemClock.elapsedRealtime()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                if (ev.hasError()) HubLog.e("camera", "video opslaan mislukt (fout ${ev.error})")
                else HubLog.i("camera", "video opgeslagen in Movies/WatchHub")
                recording = null
                WatchLink.post(camState().put("t", "camstate"))
            }
        }
        HubLog.i("camera", "video gestart")
    }

    private fun stopRecording() {
        val r = recording ?: return
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        r.stop()
        recording = null
    }

    override fun switchCamera() {
        main.post {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
    }
}
