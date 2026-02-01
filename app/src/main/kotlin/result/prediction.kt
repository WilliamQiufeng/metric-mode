package org.example.app.result

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.example.app.core.MlAutoGenCore
import org.example.app.core.PythonRunner
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * prediction.kt
 *
 * Generate predict.py and run it.
 *
 * predict.py MUST:
 * - Load trained_model.json (trained by train_tune.py) using ModelWrapper.load if possible
 * - If load fails, fall back to reading trained_model.json as a minimal artifact (e.g., constant_class)
 * - Run prediction on predictionDataPath
 * - Output predictions.csv + prediction_result.json
 * - Print: __PREDICTION_RESULT__{json}
 */
class PredictionOrchestrator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4oMini
) {
    sealed class PredictionResult {
        data class Success(
            val workDir: String,
            val predictPy: String,
            val stdout: String,
            val parsedJson: JsonObject?
        ) : PredictionResult()

        data class Failed(
            val reason: String,
            val workDir: String,
            val predictPy: String?,
            val lastOutput: String
        ) : PredictionResult()
    }

    suspend fun runPrediction(
        workDir: Path,
        spec: MlAutoGenCore.ChecklistSpec,
        predictionDataPath: String,
        modelArtifactFileName: String = "trained_model.json",
        outFileName: String = "predict.py",
        maxRetries: Int = 2
    ): PredictionResult {
        val modelPyPath = workDir.resolve("model.py")
        require(modelPyPath.exists()) { "model.py not found at: $modelPyPath" }

        val modelPy = modelPyPath.readText()
        val modelPreview = ResultIo.truncateForPrompt(
            ResultIo.smartPreview(modelPy, headChars = 4500, tailChars = 2000),
            maxChars = 8000
        )

        // preview prediction dataset for LLM so it doesn't guess columns
        val predPreview = CsvPreview.preview(predictionDataPath, workDir)

        val pythonRunner = PythonRunner()

        var lastErr: String? = null
        var previousPy: String? = null

        repeat(maxRetries) {
            val py = generatePredictPy(
                spec = spec,
                modelPreview = modelPreview,
                predictionDataPath = predictionDataPath,
                predictionPreview = predPreview,
                modelArtifactFileName = modelArtifactFileName,
                previousPredictPy = previousPy,
                lastError = lastErr
            )

            val predictPath = workDir.resolve(outFileName)
            predictPath.writeText(py)

            val run = pythonRunner.runTest(workDir = workDir, testFileName = outFileName)
            val combinedOut = run.stderr.ifBlank { run.stdout }.ifBlank { run.stdout }

            if (run.exitCode == 0) {
                val parsed = tryParseMarkedJson(combinedOut, "__PREDICTION_RESULT__")
                return PredictionResult.Success(
                    workDir = workDir.toString(),
                    predictPy = py,
                    stdout = combinedOut,
                    parsedJson = parsed
                )
            }

            lastErr = combinedOut
            previousPy = py
        }

        return PredictionResult.Failed(
            reason = "predict.py failed after $maxRetries retries",
            workDir = workDir.toString(),
            predictPy = previousPy,
            lastOutput = lastErr ?: ""
        )
    }

    private suspend fun generatePredictPy(
        spec: MlAutoGenCore.ChecklistSpec,
        modelPreview: String,
        predictionDataPath: String,
        predictionPreview: String?,
        modelArtifactFileName: String,
        previousPredictPy: String?,
        lastError: String?
    ): String {
        val p = prompt("generate-predict-py") {
            system(
                """
You are a strict Python engineer.
Generate ONE FILE named predict.py.

Hard requirements:
1) Output ONLY valid Python code. No markdown fences. No explanations.
2) Dependency-minimal:
   - Prefer Python stdlib + numpy only.
   - Do NOT require pandas.

3) Load model artifact (MUST):
   - Artifact file name: "$modelArtifactFileName" in current working directory.
   - First try:
       from model import ModelWrapper, build_model
       wrapper = ModelWrapper.load("$modelArtifactFileName")
     If that fails:
       - Read "$modelArtifactFileName" as JSON minimal artifact (e.g., constant_class, classes, label_mapping)
       - Implement a fallback predictor using that artifact (constant prediction is acceptable)
   - DO NOT tune or train on prediction dataset.

4) Prediction dataset rules:
   - predictionDataPath may be a directory or a .csv.
   - If directory: prefer iris.csv if present, else first .csv.
   - Must handle headered or headerless CSV.
   - If header exists and includes a label column name in:
     ["label","target","y","class","species","outcome"]
     then IGNORE that column for prediction (drop it).
   - Otherwise, treat all columns as features.
   - Convert features to float safely.

5) Outputs:
   - Write predictions.csv in current working directory:
       row_index,prediction
     If you can decode to label names, optionally add:
       row_index,prediction,prediction_label
   - Write prediction_result.json with a summary.

6) Machine-readable summary:
   - Print EXACTLY one line:
     __PREDICTION_RESULT__{json}
   - The JSON should include:
     status ("ok"/"degraded"),
     prediction_rows (int),
     model_loaded (bool),
     model_path (string),
     predictions_path ("predictions.csv"),
     notes (list of strings)

Write in English comments/strings.
                """.trimIndent()
            )

            user(
                """
Spec context:
- inputType = ${spec.inputType}
- outputType = ${spec.outputType}
- trainingType = ${spec.trainingType}
- train dataPath (for reference only) = ${spec.dataPath}
- predictionDataPath = $predictionDataPath

model.py preview (may be truncated):
$modelPreview
                """.trimIndent()
            )

            if (predictionPreview != null) {
                user(
                    """
Prediction dataset preview:
$predictionPreview
                    """.trimIndent()
                )
            }

            if (lastError != null) {
                user(
                    """
The previous predict.py FAILED with:
$lastError

Fix it and regenerate the FULL predict.py.
                    """.trimIndent()
                )
            }

            if (previousPredictPy != null) {
                user(
                    """
Previous predict.py (for reference):
$previousPredictPy
                    """.trimIndent()
                )
            }
        }

        val responses = executor.execute(prompt = p, model = llmModel)
        val assistant = responses.firstOrNull { it is Message.Assistant } as? Message.Assistant
            ?: error("LLM returned no assistant message")
        return assistant.content.trim()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun tryParseMarkedJson(output: String, marker: String): JsonObject? {
        val line = output.lineSequence().firstOrNull { it.trim().startsWith(marker) } ?: return null
        val jsonPart = line.trim().removePrefix(marker).trim()
        return runCatching { json.parseToJsonElement(jsonPart).jsonObject }.getOrNull()
    }
}