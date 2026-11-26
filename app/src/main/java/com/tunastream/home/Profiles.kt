package com.tunastream.home

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Who's watching: several people's TunaHome on one TV. Each profile is a whole setup of its own
 * (sections, favourites, look, background, app lock, what For you has learnt), kept as a saved
 * layout and swapped in when that person is chosen. A profile can have a PIN of its own, so
 * children can't switch into a grown-up's.
 *
 * The list of profiles lives apart from the settings it swaps, so it's the same whoever is in.
 */
object Profiles {
    class Profile(val id: String, var name: String, var colour: Int, var pinHash: String?, var pinSalt: String?) {
        val locked: Boolean get() = pinHash != null

        /** Kids mode: only [allowed] apps, and TunaHome settings behind a grown-up's PIN. */
        var kids = false
        var allowed: Set<String> = emptySet()

        /** Minutes of apps a day (TunaHome itself not counted); 0 for no limit. */
        var dailyMinutes = 0
    }

    const val ASK_NEVER = 0
    const val ASK_WAKE = 1
    const val ASK_START = 2
    val ASK_NAMES = intArrayOf(R.string.who_ask_never, R.string.who_ask_wake, R.string.who_ask_start)

    /** Colours for each person's circle. */
    val COLOURS = intArrayOf(
        0xFF2E86DE.toInt(), 0xFFE84A5F.toInt(), 0xFF28B485.toInt(), 0xFFF0A500.toInt(),
        0xFF8E44AD.toInt(), 0xFF16A0A0.toInt(), 0xFFE67E22.toInt(), 0xFF7F8C8D.toInt(),
    )

    private fun store(context: Context): SharedPreferences =
        context.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    private fun dir(context: Context) = File(context.filesDir, "profiles").apply { mkdirs() }

    private fun fileOf(context: Context, id: String) = File(dir(context), "$id.zip")

    private fun photoOf(context: Context, id: String) = File(dir(context), "$id-photo.png")

    fun hasPhoto(context: Context, id: String): Boolean = photoOf(context, id).exists()

