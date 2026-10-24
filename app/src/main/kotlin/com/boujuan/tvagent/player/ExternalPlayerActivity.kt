package com.boujuan.tvagent.player

/**
 * TV Agent Player as another app's external player. Stremio's "External" player fires an implicit VIEW intent for
 * an http(s) link of any video type with startActivityForResult; Android's "Open with" lists the apps that take it,
 * so this one does. It plays the link by itself, without tv-agent: the agent plays through PlayerActivity, and
 * couldn't tell what this is (it would save the wrong title's progress). Back, leaving or the end of the file gives
 * the caller the position and length.
 *
 * Its own entry, because PlayerActivity is for shell only (it needs DUMP) and singleTask, which a caller waiting for
 * a result can't start.
 */
class ExternalPlayerActivity : PlayerActivity() {
    override val standalone = true
}
