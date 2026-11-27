package com.tunastream.home

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.provider.Settings
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.concurrent.thread

// The home screen: the top of the screen (name and logo, clock, date, weather, storage and memory, network).

internal fun HomeActivity.setUpTopBar() {
    setUpBrand()

    val search = findViewById<View>(R.id.openSearch)
    search.visibility = if (Look.SHOW_SEARCH_BUTTON.get(this)) View.VISIBLE else View.GONE
    search.setOnClickListener { openSearch(false) }

    val update = findViewById<View>(R.id.checkUpdate)
    update.visibility = if (Look.SHOW_UPDATE_BUTTON.get(this)) View.VISIBLE else View.GONE
    update.setOnClickListener { Updater.check(this, true) }
    val tvSettings = findViewById<View>(R.id.openTvSettings)
    tvSettings.visibility = if (Look.SHOW_TV_SETTINGS_BUTTON.get(this)) View.VISIBLE else View.GONE
    tvSettings.setOnClickListener { HomeActivity.openTvSettings(this) }
    // Always shown: it is the way back to every other setting
    findViewById<View>(R.id.openSettings).setOnClickListener { openTunaHomeSettings() }
    findViewById<View>(R.id.whoChip).setOnClickListener { WhoActivity.start(this) }
    showWho()

    val clock = findViewById<TextClock>(R.id.clock)
    when (Look.CLOCK.get(this)) {
        Look.CLOCK_24 -> {
            clock.format12Hour = "HH:mm"
            clock.format24Hour = "HH:mm"
        }
        Look.CLOCK_12 -> {
            clock.format12Hour = "h:mm"
            clock.format24Hour = "h:mm"
        }
        Look.CLOCK_12_AMPM -> {
            clock.format12Hour = "h:mm a"
            clock.format24Hour = "h:mm a"
        }
        Look.CLOCK_HIDDEN -> clock.visibility = View.GONE
        else -> {
            clock.format12Hour = "h:mm"
            clock.format24Hour = "HH:mm"
        }
    }
    clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, clock.textSize * Look.clockScale(this))
    // A clock with hands, beside the numbers or instead of them
    val face = findViewById<View>(R.id.clockFace)
    val faceStyle = Look.CLOCK_FACE.get(this)
    if (faceStyle != Look.FACE_DIGITAL && Look.CLOCK.get(this) != Look.CLOCK_HIDDEN) {
        face.visibility = View.VISIBLE
        val side = Math.round(Look.dp(this, 44f) * Look.clockScale(this))
        face.layoutParams = face.layoutParams.apply {
            width = side
            height = side
        }
        if (faceStyle == Look.FACE_HANDS) {
            clock.visibility = View.GONE
        }
    }
    Look.clockFont(this)?.let { id -> Fonts.typeface(this, id, 300)?.let { clock.typeface = it } }

    val date = findViewById<TextClock>(R.id.date)
    val skeleton = when (Look.DATE.get(this)) {
        Look.DATE_SHORT -> "EEEdMMM"
        Look.DATE_LONG -> "EEEEdMMMM"
        else -> null
    }
    if (skeleton != null) {
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        date.format12Hour = pattern
        date.format24Hour = pattern
        date.visibility = View.VISIBLE
    }
}

/** Who's in now: their circle and name, when more than one person uses this TV. */
internal fun HomeActivity.showWho() {
    val chip = findViewById<View>(R.id.whoChip) ?: return
    if (Guest.on(this)) {
        // Guest mode says so, instead of whose TunaHome it is
        chip.visibility = View.VISIBLE
        chip.contentDescription = getString(R.string.guest_mode)
        findViewById<TextView>(R.id.whoName).text = getString(R.string.guest_chip)
        val initial = findViewById<TextView>(R.id.whoInitial)
        initial.text = "G"
        initial.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(themeColor(R.attr.tsAccent))
        }
        findViewById<ImageView>(R.id.whoPhoto)?.visibility = View.GONE
        initial.visibility = View.VISIBLE
        chip.setOnClickListener { Guest.leave(this) }
        return
    }
    chip.setOnClickListener { WhoActivity.start(this) }
    val person = if (Look.SHOW_WHO.get(this) && Profiles.all(this).size > 1) Profiles.current(this) else null
    if (person == null) {
        chip.visibility = View.GONE
        return
    }
    chip.visibility = View.VISIBLE
    chip.contentDescription = getString(R.string.who_in_chip, person.name)
    findViewById<TextView>(R.id.whoName).text = person.name
    val initial = findViewById<TextView>(R.id.whoInitial)
    initial.text = person.name.trim().take(1).uppercase()
    initial.background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(person.colour)
    }
    val photo = findViewById<ImageView>(R.id.whoPhoto)
    val bitmap = try {
        Profiles.photo(this, person.id)
    } catch (e: Exception) {
        null
    }
    if (bitmap != null) {
        // Their photo, round, in place of the letter
        val round = android.graphics.Bitmap.createBitmap(bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(round).drawOval(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.BitmapShader(bitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            })
        photo.setImageBitmap(round)
        photo.visibility = View.VISIBLE
        initial.visibility = View.INVISIBLE
    } else {
        photo.setImageDrawable(null)
        photo.visibility = View.GONE
        initial.visibility = View.VISIBLE
    }
}

