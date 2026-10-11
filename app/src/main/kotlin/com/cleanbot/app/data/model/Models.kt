package com.cleanbot.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Position(val x: Int, val y: Int)

@Serializable
data class Telemetry(
    val robotId: String,
    val timestamp: String,
    val state: String,
    val battery: Int,
    val progress: Double,
    val position: Position,
    val error: String? = null
)

@Serializable
data class RobotSummary(
    val id: String,
    val name: String,
    val state: String,
    val battery: Int,
    val error: String? = null
)

@Serializable
data class CommandRequest(
    val command: String,
    val requestId: String
)

@Serializable
data class CommandResponse(
    val accepted: Boolean,
    val requestId: String,
    val reason: String? = null
)

@Serializable
data class Schedule(
    val id: String,
    val robotId: String,
    val dayOfWeek: Int,
    val time: String,
    val mode: String
)

@Serializable
data class ScheduleUpdateRequest(
    val entries: List<Schedule>
)

@Serializable
data class Session(
    val id: String,
    val robotId: String,
    val startedAt: String,
    val endedAt: String,
    val durationMinutes: Int,
    val areaCleaned: Double,
    val mode: String,
    val aborted: Boolean
)

@Serializable
data class LogEntry(
    val timestamp: String,
    val robotId: String,
    val level: String,
    val message: String
)

@Serializable
data class RobotSettings(
    val robotId: String,
    val displayName: String,
    val serverAddress: String
)
