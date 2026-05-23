package com.trustableai.koru.runtime.deterministic

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.Corner
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.DriverState
import com.trustableai.koru.model.EdgeReasoningWindow
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.SkillLevel
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track
import com.trustableai.koru.runtime.TrackExpertiseCatalog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class CornerDetection(
    val phase: CornerPhase,
    val corner: Corner? = null,
)

class CornerPhaseDetector(track: Track?) {
    private var track: Track? = track
    private var cosLat = track?.centerLat?.let { cos(it * Math.PI / 180.0) } ?: cos(38.16 * Math.PI / 180.0)

    fun setTrack(track: Track?) {
        this.track = track
        this.cosLat = track?.centerLat?.let { cos(it * Math.PI / 180.0) } ?: this.cosLat
    }

    fun detect(frame: TelemetryFrame): CornerDetection {
        val localTrack = track
        if (localTrack != null) {
            detectFromGps(frame, localTrack)?.let { return it }
        }
        return detectFromGForces(frame)
    }

    private fun detectFromGps(frame: TelemetryFrame, track: Track): CornerDetection? {
        if (!isValidGps(frame.latitude, frame.longitude)) return null
        for (corner in track.corners) {
            val refLat = corner.entryLat ?: corner.lat
            val refLon = corner.entryLon ?: corner.lon
            val dLat = (frame.latitude - refLat) * 111320.0
            val dLon = (frame.longitude - refLon) * 111320.0 * cosLat
            val fastDistance = sqrt(dLat * dLat + dLon * dLon)
            if (fastDistance > 300.0) continue

            val distanceToEntry = haversineDistance(frame.latitude, frame.longitude, refLat, refLon)
            val distanceToApex = haversineDistance(frame.latitude, frame.longitude, corner.lat, corner.lon)
            if (distanceToEntry < 200.0 || distanceToApex < 150.0) {
                return CornerDetection(
                    phase = classifyPhase(distanceToEntry, distanceToApex),
                    corner = corner,
                )
            }
        }
        return null
    }

    private fun detectFromGForces(frame: TelemetryFrame): CornerDetection {
        val absGLat = abs(frame.gLat)
        val phase = when {
            absGLat > 0.5 && frame.brake < 10.0 && frame.throttle > 30.0 -> CornerPhase.EXIT
            absGLat > 0.5 -> CornerPhase.MID_CORNER
            frame.brake > 30.0 && absGLat < 0.3 -> CornerPhase.BRAKE_ZONE
            frame.brake > 10.0 && absGLat > 0.3 -> CornerPhase.TURN_IN
            frame.throttle > 60.0 && absGLat < 0.2 && frame.gLong > 0.1 -> CornerPhase.ACCELERATION
            else -> CornerPhase.STRAIGHT
        }
        return CornerDetection(phase = phase)
    }

    private fun classifyPhase(distanceToEntry: Double, distanceToApex: Double): CornerPhase {
        return when {
            distanceToEntry < 100.0 && distanceToApex > 80.0 -> CornerPhase.BRAKE_ZONE
            distanceToEntry < 50.0 && distanceToApex > 40.0 -> CornerPhase.TURN_IN
            distanceToApex < 30.0 -> CornerPhase.APEX
            distanceToEntry < 30.0 && distanceToApex < 80.0 -> CornerPhase.MID_CORNER
            distanceToApex in 30.0..99.999 -> CornerPhase.EXIT
            distanceToEntry < 200.0 && distanceToApex > 100.0 -> CornerPhase.BRAKE_ZONE
            else -> CornerPhase.STRAIGHT
        }
    }
}

data class FeedforwardCandidate(
    val corner: Corner,
    val distanceMeters: Double,
    val lookaheadMeters: Double,
    val targetLeadSeconds: Double,
)

interface AnticipationTrigger {
    fun evaluate(frame: TelemetryFrame, track: Track?): FeedforwardCandidate?
}

object FeedforwardTimingPolicy {
    const val DEFAULT_LEAD_SECONDS = 4.0
    const val MIN_LOOKAHEAD_METERS = 120.0
    const val MAX_LOOKAHEAD_METERS = 320.0

