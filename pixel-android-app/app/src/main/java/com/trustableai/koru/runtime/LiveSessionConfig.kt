package com.trustableai.koru.runtime

import com.trustableai.koru.model.CoachAction
import com.trustableai.koru.model.ObdTransportPreference
import com.trustableai.koru.model.SessionGoal
import com.trustableai.koru.model.SessionGoalFocus
import com.trustableai.koru.model.SessionGoalSource
import com.trustableai.koru.model.SessionMode
import com.trustableai.koru.model.TelemetrySourceKind
import com.trustableai.koru.model.bridgeValue
import org.json.JSONArray
import org.json.JSONObject

data class LiveSessionConfig(
    val coachId: String,
    val audioEnabled: Boolean,
    val trackName: String,
    val sessionMode: SessionMode,
    val telemetrySource: TelemetrySourceKind,
    val obdTransportPreference: ObdTransportPreference = ObdTransportPreference.AUTO,
    val sessionGoals: List<SessionGoal>,
    val sourceUrl: String?,
) {
    fun toJson(): String {
        return JSONObject()
            .put("coachId", coachId)
            .put("audioEnabled", audioEnabled)
            .put("trackName", trackName)
            .put("sessionMode", sessionMode.bridgeValue())
            .put("telemetrySource", telemetrySource.bridgeValue())
            .put("obdTransportPreference", obdTransportPreference.bridgeValue())
            .put("sessionGoals", JSONArray(sessionGoals.map(::sessionGoalJson)))
            .put("sourceUrl", sourceUrl)
            .toString()
    }

    companion object {
        fun fromJson(json: String): LiveSessionConfig {
            val root = JSONObject(json)
            return LiveSessionConfig(
                coachId = root.optString("coachId", "superaj"),
                audioEnabled = root.optBoolean("audioEnabled", true),
                trackName = root.optString("trackName", TrackCatalog.defaultTrack.name),
                sessionMode = when (root.optString("sessionMode", "telemetry")) {
                    "device_test" -> SessionMode.DEVICE_TEST
                    "camera_direct" -> SessionMode.CAMERA_DIRECT
                    else -> SessionMode.TELEMETRY
                },
                telemetrySource = when (root.optString("telemetrySource", "aim_can_usb")) {
                    "synthetic" -> TelemetrySourceKind.SYNTHETIC
                    "phone_imu_gps" -> TelemetrySourceKind.PHONE_IMU_GPS
                    "racebox_ble" -> TelemetrySourceKind.RACEBOX_BLE
                    "obd_bluetooth" -> TelemetrySourceKind.OBD_BLUETOOTH
                    "racebox_obd_fusion" -> TelemetrySourceKind.RACEBOX_OBD_FUSION
                    "aim_can_usb" -> TelemetrySourceKind.AIM_CAN_USB
                    "dauntless_can_bluetooth" -> TelemetrySourceKind.DAUNTLESS_CAN_BLUETOOTH
                    else -> TelemetrySourceKind.AIM_CAN_USB
                },
                obdTransportPreference = when (root.optString("obdTransportPreference", "auto")) {
                    "bluetooth" -> ObdTransportPreference.BLUETOOTH
                    "usb" -> ObdTransportPreference.USB
                    else -> ObdTransportPreference.AUTO
                },
                sessionGoals = parseSessionGoals(root.optJSONArray("sessionGoals")),
                sourceUrl = root.optString("sourceUrl").ifBlank { null },
            )
        }

        private fun parseSessionGoals(values: JSONArray?): List<SessionGoal> {
            if (values == null) return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    val goal = values.optJSONObject(index) ?: continue
                    add(
                        SessionGoal(
                            id = goal.optString("id", "goal-$index"),
                            focus = when (goal.optString("focus", "custom")) {
                                "braking" -> SessionGoalFocus.BRAKING
                                "throttle" -> SessionGoalFocus.THROTTLE
                                "vision" -> SessionGoalFocus.VISION
                                "lines" -> SessionGoalFocus.LINES
                                "smoothness" -> SessionGoalFocus.SMOOTHNESS
                                else -> SessionGoalFocus.CUSTOM
                            },
                            description = goal.optString("description", "Custom session focus"),
                            source = when (goal.optString("source", "pre_race_chat")) {
                                "auto_generated" -> SessionGoalSource.AUTO_GENERATED
                                "coach_assigned" -> SessionGoalSource.COACH_ASSIGNED
                                else -> SessionGoalSource.PRE_RACE_CHAT
                            },
                            prioritizedActions = parseActions(goal.optJSONArray("prioritizedActions")),
                        ),
                    )
                }
            }.take(3)
        }

        private fun parseActions(values: JSONArray?): List<CoachAction> {
            if (values == null) return emptyList()
            return buildList {
                for (index in 0 until values.length()) {
                    val actionName = values.optString(index).trim()
                    if (actionName.isEmpty()) continue
                    runCatching { CoachAction.valueOf(actionName) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }

        private fun sessionGoalJson(goal: SessionGoal): JSONObject {
            return JSONObject()
                .put("id", goal.id)
                .put("focus", goal.focus.bridgeValue())
                .put("description", goal.description)
                .put("source", goal.source.bridgeValue())
                .put("prioritizedActions", JSONArray(goal.prioritizedActions.map { action -> action.name }))
        }
    }
}
