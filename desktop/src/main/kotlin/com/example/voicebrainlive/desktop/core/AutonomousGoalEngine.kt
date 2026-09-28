package com.example.voicebrainlive.desktop.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Autonomous Goal Execution Engine:
 * Coordinates the full lifecycle of goal decomposition, step-by-step dispatch,
 * result observation, progress state emission, and terminal completion.
 */
class AutonomousGoalEngine(
    private val executor: PlatformCommandExecutor,
    val decomposer: PlanDecomposer = PlanDecomposer(CompoundCommandHandler(executor))
) {

    private val _activeGoal = MutableStateFlow<GoalDefinition?>(null)
    val activeGoal: StateFlow<GoalDefinition?> = _activeGoal.asStateFlow()

    private val executionMutex = Mutex()
    private val isCancelled = AtomicBoolean(false)

    /**
     * Accepts a high-level goal intent, decomposes it into an execution plan,
     * and drives it autonomously to completion.
     */
    suspend fun executeGoalFromIntent(
        intent: String,
        optionalProject: String? = null,
        onStepMilestone: (suspend (stepIndex: Int, totalSteps: Int, step: GoalStep) -> Unit)? = null
    ): GoalExecutionResult = withContext(Dispatchers.IO) {
        val plan = decomposer.decompose(intent, optionalProject)
        executePlan(plan, onStepMilestone)
    }

    /**
     * Executes a pre-formed GoalDefinition step-by-step.
     */
    suspend fun executePlan(
        plan: GoalDefinition,
        onStepMilestone: (suspend (stepIndex: Int, totalSteps: Int, step: GoalStep) -> Unit)? = null
    ): GoalExecutionResult = executionMutex.withLock {
        withContext(Dispatchers.IO) {
            isCancelled.set(false)

            if (plan.steps.isEmpty()) {
                val emptyResult = GoalExecutionResult(
                    success = false,
                    goalId = plan.id,
                    title = plan.title,
                    completedSteps = 0,
                    totalSteps = 0,
                    finalReport = "လုပ်ဆောင်ရန် အဆင့်များ မရှိပါ",
                    steps = emptyList()
                )
                _activeGoal.value = null
                return@withContext emptyResult
            }

            var currentGoal = plan.copy(status = GoalStatus.EXECUTING)
            _activeGoal.value = currentGoal

            val updatedSteps = currentGoal.steps.toMutableList()
            var allSucceeded = true
            var lastFailureMessage: String? = null

            for (i in 0 until updatedSteps.size) {
                if (isCancelled.get()) {
                    currentGoal = currentGoal.copy(
                        status = GoalStatus.CANCELLED,
                        completedAt = System.currentTimeMillis(),
                        statusReport = "အသုံးပြုသူမှ ပန်းတိုင် လုပ်ဆောင်ချက်ကို ရပ်တန့်လိုက်ပါသည်"
                    )
                    _activeGoal.value = currentGoal
                    return@withContext GoalExecutionResult(
                        success = false,
                        goalId = currentGoal.id,
                        title = currentGoal.title,
                        completedSteps = updatedSteps.count { it.status == StepStatus.COMPLETED },
                        totalSteps = updatedSteps.size,
                        finalReport = "ပန်းတိုင်အား ရပ်တန့်လိုက်ပါသည်ရှင်။",
                        steps = updatedSteps
                    )
                }

                val currentStep = updatedSteps[i]
                val stepStartTime = System.currentTimeMillis()

                // Mark RUNNING
                updatedSteps[i] = currentStep.copy(
                    status = StepStatus.RUNNING,
                    startedAt = stepStartTime
                )
                currentGoal = currentGoal.copy(steps = updatedSteps.toList())
                _activeGoal.value = currentGoal

                // Spoken milestone callback
                runCatching {
                    onStepMilestone?.invoke(i + 1, updatedSteps.size, updatedSteps[i])
                }

                // Execute step command
                val result = runCatching {
                    executor.execute(currentStep.command)
                }.getOrElse { error ->
                    CommandResult(false, error.message ?: "အဆင့် လုပ်ဆောင်မှု မအောင်မြင်ပါ")
                }

                val stepEndTime = System.currentTimeMillis()

                if (result.success) {
                    updatedSteps[i] = updatedSteps[i].copy(
                        status = StepStatus.COMPLETED,
                        outputMessage = result.message,
                        finishedAt = stepEndTime
                    )
                } else {
                    updatedSteps[i] = updatedSteps[i].copy(
                        status = StepStatus.FAILED,
                        outputMessage = result.message,
                        finishedAt = stepEndTime
                    )
                    allSucceeded = false
                    lastFailureMessage = result.message

                    // If step required confirmation or critical failure, halt execution
                    if (result.requiresConfirmation) {
                        currentGoal = currentGoal.copy(
                            steps = updatedSteps.toList(),
                            status = GoalStatus.FAILED,
                            completedAt = stepEndTime,
                            statusReport = "အတည်ပြုချက် လိုအပ်သောကြောင့် ရပ်နားထားပါသည်: ${result.message}"
                        )
                        _activeGoal.value = currentGoal
                        return@withContext GoalExecutionResult(
                            success = false,
                            goalId = currentGoal.id,
                            title = currentGoal.title,
                            completedSteps = updatedSteps.count { it.status == StepStatus.COMPLETED },
                            totalSteps = updatedSteps.size,
                            finalReport = "အဆင့် ${i + 1} တွင် အတည်ပြုချက် လိုအပ်ပါသည်: ${result.message}",
                            steps = updatedSteps
                        )
                    }
                }

                currentGoal = currentGoal.copy(steps = updatedSteps.toList())
                _activeGoal.value = currentGoal
            }

            val finalStatus = if (allSucceeded) GoalStatus.COMPLETED else GoalStatus.FAILED
            val completedCount = updatedSteps.count { it.status == StepStatus.COMPLETED }
            val finalSummary = if (allSucceeded) {
                "ပန်းတိုင် '${currentGoal.title}' အောင်မြင်စွာ ပြီးဆုံးပါပြီရှင် (အဆင့် $completedCount ခုလုံး ပြီးမြောက်ပါသည်)။"
            } else {
                "ပန်းတိုင် '${currentGoal.title}' တစ်စိတ်တစ်ပိုင်းသာ ပြီးစီးပါသည် ($completedCount/${updatedSteps.size} အောင်မြင်)။ အခက်အခဲ: $lastFailureMessage"
            }

            currentGoal = currentGoal.copy(
                status = finalStatus,
                completedAt = System.currentTimeMillis(),
                statusReport = finalSummary
            )
            _activeGoal.value = currentGoal

            return@withContext GoalExecutionResult(
                success = allSucceeded,
                goalId = currentGoal.id,
                title = currentGoal.title,
                completedSteps = completedCount,
                totalSteps = updatedSteps.size,
                finalReport = finalSummary,
                steps = updatedSteps
            )
        }
    }

    /**
     * Cancels the active goal execution immediately.
     */
    fun cancelGoal() {
        isCancelled.set(true)
        val current = _activeGoal.value
        if (current != null && current.status == GoalStatus.EXECUTING) {
            _activeGoal.value = current.copy(
                status = GoalStatus.CANCELLED,
                statusReport = "ပန်းတိုင်ကို အသုံးပြုသူမှ ရပ်တန့်လိုက်ပါသည်"
            )
        }
    }

    /**
     * Clears finished goal state from memory.
     */
    fun clearActiveGoal() {
        _activeGoal.value = null
    }
}