    fun targetLeadSeconds(track: Track?, corner: Corner): Double {
        val isSonoma = track?.name == "Sonoma Raceway"
        return when {
            isSonoma && corner.id in setOf(910, 11) -> 5.0
            else -> DEFAULT_LEAD_SECONDS
        }
    }

    fun lookaheadMeters(speedMph: Double, track: Track?, corner: Corner): Double {
        val speedMetersPerSecond = max(speedMph, 0.0) * 0.44704
        return (speedMetersPerSecond * targetLeadSeconds(track, corner))
            .coerceIn(MIN_LOOKAHEAD_METERS, MAX_LOOKAHEAD_METERS)
    }
}

class SpatialAnticipationTrigger : AnticipationTrigger {
    private val previousDistances = mutableMapOf<Int, Double>()
    private var previousLat: Double? = null
    private var previousLon: Double? = null

    override fun evaluate(frame: TelemetryFrame, track: Track?): FeedforwardCandidate? {
        val localTrack = track ?: return null
        if (!isValidGps(frame.latitude, frame.longitude)) return null

        val movementHeading = movementHeading(frame)
        var bestCandidate: FeedforwardCandidate? = null

        for (corner in localTrack.corners) {
            val refLat = corner.entryLat ?: corner.lat
            val refLon = corner.entryLon ?: corner.lon
            val distance = haversineDistance(frame.latitude, frame.longitude, refLat, refLon)
            val lookahead = FeedforwardTimingPolicy.lookaheadMeters(frame.speedMph, localTrack, corner)
            val previousDistance = previousDistances[corner.id]
            previousDistances[corner.id] = distance

            if (distance > lookahead || distance < MIN_DELIVERY_DISTANCE_METERS) continue
            val progressApproach = previousDistance == null || distance < previousDistance - APPROACH_EPSILON_METERS
            val headingApproach = movementHeading?.let { heading ->
                val bearingToCorner = bearingDegrees(frame.latitude, frame.longitude, refLat, refLon)
                angularDeltaDegrees(heading, bearingToCorner) <= MAX_APPROACH_HEADING_DELTA_DEGREES
            } ?: true

            if (!progressApproach && !headingApproach) continue

            val candidate = FeedforwardCandidate(
                corner = corner,
                distanceMeters = distance,
                lookaheadMeters = lookahead,
                targetLeadSeconds = FeedforwardTimingPolicy.targetLeadSeconds(localTrack, corner),
            )
            if (bestCandidate == null || candidate.distanceMeters < bestCandidate.distanceMeters) {
                bestCandidate = candidate
            }
        }

        previousLat = frame.latitude
        previousLon = frame.longitude
        return bestCandidate
    }

    private fun movementHeading(frame: TelemetryFrame): Double? {
        val startLat = previousLat
        val startLon = previousLon
        if (startLat == null || startLon == null) return null
        if (!isValidGps(startLat, startLon)) return null
        val movementMeters = haversineDistance(startLat, startLon, frame.latitude, frame.longitude)
        if (movementMeters < 1.0) return null
        return bearingDegrees(startLat, startLon, frame.latitude, frame.longitude)
    }

    companion object {
        private const val MIN_DELIVERY_DISTANCE_METERS = 20.0
        private const val APPROACH_EPSILON_METERS = 1.5
        private const val MAX_APPROACH_HEADING_DELTA_DEGREES = 95.0
    }
}

class TimingGate(
    private var cooldownMs: Long = 1500L,
    private var deliveryMs: Long = 2000L,
    blackoutPhases: Set<CornerPhase> = setOf(CornerPhase.MID_CORNER, CornerPhase.APEX),
) {
    private val blackoutPhases = blackoutPhases.toMutableSet()
    private var state = State.OPEN
    private var lastDeliveryAtMs = 0L

    enum class State {
        OPEN,
        DELIVERING,
        COOLDOWN,
        BLACKOUT,
    }

    fun updateConfig(cooldownMs: Long, deliveryMs: Long, blackoutPhases: Set<CornerPhase>) {
        this.cooldownMs = cooldownMs
        this.deliveryMs = deliveryMs
        this.blackoutPhases.clear()
        this.blackoutPhases.addAll(blackoutPhases)
    }

    fun update(cornerPhase: CornerPhase, nowMs: Long) {
        if (cornerPhase in blackoutPhases && state != State.DELIVERING) {
            state = State.BLACKOUT
            return
        }
        when (state) {
            State.BLACKOUT -> state = State.OPEN
            State.DELIVERING -> {
                val elapsed = nowMs - lastDeliveryAtMs
                state = when {
                    elapsed >= deliveryMs + cooldownMs -> State.OPEN
                    elapsed >= deliveryMs -> State.COOLDOWN
                    else -> State.DELIVERING
                }
            }
            State.COOLDOWN -> if (nowMs - lastDeliveryAtMs >= deliveryMs + cooldownMs) state = State.OPEN
            State.OPEN -> Unit
        }
    }

    fun canDeliver(priority: Int): Boolean = priority == 0 || state == State.OPEN

    fun startDelivery(nowMs: Long) {
        lastDeliveryAtMs = nowMs
        state = State.DELIVERING
    }
}

