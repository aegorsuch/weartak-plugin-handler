package com.weartak.plugin.handler.monitor

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class HarnessReplayTest {
    @Test
    fun replayLabeledRecordings() {
        val directory = System.getenv("HANDLER_REPLAY_DIR")
        assumeTrue("Set HANDLER_REPLAY_DIR to replay field recordings", !directory.isNullOrBlank())
        val root = File(requireNotNull(directory))
        require(root.isDirectory) { "Recording directory does not exist: $root" }
        val recordings = root.listFiles { file ->
            file.isFile && file.extension == "csv" && !file.name.endsWith(".breaths.csv")
        }
            .orEmpty().sortedBy { it.name }
        require(recordings.isNotEmpty()) { "No CSV recordings in $root" }
        val groups = mutableMapOf<Pair<String, String>, MutableList<Result>>()
        for (file in recordings) {
            val name = RECORDING_NAME.matchEntire(file.nameWithoutExtension)
                ?: error("Expected <dog|horse>_<label>_<id>.csv: ${file.name}")
            val (species, label) = name.destructured
            val flankMounted = file.nameWithoutExtension.endsWith("-flank-z")
            val result = file.useLines { replay(it, file.name, flankMounted) }
            groups.getOrPut(species to label) { mutableListOf() }.add(result)
            println("${file.name}: durationMs=${result.durationMs} scoredWindows=${result.scoredWindows} " +
                "rawRmsMps2=${span(result.windows.map { it.rms })} liveMean=${result.meanExertion} " +
                "sustainedMotionFraction=${span(result.windows.mapNotNull { it.sustainedMotion })} " +
                "warningStarts=${result.alertStarts} rejectedSamples=${result.rejectedSamples} " +
                "gaps=${result.gaps} maxGapMs=${result.maxGapMs}")
            if (flankMounted) {
                val candidates = result.windows.mapNotNull { it.flank }
                val gated = result.windows.count { it.flankWithGyro != null }
                val withoutGyro = result.windows.count { it.flank != null && it.gyro == null }
                println("${file.name}: flankRhythmWindows=${candidates.size}/${result.scoredWindows} " +
                    "flankCyclesPerMinute=${span(candidates.map { it.hz * 60 })} " +
                    "gyroGatedCandidates=$gated missingGyro=$withoutGyro " +
                    "rotationVetoed=${candidates.size - gated - withoutGyro}")
                val sidecar = File(root, "${file.nameWithoutExtension}.breaths.csv")
                if (sidecar.isFile) {
                    val reference = sidecar.useLines { BreathReplayReference.read(it, sidecar.name) }
                    val baseline = BreathCountComparison()
                    val pairedBaseline = BreathCountComparison()
                    val filtered = BreathCountComparison()
                    val stable = BreathCountComparison()
                    val adaptive = BreathCountComparison()
                    val burstGated = BreathCountComparison()
                    for (window in result.windows) {
                        val count = reference.countEndingAt(window.endNanos) ?: continue
                        baseline.add(count, window.flank)
                        if (window.gyro != null) {
                            pairedBaseline.add(count, window.flank)
                            filtered.add(count, window.flankWithGyro)
                            stable.add(count, window.flankWithGyroAndStability)
                            adaptive.add(count, window.flankWithAdaptiveRecovery)
                            burstGated.add(count, window.flankWithBurstVeto)
                        }
                    }
                    println("${file.name}: reference all=$baseline pairedBaseline=$pairedBaseline " +
                        "pairedGated=$filtered pairedGatedStable=$stable pairedAdaptiveRecovery=$adaptive " +
                        "pairedBurstVeto=$burstGated")
                }
            }
        }
        for ((key, clips) in groups) {
            val (species, label) = key
            val eligible = clips.filter { it.durationMs >= 40_000 && it.scoredWindows >= 3 }
            val warningRate = if (label == "trot-run" || eligible.isEmpty()) null else
                eligible.sumOf { it.alertStarts } * 60_000.0 / eligible.sumOf { it.durationMs }
            val windows = clips.flatMap { it.windows }
            println("$species $label: clips=${clips.size} windows=${windows.size} " +
                "rawRmsMps2=${span(windows.map { it.rms })} " +
                "warningEligibleClips=${eligible.size} falseWarningStartsPerMinute=${warningRate ?: "n/a"}")
            val gyro = windows.mapNotNull { it.gyro }
            val rhythm = gyro.mapNotNull { it.repetitionHz }
            println("$species $label gyro: windows=${gyro.size}/${windows.size} rhythmic=${rhythm.size} " +
                "rotationRmsRadS=${span(gyro.map { it.rmsRadS })} repetitionHz=${span(rhythm)} " +
                "peakCorrelation=${span(gyro.mapNotNull { it.correlation })}")
            for ((scale, score) in SCALES) {
                val scores = windows.map(score)
                val pinned = if (scores.isEmpty()) null else scores.count { it == 1.0 } * 100.0 / scores.size
                println("$species $label $scale: scoreFraction=${span(scores)} pinnedAt100Percent=${pinned ?: "n/a"}")
            }
        }
        for (species in groups.keys.map { it.first }.distinct()) {
            val walk = groups[species to "walk"].orEmpty().flatMap { it.windows }
            val trot = groups[species to "trot-run"].orEmpty().flatMap { it.windows }
            println("$species walk/trot overlap rawRmsMps2=${overlap(walk.map { it.rms }, trot.map { it.rms })}")
            println("$species walk/trot overlap gyroRotationRms=${overlap(
                walk.mapNotNull { it.gyro?.rmsRadS }, trot.mapNotNull { it.gyro?.rmsRadS },
            )} gyroRepetitionHz=${overlap(
                walk.mapNotNull { it.gyro?.repetitionHz }, trot.mapNotNull { it.gyro?.repetitionHz },
            )}")
            for ((scale, score) in SCALES) {
                println("$species walk/trot overlap $scale=${overlap(walk.map(score), trot.map(score))}")
            }
        }
    }

    @Test
    fun sustainedMotionReplayBalancesBriefActivityAndRecoveryWithoutCarryingStaleScores() {
        fun run(activeSeconds: Int, dropout: Boolean = false): Result {
            val rows = sequence {
                yield(HEADER)
                for (i in 0..3_750) {
                    if (dropout && i in 2_501..2_524) continue
                    val time = i / 25.0
                    val x = if (time >= 45 && time < 45 + activeSeconds) 5 * sin(2 * PI * 2 * time) else 0.0
                    yield("${i * 40_000_000L},$x,0,9.81,true,40")
                }
            }
            return replay(rows, "synthetic sustained motion")
        }
        val brief = run(5)
        val sustained = run(50)
        val interrupted = run(50, dropout = true)
        val briefPeak = brief.windows.maxOf { requireNotNull(it.sustainedMotion) }
        val sustainedPeak = sustained.windows.maxOf { requireNotNull(it.sustainedMotion) }
        val afterRest = requireNotNull(sustained.windows.last().sustainedMotion)
        assertTrue(sustainedPeak > briefPeak + 0.15)
        assertTrue(afterRest < sustainedPeak)
        val afterGap = interrupted.windows.first { it.endNanos > 101_000_000_000 }
        assertTrue(afterGap.endNanos >= 131_000_000_000)
        assertEquals(0.0, requireNotNull(afterGap.sustainedMotion), 0.0001)
        println("sustained motion simulation: briefPeak=$briefPeak sustainedPeak=$sustainedPeak afterRest=$afterRest")
    }

    @Test
    fun fixedFlankSimulationComparesAccuracyAndCoverage() {
        data class Scenario(
            val name: String,
            val breaths: Int,
            val phaseCycles: (Double) -> Double,
            val amplitude: (Double) -> Double = { 0.1 },
            val rotation: Double = 0.01,
            val noise: Double = 0.01,
            val rotationBurst: Boolean = false,
        )
        val scenarios = listOf(
            Scenario("steady", 15, { t -> t * 0.5 }),
            Scenario("noisy", 18, { t -> t * 0.6 }, noise = 0.035),
            Scenario("slow-shallow", 9, { t -> t * 0.3 }, { 0.04 }),
            Scenario("recovery", 18, { t -> t * (0.9 - t * 0.01) }),
            Scenario("breathing-with-rocking", 15, { t -> t * 0.5 }, rotation = 0.4),
            Scenario("still", 0, { 0.0 }, { 0.0 }),
            Scenario("rocking-only", 0, { t -> t * 0.5 }, rotation = 0.4),
            Scenario("intermittent-sway", 0, { t -> t * 0.5 }, { t -> if (t >= 15) 0.1 else 0.0 }),
            Scenario("steady-translation", 0, { t -> t * 0.5 }),
            Scenario("sway-with-rotation-burst", 0, { t -> t * 0.5 }, rotationBurst = true),
            Scenario("breathing-with-rotation-burst", 15, { t -> t * 0.5 }, rotationBurst = true),
        )
        val names = listOf("flank", "gyro", "gyro+stability", "gyro+adaptive-recovery", "gyro+burst-veto")
        val breathing = names.associateWith { BreathCountComparison() }
        val artifacts = names.associateWith { BreathCountComparison() }
        val estimates = scenarios.associate { scenario ->
            val random = Random(413)
            val rows = sequence {
                yield(GYRO_HEADER)
                for (i in 0..750) {
                    val time = i / 25.0
                    val wave = sin(2 * PI * scenario.phaseCycles(time))
                    val normal = scenario.amplitude(time) * wave + random.nextDouble(-1.0, 1.0) * scenario.noise
                    val burst = if (scenario.rotationBurst && time >= 14.5 && time < 15.5) 0.50 else 0.0
                    yield("${i * 40_000_000L},0,9.81,$normal,true,40,${scenario.rotation * wave + burst},0,0")
                }
            }
            val window = replay(rows, scenario.name, flankMounted = true).windows.single()
            val variants = listOf(
                window.flank, window.flankWithGyro, window.flankWithGyroAndStability, window.flankWithAdaptiveRecovery,
                window.flankWithBurstVeto,
            )
            val group = if (scenario.breaths > 0) breathing else artifacts
            names.zip(variants).forEach { (name, rhythm) -> group.getValue(name).add(scenario.breaths, rhythm) }
            println("flank simulation ${scenario.name}: ref=${scenario.breaths} " +
                names.zip(variants).joinToString(" ") { (name, rhythm) -> "$name=${rhythm?.let { it.hz * 60 }}" })
            scenario.name to variants
        }
        for (name in names) {
            println("flank simulation $name: breathing ${breathing.getValue(name)}; artifacts ${artifacts.getValue(name)}")
        }
        assertTrue(estimates.getValue("intermittent-sway")[1] != null)
        assertNull(estimates.getValue("intermittent-sway")[2])
        assertTrue(estimates.getValue("steady")[2] != null)
        assertTrue(estimates.getValue("steady-translation")[2] != null)
        assertNull(estimates.getValue("intermittent-sway")[3])
        assertNull(estimates.getValue("breathing-with-rocking")[3])
        assertEquals(36.0, requireNotNull(estimates.getValue("recovery")[3]).hz * 60, 2.0)
        for (name in listOf("sway-with-rotation-burst", "breathing-with-rotation-burst")) {
            assertTrue(estimates.getValue(name)[3] != null)
            assertNull(estimates.getValue(name)[4])
        }
        assertTrue(estimates.getValue("recovery")[4] != null)
    }

    @Test
    fun flankGyroVetoAndReferenceReportCoverageAndCountError() {
        fun run(rotation: Double?): WindowScore {
            val rows = sequence {
                yield(GYRO_HEADER)
                for (i in 0..750) {
                    val wave = sin(2 * PI * i / 50)
                    val axes = rotation?.let { "${it * wave},0,0" } ?: ",,"
                    yield("${i * 40_000_000L},0,9.81,${0.1 * wave},true,40,$axes")
                }
            }
            return replay(rows, "synthetic synchronized flank", flankMounted = true).windows.single()
        }
        val quiet = run(0.01)
        val rocking = run(0.4)
        val missing = run(null)
        assertTrue(quiet.flankWithGyro != null)
        assertTrue(rocking.flank != null)
        assertNull(rocking.flankWithGyro)
        assertTrue(missing.flank != null)
        assertNull(missing.flankWithGyro)

        val events = sequence {
            yield("timestamp_ns,event")
            yield("0,start")
            for (i in 0..14) yield("${1_000_000_000L + i * 2_000_000_000L},breath")
            yield("31000000000,end")
        }
        val reference = BreathReplayReference.read(events, "synthetic labels")
        val count = requireNotNull(reference.countEndingAt(quiet.endNanos))
        assertEquals(15, count)
        assertNull(reference.countEndingAt(32_000_000_000L))
        val counts = BreathCountComparison()
        counts.add(count, quiet.flankWithGyro)
        counts.add(0, rocking.flankWithGyro)
        counts.add(0, rocking.flank)
        assertEquals("candidates=2/3 withheld=1 undercount=0 overcount=15 meanAbsoluteCountError=7.5", counts.toString())
    }

    @Test
    fun flankReplayFindsQuietNormalRhythmButRejectsTangentialAndGaitMotion() {
        fun run(xAmplitude: Double, zAmplitude: Double, optedIn: Boolean = true): WindowScore {
            val rows = sequence {
                yield(HEADER)
                for (i in 0..750) {
                    val wave = sin(2 * PI * i / 50)
                    yield("${i * 40_000_000L},${xAmplitude * wave},9.81,${zAmplitude * wave},true,40")
                }
            }
            return replay(rows, "synthetic flank", optedIn).windows.single()
        }
        assertEquals(30.0, requireNotNull(run(0.01, 0.10).flank).hz * 60, 1.0)
        assertNull(run(0.10, 0.01).flank)
        assertNull(run(0.0, 0.0).flank)
        assertNull(run(0.01, 1.0).flank)
        assertNull(run(0.01, 0.10, optedIn = false).flank)
    }

    @Test
    fun gyroReplayDistinguishesRhythmicRotationFromAnIsolatedBurst() {
        fun run(rhythmic: Boolean, dropout: Boolean = false): WindowScore {
            val rows = sequence {
                yield(GYRO_HEADER)
                for (i in 0..750) {
                    val gyro = when {
                        dropout && i == 500 -> ",,"
                        rhythmic -> "${2 + sin(2 * PI * 2 * i / 25)},0,0"
                        i in 445..455 -> "8,0,0"
                        else -> "0,0,0"
                    }
                    yield("${i * 40_000_000L},0,0,9.81,true,40,$gyro")
                }
            }
            return replay(rows, "synthetic rotation").windows.single()
        }
        assertEquals(2.0, requireNotNull(run(true).gyro?.repetitionHz), 0.2)
        val burst = requireNotNull(run(false).gyro)
        assertTrue(burst.rmsRadS > 0.5)
        assertNull(burst.repetitionHz)
        val missing = run(true, dropout = true)
        assertNull(missing.gyro)
        assertEquals(0.0, missing.live, 0.0001)
    }

    @Test
    fun alternativeScalesRetainHeadroomButCannotSeparateIdenticalRms() {
        val walk = WindowScore(2.6, 1.0)
        val trot = WindowScore(5.2, 1.0)
        assertEquals("1.0..1.0 (walk=1/1 trot=1/1)", overlap(listOf(walk.live), listOf(trot.live)))
        for (scale in SCALES.drop(1).map { it.second }) {
            assertEquals("none", overlap(listOf(scale(walk)), listOf(scale(trot))))
            assertEquals("${scale(walk)}..${scale(walk)} (walk=1/1 trot=1/1)",
                overlap(listOf(scale(walk)), listOf(scale(walk))))
        }
        assertEquals("n/a", overlap(emptyList(), listOf(1.0)))
    }

    @Test
    fun replayStationaryControlProducesValidZeroAndNoWarning() {
        val rows = sequence {
            yield(HEADER)
            for (index in 0..875) yield("${index * 40_000_000L},0,0,9.81,true,40")
        }
        val result = replay(rows, "synthetic rest")
        assertEquals(2, result.scoredWindows)
        assertEquals(0.0, result.meanExertion!!, 0.0001)
        assertEquals(0, result.alertStarts)
    }

    @Test
    fun replayShortRecordingReportsNoScoredWindows() {
        val rows = sequence {
            yield(HEADER)
            for (index in 0..500) yield("${index * 40_000_000L},0,0,9.81,true,40")
        }
        val result = replay(rows, "short recording")
        assertEquals(20_000L, result.durationMs)
        assertEquals(0, result.scoredWindows)
        assertNull(result.meanExertion)
    }

    @Test
    fun qualityFaultClearsScoreAndRestartsWarmup() {
        for ((x, z, reliable) in listOf(
            Triple(0f, 0f, true), Triple(Float.NaN, 9.81f, true),
            Triple(40f, 9.81f, true), Triple(0f, 9.81f, false),
        )) {
            val stream = HarnessMotionStream(40f)
            repeat(751) { index ->
                val now = index * 40_000_000L
                stream.onSample(now, now, 0f, 0f, 9.81f, true)
            }
            assertEquals(0.0, stream.estimate!!.exertionFraction, 0.0001)
            stream.onSample(30_040_000_000L, 30_040_000_000L, x, 0f, z, reliable)
            assertNull(stream.estimate)
            stream.onSample(30_080_000_000L, 30_080_000_000L, 0f, 0f, 9.81f, true)
            assertNull(stream.estimate)
        }
    }

    @Test
    fun lateDeliveryClearsScoreEvenWhenSensorTimestampsAreContinuous() {
        val stream = HarnessMotionStream(40f)
        repeat(751) { index ->
            val now = index * 40_000_000L
            stream.onSample(now, now, 0f, 0f, 9.81f, true)
        }
        assertEquals(0.0, stream.estimate!!.exertionFraction, 0.0001)
        stream.onSample(30_040_000_000L, 30_291_000_000L, 0f, 0f, 9.81f, true)
        assertNull(stream.estimate)
        assertEquals(1L, stream.rejectedSamples)
        assertEquals(0L, stream.gapCount)
    }

    @Test
    fun replayGapDropsOldScoreAndDoesNotInventRecovery() {
        val rows = sequence {
            yield(HEADER)
            for (index in 0..875) {
                if (index in 751..775) continue
                yield("${index * 40_000_000L},1,0,9.81,true,40")
            }
        }
        val result = replay(rows, "synthetic gap")
        assertEquals(1, result.scoredWindows)
        assertEquals(1L, result.gaps)
        assertEquals(1040L, result.maxGapMs)
    }

    private fun replay(lines: Sequence<String>, source: String, flankMounted: Boolean = false): Result {
        val rows = lines.iterator()
        val header = if (rows.hasNext()) rows.next().trim() else ""
        require(header == HEADER || header == GYRO_HEADER) { "$source: expected $HEADER or $GYRO_HEADER" }
        val gyro = if (header == GYRO_HEADER) GyroReplayWindow() else null
        val flank = if (flankMounted) FlankReplayWindow() else null
        val sustained = SustainedMotionReplay()
        var stream: HarnessMotionStream? = null
        var range = 0f
        var start: Long? = null
        var end = 0L
        var measuredAt: Long? = null
        var alert = false
        var alertStarts = 0
        val windows = mutableListOf<WindowScore>()
        var rowNumber = 1
        while (rows.hasNext()) {
            rowNumber++
            val fields = rows.next().split(',')
            val expected = if (gyro == null) 6 else 9
            require(fields.size == expected) { "$source:$rowNumber: expected $expected fields" }
            val (timestamp, x, y, z, reliable) = fields.map(String::trim)
            val rowRange = fields[5].trim().toFloat()
            require(rowRange.isFinite() && rowRange > 0) { "$source:$rowNumber: invalid sensor range" }
            val now = timestamp.toLong()
            if (gyro != null) {
                val axes = fields.subList(6, 9).map { it.trim().toDoubleOrNull() }
                gyro.accept(now, axes.filterNotNull().takeIf { it.size == 3 })
            }
            if (start == null) {
                start = now
                range = rowRange
            }
            require(rowRange == range) { "$source:$rowNumber: sensor range changed" }
            end = maxOf(end, now)
            val pipeline = stream ?: HarnessMotionStream(range).also { stream = it }
            val ax = x.toFloat()
            val ay = y.toFloat()
            val az = z.toFloat()
            pipeline.onSample(now, end, ax, ay, az, reliable.toBooleanStrict())
            flank?.accept(now, ax, ay, az, pipeline.lastGoodSampleNanos == now)
            val estimate = pipeline.estimate
            if (estimate == null) {
                alert = false
                sustained.reset()
            } else if (measuredAt != pipeline.measuredAtNanos) {
                measuredAt = pipeline.measuredAtNanos
                if (estimate.highMovementAlert && !alert) alertStarts++
                alert = estimate.highMovementAlert
                windows += WindowScore(
                    estimate.movementRmsMetersPerSecondSquared, estimate.exertionFraction, gyro?.features(now),
                    flank?.candidate(now, estimate.exertionLevel), now,
                    sustained.observe(now, estimate.movementRmsMetersPerSecondSquared),
                )
            }
        }
        val pipeline = requireNotNull(stream) { "$source: no samples" }
        val durationMs = (end - requireNotNull(start)) / 1_000_000
        return Result(
            durationMs, windows, alertStarts,
            pipeline.rejectedSamples, pipeline.gapCount, pipeline.maxGapNanos / 1_000_000,
        )
    }

    private fun span(values: List<Double>): String =
        if (values.isEmpty()) "n/a" else "${values.min()}..${values.max()}"

    private fun overlap(walk: List<Double>, trot: List<Double>): String {
        if (walk.isEmpty() || trot.isEmpty()) return "n/a"
        val low = maxOf(walk.min(), trot.min())
        val high = minOf(walk.max(), trot.max())
        if (low > high) return "none"
        return "$low..$high (walk=${walk.count { it in low..high }}/${walk.size} " +
            "trot=${trot.count { it in low..high }}/${trot.size})"
    }

    private data class WindowScore(
        val rms: Double,
        val live: Double,
        val gyro: GyroReplayWindow.Features? = null,
        val flankRhythms: FlankReplayWindow.Candidates? = null,
        val endNanos: Long = 0,
        val sustainedMotion: Double? = null,
    ) {
        val flank get() = flankRhythms?.fullWindow
        private val rotationAllowed get() = gyro != null && gyro.rmsRadS <= 0.12
        val flankWithGyro get() = flank.takeIf { rotationAllowed }
        val flankWithGyroAndStability get() = flankRhythms?.stableHalves.takeIf { rotationAllowed }
        val flankWithAdaptiveRecovery get() =
            (flankRhythms?.stableHalves ?: flankRhythms?.recoveryTrend).takeIf { rotationAllowed }
        val flankWithBurstVeto get() =
            flankWithAdaptiveRecovery.takeIf { gyro != null && gyro.peakSecondRmsRadS <= 0.25 }
    }

    private data class Result(
        val durationMs: Long,
        val windows: List<WindowScore>,
        val alertStarts: Int,
        val rejectedSamples: Long,
        val gaps: Long,
        val maxGapMs: Long,
    ) {
        val scoredWindows get() = windows.size
        val meanExertion get() = windows.takeIf { it.isNotEmpty() }?.map { it.live }?.average()
    }

    private companion object {
        const val HEADER = "timestamp_ns,x_mps2,y_mps2,z_mps2,reliable,range_mps2"
        const val GYRO_HEADER = "$HEADER,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s"
        val RECORDING_NAME = Regex("(dog|horse)_(rest|walk|trot-run|loose-strap|off-harness)_(.+)")
        val SCALES: List<Pair<String, (WindowScore) -> Double>> = listOf(
            "live" to { it.live },
            "linear6" to { (it.rms / 6.0).coerceAtMost(1.0) },
            "log12" to { boundedMotionScore(it.rms) },
        )
    }
}
