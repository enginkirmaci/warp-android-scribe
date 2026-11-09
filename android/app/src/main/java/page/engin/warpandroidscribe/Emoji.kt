package com.engin.warpandroidscribe

import android.content.Context

/** One labelled run of the emoji panel; the panel's order is this order. */
data class EmojiGroup(val label: String, val emoji: List<String>)

/** One entry of the most-used row: a tapped emoji and how it got there. */
data class EmojiUse(val emoji: String, val count: Int, val lastUsed: Long)

/**
 * The most-used row's cap. The store keeps no more than this, so the row is
 * one glance and a tap, never a second catalogue.
 */
const val MOST_USED_EMOJI_LIMIT = 20

private const val GROUP_PREFIX = "group: "

/**
 * Read the panel's grid from `res/raw/emoji.txt`: `#` comments and a
 * `group: NAME` line per section, then one fully-qualified emoji per line. The
 * file is generated from Unicode's `emoji-test.txt` 16.0 — the set the system
 * emoji font draws — and leaves out skin-tone variants, which the panel has no
 * tone picker for.
 */
fun loadEmojiGroups(context: Context): List<EmojiGroup> {
    val groups = mutableListOf<EmojiGroup>()
    var label: String? = null
    var current = mutableListOf<String>()

    fun flush() {
        val name = label ?: return
        if (current.isNotEmpty()) groups += EmojiGroup(name, current.toList())
        current = mutableListOf()
    }

    context.resources.openRawResource(R.raw.emoji)
        .bufferedReader(Charsets.UTF_8)
        .useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("# ") -> Unit
                    line.startsWith(GROUP_PREFIX) -> {
                        flush()
                        label = line.removePrefix(GROUP_PREFIX).trim()
                    }
                    else -> current += line
                }
            }
        }
    flush()
    return groups
}