class CoachingQueue {
    private val queue = mutableListOf<QueuedDecision>()

    fun enqueue(decision: QueuedDecision, nowMs: Long) {
        expireStale(nowMs)
        if (queue.size >= MAX_QUEUE_SIZE) {
            val worst = queue.maxWithOrNull(compareBy<QueuedDecision>({ it.priority }, { -it.timestampMs })) ?: return
            if (decision.priority <= worst.priority) {
                queue.remove(worst)
            } else {
                return
            }
        }
        queue.add(decision)
        queue.sortWith(compareBy<QueuedDecision>({ it.priority }, { it.timestampMs }))
    }

    fun dequeue(nowMs: Long, timingGate: TimingGate): QueuedDecision? {
        expireStale(nowMs)
        val next = queue.firstOrNull { timingGate.canDeliver(it.priority) } ?: return null
        queue.remove(next)
        return next
    }

    fun preempt(decision: QueuedDecision): QueuedDecision {
        queue.removeAll { it.priority != 0 && it.path != "feedforward" }
        return decision
    }

    fun clear() {
        queue.clear()
    }

    data class QueuedDecision(
        val action: CoachAction?,
        val path: String,
        val text: String,
        val priority: Int,
        val cornerPhase: CornerPhase,
        val timestampMs: Long,
        val confidence: Double? = null,
        val phraseId: String? = null,
        val backend: RuntimeBackend? = null,
    )

    private fun expireStale(nowMs: Long) {
        queue.removeAll { nowMs - it.timestampMs >= STALE_MS }
    }

    companion object {
        private const val MAX_QUEUE_SIZE = 5
        private const val STALE_MS = 5000L
    }
}

class DriverModel {
    private val throttleDeltas = TimedDoubleWindow(MAX_WINDOW_SAMPLES)
    private val brakeDeltas = TimedDoubleWindow(MAX_WINDOW_SAMPLES)
    private val coastingFrames = TimedBooleanWindow(MAX_WINDOW_SAMPLES)
    private var previousThrottle: Double? = null
    private var previousBrake: Double? = null
    private var currentLevel = SkillLevel.BEGINNER
    private var candidateLevel = SkillLevel.BEGINNER
    private var candidateStartTime = 0.0
    private var currentSmoothness = 1.0
    private var currentCoastingRatio = 0.0

    fun update(frame: TelemetryFrame) {
        val time = frame.timeSeconds
        val prevThrottle = previousThrottle
        val prevBrake = previousBrake
        if (prevThrottle != null && prevBrake != null) {
            throttleDeltas.add(time, abs(frame.throttle - prevThrottle))
            brakeDeltas.add(time, abs(frame.brake - prevBrake))
        }

        previousThrottle = frame.throttle
        previousBrake = frame.brake
        coastingFrames.add(time, frame.throttle < 10.0 && frame.brake < 10.0)

        val cutoff = time - WINDOW_DURATION_SECONDS
        throttleDeltas.prune(cutoff)
        brakeDeltas.prune(cutoff)
        coastingFrames.prune(cutoff)
        currentSmoothness = computeSmoothness()
        currentCoastingRatio = computeCoastingRatio()

        if (throttleDeltas.size >= MIN_SAMPLES) {
            val classified = classify()
            if (classified == candidateLevel) {
                if (time - candidateStartTime >= HYSTERESIS_SECONDS) {
                    currentLevel = candidateLevel
                }
            } else {
                candidateLevel = classified
                candidateStartTime = time
            }
        }
    }

