package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.CoachingDecision
import com.trustableai.koru.model.CoachingPath
import com.trustableai.koru.model.CornerPhase
import com.trustableai.koru.model.RuntimeBackend
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.TelemetryFrame
import com.trustableai.koru.model.Track
import com.trustableai.koru.model.bridgeValue
import com.trustableai.koru.runtime.deterministic.CoachingQueue
import com.trustableai.koru.runtime.deterministic.CornerPhaseDetector
import com.trustableai.koru.runtime.deterministic.DecisionMatrix
import com.trustableai.koru.runtime.deterministic.DriverModel
import com.trustableai.koru.runtime.deterministic.EdgeTriggerEvaluator
import com.trustableai.koru.runtime.deterministic.SpatialAnticipationTrigger
import com.trustableai.koru.runtime.deterministic.TimingGate
import com.trustableai.koru.runtime.reasoner.OnDeviceReasoner

class KoruRealtimeEngine(
    track: Track?,
    private val phraseCatalog: PhraseRenderer,
    private var sessionGoals: List<SessionGoal> = emptyList(),
    private val reasonerProvider: () -> OnDeviceReasoner,
    private val edgeReasonerQueue: EdgeReasonerQueue = EdgeReasonerQueue(reasonerProvider),
) {
    private val track = track
    private val cornerDetector = CornerPhaseDetector(track)
    private val timingGate = TimingGate()
    private val queue = CoachingQueue()
    private val driverModel = DriverModel()
    private val edgeTriggerEvaluator = EdgeTriggerEvaluator()
    private val anticipationTrigger = SpatialAnticipationTrigger()
    private val seenActionStamp = IntArray(CoachAction.entries.size)
    private val goalBoosts = IntArray(CoachAction.entries.size)
    private var activeCoachId = "superaj"
    private var lastHotAction: CoachAction? = null
    private var lastFeedforwardCornerId: Int? = null
    private var currentPhase: CornerPhase = CornerPhase.STRAIGHT
    private var actionStamp = 1

    init {
        setSessionGoals(sessionGoals)
    }

    fun setActiveCoach(coachId: String) {
        activeCoachId = coachId
    }

    fun setSessionGoals(goals: List<SessionGoal>) {
        sessionGoals = goals.take(3)
        goalBoosts.fill(0)
        sessionGoals.forEach { goal ->
            goal.prioritizedActions.forEach { action ->
                goalBoosts[action.ordinal] += 1
            }
        }
    }

    fun close() {
        edgeReasonerQueue.close()
    }

    suspend fun processFrame(frame: TelemetryFrame, nowMs: Long = System.currentTimeMillis()): List<CoachingDecision> {
        val latencyProbe = LatencyProbe.start()
        val fallbackStage = frame.sourceHealth?.fallbackStage
        if (fallbackStage == "no_live_data") {
            queue.clear()
            return emptyList()
        }
        val detection = cornerDetector.detect(frame)
        currentPhase = detection.phase
        timingGate.update(currentPhase, nowMs)
        driverModel.update(frame)
        val driverState = driverModel.state()

        adaptTiming(driverState.skillLevel)

        var bestAction: CoachAction? = null
        var bestPriority = Int.MAX_VALUE
        var bestGoalBoost = Int.MIN_VALUE
        var bestOrder = Int.MAX_VALUE
        var order = 0
        val stamp = nextActionStamp()

        fun considerHotAction(action: CoachAction) {
            val ordinal = action.ordinal
            if (seenActionStamp[ordinal] == stamp) return
            seenActionStamp[ordinal] = stamp

            val priority = actionPriority(action)
            if (shouldSuppressForTelemetryTrust(action, fallbackStage)) {
                order += 1
                return
            }
            val suppress =
                TrackExpertiseCatalog.shouldSuppressHotAction(
                    track = track,
                    corner = detection.corner,
                    action = action,
                    skillLevel = driverState.skillLevel,
                    phase = currentPhase,
                    timeSeconds = frame.timeSeconds,
                    cognitiveLoad = driverState.cognitiveLoad,
                )
            if ((action == lastHotAction && priority != 0) || suppress) {
                order += 1
                return
            }

            val boost = goalBoostForAction(action)
            val isBetter =
                priority < bestPriority ||
                    (priority == bestPriority && boost > bestGoalBoost) ||
                    (priority == bestPriority && boost == bestGoalBoost && order < bestOrder)
            if (isBetter) {
                bestAction = action
                bestPriority = priority
                bestGoalBoost = boost
                bestOrder = order
            }
            order += 1
        }

        contextualBrakeAction(frame, detection.phase, detection.corner)?.let(::considerHotAction)
        DecisionMatrix.forEachAction(frame, ::considerHotAction)

        bestAction
            ?.let { action ->
                lastHotAction = action
                val decision =
                    hotDecision(
                        action = action,
                        corner = detection.corner,
                        skillLevel = driverState.skillLevel,
                        cognitiveLoad = driverState.cognitiveLoad,
                        timeSeconds = frame.timeSeconds,
                        nowMs = nowMs,
                    )
                if (decision.priority == 0) {
                    queue.enqueue(queue.preempt(decision), nowMs)
                } else {
                    queue.enqueue(decision, nowMs)
                }
            }

        if (allowsPredictiveCoaching(fallbackStage)) {
            anticipationTrigger.evaluate(frame, track)
                ?.takeIf { candidate -> candidate.corner.id != lastFeedforwardCornerId }
                ?.let { candidate ->
                    lastFeedforwardCornerId = candidate.corner.id
                    queue.enqueue(
                        CoachingQueue.QueuedDecision(
                            action = null,
                            path = CoachingPath.FEEDFORWARD.bridgeValue(),
                            text = TrackExpertiseCatalog.feedforwardText(
                                track = track,
                                corner = candidate.corner,
                                skillLevel = driverState.skillLevel,
                            ),
                            priority = 1,
                            cornerPhase = currentPhase,
                            timestampMs = nowMs,
                        ),
                        nowMs,
                    )
                }

            edgeTriggerEvaluator.evaluate(frame, currentPhase, driverState, track, detection.corner, nowMs)?.let { window ->
                edgeReasonerQueue.submit(window, currentPhase, nowMs)
            }
        }

        edgeReasonerQueue.drainReady(nowMs).forEach { edgeDecision ->
            queue.enqueue(edgeDecision, nowMs)
        }

        val next = queue.dequeue(nowMs, timingGate) ?: return emptyList()
        timingGate.startDelivery(nowMs)
        val backend = next.backend ?: RuntimeBackend.DETERMINISTIC
        return listOf(
            CoachingDecision(
                path = CoachingPath.valueOf(next.path.uppercase()),
                action = next.action,
                text = next.text,
                priority = next.priority,
                cornerPhase = next.cornerPhase,
                timestampMs = nowMs,
                backend = backend,
                latencyMs = latencyProbe.elapsedMs(),
                confidence = next.confidence,
                phraseId = next.phraseId,
            ),
        )
    }

    private fun hotDecision(
        action: CoachAction,
        corner: com.trustableai.koru.model.Corner?,
        skillLevel: com.trustableai.koru.model.SkillLevel,
        cognitiveLoad: Double,
        timeSeconds: Double,
        nowMs: Long,
    ): CoachingQueue.QueuedDecision {
        val priority = actionPriority(action)
        val doctrineText =
            TrackExpertiseCatalog.hotActionText(
                track = track,
                corner = corner,
                action = action,
                skillLevel = skillLevel,
                phase = currentPhase,
                timeSeconds = timeSeconds,
                cognitiveLoad = cognitiveLoad,
            )
        return CoachingQueue.QueuedDecision(
            action = action,
            path = CoachingPath.HOT.bridgeValue(),
            text = SafetyPhrasePolicy.textFor(action) ?: doctrineText ?: phraseCatalog.render(action, skillLevel, activeCoachId),
            priority = priority,
            cornerPhase = currentPhase,
            timestampMs = nowMs,
            phraseId = phraseCatalog.phraseIdFor(action, skillLevel, activeCoachId),
        )
    }

    private fun adaptTiming(skillLevel: com.trustableai.koru.model.SkillLevel) {
        val profile = LiveAudioPolicy.timingProfile(skillLevel)
        timingGate.updateConfig(profile.cooldownMs, profile.deliveryMs, profile.blackoutPhases)
    }

    private fun actionPriority(action: CoachAction): Int {
        return when (action) {
            CoachAction.OVERSTEER_RECOVERY, CoachAction.BRAKE -> 0
            CoachAction.EARLY_THROTTLE, CoachAction.LIFT_MID_CORNER, CoachAction.SPIKE_BRAKE -> 1
            CoachAction.COGNITIVE_OVERLOAD -> 2
            else -> 3
        }
    }

    private fun goalBoostForAction(action: CoachAction): Int {
        return goalBoosts[action.ordinal]
    }

    private fun allowsPredictiveCoaching(fallbackStage: String?): Boolean {
        return fallbackStage == null ||
            fallbackStage == "full" ||
            fallbackStage == "racebox_only" ||
            fallbackStage == "aim_can_full" ||
            fallbackStage == "aim_can_racebox_motion" ||
            fallbackStage == "phone_only"
    }

    private fun shouldSuppressForTelemetryTrust(action: CoachAction, fallbackStage: String?): Boolean {
        return fallbackStage == "phone_only" && action in throttleDependentActions
    }

    private fun nextActionStamp(): Int {
        actionStamp += 1
        if (actionStamp == Int.MAX_VALUE) {
            seenActionStamp.fill(0)
            actionStamp = 1
        }
        return actionStamp
    }

    private fun contextualBrakeAction(
        frame: TelemetryFrame,
        phase: CornerPhase,
        corner: com.trustableai.koru.model.Corner?,
    ): CoachAction? {
        if (phase != CornerPhase.BRAKE_ZONE) return null
        val targetSpeed = corner?.targetSpeed ?: 60.0
        val tooFastForEntry = frame.speedMph >= targetSpeed + 25.0 || frame.speedMph >= 85.0
        val notActuallyBraking = frame.brake < 12.0 && frame.gLong > -0.25
        return if (tooFastForEntry && notActuallyBraking) {
            CoachAction.BRAKE
        } else {
            null
        }
    }
}

private val throttleDependentActions = setOf(
    CoachAction.THROTTLE,
    CoachAction.COMMIT,
    CoachAction.EARLY_THROTTLE,
    CoachAction.HUSTLE,
    CoachAction.COAST,
    CoachAction.LIFT_MID_CORNER,
    CoachAction.PUSH,
    CoachAction.FULL_THROTTLE,
)

object SafetyPhrasePolicy {
    fun textFor(action: CoachAction): String? {
        return when (action) {
            CoachAction.BRAKE -> "Brake now"
            CoachAction.OVERSTEER_RECOVERY -> "Both feet in"
            else -> null
        }
    }
}
