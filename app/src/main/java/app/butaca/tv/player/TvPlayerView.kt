package app.butaca.tv.player

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * PlayerView con avance/retroceso por pasos que se aceleran al mantener la flecha.
 * El salto real se hace al soltar, así no se recarga el vídeo en cada pulsación.
 */
@OptIn(UnstableApi::class)
class TvPlayerView(context: Context) : PlayerView(context) {
    var onScrub: ((Long) -> Unit)? = null
    var onScrubEnd: (() -> Unit)? = null
    var canScrub: () -> Boolean = { true }

    private val timeBar: View? by lazy { findViewById(androidx.media3.ui.R.id.exo_progress) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
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
                } else if (event.action == KeyEvent.ACTION_UP) {
                    onScrubEnd?.invoke()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