    fun state(): DriverState {
        return DriverState(
            skillLevel = currentLevel,
            cognitiveLoad = 1.0 - currentSmoothness,
            inputSmoothness = currentSmoothness,
            coastingRatio = currentCoastingRatio,
        )
    }

    private fun classify(): SkillLevel {
        return when {
            currentSmoothness < 0.4 || currentCoastingRatio > 0.3 -> SkillLevel.BEGINNER
            currentSmoothness > 0.7 && currentCoastingRatio < 0.1 -> SkillLevel.ADVANCED
            else -> SkillLevel.INTERMEDIATE
        }
    }

    private fun computeCoastingRatio(): Double {
        return coastingFrames.trueRatio()
    }

    private fun computeSmoothness(): Double {
        val throttleVariance = min(throttleDeltas.variance() / 2500.0, 1.0)
        val brakeVariance = min(brakeDeltas.variance() / 2500.0, 1.0)
        val combined = (throttleVariance + brakeVariance) * 0.5
        return (1.0 - combined).coerceIn(0.0, 1.0)
    }

    companion object {
        private const val WINDOW_DURATION_SECONDS = 10.0
        private const val HYSTERESIS_SECONDS = 5.0
        private const val MIN_SAMPLES = 20
        private const val MAX_WINDOW_SAMPLES = 256
    }
}

private class TimedDoubleWindow(capacity: Int) {
    private val times = DoubleArray(capacity)
    private val values = DoubleArray(capacity)
    private var head = 0
    var size = 0
        private set

    fun add(time: Double, value: Double) {
        val index = (head + size) % values.size
        times[index] = time
        values[index] = value
        if (size == values.size) {
            head = (head + 1) % values.size
        } else {
            size += 1
        }
    }

    fun prune(cutoffTime: Double) {
        while (size > 0 && times[head] <= cutoffTime) {
            head = (head + 1) % values.size
            size -= 1
        }
    }

    fun variance(): Double {
        if (size < 2) return 0.0
        var sum = 0.0
        for (offset in 0 until size) {
            sum += values[(head + offset) % values.size]
        }
        val mean = sum / size
        var squaredDiffs = 0.0
        for (offset in 0 until size) {
            val diff = values[(head + offset) % values.size] - mean
            squaredDiffs += diff * diff
        }
        return squaredDiffs / size
    }
}

private class TimedBooleanWindow(capacity: Int) {
    private val times = DoubleArray(capacity)
    private val values = BooleanArray(capacity)
    private var head = 0
    private var trueCount = 0
    private var size = 0

    fun add(time: Double, value: Boolean) {
        val index = (head + size) % values.size
        if (size == values.size) {
            if (values[head]) trueCount -= 1
            head = (head + 1) % values.size
        } else {
            size += 1
        }
        times[index] = time
        values[index] = value
        if (value) trueCount += 1
    }

    fun prune(cutoffTime: Double) {
        while (size > 0 && times[head] <= cutoffTime) {
            if (values[head]) trueCount -= 1
            head = (head + 1) % values.size
            size -= 1
        }
    }

    fun trueRatio(): Double {
        if (size == 0) return 0.0
        return trueCount.toDouble() / size.toDouble()
    }
}

object DecisionMatrix {
    fun evaluate(frame: TelemetryFrame): CoachAction? {
        return evaluateAll(frame).firstOrNull()
    }

    fun evaluateAll(frame: TelemetryFrame): List<CoachAction> {
        if (!isTelemetryMotionArmed(frame)) return emptyList()
        return buildList {
            forEachAction(frame) { add(it) }
        }
    }

