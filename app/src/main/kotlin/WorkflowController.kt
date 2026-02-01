package org.example.app

import ai.koog.agents.core.agent.AIAgent
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
import org.example.app.core.MlAutoGenCore
import org.example.app.core.PipelineResult
import org.example.app.core.pickBest
import org.example.app.core.readMetricValue
import org.example.app.intermediate.ModelAutoGenChecklist
import org.example.app.result.*
import org.example.app.user.ChecklistTools
import org.example.app.user.CsvPreprocessTools
import org.example.app.user.PythonDataGenTools
import org.example.app.user.QuitTools
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * WorkflowController orchestrates the entire ML pipeline workflow:
 * 1. User interaction to build checklist
 * 2. Core model generation (model.py and test.py)
 * 3. Result pipeline (tune -> final_train -> prediction -> explanation -> report)
 */
class WorkflowController(
    private val workDir: Path = Paths.get("generated_ml"),
    private val apiKey: String = System.getenv("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY env var")
) {
    /**
     * Extension function to convert ModelAutoGenChecklist to MlAutoGenCore.ChecklistLike
     */
    private fun ModelAutoGenChecklist.asCoreChecklistLike(): MlAutoGenCore.ChecklistLike =
        object : MlAutoGenCore.ChecklistLike {
            override fun check(): Boolean = this@asCoreChecklistLike.check().ok
            override fun snapshot(): String = this@asCoreChecklistLike.snapshot()
        }

    /**
     * Extract prediction data path from checklist snapshot
     */
    private fun extractPredictionDataPath(snapshot: String): String? {
        val regex = Regex("""Prediction data path.*?:\s*(.+?)\s*\([^()]*\)""", RegexOption.MULTILINE)
        val match = regex.find(snapshot)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
    }

    /**
     * Run the complete workflow
     */
    suspend fun run(): WorkflowResult {
        println("=== Starting ML Pipeline Workflow ===\n")

        // Step 1: User interaction to build checklist
        println("Step 1: Building checklist through user interaction...")
        val checklist = buildChecklist()
        
        val checkResult = checklist.check()
        if (!checkResult.ok) {
            return WorkflowResult.Failed(
                stage = "Checklist",
                reason = "Checklist is not complete. Missing: ${checkResult.missing}, Unconfirmed: ${checkResult.unconfirmed}",
                checklistSnapshot = checklist.snapshot()
            )
        }

        println("\n✓ Checklist is complete!")
        println(checklist.snapshot())

        // Step 2: Core model generation
        println("\n=== Step 2: Generating model.py and test.py ===")
        val executor = simpleOpenAIExecutor(apiKey)
        executor.use {
            val core = MlAutoGenCore(executor = it)
            val coreChecklist = checklist.asCoreChecklistLike()
            
            val coreResult = core.run(
                checklist = coreChecklist,
                workDir = workDir,
                maxModelRetries = 5
            )

            when (coreResult) {
                is PipelineResult.Multi -> {
                    println("=== Pipeline finished: ${coreResult.outcomes.size} candidates ===")

                    coreResult.outcomes.forEach { o ->
                        val tag = "candidate#${o.index} ${o.concrete.library}/${o.concrete.modelId}"
                        when (val r = o.result) {
                            is MlAutoGenCore.CandidateResult.Success -> {
                                val mv = readMetricValue(o.workDir)
                                println("✅ $tag SUCCESS dir=${o.workDir} metric=${mv ?: "NA"}")
                                println("stdout:\n${r.stdout}")
                                if (r.stderr.isNotBlank()) println("stderr:\n${r.stderr}")
                            }
                            is MlAutoGenCore.CandidateResult.Failed -> {
                                println("❌ $tag FAILED dir=${o.workDir}")
                                println("reason=${r.reason}")
                                if (!r.lastError.isNullOrBlank()) println("LastError:\n${r.lastError}")
                            }
                        }
                        println("------------------------------------------------------------")
                    }

                    val best = pickBest(coreResult.outcomes)
                    val spec = core.specFromSnapshot(checklist.snapshot())
                    val predictionDataPath = extractPredictionDataPath(checklist.snapshot())
                    if (best == null) {
                        error("❌ All candidates failed.")
                    } else {
                        val mv = readMetricValue(best.workDir)
                        println("🏆 BEST = candidate#${best.index} ${best.concrete.library}/${best.concrete.modelId} dir=${best.workDir} metric=${mv ?: "NA"}")
                        return runResultPipeline(
                            executor = it,
                            workDir = Paths.get(best.workDir),
                            spec = spec,
                            family = best.family,
                            concrete = best.concrete,
                            predictionDataPath = predictionDataPath
                        )
                    }
                }
                is PipelineResult.Failed -> {
                    return WorkflowResult.Failed(
                        stage = "Core Model Generation",
                        reason = coreResult.reason,
                        checklistSnapshot = coreResult.checklistSnapshot,
                        lastError = coreResult.lastError
                    )
                }
            }
        }
    }

    /**
     * Step 1: Build checklist through user interaction
     */
    private suspend fun buildChecklist(): ModelAutoGenChecklist {
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
//            tools(quitTools)
        }

        val systemPrompt = """
You are an interactive intake assistant for building a model to describe data.
The user does not know much about machine learning, so you will have to figure out the type of model to use through
conversation. Listen to and guide the user describing their expectations. Ask anything needed. 
Fill in a checklist while you listen. Ask for data files like CSV. Read through the first few lines to know more about
 it, and use tools given to check any null values and propose to fill in a default value, and also promote a column as the
 target column to train for. After promotion, set the data path using setDataPath to point to the csv after promotion.
 Do not call setDataPath until you have done all the steps above.
 Ask to generate a prediction set with generateCsvFromPython. The prediction set should contain random data with 
 the columns from the data file, but with the promoted column deleted (as well as target column).
 Use the tool given to achieve that, and set the prediction data path in the checklist to the generated prediction set file.
 You MUST NOT write any imports. Use only provided `np` and `pd`.

Hard rules:
1) To ask the user anything, ALWAYS call __ask_user__ (AskUser tool). Do not wait for an external "next message".
2) Whenever the user provides info for a checklist field, call the corresponding checklist tool (setXxx).
3) Use __say_to_user__ to simply give information to the user.
4) If DATA_PATH is provided, inspect it when needed using __list_directory__ / __read_file__ before confirming DATA_PATH.
   Read ONLY the first few lines.
5) Keep looping until checkStatus says ok=true. Then output a final concise summary (and stop calling tools).
""".trimIndent()

        val executor = simpleOpenAIExecutor(apiKey)
        executor.use {
            val agent = AIAgent<String, String>(
                systemPrompt = systemPrompt,
                promptExecutor = it,
                llmModel = OpenAIModels.Chat.GPT4o,
                toolRegistry = toolRegistry,
                strategy = functionalStrategy { input ->
                    // 1) Kick off with tools-only so the model must use AskUser/SayToUser/etc.
                    var responses = listOf(requestLLMOnlyCallingTools(input))

                    var guard = 0
                    val maxSteps = 200

                    while (guard++ < maxSteps) {
                        // Execute any tool calls
                        while (responses.containsToolCalls()) {
                            val pending = extractToolCalls(responses)
                            val results = executeMultipleTools(pending)
                            responses = sendMultipleToolResults(results)
                        }

                        // If no tool calls in the last response, decide what to do next.
                        // Use YOUR checklist as the stop condition.
                        val check = checklist.check()
                        if (check.ok) {
                            return@functionalStrategy checklist.snapshot()
                        }
                        // If checklist is not complete, the agent will continue processing
                        // The functional strategy will handle the continuation naturally
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

                    "Stopped after $maxSteps steps (safety guard). Current checklist:\n${checklist.snapshot()}"
                }
            )

            val final = agent.run("Start the intake. Ask me questions until the checklist is complete.")
            println("\n=== FINAL AGENT OUTPUT ===\n$final")
            println("\n=== FINAL CHECKLIST SNAPSHOT ===\n${checklist.snapshot()}")
        }

        return checklist
    }

    /**
     * Step 3: Run the result pipeline (tune -> final_train -> prediction -> explanation -> report)
     */
    private suspend fun runResultPipeline(
        executor: ai.koog.prompt.executor.model.PromptExecutor,
        workDir: Path,
        spec: MlAutoGenCore.ChecklistSpec,
        family: MlAutoGenCore.ModelFamilyPick,
        concrete: MlAutoGenCore.ConcreteModelChoice,
        predictionDataPath: String?
    ): WorkflowResult {
        val llmModel = OpenAIModels.Chat.GPT4oMini
        val fixerModel = OpenAIModels.Chat.GPT4o

        // 3.1: Tune
        println("\n3.1: Running tune...")
        val tuneOrchestrator = TuneOrchestrator(executor, llmModel)
        val tuneResult = tuneOrchestrator.runTune(
            workDir = workDir,
            spec = spec,
            maxRetries = 3
        )

        when (tuneResult) {
            is TuneOrchestrator.TuneResult.Success -> {
                println("✓ Tune successful!")
            }
            is TuneOrchestrator.TuneResult.Failed -> {
                return WorkflowResult.Failed(
                    stage = "Tune",
                    reason = tuneResult.reason,
                    lastError = tuneResult.lastOutput
                )
            }
        }

        // 3.2: Final Train
        println("\n3.2: Running final train...")
        val finalTrainOrchestrator = FinalTrainOrchestrator(executor, llmModel)
        val finalTrainResult = finalTrainOrchestrator.runFinalTrain(
            workDir = workDir,
            spec = spec,
            maxRetries = 3
        )

        when (finalTrainResult) {
            is FinalTrainOrchestrator.FinalTrainResult.Success -> {
                println("✓ Final train successful!")
            }
            is FinalTrainOrchestrator.FinalTrainResult.Failed -> {
                return WorkflowResult.Failed(
                    stage = "Final Train",
                    reason = finalTrainResult.reason,
                    lastError = finalTrainResult.lastOutput
                )
            }
        }

        // 3.3: Prediction (if prediction data path is available)
        var predictionResult: PredictionOrchestrator.PredictionResult? = null
        if (predictionDataPath != null) {
            println("\n3.3: Running prediction...")
            val predictionOrchestrator = PredictionOrchestrator(executor, llmModel)
            predictionResult = predictionOrchestrator.runPrediction(
                workDir = workDir,
                spec = spec,
                predictionDataPath = predictionDataPath,
                maxRetries = 2
            )

            when (predictionResult) {
                is PredictionOrchestrator.PredictionResult.Success -> {
                    println("✓ Prediction successful!")
                }
                is PredictionOrchestrator.PredictionResult.Failed -> {
                    println("⚠ Prediction failed: ${predictionResult.reason}")
                    // Continue with explanation and report even if prediction fails
                }
            }
        } else {
            println("\n3.3: Skipping prediction (no prediction data path provided)")
        }

        // 3.4: Explanation
        println("\n3.4: Generating explanation...")
        val explanationGenerator = ExplanationGenerator(executor, llmModel, fixerModel)
        val explanationPath = explanationGenerator.generate(
            workDir = workDir,
            spec = spec,
            family = family.family,
            concrete = concrete
        )
        println("✓ Explanation generated at: $explanationPath")

        // 3.5: Report
        println("\n3.5: Generating report...")
        val reportGenerator = ReportGenerator(executor, llmModel, fixerModel)
        val reportArtifacts = reportGenerator.generatePipelineReport(
            workDir = workDir,
            spec = spec,
            tuneStdout = (tuneResult as? TuneOrchestrator.TuneResult.Success)?.stdout,
            tuneJson = (tuneResult as? TuneOrchestrator.TuneResult.Success)?.parsedJson,
            finalTrainStdout = (finalTrainResult as? FinalTrainOrchestrator.FinalTrainResult.Success)?.stdout,
            finalTrainJson = (finalTrainResult as? FinalTrainOrchestrator.FinalTrainResult.Success)?.parsedJson,
            predictionStdout = (predictionResult as? PredictionOrchestrator.PredictionResult.Success)?.stdout,
            predictionJson = (predictionResult as? PredictionOrchestrator.PredictionResult.Success)?.parsedJson
        )
        println("✓ Report generated:")
        println("  - Markdown: ${reportArtifacts.markdownPath}")
        println("  - PDF: ${reportArtifacts.pdfPath}")

        return WorkflowResult.Success(
            workDir = workDir.toString(),
            explanationPath = explanationPath.toString(),
            reportMarkdownPath = reportArtifacts.markdownPath.toString(),
            reportPdfPath = reportArtifacts.pdfPath.toString()
        )
    }

    /**
     * Result of the workflow execution
     */
    sealed class WorkflowResult {
        data class Success(
            val workDir: String,
            val explanationPath: String,
            val reportMarkdownPath: String,
            val reportPdfPath: String
        ) : WorkflowResult()

        data class Failed(
            val stage: String,
            val reason: String,
            val checklistSnapshot: String? = null,
            val lastError: String? = null
        ) : WorkflowResult()
    }
}

/**
 * Main entry point for the workflow controller
 */
fun main() = runBlocking {
    val controller = WorkflowController()
    val result = controller.run()

    when (result) {
        is WorkflowController.WorkflowResult.Success -> {
            println("\n=== Workflow Completed Successfully ===")
            println("Work directory: ${result.workDir}")
            println("Explanation: ${result.explanationPath}")
            println("Report (MD): ${result.reportMarkdownPath}")
            println("Report (PDF): ${result.reportPdfPath}")
        }
        is WorkflowController.WorkflowResult.Failed -> {
            println("\n=== Workflow Failed ===")
            println("Stage: ${result.stage}")
            println("Reason: ${result.reason}")
            if (result.lastError != null) {
                println("Last Error: ${result.lastError}")
            }
            if (result.checklistSnapshot != null) {
                println("\nChecklist Snapshot:\n${result.checklistSnapshot}")
            }
            System.exit(1)
        }
    }
}
