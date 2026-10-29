package com.devicescope.app.pcmovil.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Cámara del teléfono como webcam de la PC.
 *
 * Se usa Camera2 con `ImageReader` en JPEG: cada fotograma se envía por el canal
 * CAMERA y el agente lo publica en una ventana de vista previa y/o en un
 * dispositivo virtual (`/dev/video*` con `v4l2loopback`, si está instalado).
 *
 * No hay vista previa local para no duplicar la sesión de cámara; la pantalla
 * del teléfono muestra el contador de fotogramas y el caudal enviado.
 * En modo `YUV` no se envía nada: solo JPEG, que es lo que aceptan Zoom, Meet,
 * OBS y el resto de aplicaciones de videollamada vía dispositivo virtual.
 */
class PcCameraStreamer(
    private val context: Context,
    private val link: PcLinkManager,
) {

    sealed interface CameraState {
        data object Idle : CameraState
        data class Starting(val detail: String) : CameraState
        data class Streaming(val frames: Int, val bytes: Long, val width: Int, val height: Int) : CameraState
        data class Error(val reason: String) : CameraState
    }

    private val _state = MutableStateFlow<CameraState>(CameraState.Idle)
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var targetFps = 5
    private var jpegQuality = 65
    private var frames = 0
    private var bytesSent = 0L
    private var lastCaptureAt = 0L
    private var busy = false

    val hasCameraPermission: Boolean
        get() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun cameraCount(): Int = runCatching {
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.size
    }.getOrDefault(0)

    /** Cámara trasera si existe (mejor imagen); si no, la frontal. */
    fun preferredCameraId(): String? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = manager.cameraIdList
        ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }.getOrNull()

    @Suppress("MissingPermission")
    fun start(cameraId: String? = null, fps: Int = 5, quality: Int = 65): Result<Unit> {
        if (!hasCameraPermission) return Result.failure(PcLinkException("Falta el permiso de cámara"))
        if (link.connectionOrNull == null) {
            return Result.failure(PcLinkException("Sin conexión con la PC"))
        }
        if (_state.value is CameraState.Streaming) return Result.success(Unit)
        val id = cameraId ?: preferredCameraId()
            ?: return Result.failure(PcLinkException("No hay cámaras disponibles"))
        targetFps = fps.coerceIn(1, 15)
        jpegQuality = quality.coerceIn(30, 95)
        _state.value = CameraState.Starting("Abriendo cámara $id")
        thread = HandlerThread("pc-camera").also { it.start() }
        handler = Handler(thread!!.looper)

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val size = chooseSize(manager, id)
        frames = 0
        bytesSent = 0L
        busy = false
        lastCaptureAt = 0L

        return try {
            val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
            reader = imageReader
            imageReader.setOnImageAvailableListener({ available ->
                val image = available.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val data = ByteArray(buffer.remaining())
                    buffer.get(data)
                    if (link.sendCameraFrame(data)) {
                        frames++
                        bytesSent += data.size
                        _state.value = CameraState.Streaming(frames, bytesSent, size.width, size.height)
                    }
                } catch (_: Exception) {
                    // Fotograma descartado.
                } finally {
                    image.close()
                    busy = false
                }
            }, handler)

            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        createSession(camera, imageReader.surface, size)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        device = null
                        _state.value = CameraState.Error("La cámara se desconectó")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        device = null
                        _state.value = CameraState.Error("Error de cámara ($error)")
                        stop()
                    }
                },
                handler,
            )
            link.startCamera(size.width, size.height, jpegQuality)
            Result.success(Unit)
        } catch (error: Exception) {
            stop()
            _state.value = CameraState.Error(error.localizedMessage ?: "No se pudo abrir la cámara")
            Result.failure(error)
        }
    }

    fun stop() {
        _state.value = CameraState.Idle
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        runCatching { link.stopCamera() }
    }

    fun setFps(fps: Int) {
        targetFps = fps.coerceIn(1, 15)
    }

    private fun createSession(
        camera: CameraDevice,
        surface: Surface,
        size: Size,
    ) {
        val sessionHandler = handler ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(surface)
                set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        _state.value = CameraState.Streaming(0, 0L, size.width, size.height)
                        captureNext(configured, request.build())
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        _state.value = CameraState.Error("La cámara no aceptó la sesión JPEG")
                    }
                },
                sessionHandler,
            )
        } catch (error: Exception) {
            _state.value = CameraState.Error(error.localizedMessage ?: "Sesión de cámara fallida")
        }
    }

    private fun captureNext(configured: CameraCaptureSession, request: CaptureRequest) {
        val targetInterval = 1000L / targetFps
        val wait = (lastCaptureAt + targetInterval) - System.currentTimeMillis()
        val action = Runnable {
            if (_state.value !is CameraState.Streaming || busy) return@Runnable
            try {
                busy = true
                lastCaptureAt = System.currentTimeMillis()
                configured.capture(request, null, handler)
            } catch (_: Exception) {
                busy = false
                return@Runnable
            }
            scheduleNext(configured, request)
        }
        if (wait > 0) handler?.postDelayed(action, wait) else handler?.post(action)
    }

    private fun scheduleNext(configured: CameraCaptureSession, request: CaptureRequest) {
        val interval = 1000L / targetFps
        handler?.postDelayed({
            if (_state.value is CameraState.Streaming) captureNext(configured, request)
        }, interval)
    }

    private fun chooseSize(manager: CameraManager, cameraId: String): Size {
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull()
        val sizes = characteristics
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        val preferred = listOf(1280 to 720, 960 to 540, 1920 to 1080, 640 to 480)
        for ((width, height) in preferred) {
            sizes.firstOrNull { it.width == width && it.height == height }?.let { return it }
        }
        // El más cercano a 720p sin pasar de 1080p.
        return sizes.minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: sizes.first()
    }
}
