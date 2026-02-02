package org.example.app.result

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.JsonObject
import org.example.app.core.MlAutoGenCore
import org.example.app.core.PythonRunner
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * final_train.kt
 *
 * Goal:
 * - Re-run test.py gate
 * - Load best_params.json (from tune stage)
 * - Train final model on ALL labeled data using best_params
 * - Save trained_model.json + final_train_result.json + marker line
 */
class FinalTrainOrchestrator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4oMini
) {
    sealed class FinalTrainResult {
        data class Success(
            val workDir: String,
            val finalTrainPy: String,
            val stdout: String,
            val parsedJson: JsonObject?
        ) : FinalTrainResult()

        data class Failed(
            val reason: String,
            val workDir: String,
            val finalTrainPy: String?,
            val lastOutput: String
        ) : FinalTrainResult()
    }

    suspend fun runFinalTrain(
        workDir: Path,
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String = MlAutoGenCore.MODEL_API_CONTRACT,
        maxRetries: Int = 3,
        outFileName: String = "final_train.py",
        sanityTestFileName: String = "test.py",
        bestParamsFileName: String = "best_params.json",
        modelArtifactFileName: String = "trained_model.json"
    ): FinalTrainResult {
        val modelPath = workDir.resolve("model.py")
        require(modelPath.exists()) { "model.py not found at: $modelPath" }

        val pythonRunner = PythonRunner()

        // ✅ Gate: verify model.py still passes test.py
        val sanityPath = workDir.resolve(sanityTestFileName)
        if (sanityPath.exists()) {
            val sanity = pythonRunner.runTest(workDir = workDir, testFileName = sanityTestFileName)
            require(sanity.exitCode == 0) {
                "Sanity test ($sanityTestFileName) FAILED. model.py is not safe to final-train.\nOutput:\n${sanity.stdout}"
            }
        }

        // feed best_params preview for LLM
        val bestParamsPath = workDir.resolve(bestParamsFileName)
        require(bestParamsPath.exists()) { "Missing $bestParamsFileName at: $bestParamsPath (run tune first)" }
        val bestParamsPreview = ResultIo.truncateForPrompt(bestParamsPath.readText(), 2000)

        val modelPy = modelPath.readText()
        val modelPreview = ResultIo.truncateForPrompt(
            ResultIo.smartPreview(modelPy, headChars = 6000, tailChars = 2500),
            maxChars = 9000
        )

        val dataPreview = spec.dataPath?.let { CsvPreview.preview(it, workDir) }

        var lastErr: String? = null
        var previousPy: String? = null

        repeat(maxRetries) {
            val py = generateFinalTrainPy(
                spec = spec,
                contract = contract,
                modelPreview = modelPreview,
                dataPreview = dataPreview,
                bestParamsPreview = bestParamsPreview,
                bestParamsFileName = bestParamsFileName,
                modelArtifactFileName = modelArtifactFileName,
                previousPy = previousPy,
                lastError = lastErr
            )

            val path = workDir.resolve(outFileName)
            path.writeText(py)

            val run = pythonRunner.runTest(workDir = workDir, testFileName = outFileName)
            val combinedOut = run.stdout

            if (run.exitCode == 0) {
                val parsed = ResultIo.tryParseMarkedJson(combinedOut, "__FINAL_TRAIN_RESULT__")
                return FinalTrainResult.Success(
                    workDir = workDir.toString(),
                    finalTrainPy = py,
                    stdout = combinedOut,
                    parsedJson = parsed
                )
            }

            lastErr = combinedOut
            previousPy = py
        }

        return FinalTrainResult.Failed(
            reason = "final_train.py failed after $maxRetries retries",
            workDir = workDir.toString(),
            finalTrainPy = previousPy,
            lastOutput = lastErr ?: ""
        )
    }

    private suspend fun generateFinalTrainPy(
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String,
        modelPreview: String,
        dataPreview: String?,
        bestParamsPreview: String,
        bestParamsFileName: String,
        modelArtifactFileName: String,
        previousPy: String?,
        lastError: String?
    ): String {
        val p = prompt("generate-final-train-py") {
            system(
                """
You are a strict Python ML engineer.
Generate ONE FILE named final_train.py.

Hard requirements:
1) Work with existing model.py following contract:
$contract

2) Output ONLY valid Python code. No markdown fences.

3) Must read best params from "$bestParamsFileName" in CWD.
   - best_params.json may include:
       {"best_params": {...}, "label_mapping": {...}}
   - If not present, fall back to empty params, but mark status="degraded".

4) Load labeled dataset from spec.dataPath (same rules as tune):
   - directory or .csv
   - headered/headerless
   - target column by name priority else last column
   - X exclude y; X float
   - y encode; if label_mapping exists in best_params.json, use it.

5) Final training:
   - Train final model on ALL labeled data using best_params.
   - Save model artifact to "$modelArtifactFileName":
       * Prefer wrapper.save("$modelArtifactFileName") if available.
       * If save fails, write a minimal JSON artifact that prediction can use.

6) Outputs:
   - Write final_train_result.json
   - Print EXACTLY:
       __FINAL_TRAIN_RESULT__{json}
     JSON must include:
       status, seed(42), rows, model_path, best_params_path, notes

English comments/strings.
                """.trimIndent()
            )

            user(
                """
Task spec:
- inputType=${spec.inputType}
- outputType=${spec.outputType}
- trainingType=${spec.trainingType}
- metric=${spec.metric}
- dataPath=${spec.dataPath}

best_params.json preview:
$bestParamsPreview

model.py preview:
$modelPreview
                """.trimIndent()
            )

            if (dataPreview != null) user("Dataset preview:\n$dataPreview")

            if (lastError != null) {
                user(
                    """
Previous final_train.py FAILED with:
$lastError

Fix it and regenerate FULL final_train.py.
                    """.trimIndent()
                )
            }

            if (previousPy != null) user("Previous final_train.py (reference):\n$previousPy")
        }

        val responses = executor.execute(prompt = p, model = llmModel)
        val assistant = responses.firstOrNull { it is Message.Assistant } as? Message.Assistant
            ?: error("LLM returned no assistant message")
        return assistant.content.trim()
    }
}