/** The fish and the name: shown or not, which parts, and how big. */
internal fun HomeActivity.setUpBrand() {
    findViewById<View>(R.id.brand).visibility = if (Look.SHOW_BRAND.get(this)) View.VISIBLE else View.GONE
    val parts = Look.BRAND_PARTS.get(this)
    val logo = findViewById<ImageView>(R.id.brandLogo)
    val text = findViewById<View>(R.id.brandText)
    logo.visibility = if (parts == Look.BRAND_NAME) View.GONE else View.VISIBLE
    text.visibility = if (parts == Look.BRAND_LOGO) View.GONE else View.VISIBLE
    if (parts == Look.BRAND_NAME) {
        (text.layoutParams as LinearLayout.LayoutParams).marginStart = 0
    }
    val version = findViewById<TextView>(R.id.brandVersion)
    version.text = getString(R.string.version, BuildConfig.VERSION_NAME)
    version.visibility = if (Look.SHOW_VERSION.get(this)) View.VISIBLE else View.GONE

    val scale = Look.brandScale(this)
    if (scale != 1f) {
        logo.layoutParams = logo.layoutParams.apply { height = Math.round(height * scale) }
        (text.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = Math.round(marginStart * scale)
        }
        for (id in intArrayOf(R.id.brandTuna, R.id.brandWord)) {
            val word = findViewById<TextView>(id)
            word.setTextSize(TypedValue.COMPLEX_UNIT_PX, word.textSize * scale)
        }
        // The version stays readable even when the rest is small
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, version.textSize * maxOf(scale, 0.8f))
    }
}

/** Puts the weather beside or under the clock, in the chosen detail, picture and size. */
internal fun HomeActivity.showWeather() {
    val box = findViewById<LinearLayout>(R.id.weatherBox)
    val now = Weather.current
    if (!Weather.SHOW.get(this) || Weather.place(this) == null || now == null) {
        box.visibility = View.GONE
        return
    }
    val icon = findViewById<ImageView>(R.id.weatherIcon)
    val temp = findViewById<TextView>(R.id.weatherTemp)
    val detail = findViewById<TextView>(R.id.weatherDetail)
    temp.text = getString(R.string.wx_degrees, now.temperature)
    val condition = getString(Weather.condition(now.code))
    val parts = ArrayList<String>()
    when (Weather.DETAIL.get(this)) {
        Weather.DETAIL_CONDITION -> parts.add(condition)
        Weather.DETAIL_HIGH_LOW -> {
            parts.add(condition)
            parts.add(getString(R.string.wx_high_low, now.high, now.low))
        }
        Weather.DETAIL_ALL -> {
            parts.add(condition)
            parts.add(getString(R.string.wx_high_low, now.high, now.low))
            parts.add(getString(R.string.wx_feels, now.feelsLike))
            if (now.humidity >= 0) parts.add(getString(R.string.wx_humidity, now.humidity))
            if (now.wind >= 0) parts.add(getString(
                if (Weather.UNITS.get(this) == Weather.UNITS_F) R.string.wx_wind_mph else R.string.wx_wind_kmh, now.wind))
        }
    }
    detail.text = parts.joinToString(" · ")
    detail.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

    val style = Weather.ICONS.get(this)
    icon.visibility = if (style == Weather.ICONS_NONE) View.GONE else View.VISIBLE
    icon.setImageResource(Weather.icon(now.code, now.day))
    when (style) {
        Weather.ICONS_WHITE -> icon.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
        Weather.ICONS_ACCENT -> icon.setColorFilter(themeColor(R.attr.tsAccent), android.graphics.PorterDuff.Mode.SRC_IN)
        else -> icon.clearColorFilter()
    }

    if (!weatherSized) {
        weatherSized = true
        val scale = Weather.scale(this)
        icon.layoutParams = icon.layoutParams.apply {
            width = Math.round(width * scale)
            height = Math.round(height * scale)
        }
        temp.setTextSize(TypedValue.COMPLEX_UNIT_PX, temp.textSize * scale)
        detail.setTextSize(TypedValue.COMPLEX_UNIT_PX, detail.textSize * maxOf(scale, 0.9f))
        if (Weather.PLACE.get(this) == Weather.PLACE_UNDER) {
            // Under the clock instead of beside it
            (box.parent as ViewGroup).removeView(box)
            findViewById<LinearLayout>(R.id.clockBox).addView(box)
            (box.layoutParams as LinearLayout.LayoutParams).apply {
                marginStart = 0
                marginEnd = 0
                topMargin = Math.round(Look.dp(this@showWeather, 4f))
            }
        }
    }
    if (box.visibility != View.VISIBLE) {
        box.alpha = 0f
        box.visibility = View.VISIBLE
        box.animate().alpha(1f).setDuration(400).start()
    }
}

