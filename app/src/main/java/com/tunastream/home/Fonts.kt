package com.tunastream.home

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Every font TunaHome offers: Android's own, the open-licence fonts bundled in the app
 * (assets/fonts, each with its licence in assets/fonts/licences), and one font file of the
 * viewer's own.
 *
 * A font is chosen by id and stored as text, so the list can grow without breaking a choice.
 */
object Fonts {
    /** "" is the design as it is: Android's standard font, in each text's own weight. */
    const val STANDARD = ""
    const val CUSTOM = "custom"

    class SystemFont(val id: String, val nameRes: Int, val family: String)

    class BundledFont(
        val id: String,
        val name: String,
        val file: String,
        val boldFile: String?,
        /** One file holding every weight, picked with its weight setting. */
        val variable: Boolean,
    )

    val SYSTEM = listOf(
        SystemFont(STANDARD, R.string.font_standard, "sans-serif"),
        SystemFont("sys-light", R.string.font_light, "sans-serif-light"),
        SystemFont("sys-condensed", R.string.font_condensed, "sans-serif-condensed"),
        SystemFont("sys-serif", R.string.font_serif, "serif"),
        SystemFont("sys-mono", R.string.font_mono, "monospace"),
        SystemFont("sys-casual", R.string.font_casual, "casual"),
        SystemFont("sys-cursive", R.string.font_cursive, "cursive"),
        SystemFont("sys-smallcaps", R.string.font_smallcaps, "sans-serif-smallcaps"),
    )

    val BUNDLED = listOf(
        BundledFont("inter", "Inter", "inter.ttf", null, true),
        BundledFont("poppins", "Poppins", "poppins.ttf", "poppins-bold.ttf", false),
        BundledFont("montserrat", "Montserrat", "montserrat.ttf", null, true),
        BundledFont("nunito", "Nunito", "nunito.ttf", null, true),
        BundledFont("lato", "Lato", "lato.ttf", "lato-bold.ttf", false),
        BundledFont("opensans", "Open Sans", "opensans.ttf", null, true),
        BundledFont("raleway", "Raleway", "raleway.ttf", null, true),
        BundledFont("rubik", "Rubik", "rubik.ttf", null, true),
        BundledFont("outfit", "Outfit", "outfit.ttf", null, true),
        BundledFont("manrope", "Manrope", "manrope.ttf", null, true),
        BundledFont("urbanist", "Urbanist", "urbanist.ttf", null, true),
        BundledFont("lexend", "Lexend", "lexend.ttf", null, true),
        BundledFont("dmsans", "DM Sans", "dmsans.ttf", null, true),
        BundledFont("spacegrotesk", "Space Grotesk", "spacegrotesk.ttf", null, true),
        BundledFont("quicksand", "Quicksand", "quicksand.ttf", null, true),
        BundledFont("comfortaa", "Comfortaa", "comfortaa.ttf", null, true),
        BundledFont("josefinsans", "Josefin Sans", "josefinsans.ttf", null, true),
        BundledFont("kanit", "Kanit", "kanit.ttf", "kanit-bold.ttf", false),
        BundledFont("firasans", "Fira Sans", "firasans.ttf", "firasans-bold.ttf", false),
        BundledFont("exo2", "Exo 2", "exo2.ttf", null, true),
        BundledFont("oswald", "Oswald", "oswald.ttf", null, true),
        BundledFont("teko", "Teko", "teko.ttf", null, true),
        BundledFont("bebasneue", "Bebas Neue", "bebasneue.ttf", null, false),
        BundledFont("anton", "Anton", "anton.ttf", null, false),
        BundledFont("orbitron", "Orbitron", "orbitron.ttf", null, true),
        BundledFont("audiowide", "Audiowide", "audiowide.ttf", null, false),
        BundledFont("russoone", "Russo One", "russoone.ttf", null, false),
        BundledFont("righteous", "Righteous", "righteous.ttf", null, false),
        BundledFont("archivoblack", "Archivo Black", "archivoblack.ttf", null, false),
        BundledFont("bangers", "Bangers", "bangers.ttf", null, false),
        BundledFont("playfairdisplay", "Playfair Display", "playfairdisplay.ttf", null, true),
        BundledFont("bitter", "Bitter", "bitter.ttf", null, true),
        BundledFont("crimsontext", "Crimson Text", "crimsontext.ttf", "crimsontext-bold.ttf", false),
        BundledFont("lobster", "Lobster", "lobster.ttf", null, false),
        BundledFont("pacifico", "Pacifico", "pacifico.ttf", null, false),
        BundledFont("caveat", "Caveat", "caveat.ttf", null, true),
        BundledFont("comicrelief", "Comic Relief", "comicrelief.ttf", "comicrelief-bold.ttf", false),
        BundledFont("jetbrainsmono", "JetBrains Mono", "jetbrainsmono.ttf", null, true),
        BundledFont("robotomono", "Roboto Mono", "robotomono.ttf", null, true),
        BundledFont("sourcecodepro", "Source Code Pro", "sourcecodepro.ttf", null, true),
        BundledFont("pressstart2p", "Press Start 2P", "pressstart2p.ttf", null, false),
        BundledFont("vt323", "VT323", "vt323.ttf", null, false),
        BundledFont("silkscreen", "Silkscreen", "silkscreen.ttf", "silkscreen-bold.ttf", false),
    )

