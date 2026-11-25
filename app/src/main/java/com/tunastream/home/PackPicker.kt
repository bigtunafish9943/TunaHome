package com.tunastream.home

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Choosing any drawing from the icon pack for one app, for apps the pack doesn't know. Opens
 * with the closest matches to the app's name; typing searches the whole pack.
 */
object PackPicker {
    fun show(activity: Activity, app: AppEntry, done: () -> Unit) {
        val pack = IconPacks.chosen(activity)
        if (pack == null) {
            Toast.makeText(activity, R.string.pick_no_pack, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, R.string.pick_loading, Toast.LENGTH_SHORT).show()
        thread(name = "TunaHome pack picker") {
            val loaded = IconPacks.load(activity.applicationContext, pack)
            activity.runOnUiThread {
                if (!activity.isDestroyed) {
                    open(activity, app, pack, loaded, done)
                }
            }
        }
    }

    private fun open(activity: Activity, app: AppEntry, pack: String, loaded: IconPacks.Loaded, done: () -> Unit) {
        val pad = Math.round(Look.dp(activity, 16f))
        val layout = LinearLayout(activity)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(pad, pad / 2, pad, 0)

        val box = EditText(activity)
        box.setSingleLine()
        box.hint = activity.getString(R.string.pick_search)
        box.setTextColor(activity.themeColor(R.attr.tsText))
        box.setHintTextColor(activity.themeColor(R.attr.tsTextSecondary))
        box.setText(app.label)
        layout.addView(box)

        val grid = GridView(activity)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = Math.round(Look.dp(activity, 96f))
        grid.verticalSpacing = Math.round(Look.dp(activity, 8f))
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(Look.dp(activity, 300f)))
        layout.addView(grid)

        val adapter = object : BaseAdapter() {
            var shown: List<String> = loaded.suggest(app.label)
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = (convertView as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    val p = Math.round(Look.dp(activity, 6f))
                    setPadding(p, p, p, p)
                    addView(ImageView(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(Math.round(Look.dp(activity, 56f)), Math.round(Look.dp(activity, 56f)))
                    })
                    addView(TextView(activity).apply {
                        maxLines = 1
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(activity.themeColor(R.attr.tsTextSecondary))
                    })
                }
                val name = shown[position]
                (cell.getChildAt(0) as ImageView).setImageDrawable(loaded.drawable(name))
                (cell.getChildAt(1) as TextView).text = name.replace('_', ' ')
                return cell
            }
        }
        grid.adapter = adapter
        grid.setSelector(android.R.drawable.list_selector_background)
        grid.setBackgroundColor(Color.TRANSPARENT)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.pick_title, app.label))
            .setView(layout)
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (IconPacks.picked(activity, pack, app.appPkg) != null) {
                    setNeutralButton(R.string.pick_reset) { _, _ ->
                        IconPacks.pick(activity, pack, app.appPkg, null)
                        done()
                    }
                }
            }
            .show()

        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.shown = loaded.suggest(s?.toString() ?: "", 120)
                adapter.notifyDataSetChanged()
            }
        })
        grid.setOnItemClickListener { _, _, position, _ ->
            IconPacks.pick(activity, pack, app.appPkg, adapter.shown[position])
            dialog.dismiss()
            done()
        }
        if (adapter.count > 0) grid.requestFocus() else box.requestFocus()
    }
}