/** Shows free storage, memory and batteries as chosen; false when all are switched off. */
internal fun HomeActivity.showSystemInfo(): Boolean {
    val view = findViewById<TextView>(R.id.systemInfo)
    val mode = Look.SYSTEM_INFO.get(this)
    val parts = ArrayList<String>()
    val sleepLeft = SleepTimer.minutesLeft()
    if (sleepLeft > 0) {
        parts.add(getString(R.string.sleep_in, sleepLeft))
    }
    if (mode == Look.INFO_STORAGE || mode == Look.INFO_BOTH) {
        try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            parts.add(getString(R.string.info_storage_free,
                android.text.format.Formatter.formatShortFileSize(this, stat.availableBytes)))
        } catch (e: Exception) {
            // Left out
        }
    }
    if (mode == Look.INFO_MEMORY || mode == Look.INFO_BOTH) {
        try {
            val memory = android.app.ActivityManager.MemoryInfo()
            getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
            parts.add(getString(R.string.info_memory_free,
                android.text.format.Formatter.formatShortFileSize(this, memory.availMem)))
        } catch (e: Exception) {
            // Left out
        }
    }
    val batteries = Look.BATTERIES.get(this)
    if (batteries != Look.BATTERY_OFF) {
        for (b in Batteries.levels(this)) {
            if (batteries == Look.BATTERY_ALWAYS || b.percent <= Batteries.LOW) {
                parts.add(getString(if (b.percent <= Batteries.LOW) R.string.info_battery_low else R.string.info_battery,
                    b.name, b.percent))
            }
        }
    }
    view.text = parts.joinToString("   ")
    view.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
    return mode != Look.INFO_HIDDEN || sleepLeft > 0 || batteries != Look.BATTERY_OFF
}

internal fun HomeActivity.watchNetwork() {
    val icon = findViewById<ImageView>(R.id.networkIcon)
    val vpn = findViewById<View>(R.id.vpnIcon)
    val showNetwork = Look.SHOW_NETWORK.get(this)
    val showVpn = Look.SHOW_VPN.get(this)
    if (!showNetwork && !showVpn) {
        icon.visibility = View.GONE
        vpn.visibility = View.GONE
        return
    }
    val manager = getSystemService(ConnectivityManager::class.java) ?: return
    fun update() {
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        vpn.visibility = if (showVpn && caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) View.VISIBLE else View.GONE
        if (!showNetwork) {
            icon.visibility = View.GONE
            return
        }
        val picture = when {
            caps == null -> R.drawable.ic_net_none
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.drawable.ic_net_wired
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.drawable.ic_net_wifi
            else -> R.drawable.ic_net_wired
        }
        icon.setImageResource(picture)
        icon.visibility = View.VISIBLE
    }
    update()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { update() }
        }

        override fun onLost(network: Network) {
            runOnUiThread { update() }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            runOnUiThread { update() }
        }
    }
    try {
        manager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    } catch (e: Exception) {
        // The icon just won't change until the home screen is shown again
    }
}
