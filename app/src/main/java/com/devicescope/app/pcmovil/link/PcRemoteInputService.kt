package com.devicescope.app.pcmovil.link

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.devicescope.app.pcmovil.data.InputTarget
import com.devicescope.app.pcmovil.data.RemoteKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Recibe la entrada que llega desde la PC y la aplica al teléfono.
 *
 * Es la pieza que hace posible el sentido inverso del módulo (teclado y mouse de
 * la PC controlando el móvil). Android no permite inyectar eventos sin root ni
 * permisos de sistema, así que se usan las dos vías legítimas:
 *
 * - **Texto**: `ACTION_SET_TEXT` sobre el campo con foco (y `ACTION_PASTE` como
 *   respaldo). Funciona en editores de texto estándar; en apps que no exponen el
 *   nodo editable (bancos, algunos juegos) no hay forma de escribir por
 *   accesibilidad.
 * - **Gestos**: `dispatchGesture()` para toques, doble toque, pulsación larga y
 *   deslizamientos.
 * - **Acciones globales**: atrás, inicio, recientes, notificaciones, volumen y
 *   reproducción multimedia.
 *
 * El usuario debe activar el servicio a mano en Ajustes → Accesibilidad →
 * DeviceScope PC-Móvil. La app nunca lo activa por su cuenta.
 */
class PcRemoteInputService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null

    private val audioManager get() = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connected.value = true
        val link = PcLinkManager.get(this)
        collector?.cancel()
        collector = scope.launch {
            link.incoming.collect { envelope ->
                if (envelope.string("target") == InputTarget.PHONE.wire) {
                    handle(envelope)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        collector?.cancel()
        _connected.value = false
        super.onDestroy()
    }

    private fun handle(envelope: PcEnvelope) {
        when (envelope.type) {
            PcProtocol.Type.TEXT -> {
                val text = envelope.string("text").orEmpty()
                if (text.isNotEmpty()) {
                    _lastText.value = text
                    injectText(text)
                }
            }
            PcProtocol.Type.KEY -> handleKey(envelope)
            PcProtocol.Type.GESTURE -> handleGesture(
                action = envelope.stringOr("action", "tap"),
                startX = envelope.double("x", 0.5).toFloat(),
                startY = envelope.double("y", 0.5).toFloat(),
                endX = envelope.double("x2", envelope.double("x", 0.5)).toFloat(),
                endY = envelope.double("y2", envelope.double("y", 0.5)).toFloat(),
                duration = envelope.long("duration", 40L),
            )
            PcProtocol.Type.MOUSE -> handlePointer(envelope)
            PcProtocol.Type.SCROLL -> {
                val forward = envelope.double("dy", 1.0) > 0
                performScroll(forward)
            }
            PcProtocol.Type.CLIPBOARD -> {
                val text = envelope.string("text").orEmpty()
                if (text.isNotEmpty()) copyToClipboard(text)
            }
            else -> Unit
        }
    }

    private fun handleKey(envelope: PcEnvelope) {
        val key = RemoteKey.entries.firstOrNull { it.wire == envelope.string("key") } ?: return
        val target = envelope.string("target")
        if (target != null && target != InputTarget.PHONE.wire) return
        when (key) {
            RemoteKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteKey.HOME_SCREEN -> performGlobalAction(GLOBAL_ACTION_HOME)
            RemoteKey.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            RemoteKey.ENTER -> pressEnter()
            RemoteKey.DELETE, RemoteKey.BACKSPACE -> deleteCharacter()
            RemoteKey.SPACE -> injectText(" ")
            RemoteKey.TAB -> injectText("\t")
            RemoteKey.UP -> performScroll(forward = false)
            RemoteKey.DOWN -> performScroll(forward = true)
            RemoteKey.VOLUME_UP -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI,
            )
            RemoteKey.VOLUME_DOWN -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI,
            )
            RemoteKey.PLAY_PAUSE -> audioManager?.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
            )
            RemoteKey.SCREEN_OFF -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
            else -> Unit
        }
    }

    private fun handlePointer(envelope: PcEnvelope) {
        val action = envelope.stringOr("action", "tap")
        val x = envelope.double("x", 0.5).toFloat()
        val y = envelope.double("y", 0.5).toFloat()
        when (action) {
            "click", "down", "up", "move" -> handleGesture("tap", x, y, x, y, 60L)
            "double" -> {
                handleGesture("tap", x, y, x, y, 50L)
                scope.launch {
                    kotlinx.coroutines.delay(140)
                    handleGesture("tap", x, y, x, y, 50L)
                }
            }
            else -> Unit
        }
    }

    private fun handleGesture(
        action: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long,
    ) {
        val metrics = resources.displayMetrics
        val fromX = (startX.coerceIn(0f, 1f) * metrics.widthPixels)
        val fromY = (startY.coerceIn(0f, 1f) * metrics.heightPixels)
        val toX = (endX.coerceIn(0f, 1f) * metrics.widthPixels)
        val toY = (endY.coerceIn(0f, 1f) * metrics.heightPixels)
        val path = android.graphics.Path().apply { moveTo(fromX, fromY) }
        val milliseconds = duration.coerceIn(30L, 4_000L)
        when (action) {
            "tap" -> path.lineTo(fromX + 1f, fromY + 1f)
            "long_press" -> path.lineTo(fromX + 1f, fromY + 1f)
            else -> path.lineTo(toX, toY)
        }
        val effectiveDuration = when (action) {
            "tap" -> 60L
            "long_press" -> 900L
            else -> milliseconds
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, effectiveDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        runCatching { dispatchGesture(gesture, null, null) }
        _lastGestureAt.value = System.currentTimeMillis()
    }

    private fun injectText(text: String) {
        val node = focusedEditable() ?: run {
            fallbackPaste(text)
            return
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            fallbackPaste(text)
        } else {
            _lastInjected.value = text
        }
    }

    private fun fallbackPaste(text: String) {
        copyToClipboard(text)
        val node = focusedEditable() ?: return
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
        _lastInjected.value = text
    }

    private fun pressEnter() {
        val node = focusedEditable() ?: return
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private fun deleteCharacter() {
        val node = focusedEditable() ?: return
        val text = node.text?.toString().orEmpty()
        if (text.isEmpty()) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text.dropLast(1),
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun performScroll(forward: Boolean) {
        val root = rootInActiveWindow ?: return
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: root
        target.performAction(action)
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return focused.takeIf { it.isEditable }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("DeviceScope", text))
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _lastText = MutableStateFlow("")
        val lastText: StateFlow<String> = _lastText.asStateFlow()

        private val _lastInjected = MutableStateFlow("")
        val lastInjected: StateFlow<String> = _lastInjected.asStateFlow()

        private val _lastGestureAt = MutableStateFlow(0L)
        val lastGestureAt: StateFlow<Long> = _lastGestureAt.asStateFlow()

        fun isConnected(): Boolean = _connected.value
    }
}
