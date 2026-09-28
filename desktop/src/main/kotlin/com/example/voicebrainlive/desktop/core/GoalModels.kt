package com.example.voicebrainlive.desktop.core

import java.util.UUID

/**
 * Status of an autonomous multi-step goal execution.
 */
enum class GoalStatus {
    PENDING,
    PLANNING,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Status of an individual step in a goal plan.
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}

/**
 * A concrete atomic step within an autonomous goal plan.
 */
data class GoalStep(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val title: String,
    val command: DesktopCommand,
    val status: StepStatus = StepStatus.PENDING,
    val outputMessage: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null
) {
    val isDone: Boolean get() = status == StepStatus.COMPLETED || status == StepStatus.SKIPPED
    val isFailed: Boolean get() = status == StepStatus.FAILED
    val isRunning: Boolean get() = status == StepStatus.RUNNING
}

/**
 * Defines an autonomous goal with its step-by-step execution plan and real-time state.
 */
data class GoalDefinition(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val rawIntent: String,
    val steps: List<GoalStep> = emptyList(),
    val status: GoalStatus = GoalStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val statusReport: String? = null
) {
    val totalSteps: Int get() = steps.size
    val completedStepsCount: Int get() = steps.count { it.status == StepStatus.COMPLETED }
    val progress: Float
        get() = if (steps.isEmpty()) 0f else completedStepsCount.toFloat() / steps.size.toFloat()

    val currentStep: GoalStep?
        get() = steps.firstOrNull { it.status == StepStatus.RUNNING }
            ?: steps.firstOrNull { it.status == StepStatus.PENDING }
}

/**
 * Final execution report for an autonomous goal.
 */
data class GoalExecutionResult(
    val success: Boolean,
    val goalId: String,
    val title: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val finalReport: String,
    val steps: List<GoalStep>
)
