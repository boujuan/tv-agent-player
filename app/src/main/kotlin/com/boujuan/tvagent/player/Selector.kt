package com.boujuan.tvagent.player

import android.content.Context
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/**
 * Media3's track choice with the agent's rules (Lang) in it: the first choice is already the right one, so nothing
 * is chosen twice (an override after the tracks are known would make Media3 select again). Explicit choices
 * (`select`) are overrides in the player's parameters, which Media3 applies on top of this.
 */
class Selector(context: Context) : DefaultTrackSelector(context) {

    /** Audio language (null: the file's default) and subtitle languages in order (null: off, forced ones shown). */
    data class Plan(val audio: String?, val subs: List<String>?)

    /** Read on the playback thread. Set before a file is prepared; replan() for the file that's playing. */
    @Volatile var plan = Plan(null, null)

    fun replan(plan: Plan) {
        this.plan = plan
        invalidate(null)                        // chosen again, with the parameters as they are
    }

    override fun selectAudioTrack(
        info: MappedTrackInfo, supports: Array<Array<IntArray>>, mixed: IntArray, params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        plan.audio?.let { want -> pick(info, supports, C.TRACK_TYPE_AUDIO) { Lang.pickAudio(it, want) }?.let { return it } }
        return super.selectAudioTrack(info, supports, mixed, params)
    }

    override fun selectTextTrack(
        info: MappedTrackInfo, supports: Array<Array<IntArray>>, params: Parameters, selectedAudioLanguage: String?,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        // A language the file lacks shows nothing: the agent adds one from your subtitle add-ons.
        val subs = plan.subs
        return if (subs == null) pick(info, supports, C.TRACK_TYPE_TEXT) { Lang.pickForced(it, selectedAudioLanguage) }
        else pick(info, supports, C.TRACK_TYPE_TEXT) { Lang.pickText(it, subs) }
    }

    /** The track `choose` picks among the supported ones of a type, as Media3's selection. */
    private fun pick(
        info: MappedTrackInfo, supports: Array<Array<IntArray>>, type: Int, choose: (List<Format>) -> Int?,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val where = ArrayList<IntArray>()
        val formats = ArrayList<Format>()
        for (r in 0 until info.rendererCount) {
            if (info.getRendererType(r) != type) continue
            val groups = info.getTrackGroups(r)
            for (g in 0 until groups.length) {
                for (t in 0 until groups[g].length) {
                    if (RendererCapabilities.getFormatSupport(supports[r][g][t]) == C.FORMAT_HANDLED) {
                        where += intArrayOf(r, g, t)
                        formats += groups[g].getFormat(t)
                    }
                }
            }
        }
        val (r, g, t) = where[choose(formats) ?: return null]
        return Pair.create(ExoTrackSelection.Definition(info.getTrackGroups(r)[g], t), r)
    }
}
