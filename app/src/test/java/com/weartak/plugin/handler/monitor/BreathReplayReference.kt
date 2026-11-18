package com.weartak.plugin.handler.monitor

import kotlin.math.roundToInt

internal class BreathReplayReference private constructor(
    private val start: Long,
    private val end: Long,
    private val breaths: List<Long>,
) {
    fun countEndingAt(at: Long): Int? {
        val from = at - FLANK_WINDOW_NANOS
        if (from < start || at > end) return null
        return breaths.count { it > from && it <= at }
    }

    companion object {
        fun read(lines: Sequence<String>, source: String): BreathReplayReference {
            val rows = lines.iterator()
            require(rows.hasNext() && rows.next().trim() == "timestamp_ns,event") {
                "$source: expected timestamp_ns,event"
            }
            val events = rows.asSequence().mapIndexed { index, row ->
                val fields = row.split(',')
                val time = fields.first().trim().toLongOrNull()
                require(fields.size == 2 && time != null && time >= 0) { "$source:${index + 2}: invalid event" }
                time to fields[1].trim()
            }.toList()
            require(events.size >= 2 && events.first().second == "start" && events.last().second == "end" &&
                events.zipWithNext().all { (a, b) -> a.first < b.first } &&
                events.subList(1, events.lastIndex).all { it.second == "breath" }
            ) { "$source: expected strictly ordered start, breath(s), end" }
            return BreathReplayReference(
                events.first().first, events.last().first, events.subList(1, events.lastIndex).map { it.first },
            )
        }
    }
}

internal class BreathCountComparison {
    private var referenceWindows = 0
    private var candidates = 0
    private var undercount = 0L
    private var overcount = 0L

    fun add(referenceCount: Int, rhythm: ReplayRhythm?) {
        referenceWindows++
        if (rhythm == null) return
        candidates++
        val estimated = (rhythm.hz * (FLANK_WINDOW_NANOS / 1e9)).roundToInt()
        undercount += maxOf(0, referenceCount - estimated)
        overcount += maxOf(0, estimated - referenceCount)
    }

    override fun toString(): String =
        "candidates=$candidates/$referenceWindows withheld=${referenceWindows - candidates} " +
            if (candidates == 0) "countError=n/a" else
                "undercount=$undercount overcount=$overcount meanAbsoluteCountError=" +
                    (undercount + overcount).toDouble() / candidates
}
