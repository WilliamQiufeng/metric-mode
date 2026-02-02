package org.example.app.user

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

@LLMDescription("Tool used to indicate that the whole process is finished.")
class QuitTools : ToolSet {
    var quit = false
        private set
    @Tool
    @LLMDescription("Indicate that the whole process is finished. This happens when the checklist is filled," +
            "or there are unfilled optional fields but the user confirms they don't want to fill them in.")
    fun quit() {
        quit = true
    }
}