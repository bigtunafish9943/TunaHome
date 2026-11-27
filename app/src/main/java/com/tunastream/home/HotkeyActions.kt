package com.tunastream.home

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast

/** What a shortcut can do, and doing it. */
object HotkeyActions {
    const val HOME_TOP = "home_top"
    const val SIDE_MENU = "side_menu"
    const val SEARCH = "search"
    const val VOICE = "voice_search"
    const val SETTINGS = "tunahome_settings"
    const val TV_SETTINGS = "tv_settings"
    const val OPEN_APP = "open_app"
    const val SECTION = "section"
    const val NEXT_BACKGROUND = "next_background"
    const val IDLE = "idle_now"
    const val RECENTS = "recents"
    const val NOTIFICATIONS = "notifications"
    const val SLEEP = "sleep"
    const val SCREENSHOT = "screenshot"
    const val BACK = "back"
    const val PLAY_PAUSE = "play_pause"
    const val NEXT_TRACK = "next_track"
    const val PREVIOUS_TRACK = "previous_track"
    const val MUTE = "mute"
    const val UPDATES = "check_updates"
    const val SLEEP_TIMER = "sleep_timer"
    const val QUICK = "quick_changes"
    const val PHONE = "phone_setup"
    const val WHO = "whos_watching"
    const val OPEN_APPS = "open_apps"
    const val POINTER = "pointer"
    const val FIND_REMOTE = "find_remote"
    const val GUEST = "guest"

    /**
     * Opens one page of TunaHome settings (the argument names it). Not offered as a shortcut:
     * it's how the automatic checks on each build reach each page. The settings lock still applies.
     */
    const val SETTINGS_PAGE = "settings_page"

    /** Every action in the order the list shows them, with its name. */
    val ALL = listOf(
        OPEN_APP to R.string.act_open_app,
        SECTION to R.string.act_section,
        HOME_TOP to R.string.act_home_top,
        SIDE_MENU to R.string.act_side_menu,
        SEARCH to R.string.act_search,
        VOICE to R.string.act_voice,
        PLAY_PAUSE to R.string.act_play_pause,
        NEXT_TRACK to R.string.act_next_track,
        PREVIOUS_TRACK to R.string.act_previous_track,
        MUTE to R.string.act_mute,
        NEXT_BACKGROUND to R.string.act_next_background,
        IDLE to R.string.act_idle,
        OPEN_APPS to R.string.act_open_apps,
        POINTER to R.string.act_pointer,
        FIND_REMOTE to R.string.find_remote,
        GUEST to R.string.guest_mode,
        RECENTS to R.string.act_recents,
        NOTIFICATIONS to R.string.act_notifications,
        SCREENSHOT to R.string.act_screenshot,
        SLEEP to R.string.act_sleep,
        SLEEP_TIMER to R.string.act_sleep_timer,
        BACK to R.string.act_back,
        QUICK to R.string.quick_title,
        PHONE to R.string.phone_title,
        WHO to R.string.page_profiles,
        SETTINGS to R.string.act_settings,
        TV_SETTINGS to R.string.act_tv_settings,
        UPDATES to R.string.act_updates,
    )

    /** Actions that only the Home button service can do. */
    private val NEEDS_SERVICE = setOf(RECENTS, NOTIFICATIONS, SLEEP, SCREENSHOT, BACK, POINTER)

    /** Actions about the home screen itself: from elsewhere, TunaHome opens first. */
    private val ON_HOME_SCREEN = setOf(HOME_TOP, SIDE_MENU, SECTION, NEXT_BACKGROUND, IDLE, SLEEP_TIMER, QUICK, PHONE, WHO, GUEST)

    const val EXTRA_ACTION = "hotkey_action"
    const val EXTRA_ARG = "hotkey_arg"

    fun name(context: Context, action: String, arg: String?): String {
        val base = ALL.firstOrNull { it.first == action }?.second?.let { context.getString(it) } ?: action
        return when (action) {
            OPEN_APP -> context.getString(R.string.act_open_named, appName(context, arg))
            SECTION -> context.getString(R.string.act_section_named,
                Rows.load(context).firstOrNull { it.id == arg }?.name(context) ?: "?")
            else -> base
        }
    }

    private fun appName(context: Context, pkg: String?): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(pkg ?: "", 0).loadLabel(pm).toString()
    } catch (e: Exception) {
        pkg ?: "?"
    }

    /**
     * Does [action]. [home] is the home screen when the shortcut was pressed there; null when it
     * came from elsewhere through the Home button service.
     */
    fun run(context: Context, action: String, arg: String?, home: HomeActivity?) {
        Sounds.play(context, Sounds.SELECT)
        if (action in ON_HOME_SCREEN && home == null) {
            context.startActivity(Intent(context, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_ACTION, action).putExtra(EXTRA_ARG, arg))
            return
        }
        if (action in NEEDS_SERVICE) {
            val service = HomeButtonService.running
            if (service == null) {
                Toast.makeText(context, R.string.act_needs_service, Toast.LENGTH_LONG).show()
                return
            }
            if (action == POINTER) {
                service.pointer.toggle()
                return
            }
            val global = when (action) {
                RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
                NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                BACK -> AccessibilityService.GLOBAL_ACTION_BACK
                SLEEP -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
                SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
                else -> -1
            }
            if (global < 0 || !service.performGlobalAction(global)) {
                Toast.makeText(context, R.string.act_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        when (action) {
            HOME_TOP, SIDE_MENU, SECTION, NEXT_BACKGROUND, IDLE, SLEEP_TIMER, QUICK, PHONE, WHO -> home?.hotkey(action, arg)
            SEARCH, VOICE -> context.startActivity(Intent(context, SearchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SearchActivity.EXTRA_VOICE, action == VOICE))
            SETTINGS -> context.startActivity(Intent(context, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            TV_SETTINGS -> HomeActivity.openTvSettings(context)
            FIND_REMOTE -> FindRemote.find(context)
            OPEN_APPS -> context.startActivity(Intent(context, OpenAppsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OPEN_APP -> {
                val pm = context.packageManager
                val launch = arg?.let { pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it) }
                if (launch == null) {
                    Toast.makeText(context, context.getString(R.string.open_failed, appName(context, arg)), Toast.LENGTH_LONG).show()
                } else {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    arg?.let { Prefs.addRecent(context, it) }
                }
            }
            PLAY_PAUSE -> media(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            NEXT_TRACK -> media(context, KeyEvent.KEYCODE_MEDIA_NEXT)
            PREVIOUS_TRACK -> media(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            MUTE -> context.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            UPDATES -> if (home != null) Updater.check(home, true)
                else context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A media button press, sent to whatever is playing. */
    private fun media(context: Context, code: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }
}