    internal inline fun forEachAction(frame: TelemetryFrame, emit: (CoachAction) -> Unit) {
        if (!isTelemetryMotionArmed(frame)) return
        val absGLat = abs(frame.gLat)
        if (absGLat > 0.7 && frame.gLong < -0.3 && frame.throttle < 5.0 && frame.speedMph > 40.0) {
            emit(CoachAction.OVERSTEER_RECOVERY)
        }
        if (frame.brake > 50.0 && frame.gLong < -0.8 && frame.speedMph > 25.0) {
            emit(CoachAction.THRESHOLD)
        }
        if (frame.brake > 10.0 && absGLat > 0.4 && frame.speedMph > 20.0) {
            emit(CoachAction.TRAIL_BRAKE)
        }
        if (absGLat > 1.0 && frame.throttle < 20.0 && frame.speedMph > 35.0) {
            emit(CoachAction.COMMIT)
        }
        if (absGLat > 0.6 && frame.throttle < 50.0 && frame.speedMph > 25.0) {
            emit(CoachAction.THROTTLE)
        }
        if (frame.throttle > 80.0 && absGLat < 0.3 && frame.speedMph > 35.0) {
            emit(CoachAction.PUSH)
        }
        if (frame.throttle < 10.0 && frame.brake < 10.0 && frame.speedMph > 60.0) {
            emit(CoachAction.COAST)
        }
        if (
            (frame.brake > 40.0 && frame.speedMph < 45.0) ||
            (frame.throttle < 15.0 && frame.brake < 5.0 && frame.speedMph > 80.0 && absGLat < 0.3)
        ) {
            emit(CoachAction.HESITATION)
        }
        if (absGLat < 0.2 && frame.gLong > 0.1 && frame.throttle > 70.0 && frame.speedMph > 35.0) {
            emit(CoachAction.FULL_THROTTLE)
        }
        if (frame.throttle > 30.0 && absGLat > 0.6 && frame.gLong < -0.1 && frame.speedMph > 35.0) {
            emit(CoachAction.EARLY_THROTTLE)
        }
        if (frame.throttle < 5.0 && absGLat > 0.4 && frame.speedMph > 50.0) {
            emit(CoachAction.LIFT_MID_CORNER)
        }
        if (frame.brake > 70.0 && frame.gLong < -1.2 && frame.speedMph > 25.0) {
            emit(CoachAction.SPIKE_BRAKE)
        }
    }
}

class EdgeTriggerEvaluator {
    private var lastTriggerAtMs = 0L
    private var repeatedCoastCount = 0

