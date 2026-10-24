package com.boujuan.tvagent.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * What the viewer sets with the remote, in the pause screen's Settings or the app on the home screen, kept on the
 * TV. Each option is a list of values; the subtitles' look starts from the TV's own captions settings ("As the TV").
 */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** One setting: its key, its name, the values offered (the default among them) and how a value reads. */
    class Option(val key: String, val label: Int, val values: IntArray, val default: Int, val names: Int = 0,
                 val format: Int = 0) {
        fun show(c: Context, v: Int): String = when {
            names != 0 -> c.resources.getStringArray(names)[v]
            format != 0 -> c.getString(format, v)
            else -> v.toString()
        }
    }

    operator fun get(o: Option): Int = prefs.getInt(o.key, o.default).takeIf { it in o.values } ?: o.default
    operator fun set(o: Option, v: Int) = prefs.edit().putInt(o.key, v).apply()

    /** Subtitles as set, from the TV's captions settings for what's left "As the TV". */
    fun styleSubtitles(view: SubtitleView) {
        // As SubtitleView's own setUserDefaultStyle and setUserDefaultTextSize read them.
        val captions = context.getSystemService(CaptioningManager::class.java)?.takeIf { it.isEnabled }
        val tv = captions?.let { CaptionStyleCompat.createFromCaptionStyle(it.userStyle) } ?: CaptionStyleCompat.DEFAULT
        val edge = when (get(SUB_EDGE)) {
            1 -> CaptionStyleCompat.EDGE_TYPE_NONE
            2 -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            3 -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            4 -> CaptionStyleCompat.EDGE_TYPE_RAISED
            5 -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
            else -> tv.edgeType
        }
        view.setStyle(CaptionStyleCompat(
            when (get(SUB_COLOR)) {
                1 -> Color.WHITE
                2 -> Color.YELLOW
                3 -> Color.CYAN
                4 -> Color.GREEN
                else -> tv.foregroundColor
            },
            when (get(SUB_BACKGROUND)) {
                1 -> Color.TRANSPARENT
                2 -> 0x99000000.toInt()
                3 -> Color.BLACK
                else -> tv.backgroundColor
            },
            tv.windowColor, edge,
            if (get(SUB_EDGE) != 0 && Color.alpha(tv.edgeColor) == 0) Color.BLACK else tv.edgeColor,
            when (get(SUB_FONT)) {
                1 -> Typeface.SANS_SERIF
                2 -> Typeface.SERIF
                3 -> Typeface.MONOSPACE
                4 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                else -> tv.typeface
            }))
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * (captions?.fontScale ?: 1f) * get(SUB_SIZE) / 100f)
        view.setBottomPaddingFraction(get(SUB_PLACE) / 100f)
    }

    companion object {
        val SUB_SIZE = Option("sub_size", R.string.subtitle_size,
            intArrayOf(50, 60, 70, 80, 90, 100, 110, 125, 150, 175, 200), 100, format = R.string.percent)
        // From the bottom of the screen; the lowest put a scope film's lines in the black bar under the picture.
        val SUB_PLACE = Option("sub_place", R.string.subtitle_place, IntArray(16) { it * 2 }, 8, format = R.string.percent)
        val SUB_COLOR = Option("sub_color", R.string.subtitle_color, IntArray(5) { it }, 0, names = R.array.colors)
        val SUB_BACKGROUND = Option("sub_background", R.string.subtitle_background, IntArray(4) { it }, 0, names = R.array.backgrounds)
        val SUB_EDGE = Option("sub_edge", R.string.subtitle_edge, IntArray(6) { it }, 0, names = R.array.edges)
        val SUB_FONT = Option("sub_font", R.string.subtitle_font, IntArray(5) { it }, 0, names = R.array.fonts)
        val JUMP_BACK = Option("jump_back", R.string.jump_back, intArrayOf(5, 10, 15, 20, 30), 10, format = R.string.seconds)
        val JUMP_FORWARD = Option("jump_forward", R.string.jump_forward, intArrayOf(10, 15, 30, 60), 30, format = R.string.seconds)
        val HELD_SPEED = Option("held_speed", R.string.held_speed, IntArray(3) { it }, 1, names = R.array.speeds)
        val BADGES = Option("badges", R.string.badges_after_load, intArrayOf(1, 0), 1, names = R.array.on_off)

        val ALL = listOf(SUB_SIZE, SUB_PLACE, SUB_COLOR, SUB_BACKGROUND, SUB_EDGE, SUB_FONT, JUMP_BACK, JUMP_FORWARD,
            HELD_SPEED, BADGES)
        /** The options that change how subtitles look: shown on a sample line while they're picked. */
        val LOOK = setOf(SUB_SIZE, SUB_PLACE, SUB_COLOR, SUB_BACKGROUND, SUB_EDGE, SUB_FONT)

        /** The pause menu's quick sizes, with names; Settings has them all as numbers. */
        val SIZES = intArrayOf(60, 80, 100, 125, 150)

        /** How much faster or slower than PlayerActivity.speed a held ←/→ goes. */
        fun speedFactor(v: Int) = when (v) {
            0 -> 0.6
            2 -> 1.6
            else -> 1.0
        }
    }
}