    /** Someone's photo, square. Slow-ish: fine on the main thread for a handful of people. */
    fun photo(context: Context, id: String): android.graphics.Bitmap? =
        photoOf(context, id).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }

    /** A photo for someone's circle, cut to a square from the middle. Slow: off the main thread. */
    fun savePhoto(context: Context, id: String, uri: android.net.Uri): Boolean {
        val picture = Pictures.decode(context, uri, PHOTO, PHOTO) ?: return false
        val side = minOf(picture.width, picture.height)
        val square = android.graphics.Bitmap.createBitmap(picture, (picture.width - side) / 2, (picture.height - side) / 2, side, side)
        val sized = android.graphics.Bitmap.createScaledBitmap(square, PHOTO, PHOTO, true)
        val target = photoOf(context, id)
        val temp = File(target.path + ".tmp")
        java.io.FileOutputStream(temp).use { sized.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        target.delete()
        return temp.renameTo(target)
    }

    fun removePhoto(context: Context, id: String) {
        photoOf(context, id).delete()
    }

    private const val PHOTO = 320

    fun all(context: Context): List<Profile> {
        val found = ArrayList<Profile>()
        try {
            val array = JSONArray(store(context).getString("list", "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                found.add(Profile(o.getString("id"), o.optString("name"), o.optInt("colour"),
                    o.optString("pin").ifEmpty { null }, o.optString("salt").ifEmpty { null }).apply {
                    kids = o.optBoolean("kids")
                    dailyMinutes = o.optInt("daily")
                    val apps = o.optJSONArray("allowed")
                    allowed = if (apps == null) emptySet() else (0 until apps.length()).map { apps.optString(it) }.toSet()
                })
            }
        } catch (e: Exception) {
            // None yet
        }
        return found
    }

    private fun save(context: Context, list: List<Profile>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("colour", it.colour)
                .put("pin", it.pinHash ?: "").put("salt", it.pinSalt ?: "")
                .put("kids", it.kids).put("daily", it.dailyMinutes).put("allowed", JSONArray(it.allowed.toList())))
        }
        store(context).edit().putString("list", array.toString()).apply()
    }

    fun currentId(context: Context): String? = store(context).getString("current", null)

    fun current(context: Context): Profile? = currentId(context)?.let { id -> all(context).firstOrNull { it.id == id } }

    fun ask(context: Context): Int = store(context).getInt("ask", ASK_WAKE)

    fun setAsk(context: Context, value: Int) {
        store(context).edit().putInt("ask", value).apply()
    }

    /** More than one person: worth asking who's watching. */
    fun inUse(context: Context): Boolean = all(context).size > 1

    /**
     * Starts profiles: the setup in use now becomes the first person's. Then [add]s the next
     * person, either as a copy of this setup or starting fresh.
     */
    fun begin(context: Context, firstName: String) {
        if (all(context).isNotEmpty()) return
        val first = Profile(newId(), firstName, COLOURS[0], null, null)
        save(context, listOf(first))
        store(context).edit().putString("current", first.id).apply()
        Layouts.saveTo(context, fileOf(context, first.id))
    }

    fun add(context: Context, name: String, copy: Boolean): Profile {
        val list = all(context)
        val profile = Profile(newId(), name, COLOURS[list.size % COLOURS.size], null, null)
        if (copy) {
            Layouts.saveTo(context, fileOf(context, profile.id))
        } else {
            // A fresh start: nothing saved, so every setting is as it comes
            Layouts.saveEmpty(fileOf(context, profile.id))
        }
        save(context, list + profile)
        return profile
    }

    fun rename(context: Context, id: String, name: String) {
        save(context, all(context).onEach { if (it.id == id) it.name = name })
    }

    fun setKids(context: Context, id: String, kids: Boolean) {
        save(context, all(context).onEach { if (it.id == id) it.kids = kids })
    }

    fun setAllowed(context: Context, id: String, apps: Set<String>) {
        save(context, all(context).onEach { if (it.id == id) it.allowed = apps })
    }

    fun setDailyMinutes(context: Context, id: String, minutes: Int) {
        save(context, all(context).onEach { if (it.id == id) it.dailyMinutes = minutes })
    }

    /** People who aren't in kids mode and have a PIN: who can let a child do more. */
    fun grownUps(context: Context): List<Profile> = all(context).filter { !it.kids && it.locked }

    fun setColour(context: Context, id: String, colour: Int) {
        save(context, all(context).onEach { if (it.id == id) it.colour = colour })
    }

    fun setPin(context: Context, id: String, pin: String?) {
        save(context, all(context).onEach {
            if (it.id == id) {
                if (pin == null) {
                    it.pinHash = null
                    it.pinSalt = null
                } else {
                    val salt = ByteArray(16).also { b -> SecureRandom().nextBytes(b) }.joinToString("") { b -> String.format("%02x", b) }
                    it.pinSalt = salt
                    it.pinHash = hash(salt, pin)
                }
            }
        })
    }

    fun pinMatches(profile: Profile, pin: String): Boolean =
        profile.pinSalt != null && profile.pinHash == hash(profile.pinSalt!!, pin)

    /** Removes someone (never the person in now). With one left, profiles are simply off. */
    fun remove(context: Context, id: String) {
        if (id == currentId(context)) return
        fileOf(context, id).delete()
        photoOf(context, id).delete()
        val left = all(context).filter { it.id != id }
        save(context, left)
        if (left.size <= 1) {
            // Back to a single setup: nothing to choose between
            left.firstOrNull()?.let {
                fileOf(context, it.id).delete()
                photoOf(context, it.id).delete()
            }
            save(context, emptyList())
            store(context).edit().remove("current").apply()
        }
    }

    /**
     * Switches to [id]: this person's setup is put away and theirs brought back, then TunaHome
     * starts again in it. Slow: run off the main thread, then call [Layouts.restart].
     */
    fun switchTo(context: Context, id: String) {
        val from = currentId(context)
        if (from == id) return
        if (from != null) {
            Layouts.saveTo(context, fileOf(context, from))
        }
        val file = fileOf(context, id)
        if (file.exists()) {
            file.inputStream().use { Layouts.read(context, it) }
        }
        store(context).edit().putString("current", id).putLong("switched_at", System.currentTimeMillis()).commit()
    }

    /** Keeps the person in now saved, so their latest changes are there to come back to. */
    fun keepCurrent(context: Context) {
        currentId(context)?.let { Layouts.saveTo(context, fileOf(context, it)) }
    }

    /** Whether TunaHome just started again after a switch (so it shouldn't ask again). */
    fun justSwitched(context: Context): Boolean =
        System.currentTimeMillis() - store(context).getLong("switched_at", 0) < 20_000

    /** Not twice in a row: the Home button service and the home screen may both see a wake-up. */
    fun claimAsk(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val last = store(context).getLong("asked_at", 0)
        if (now - last < 10_000) return false
        store(context).edit().putLong("asked_at", now).apply()
        return true
    }

    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36)

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("$salt:$pin").toByteArray()).joinToString("") { String.format("%02x", it) }

    /** Asked once each time TunaHome starts, not when its screens are rebuilt. */
    @Volatile
    private var startChecked = false

    /**
     * Shows "Who's watching?" if the viewer asked for it at this moment. True if it showed (so
     * the app that opens on wake-up can wait).
     */
    fun askIfWanted(context: Context, moment: Int): Boolean {
        if (moment == ASK_START) {
            if (startChecked) return false
            startChecked = true
        }
        if (!inUse(context) || ask(context) != moment || justSwitched(context) || !claimAsk(context)) return false
        WhoActivity.start(context)
        return true
    }
}
