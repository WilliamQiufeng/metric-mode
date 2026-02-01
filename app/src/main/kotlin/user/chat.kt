package org.example.app.user

import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.dsl.extension.containsToolCalls
import ai.koog.agents.core.dsl.extension.executeMultipleTools
import ai.koog.agents.core.dsl.extension.extractToolCalls
import ai.koog.agents.core.dsl.extension.requestLLMOnlyCallingTools
import ai.koog.agents.core.dsl.extension.sendMultipleToolResults
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.ext.tool.AskUser
import ai.koog.agents.ext.tool.SayToUser
import ai.koog.agents.ext.tool.file.ListDirectoryTool
import ai.koog.agents.ext.tool.file.ReadFileTool
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.rag.base.files.JVMFileSystemProvider
import kotlinx.coroutines.runBlocking
import org.example.app.intermediate.*


fun main() = runBlocking {
    val checklist = ModelAutoGenChecklist()
    val checklistTools = ChecklistTools(checklist)
    val quitTools = QuitTools()

    val toolRegistry = ToolRegistry {
        // CLI I/O tools
        tool(SayToUser)
        tool(AskUser)

        // Local file inspection
        tool(ReadFileTool(JVMFileSystemProvider.ReadOnly))
        tool(ListDirectoryTool(JVMFileSystemProvider.ReadOnly))

        // Your checklist mutation tools
        tools(checklistTools)
        tools(CsvPreprocessTools())
        tools(PythonDataGenTools())
        tools(quitTools)
    }

    val systemPrompt = """
You are an interactive intake assistant for building a model to describe data.

Hard rules:
1) To ask the user anything, ALWAYS call __ask_user__ (AskUser tool). Do not wait for an external “next message”.
2) Whenever the user provides info for a checklist field, call the corresponding checklist tool (setXxx).
3) After setting a field, you MUST ask the user to confirm it explicitly, then call confirmField. If the user explicitly 
    says one of the methods, you could call confirmField immediately, without asking.
4) After each update or confirmation, call checkStatus and snapshot, and show snapshot to the user (use __say_to_user__).
5) If DATA_PATH is provided, inspect it when needed using __list_directory__ / __read_file__ before confirming DATA_PATH.
   Read ONLY the first few lines.
6) Use CSV preprocessing tools for CSV files. Check for null values. If there is any null value, tell the user
    about it, and ask for the value it should fill in by default.
7) Let the user pick a column to represent the target column. Use __promote_target_column__ to do this. After this,
    update DATA_PATH and let the user reconfirm.
8) Keep looping until checkStatus says ok=true. Then output a final concise summary (and stop calling tools).
9) Ask the user if they want to generate a prediction set. USE __ask_user__. If so, use the tool to achieve that, and set the prediction
    data path in the checklist to the generated prediction set file. You MUST NOT write any imports. Use only provided `np` and `pd`.
    You should create a variable `df` in the end. DO NOT write any network or file access.
10) If the checklist is fully filled, or there are optional fields unfilled and the user requests to stop, call the quit
    command to exit.
""".trimIndent()

    val agent = ai.koog.agents.core.agent.AIAgent<String, String>(
        systemPrompt = systemPrompt,
        promptExecutor = simpleOpenAIExecutor(System.getenv("OPENAI_API_KEY")),
        llmModel = OpenAIModels.Chat.GPT4o,
        toolRegistry = toolRegistry,
        strategy = functionalStrategy { input ->
            // 1) Kick off with tools-only so the model must use AskUser/SayToUser/etc.
            var responses = listOf(requestLLMOnlyCallingTools(input))

            var guard = 0
            val maxSteps = 200

            while (guard++ < maxSteps) {
                // Execute any tool calls
                while (responses.containsToolCalls() || !quitTools.quit) {
                    val pending = extractToolCalls(responses)
                    val results = executeMultipleTools(pending)
                    responses = sendMultipleToolResults(results)
                }

                // If no tool calls in the last response, decide what to do next.
                // Use YOUR checklist as the stop condition.
                val check = checklist.check()
                if (check.ok || quitTools.quit) {
                    return@functionalStrategy checklist.snapshot()
                } else {
                    // Not OK but model produced no tool calls => push it back into tools-only mode.
                    responses = llm.writeSession {
                        appendPrompt {
                            system(
                                "$systemPrompt Current status:\n" +
                                        checklist.snapshot() + ". Remember to look for prediction set and promote target column."
                            )
                        }
                        listOf(requestLLMOnlyCallingTools())
                    }
                }
            }

            "Stopped after $maxSteps steps (safety guard). Current checklist:\n${checklist.snapshot()}"
        }

    )

    val final = agent.run("Start the intake. Ask me questions until the checklist is complete.")
    println("\n=== FINAL AGENT OUTPUT ===\n$final")
    println("\n=== FINAL CHECKLIST SNAPSHOT ===\n${checklist.snapshot()}")
}
