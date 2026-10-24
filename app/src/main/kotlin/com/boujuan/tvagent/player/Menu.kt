package com.boujuan.tvagent.player

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Lists worked with the remote's arrows, in a panel: a title and rows, the picked one white, the current value
 * ticked. A row can open another list (a setting's values) on top; Back or ← goes down one. No Android focus: the
 * keys come from the activity, so nothing else on the screen can take them.
 */
class Menu(private val panel: View, private val title: TextView, private val scroll: ScrollView,
           private val list: LinearLayout) {

    /**
     * One list. `moved` runs as the pick moves (a preview), `picked` on OK; `left` when it's closed without a pick.
     * `picked` returns whether the list closes (a setting's values) or stays (the settings themselves).
     */
    class Page(val title: String, var rows: List<String>, var at: Int = 0, val current: Int = -1,
               val moved: (Int) -> Unit = {}, val left: () -> Unit = {}, val picked: (Int) -> Boolean)

    private val stack = ArrayList<Page>()

    init {
        // A ScrollView makes itself focusable, then takes the arrows to scroll: here they move the pick.
        scroll.isFocusable = false
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    val open get() = stack.isNotEmpty()
    val top: Page? get() = stack.lastOrNull()
    val depth get() = stack.size

    fun push(page: Page) {
        page.at = page.at.coerceIn(0, (page.rows.size - 1).coerceAtLeast(0))
        stack += page
        panel.visibility = View.VISIBLE
        draw()
        page.moved(page.at)
    }

    /** Closes the top list as if Back was pressed; false when nothing was open. */
    fun back(): Boolean {
        val page = stack.removeLastOrNull() ?: return false
        page.left()
        if (stack.isEmpty()) hide() else draw()
        return true
    }

    /** Closes them all, each as if left (a preview is undone). */
    fun close() {
        while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).left()
        hide()
    }

    private fun hide() {
        panel.visibility = View.GONE
        list.removeAllViews()
    }

    /** The top list's rows changed (a value set, an update found). */
    fun redraw(rows: List<String>? = null) {
        val page = stack.lastOrNull() ?: return
        if (rows != null) page.rows = rows
        draw()
    }

    fun onKey(keyCode: Int): Boolean {
        val page = stack.lastOrNull() ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (page.at > 0) move(page, page.at - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (page.at < page.rows.size - 1) move(page, page.at + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (page.rows.isEmpty()) return true
                if (page.picked(page.at) && stack.lastOrNull() === page) {
                    stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) hide() else draw()
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> back()
            KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            else -> return false
        }
        return true
    }

    private fun move(page: Page, to: Int) {
        page.at = to
        mark(page)
        page.moved(to)
    }

    private fun draw() {
        val page = stack.last()
        title.text = page.title
        list.removeAllViews()
        val inflater = LayoutInflater.from(list.context)
        for ((k, name) in page.rows.withIndex()) {
            val row = inflater.inflate(R.layout.choice, list, false) as TextView
            row.text = if (k == page.current) "$name  ✓" else name
            list.addView(row)
        }
        mark(page)
    }

    private fun mark(page: Page) {
        for (k in 0 until list.childCount) list.getChildAt(k).isSelected = k == page.at
        val row = list.getChildAt(page.at) ?: return
        scroll.post { scroll.smoothScrollTo(0, (row.top - scroll.height / 3).coerceAtLeast(0)) }
    }
}