    fun evaluate(
        frame: TelemetryFrame,
        phase: CornerPhase,
        driverState: DriverState,
        track: Track?,
        corner: Corner?,
        nowMs: Long,
    ): EdgeReasoningWindow? {
        if (nowMs - lastTriggerAtMs < MIN_TRIGGER_INTERVAL_MS) return null
        if (!isTelemetryMotionArmed(frame)) return null
        val vision = frame.vision

        val trigger = when {
            frame.sourceMode == SessionMode.CAMERA_DIRECT &&
                vision != null &&
                vision.motionEnergy > 0.16 &&
                abs(vision.lateralBalance) > 0.14 ->
                buildWindow(
                    triggerId = "camera_direct_visual_instability",
                    action = CoachAction.STABILIZE,
                    priority = 1,
                    text = "Visual flow is unstable. Smooth the hands before the next input.",
                    phase = phase,
                    driverState = driverState,
                    skillLevel = driverState.skillLevel,
                    track = track,
                    corner = corner,
                    frame = frame,
                )

            frame.sourceMode == SessionMode.CAMERA_DIRECT &&
                vision != null &&
                abs(vision.lateralBalance) > 0.24 ->
                buildWindow(
                    triggerId = "camera_direct_aim_bias",
                    action = CoachAction.WAIT,
                    priority = 2,
                    text = if (vision.lateralBalance > 0.0) {
                        "View is loading the left side. Re-center before you commit."
                    } else {
                        "View is loading the right side. Re-center before you commit."
                    },
                    phase = phase,
                    driverState = driverState,
                    skillLevel = driverState.skillLevel,
                    track = track,
                    corner = corner,
                    frame = frame,
                )

            frame.sourceMode == SessionMode.CAMERA_DIRECT &&
                vision != null &&
                vision.centerContrast < -0.08 &&
                vision.averageLuma < 0.32 ->
                buildWindow(
                    triggerId = "camera_direct_low_contrast",
                    action = CoachAction.MAINTAIN,
                    priority = 2,
                    text = "Image contrast collapsed. Lift the eyes and let the picture open back up.",
                    phase = phase,
                    driverState = driverState,
                    skillLevel = driverState.skillLevel,
                    track = track,
                    corner = corner,
                    frame = frame,
                )

            frame.brake > 70.0 && frame.gLong < -1.0 -> buildWindow(
                triggerId = "brake_trace_anomaly",
                action = CoachAction.SPIKE_BRAKE,
                priority = 1,
                text = "Brake trace spiking. Smooth the release.",
                phase = phase,
                driverState = driverState,
                skillLevel = driverState.skillLevel,
                track = track,
                corner = corner,
                frame = frame,
            )

            (phase == CornerPhase.EXIT || phase == CornerPhase.ACCELERATION) && frame.throttle in 20.0..55.0 ->
                buildWindow(
                    triggerId = "exit_hesitation",
                    action = CoachAction.HUSTLE,
                    priority = 2,
                    text = "Exit hesitation. Commit harder on throttle.",
                    phase = phase,
                    driverState = driverState,
                    skillLevel = driverState.skillLevel,
                    track = track,
                    corner = corner,
                    frame = frame,
                )

            frame.throttle < 8.0 && frame.brake < 8.0 && frame.speedMph > 55.0 -> {
                repeatedCoastCount += 1
                if (repeatedCoastCount >= 3) {
                    repeatedCoastCount = 0
                    buildWindow(
                        triggerId = "repeated_coasting",
                        action = CoachAction.COAST,
                        priority = 2,
                        text = "Repeated coasting detected. Pick a pedal.",
                        phase = phase,
                        driverState = driverState,
                        skillLevel = driverState.skillLevel,
                        track = track,
                        corner = corner,
                        frame = frame,
                    )
                } else {
                    null
                }
            }

            vision != null &&
                vision.motionEnergy > 0.18 &&
                abs(vision.lateralBalance) > 0.12 &&
                abs(frame.gLat) > 0.45 &&
                frame.speedMph > 45.0 ->
                buildWindow(
                    triggerId = "visual_flow_instability",
                    action = CoachAction.STABILIZE,
                    priority = 2,
                    text = "Camera lane sees unstable visual flow. Smooth the hands and settle the car.",
                    phase = phase,
                    driverState = driverState,
                    skillLevel = driverState.skillLevel,
                    track = track,
                    corner = corner,
                    frame = frame,
                )

            driverState.cognitiveLoad > 0.72 -> buildWindow(
                triggerId = "overload_window",
                action = CoachAction.COGNITIVE_OVERLOAD,
                priority = 2,
                text = "High input variance. Strip the next lap back down.",
                phase = phase,
                driverState = driverState,
                skillLevel = driverState.skillLevel,
                track = track,
                corner = corner,
                frame = frame,
            )

            corner != null && phase == CornerPhase.BRAKE_ZONE && frame.speedMph > 70.0 -> buildWindow(
                triggerId = "corner_tactic",
                action = CoachAction.BRAKE,
                priority = 1,
                text = "Corner tactic window open. Trim speed before turn-in.",
                phase = phase,
                driverState = driverState,
                skillLevel = driverState.skillLevel,
                track = track,
                corner = corner,
                frame = frame,
            )

            else -> null
        }

        if (trigger != null) {
            lastTriggerAtMs = nowMs
        }
        return trigger
    }

    private fun buildWindow(
        triggerId: String,
        action: CoachAction,
        priority: Int,
        text: String,
        phase: CornerPhase,
        driverState: DriverState,
        skillLevel: SkillLevel,
        track: Track?,
        corner: Corner?,
        frame: TelemetryFrame,
    ): EdgeReasoningWindow {
        val vision = frame.vision
        val cause = CoachingCauseClassifier.classify(triggerId, action, frame, phase, driverState, corner)
        val features = mutableMapOf(
            "speed_mph" to frame.speedMph,
            "throttle" to frame.throttle,
            "brake" to frame.brake,
            "g_lat" to frame.gLat,
            "g_long" to frame.gLong,
            "coasting_ratio" to driverState.coastingRatio,
            "cognitive_load" to driverState.cognitiveLoad,
        )
        cause?.let { features["cause_confidence"] = it.confidence }
        vision?.let { snapshot ->
            features["vision_avg_luma"] = snapshot.averageLuma
            features["vision_motion_energy"] = snapshot.motionEnergy
            features["vision_lateral_balance"] = snapshot.lateralBalance
            features["vision_vertical_balance"] = snapshot.verticalBalance
            features["vision_center_contrast"] = snapshot.centerContrast
            features["vision_fps"] = snapshot.framesPerSecond
        }
        val suggestion =
            TrackExpertiseCatalog.edgeText(
                track = track,
                corner = corner,
                triggerId = triggerId,
                defaultText = cause?.text ?: text,
                skillLevel = skillLevel,
                phase = phase,
            )
        return EdgeReasoningWindow(
            triggerId = triggerId,
            actionHint = action,
            priority = priority,
            suggestedText = suggestion,
            phase = phase,
            skillLevel = skillLevel,
            cornerName = corner?.name,
            features = features,
            causeHint = cause?.id,
        )
    }

