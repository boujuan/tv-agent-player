package com.boujuan.tvagent.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** Which decoder plays what, and the names the agent sees for codecs and outputs. */
object Codecs {
    /**
     * Media3's decoder list, with the TV's Dolby Vision profile 5 decoder first among the DV ones. No decoder here
     * lists profile 7 (UHD Blu-ray), yet `c2.mtk.dvhe.stn` plays a P7 FEL disc in Dolby Vision (tv-agent's player
     * tests, 2026-10-23): with it first, a P7 file goes there on purpose rather than by the list's order. Files of
     * a profile some decoder does list (P5 → stn, P8 → st) still go to that one: Media3 sorts by support after this.
     */
    val selector = MediaCodecSelector { mimeType, secure, tunneling ->
        val infos = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
        if (mimeType != MimeTypes.VIDEO_DOLBY_VISION) infos
        else infos.sortedBy { if (it.name.endsWith(".stn")) 0 else 1 }
    }

    private val WARM = listOf(MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264,
        MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC)

    /**
     * Fills Media3's per-process decoder lists on a background thread while the screen comes up and the agent is
     * still finding the link: listing a type's decoders asks the media service, which takes a while the first time.
     */
    fun warmUp() {
        Thread({
            for (mime in WARM) {
                try {
                    MediaCodecUtil.getDecoderInfos(mime, false, false)
                } catch (_: MediaCodecUtil.DecoderQueryException) {
                }
            }
        }, "codec-warmup").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun hdr(f: Format): String = when {
        f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "dv"
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "hdr10"     // HDR10+ is per frame: not in the header
        f.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "hlg"
        else -> "sdr"
    }

    /** "dvhe.08.06" → 8. */
    fun dvProfile(f: Format): Int? =
        if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) f.codecs?.split('.')?.getOrNull(1)?.toIntOrNull() else null

    fun codec(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
        MimeTypes.AUDIO_AC3 -> "ac3"
        MimeTypes.AUDIO_TRUEHD -> "truehd"
        MimeTypes.AUDIO_DTS -> "dts"
        MimeTypes.AUDIO_DTS_HD -> "dts-hd"
        MimeTypes.AUDIO_DTS_EXPRESS -> "dts-express"
        MimeTypes.AUDIO_DTS_X -> "dts-x"
        MimeTypes.AUDIO_AAC -> "aac"
        MimeTypes.AUDIO_OPUS -> "opus"
        MimeTypes.AUDIO_FLAC -> "flac"
        MimeTypes.AUDIO_MPEG -> "mp3"
        MimeTypes.APPLICATION_SUBRIP -> "srt"
        MimeTypes.TEXT_SSA -> "ass"
        MimeTypes.TEXT_VTT -> "vtt"
        MimeTypes.APPLICATION_PGS -> "pgs"
        MimeTypes.APPLICATION_VOBSUB -> "vobsub"
        MimeTypes.APPLICATION_TTML -> "ttml"
        else -> mime?.substringAfter('/')
    }

    fun encoding(encoding: Int): String = when (encoding) {
        C.ENCODING_E_AC3_JOC -> "eac3-joc"
        C.ENCODING_E_AC3 -> "eac3"
        C.ENCODING_AC3 -> "ac3"
        C.ENCODING_DOLBY_TRUEHD -> "truehd"
        C.ENCODING_DTS -> "dts"
        C.ENCODING_DTS_HD -> "dts-hd"
        C.ENCODING_DTS_UHD_P2 -> "dts-uhd"
        C.ENCODING_AC4 -> "ac4"
        C.ENCODING_PCM_16BIT -> "pcm16"
        C.ENCODING_PCM_FLOAT -> "pcm-float"
        else -> "encoding-$encoding"
    }

    fun isPcm(encoding: Int) = encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT ||
        encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT || encoding == C.ENCODING_PCM_8BIT
}
