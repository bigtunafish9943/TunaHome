package com.tunastream.home

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import android.view.SoundEffectConstants

/**
 * Optional sounds as you move around: a light one for moving between apps, a fuller one for
 * selecting or opening. Nine sets made for TunaHome, or Android's own click. Off to begin with.
 */
object Sounds {
    const val MOVE = 0
    const val SELECT = 1
    const val OPEN = 2

    const val SET_OFF = 0
    const val SET_ANDROID = 1
    private val SETS = arrayOf(
        null, null,
        intArrayOf(R.raw.snd_soft_move, R.raw.snd_soft_select),
        intArrayOf(R.raw.snd_tick_move, R.raw.snd_tick_select),
        intArrayOf(R.raw.snd_pop_move, R.raw.snd_pop_select),
        intArrayOf(R.raw.snd_wood_move, R.raw.snd_wood_select),
        intArrayOf(R.raw.snd_glass_move, R.raw.snd_glass_select),
        intArrayOf(R.raw.snd_bubble_move, R.raw.snd_bubble_select),
        intArrayOf(R.raw.snd_water_move, R.raw.snd_water_select),
        intArrayOf(R.raw.snd_retro_move, R.raw.snd_retro_select),
        intArrayOf(R.raw.snd_chime_move, R.raw.snd_chime_select),
    )
    val SET = Look.Choice(
        "sound_set", SET_OFF, intArrayOf(
            R.string.snd_off, R.string.snd_android, R.string.snd_soft, R.string.snd_tick, R.string.snd_pop,
            R.string.snd_wood, R.string.snd_glass, R.string.snd_bubble, R.string.snd_water, R.string.snd_retro,
            R.string.snd_chime,
        )
    )
    val VOLUME = Look.Choice(
        "sound_volume", 1, intArrayOf(R.string.snd_quiet, R.string.snd_medium, R.string.snd_loud)
    )
    private val VOLUMES = floatArrayOf(0.25f, 0.5f, 0.9f)
    val ON_MOVE = Look.Switch("sound_on_move", true)

    private var pool: SoundPool? = null
    private var loadedSet = -1
    private var ids = IntArray(2)
    private var lastMove = 0L

    fun play(context: Context, which: Int) {
        val set = SET.get(context)
        if (set == SET_OFF || (which == MOVE && !ON_MOVE.get(context))) {
            return
        }
        if (which == MOVE) {
            // Holding the D-pad shouldn't become a buzz
            val now = SystemClock.uptimeMillis()
            if (now - lastMove < 45) {
                return
            }
            lastMove = now
        }
        if (set == SET_ANDROID) {
            val audio = context.getSystemService(AudioManager::class.java) ?: return
            audio.playSoundEffect(
                if (which == MOVE) SoundEffectConstants.NAVIGATION_RIGHT else AudioManager.FX_KEY_CLICK,
                VOLUMES[VOLUME.get(context)]
            )
            return
        }
        val pool = poolFor(context, set) ?: return
        val volume = VOLUMES[VOLUME.get(context)]
        pool.play(ids[if (which == MOVE) 0 else 1], volume, volume, 1, 0, 1f)
    }

    /** Loads the chosen set the first time it's needed, and again only if the set changes. */
    private fun poolFor(context: Context, set: Int): SoundPool? {
        val files = SETS.getOrNull(set) ?: return null
        if (pool != null && loadedSet == set) {
            return pool
        }
        pool?.release()
        val made = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        ids = IntArray(2) { made.load(context.applicationContext, files[it], 1) }
        pool = made
        loadedSet = set
        return made
    }
}