    /** Every font id in the order the list shows them; the viewer's own font last, if added. */
    fun ids(context: Context): List<String> {
        val ids = ArrayList<String>()
        SYSTEM.forEach { ids.add(it.id) }
        BUNDLED.forEach { ids.add(it.id) }
        if (hasCustom(context)) {
            ids.add(CUSTOM)
        }
        return ids
    }

    fun name(context: Context, id: String): String {
        SYSTEM.firstOrNull { it.id == id }?.let { return context.getString(it.nameRes) }
        BUNDLED.firstOrNull { it.id == id }?.let { return it.name }
        if (id == CUSTOM && hasCustom(context)) {
            return context.getString(R.string.font_custom)
        }
        return context.getString(R.string.font_standard)
    }

    fun exists(context: Context, id: String): Boolean =
        SYSTEM.any { it.id == id } || BUNDLED.any { it.id == id } || (id == CUSTOM && hasCustom(context))

    private val cache = HashMap<String, Typeface>()

    /**
     * The font [id] at [weight] (100 to 900; 400 is regular, 700 bold). Null for the standard
     * font, which leaves text as the design has it.
     */
    fun typeface(context: Context, id: String, weight: Int): Typeface? {
        if (id == STANDARD) {
            return null
        }
        val key = "$id/$weight"
        cache[key]?.let { return it }
        val made = try {
            make(context, id, weight)
        } catch (e: Exception) {
            null
        }
        if (made != null) {
            cache[key] = made
        }
        return made
    }

    private fun make(context: Context, id: String, weight: Int): Typeface? {
        val bold = weight >= 600
        val system = SYSTEM.firstOrNull { it.id == id }
        if (system != null) {
            return Typeface.create(system.family, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val assets = context.assets
        val bundled = BUNDLED.firstOrNull { it.id == id }
        if (bundled != null) {
            if (bundled.variable && Build.VERSION.SDK_INT >= 26) {
                return Typeface.Builder(assets, "fonts/" + bundled.file)
                    .setFontVariationSettings("'wght' $weight")
                    .build()
            }
            if (bold && bundled.boldFile != null) {
                return Typeface.createFromAsset(assets, "fonts/" + bundled.boldFile)
            }
            val regular = Typeface.createFromAsset(assets, "fonts/" + bundled.file)
            // No bold file: Android thickens the regular one
            return if (bold) Typeface.create(regular, Typeface.BOLD) else regular
        }
        if (id == CUSTOM && hasCustom(context)) {
            val file = customFile(context)
            if (Build.VERSION.SDK_INT >= 26) {
                val built = Typeface.Builder(file).setFontVariationSettings("'wght' $weight").build()
                return if (bold) Typeface.create(built, Typeface.BOLD) else built
            }
            val regular = Typeface.createFromFile(file)
            return if (bold) Typeface.create(regular, Typeface.BOLD) else regular
        }
        return null
    }

    // ------------------------------------------------------------------ the viewer's own font

    private fun customFile(context: Context) = File(File(context.filesDir, "fonts"), "custom.ttf")

    fun hasCustom(context: Context): Boolean = customFile(context).exists()

    /** Copies a chosen font file in and checks Android can read it. Slow: background thread. */
    fun saveCustom(context: Context, uri: Uri): Boolean {
        val target = customFile(context)
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".tmp")
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { source ->
            FileOutputStream(temp).use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) {
                        break
                    }
                    total += n
                    if (total > MAX_FONT_BYTES) {
                        temp.delete()
                        return false
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        // A file Android can't read as a font gives back the default font: refuse it
        val test = try {
            Typeface.createFromFile(temp)
        } catch (e: Exception) {
            null
        }
        if (test == null || test == Typeface.DEFAULT) {
            temp.delete()
            return false
        }
        target.delete()
        val ok = temp.renameTo(target)
        synchronized(cache) {
            cache.keys.removeAll { it.startsWith("$CUSTOM/") }
        }
        return ok
    }

    fun removeCustom(context: Context) {
        customFile(context).delete()
        cache.keys.removeAll { it.startsWith("$CUSTOM/") }
    }

    private const val MAX_FONT_BYTES = 30L * 1024 * 1024
}
