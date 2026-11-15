package app.butaca.tv.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * Reproductor pensado para el mando. Las pulsaciones le llegan siempre desde la Activity
 * (aunque el foco se haya perdido al ocultarse los controles):
 * - OK o cualquier flecha muestran los controles; cada pulsación reinicia los 5 s para ocultarlos;
 * - con los controles ocultos (o sobre la barra de tiempo), ◀ ▶ avanzan/retroceden con pasos
 *   que se aceleran al mantener; el salto real se hace al soltar.
 */
@OptIn(UnstableApi::class)
class TvPlayerView(context: Context) : PlayerView(context) {
    var onScrub: ((Long) -> Unit)? = null
    var canScrub: () -> Boolean = { true }

    private val timeBar: View? by lazy { findViewById(androidx.media3.ui.R.id.exo_progress) }
    private val playPause: View? by lazy { findViewById(androidx.media3.ui.R.id.exo_play_pause) }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        controllerAutoShow = false
        controllerShowTimeoutMs = 5000
        controllerHideOnTouch = true
    }

    private fun isNavKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MENU ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun isMediaKey(code: Int) = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY ||
        code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
        code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS

    fun focusControls() {
        post { (playPause?.takeIf { it.isShown } ?: this).requestFocus() }
    }

    /** Devuelve true si la pulsación ya está atendida. */
    fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val visible = isControllerFullyVisible

        if (isMediaKey(code)) return dispatchMediaKeyEvent(event)

        val isLR = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        if (isLR && canScrub() && (!visible || timeBar?.hasFocus() == true)) {
            if (down) {
                val r = event.repeatCount
                val step = when {
                    r < 4 -> 10_000L
                    r < 14 -> 30_000L
                    r < 30 -> 60_000L
                    else -> 180_000L
                }
                if (visible) hideController()
                onScrub?.invoke(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
            }
            return true
        }

        if (!isNavKey(code)) return false

        if (!visible) {
            if (down && event.repeatCount == 0) {
                showController()
                focusControls()
            }
            return true
        }

        // Controles visibles: reinicia la cuenta atrás y asegura que el foco está en ellos
        if (down) showController()
        if (!hasFocus()) {
            if (down) focusControls()
            return true
        }
        return false
    }
}
