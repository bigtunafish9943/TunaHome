package com.tunastream.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * PC in a corner: one of the PC's apps streamed by TunaReceive in a small window, over whatever
 * the TV itself is playing, so two shows are on at once. Android only lets an app shrink itself,
 * and TunaReceive can; Paramount+ and the like can't be put in a corner, but they can play full
 * screen underneath, or in the PC's own browser in the corner (a website added in TunaStream).
 * The corner starts muted, so the show underneath keeps the sound.
 */
object Corner {
    /** Read by TunaReceive's shortcut: shrink once the stream starts. */
    private const val EXTRA = "TunaCorner"
    private const val ACTION = "com.tunastream.receive.CORNER_"

    const val SOUND = "SOUND"
    const val FULL = "FULL"
    const val CLOSE = "CLOSE"

    fun locked(context: Context, game: AppEntry): Boolean =
        AppLock.isLocked(context, game.appPkg) || AppLock.isLocked(context, game.pkg)

    /** Starts a PC app (from TunaHome's PC games) in the corner; [done] after, or after a wrong PIN. */
    fun start(activity: Activity, game: AppEntry, done: () -> Unit = {}) {
        val go = {
            try {
                activity.startActivity(Intent(game.launch).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Sounds.play(activity, Sounds.OPEN)
                Toast.makeText(activity, R.string.corner_starting, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, activity.getString(R.string.open_failed, game.label), Toast.LENGTH_LONG).show()
            }
        }
        if (locked(activity, game)) {
            AppLock.ask(activity, game.label) {
                go()
                done()
            }
        } else {
            go()
            done()
        }
    }

    /** Sound on and off, full screen or close, for the stream in the corner. */
    fun send(context: Context, what: String) {
        context.sendBroadcast(Intent(ACTION + what).setPackage(PcGames.RECEIVER))
    }

    fun receiverInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PcGames.RECEIVER, 0)
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * The corner menu (a shortcut, the phone remote): opens over the show without hiding it, and
 * goes when a choice is made, so Back lands on the show again.
 */
class CornerActivity : Activity() {
    /** A PIN is being asked for: the menu has gone but this stays until it's answered. */
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        super.onCreate(savedInstanceState)
        if (!Corner.receiverInstalled(this)) {
            Toast.makeText(this, R.string.corner_needs_receiver, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        thread(name = "TunaHome corner") {
            val games = PcGames.load(this)
            runOnUiThread { if (!isFinishing) menu(games) }
        }
    }

    private fun menu(games: List<AppEntry>) {
        val labels = arrayListOf(
            getString(R.string.corner_sound),
            getString(R.string.corner_full),
            getString(R.string.corner_close),
        )
        val actions = arrayListOf<() -> Unit>(
            { Corner.send(this, Corner.SOUND) },
            { Corner.send(this, Corner.FULL) },
            { Corner.send(this, Corner.CLOSE) },
        )
        games.forEach { game ->
            labels.add(getString(R.string.corner_start, game.label))
            actions.add {
                asking = Corner.locked(this, game)
                Corner.start(this, game) { finish() }
            }
        }
        if (games.isEmpty()) {
            labels.add(getString(R.string.corner_no_games))
            actions.add {
                Toast.makeText(this, R.string.corner_no_games_long, Toast.LENGTH_LONG).show()
            }
        }
        QuietDialog.Builder(this)
            .setTitle(R.string.corner_title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setOnDismissListener { if (!asking) finish() }
            .show()
    }

    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, CornerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