    companion object {
        private const val MIN_TRIGGER_INTERVAL_MS = 1500L
    }
}

data class CoachingCause(
    val id: String,
    val confidence: Double,
    val text: String,
)

object CoachingCauseClassifier {
    fun classify(
        triggerId: String,
        action: CoachAction,
        frame: TelemetryFrame,
        phase: CornerPhase,
        driverState: DriverState,
        corner: Corner?,
    ): CoachingCause? {
        return when {
            triggerId.contains("brake_trace", ignoreCase = true) || action == CoachAction.SPIKE_BRAKE ->
                CoachingCause(
                    id = "brake_pressure_spike",
                    confidence = 0.84,
                    text = "Brake spike is upsetting weight transfer. Squeeze and release smoother.",
                )

            action == CoachAction.BRAKE && corner != null && phase == CornerPhase.BRAKE_ZONE ->
                CoachingCause(
                    id = "late_brake_timing",
                    confidence = 0.82,
                    text = "Late brake timing is shrinking turn-in margin. Start pressure earlier.",
                )

            action == CoachAction.COAST || triggerId.contains("coast", ignoreCase = true) ->
                CoachingCause(
                    id = "coasting_commitment_gap",
                    confidence = 0.78,
                    text = "Coasting is delaying weight transfer. Pick up maintenance throttle.",
                )

            action == CoachAction.HUSTLE && frame.throttle in 20.0..55.0 ->
                CoachingCause(
                    id = "late_throttle_commitment",
                    confidence = 0.72,
                    text = "Throttle timing is late for the exit. Commit once the wheel opens.",
                )

            action == CoachAction.STABILIZE && frame.vision != null ->
                CoachingCause(
                    id = "vision_target_drift",
                    confidence = 0.70,
                    text = "Vision drift is loading the hands. Put eyes through the exit.",
                )

            action == CoachAction.COGNITIVE_OVERLOAD || driverState.cognitiveLoad > 0.72 ->
                CoachingCause(
                    id = "input_variance_overload",
                    confidence = 0.68,
                    text = "Input variance is climbing. Simplify the next corner to one cue.",
                )

            action == CoachAction.EARLY_THROTTLE ->
                CoachingCause(
                    id = "early_throttle_weight_transfer",
                    confidence = 0.76,
                    text = "Throttle is arriving before weight transfer settles. Wait for exit.",
                )

            else -> null
        }
    }
}

private fun isTelemetryMotionArmed(frame: TelemetryFrame): Boolean {
    if (frame.sourceMode == SessionMode.CAMERA_DIRECT) return true
    return frame.speedMph >= 18.0
}

private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadius = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2.0) * sin(dLon / 2.0)
    return earthRadius * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
}

private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLon = Math.toRadians(lon2 - lon1)
    val startLat = Math.toRadians(lat1)
    val endLat = Math.toRadians(lat2)
    val y = sin(dLon) * cos(endLat)
    val x = cos(startLat) * sin(endLat) -
        sin(startLat) * cos(endLat) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private fun angularDeltaDegrees(a: Double, b: Double): Double {
    val delta = abs((a - b + 540.0) % 360.0 - 180.0)
    return delta.coerceIn(0.0, 180.0)
}

private fun isValidGps(lat: Double, lon: Double): Boolean {
    if (lat.isNaN() || lon.isNaN()) return false
    if (lat == 0.0 && lon == 0.0) return false
    if (abs(lat) > 90.0 || abs(lon) > 180.0) return false
    return true
}
