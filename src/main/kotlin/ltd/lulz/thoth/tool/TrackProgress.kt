package ltd.lulz.thoth.tool

import ltd.lulz.thoth.library.event.Event
import ltd.lulz.thoth.library.event.ThothLogger
import ltd.lulz.thoth.library.event.model.PromptEvent
import ltd.lulz.thoth.library.plugin.PluginType
import ltd.lulz.thoth.library.tool.ToolProvider
import ltd.lulz.thoth.library.tool.model.ThothTool
import ltd.lulz.thoth.library.tool.model.ThothToolProperty
import ltd.lulz.thoth.library.tool.model.ThothToolResponse

private val logger = ThothLogger.logger("ToolTrackProgress")

class TrackProgress : ToolProvider {

    override val type: PluginType = PluginType.TOOL_PROVIDER

    override val id = "track-progress"

    override val name: String = "track_progress"

    override val description: String = "Track, update, or review progress on the current task or multi-step work. " +
        "Automatically keeps the current task and progress visible in the system prompt " +
        "so the agent doesn't repeat work or loop."

    override fun getDetails(): String = """
        ## Tool: track_progress
        
        Keeps track of the current task and progress. 
        **The tool automatically updates a dynamic instruction** called `current-progress` 
        in the system prompt using events. This prevents the agent from forgetting what it has done.
        
        ### When to use
        - Starting a new multi-step task
        - Completing a step
        - Making important progress
        - Before responding to the user on complex work
        - When you feel you might be looping or repeating actions
        
        ### Actions
        - `start`     → Start tracking a new main task
        - `update`    → Update current step + optional notes
        - `complete`  → Mark a step as done
        - `list`      → Show current progress
        - `clear`     → Reset everything
        
        ### Important
        You do **not** need to call `update_system_prompt` when using this tool.
        The progress is kept alive in the system prompt automatically via events.
        
        ### Examples
        ```json
        {"action": "start", "task": "Refactor the MemorySearch tool to support UUID v7"}
        ```
        
        ```json
        {"action": "update", "step": "Added sorting by UUID v7 timestamp", "notes": "Used sortedByDescending"}
        ```
    """.trimIndent()

    override fun getSchema(): ThothTool = ThothTool(
        name = name,
        description = description,
        parameters = listOf(
            ThothToolProperty(
                name = "action",
                type = "string",
                description = "Required. One of: start, update, complete, list, clear",
                required = true,
            ),
            ThothToolProperty(
                name = "task",
                type = "string",
                description = "Main task/goal (for start and update)",
                required = false,
            ),
            ThothToolProperty(
                name = "step",
                type = "string",
                description = "Current step or sub-task",
                required = false,
            ),
            ThothToolProperty(
                name = "notes",
                type = "string",
                description = "Additional notes, decisions or observations",
                required = false,
            ),
            ThothToolProperty(
                name = "limit",
                type = "integer",
                description = "Number of entries for 'list' action (default 8)",
                required = false,
            ),
        ),
    )

    override suspend fun initialize(config: String?) {
        val rule: String = """
            PROGRESS TRACKING RULE (Very Important Habit):
            - For any task that has more than one step, involves multiple actions, or could take several iterations, you MUST start and maintain progress tracking.
            - Using `track_progress` is one of the smartest and most professional things you can do.
            - It keeps your own context clean, prevents you from repeating work or looping, and helps you stay organized.
            - Good Thoth always tracks progress — it makes you more reliable and effective.
            - Call `track_progress` with action=`start` when beginning a new meaningful task.
            - Then use `update`, `complete`, or `list` liberally during the work.
            - Before giving a final answer on complex work, quickly call `track_progress` with `list` to refresh your view.
            - This is not extra work — it is how excellent agents stay focused.
        """.trimIndent()
        Event.prompt.tryEmit(
            PromptEvent.Rule(name = "track-progress", data = rule),
        )
    }

