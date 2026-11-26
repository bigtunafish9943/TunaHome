package com.tunastream.home

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Open apps: the apps opened lately in a row over whatever is on screen, most recent first.
 * Select one to switch to it; hold select (or Menu) to close it. Home pressed twice opens it.
 */
class OpenAppsActivity : BaseActivity() {
    private var focusedPkg: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A full screen of its own (not see-through), so the app left behind is in the
        // background, where Android lets it be closed
        build()
    }

    private fun build() {
        val dp = resources.displayMetrics.density
        // Not TunaHome, and only apps still on the TV
        val pkgs = OpenApps.list(this).filter { it != packageName && launchIntent(it) != null }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.open_apps_title)
            setTextColor(themeColor(R.attr.tsText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = (32 * dp).toInt() })

        if (pkgs.isEmpty()) {
            root.addView(TextView(this).apply {
                text = getString(R.string.open_apps_none)
                setTextColor(themeColor(R.attr.tsTextSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2))
            setContentView(root)
            Look.styleText(root)
            return
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding((48 * dp).toInt(), (12 * dp).toInt(), (48 * dp).toInt(), (12 * dp).toInt())
        }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        }
        root.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        var focus: View? = null
        for ((i, pkg) in pkgs.withIndex()) {
            val card = card(pkg, dp)
            row.addView(card, LinearLayout.LayoutParams((240 * dp).toInt(), -2).apply {
                leftMargin = (10 * dp).toInt()
                rightMargin = (10 * dp).toInt()
            })
            // Where the viewer was, else the app before this one (the quickest switch back)
            if (pkg == focusedPkg || (focus == null && (i == 1 || pkgs.size == 1))) {
                focus = card
            }
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.open_apps_hint)
            setTextColor(themeColor(R.attr.tsTextSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (28 * dp).toInt() })
        setContentView(root)
        Look.styleText(root)
        (focus ?: row.getChildAt(0))?.requestFocus()
    }

    private fun launchIntent(pkg: String): Intent? = try {
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
    } catch (e: Exception) {
        null
    }

    /** One app: its TV picture (or icon) and name, ringed in the accent colour while on it. */
    private fun card(pkg: String, dp: Float): View {
        val pm = packageManager
        val info = try {
            pm.getApplicationInfo(pkg, 0)
        } catch (e: Exception) {
            null
        }
        val label = info?.loadLabel(pm)?.toString() ?: pkg
        val banner: Drawable? = try {
            launchIntent(pkg)?.component?.let { pm.getActivityInfo(it, 0).loadBanner(pm) } ?: info?.loadBanner(pm)
        } catch (e: Exception) {
            null
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = label
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(themeColor(R.attr.tsSurface))
                    setStroke((3 * dp).toInt(), themeColor(R.attr.tsAccent))
                    cornerRadius = 14 * dp
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(themeColor(R.attr.tsSurface))
                    setStroke((1 * dp).toInt(), themeColor(R.attr.tsSurfaceBorder))
                    cornerRadius = 14 * dp
                })
            }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
                if (has) {
                    focusedPkg = pkg
                    Sounds.play(this@OpenAppsActivity, Sounds.MOVE)
                }
            }
            setOnClickListener { switchTo(pkg, label) }
            setOnLongClickListener {
                closeMenu(pkg, label)
                true
            }
        }
        val picture = ImageView(this).apply {
            if (banner != null) {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.FIT_CENTER
            } else {
                setImageDrawable(info?.loadIcon(pm))
                scaleType = ImageView.ScaleType.FIT_CENTER
                val inset = (28 * dp).toInt()
                setPadding(inset, inset / 2, inset, inset / 2)
            }
        }
        card.addView(picture, LinearLayout.LayoutParams(-1, (126 * dp).toInt()))
        card.addView(TextView(this).apply {
            text = label
            setTextColor(themeColor(R.attr.tsText))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        return card
    }

    private fun switchTo(pkg: String, label: String) {
        val go = {
            val intent = launchIntent(pkg)
            if (intent == null) {
                Toast.makeText(this, getString(R.string.open_failed, label), Toast.LENGTH_LONG).show()
            } else {
                try {
                    // The launch screen with a new task brings the app back as it was left
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Sounds.play(this, Sounds.OPEN)
                    OpenApps.note(this, pkg)
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(this, getString(R.string.open_failed, label), Toast.LENGTH_LONG).show()
                }
            }
        }
        if (AppLock.isLocked(this, pkg)) AppLock.ask(this, label) { go() } else go()
    }

    private fun closeMenu(pkg: String, label: String) {
        QuietDialog.Builder(this)
            .setTitle(label)
            .setItems(arrayOf(getString(R.string.open_apps_close), getString(R.string.open_apps_close_all))) { _, which ->
                if (which == 0) close(listOf(pkg)) else close(OpenApps.list(this))
            }
            .show()
    }

    /**
     * Stops the apps (they start fresh next time) and takes them off the list. Android lets
     * TunaHome stop an app only while it's in the background, which it is behind Open apps.
     */
    private fun close(pkgs: List<String>) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (pkg in pkgs) {
            if (pkg == packageName) {
                continue
            }
            try {
                am.killBackgroundProcesses(pkg)
            } catch (e: Exception) {
                // Not allowed for this one: still off the list
            }
            OpenApps.remove(this, pkg)
        }
        focusedPkg = null
        build()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            val pkg = focusedPkg
            if (pkg != null) {
                val label = try {
                    packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
                } catch (e: Exception) {
                    pkg
                }
                closeMenu(pkg, label)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        // A panel over whatever was on screen: once something else comes up, it's done
        if (!isChangingConfigurations) {
            finish()
        }
    }
}
