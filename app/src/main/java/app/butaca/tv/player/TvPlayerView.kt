package app.butaca.tv.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * PlayerView pensado para el mando:
 * - cualquier botón de dirección u OK muestra los controles (y reinicia los 5 s para ocultarlos);
 * - con los controles ocultos, ◀ ▶ avanzan/retroceden con pasos que se aceleran al mantener;
 *   el salto real se hace al soltar, así no se recarga el vídeo en cada pulsación.
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
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_MENU

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode

        // Avance/retroceso con los controles ocultos (o sobre la barra de tiempo)
        if ((code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) && canScrub()) {
            val onTimeBar = timeBar?.hasFocus() == true
            if (!isControllerFullyVisible || onTimeBar) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val r = event.repeatCount
                    val step = when {
                        r < 4 -> 10_000L
                        r < 14 -> 30_000L
                        r < 30 -> 60_000L
                        else -> 180_000L
                    }
                    if (isControllerFullyVisible) hideController()
                    onScrub?.invoke(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                }
                return true
            }
        }

        // Con los controles ocultos: cualquier botón de navegación los muestra
        if (!isControllerFullyVisible && (isNavKey(code) || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showController()
                post { playPause?.requestFocus() }
            }
            return true
        }

        // Con los controles visibles: cada pulsación reinicia la cuenta de 5 segundos
        if (isControllerFullyVisible && event.action == KeyEvent.ACTION_DOWN) showController()
        return super.dispatchKeyEvent(event)
    }
}