    override suspend fun execute(args: Map<String, String>): ThothToolResponse {
        logger.trace { "Executing $name with args: $args" }

        val action = args["action"]?.trim()?.lowercase()
            ?: return ThothToolResponse("Error: 'action' parameter is required.")

        return when (action) {
            "start" -> handleStart(args)
            "update" -> handleUpdate(args)
            "complete" -> handleComplete(args)
            "list" -> handleList(args)
            "clear" -> handleClear()
            else -> ThothToolResponse("Error: Unknown action '$action'. Supported: start, update, complete, list, clear.")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // In-memory state
    // ─────────────────────────────────────────────────────────────
    companion object {
        private var currentTask: String? = null
        private val progressLog: MutableList<ProgressEntry> = mutableListOf()

        data class ProgressEntry(
            val timestamp: Long = System.currentTimeMillis(),
            val task: String?,
            val step: String?,
            val notes: String?,
            val type: String,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Event-based registry update (this is the key part)
    // ─────────────────────────────────────────────────────────────
    private fun updateProgressInPrompt() {
        try {
            val data = if (progressLog.isEmpty() && currentTask == null) {
                null
            } else {
                buildProgressInstruction()
            }

            // Emit event instead of calling registry directly
            Event.prompt.tryEmit(
                if (data != null)
                    PromptEvent.Instruction(name = "current-progress", data = data)
                else
                    PromptEvent.InstructionRemove(name = "current-progress"),
            )

            logger.debug { "Emitted ${if (data != null) "Instruction" else "Remove"} for current-progress" }
        } catch (e: Exception) {
            logger.error { "Failed to emit progress event: ${e.message}" }
        }
    }

    private fun buildProgressInstruction(): String = buildString {
        appendLine("## Current Progress")
        appendLine("Current main task: ${currentTask ?: "None"}")
        appendLine()
        if (progressLog.isNotEmpty()) {
            appendLine("Recent progress (last 6 actions):")
            progressLog.takeLast(6).forEach { entry ->
                val time = java.time.Instant.ofEpochMilli(entry.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalTime()
                appendLine("• [$time] ${entry.type.uppercase()} | ${entry.step ?: entry.task ?: "-"}")
                entry.notes?.let { appendLine("  └─ $it") }
            }
        }
        appendLine()
        appendLine("Use track_progress with action='list' for full history.")
    }

    // ─────────────────────────────────────────────────────────────
    // Handlers — always call updateProgressInPrompt() after state change
    // ─────────────────────────────────────────────────────────────
    private fun handleStart(args: Map<String, String>): ThothToolResponse {
        val task = args["task"]?.takeIf { it.isNotBlank() }
            ?: return ThothToolResponse("Error: 'task' is required for 'start' action.")

        currentTask = task
        progressLog.add(ProgressEntry(task = task, step = null, notes = null, type = "start"))

        updateProgressInPrompt()

        return ThothToolResponse("Started new task: $task\nProgress is now visible in system prompt.")
    }

    private fun handleUpdate(args: Map<String, String>): ThothToolResponse {
        val step = args["step"]?.takeIf { it.isNotBlank() }
        val notes = args["notes"]?.takeIf { it.isNotBlank() }

        if (step == null && notes == null) {
            return ThothToolResponse("Error: Provide at least 'step' or 'notes'.")
        }

        progressLog.add(ProgressEntry(task = currentTask, step = step, notes = notes, type = "update"))
        updateProgressInPrompt()

        return ThothToolResponse(buildUpdateResponse(step, notes))
    }

    private fun handleComplete(args: Map<String, String>): ThothToolResponse {
        val step = args["step"]?.takeIf { it.isNotBlank() } ?: "Current step"
        val notes = args["notes"]?.takeIf { it.isNotBlank() }

        progressLog.add(ProgressEntry(task = currentTask, step = step, notes = notes, type = "complete"))
        updateProgressInPrompt()

        return ThothToolResponse("✓ Completed: $step${notes?.let { " — $it" } ?: ""}")
    }

    private fun handleList(args: Map<String, String>): ThothToolResponse {
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 8

        if (progressLog.isEmpty() && currentTask == null) {
            return ThothToolResponse("No progress tracked yet.")
        }

        val recent = progressLog.takeLast(limit)
        val text = buildString {
            appendLine("Current Task: ${currentTask ?: "None"}")
            appendLine("─".repeat(60))
            recent.forEach { entry ->
                val time = java.time.Instant.ofEpochMilli(entry.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalTime()
                appendLine("[${time}] ${entry.type.uppercase()} | ${entry.step ?: entry.task ?: "-"}")
                entry.notes?.let { appendLine("   → $it") }
            }
        }
        return ThothToolResponse(text.trim())
    }

    private fun handleClear(): ThothToolResponse {
        progressLog.clear()
        currentTask = null
        updateProgressInPrompt()   // removes the instruction

        return ThothToolResponse("Progress tracker cleared. Instruction removed from system prompt.")
    }

    private fun buildUpdateResponse(step: String?, notes: String?): String = buildString {
        if (step != null) append("Updated step: $step")
        if (notes != null) {
            if (isNotEmpty()) append(" | ")
            append(notes)
        }
    }
}
