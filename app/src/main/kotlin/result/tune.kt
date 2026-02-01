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
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * tune.kt
 *
 * Goal:
 * - Always re-run existing test.py first to ensure model.py is still valid.
 * - Generate tune.py:
 *    * Load labeled CSV from spec.dataPath
 *    * TRAIN/VAL split (anti-leakage)
 *    * Tune hyperparameters using VAL metric only
 *    * Output best_params.json + tune_result.json + marker line
 */
class TuneOrchestrator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4oMini
) {
    sealed class TuneResult {
        data class Success(
            val workDir: String,
            val tunePy: String,
            val stdout: String,
            val parsedJson: JsonObject?
        ) : TuneResult()

        data class Failed(
            val reason: String,
            val workDir: String,
            val tunePy: String?,
            val lastOutput: String
        ) : TuneResult()
    }

    suspend fun runTune(
        workDir: Path,
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String = MlAutoGenCore.MODEL_API_CONTRACT,
        maxRetries: Int = 3,
        outFileName: String = "tune.py",
        sanityTestFileName: String = "test.py"
    ): TuneResult {
        val modelPath = workDir.resolve("model.py")
        require(modelPath.exists()) { "model.py not found at: $modelPath" }

        val pythonRunner = PythonRunner()

        // ✅ Gate: re-run already-generated test.py to ensure model.py still satisfies contract
        val sanityPath = workDir.resolve(sanityTestFileName)
        if (sanityPath.exists()) {
            val sanity = pythonRunner.runTest(workDir = workDir, testFileName = sanityTestFileName)
            require(sanity.exitCode == 0) {
                "Sanity test ($sanityTestFileName) FAILED. model.py is not safe to tune.\nOutput:\n${sanity.stdout}"
            }
        }

        val modelPy = modelPath.readText()
        val modelPreview = ResultIo.truncateForPrompt(
            ResultIo.smartPreview(modelPy, headChars = 6000, tailChars = 2500),
            maxChars = 9000
        )

        val dataPreview = spec.dataPath?.let { CsvPreview.preview(it, workDir) }

        var lastErr: String? = null
        var previousPy: String? = null

        repeat(maxRetries) {
            val py = generateTunePy(
                spec = spec,
                contract = contract,
                modelPreview = modelPreview,
                dataPreview = dataPreview,
                previousTunePy = previousPy,
                lastError = lastErr
            )

            val path = workDir.resolve(outFileName)
            path.writeText(py)

            val run = pythonRunner.runTest(workDir = workDir, testFileName = outFileName)
            val combinedOut = run.stdout

            if (run.exitCode == 0) {
                val parsed = ResultIo.tryParseMarkedJson(combinedOut, "__TUNE_RESULT__")
                return TuneResult.Success(
                    workDir = workDir.toString(),
                    tunePy = py,
                    stdout = combinedOut,
                    parsedJson = parsed
                )
            }

            lastErr = combinedOut
            previousPy = py
        }

        return TuneResult.Failed(
            reason = "tune.py failed after $maxRetries retries",
            workDir = workDir.toString(),
            tunePy = previousPy,
            lastOutput = lastErr ?: ""
        )
    }

    private suspend fun generateTunePy(
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String,
        modelPreview: String,
        dataPreview: String?,
        previousTunePy: String?,
        lastError: String?
    ): String {
        val p = prompt("generate-tune-py") {
            system(
                """
You are a strict Python ML engineer.
Generate a SINGLE FILE named tune.py.

Hard requirements:
1) It must work with the existing model.py that follows this contract:
$contract

2) Output ONLY valid Python code. No markdown fences. No explanations.

3) Dependencies:
   - Prefer Python stdlib + numpy + pandas + json only.
   - sklearn is optional; if unavailable, implement split/metric in numpy.

4) Dataset loading (CRITICAL):
   - spec.dataPath may be directory or .csv file.
   - If directory: prefer iris.csv, else first .csv.
   - Handle headered and headerless CSV.
   - For supervised tasks: if header exists, prefer target column names:
     ["label","target","y","class","species","outcome"]
     else ALWAYS use last column as y.
   - X must exclude y column. Convert X to float safely, dropping all non-numeric columns using select_dtypes(include=["number"])
   - If y is string labels: encode to integers and save mapping.

5) Tuning (MUST):
   - Split labeled data into TRAIN/VAL with seed=42.
   - Metric MUST be computed on VAL only (anti-leakage).
   - Compute overlap_rows between train and val (exact row match, include y).
   - Attempt to tune by trying a small set of candidate configs (10-25):
       * Always include {} and {"random_state":42} if accepted.
       * If XGBoost-like keys are accepted, include:
         n_estimators, max_depth, learning_rate, subsample, colsample_bytree
       * You MUST robustly handle invalid config keys:
         - If build_model(config) raises due to invalid keys, skip that config (do not crash).
   - Choose best_params by VAL metric.

6) Outputs:
   - Write best_params.json (include best_params dict + label_mapping if any).
   - Write tune_result.json summary.
   - Print EXACTLY one line:
       __TUNE_RESULT__{json}
     JSON must include:
       status ("ok"/"degraded"),
       metric,
       val_value,
       seed,
       train_rows,
       val_rows,
       overlap_rows,
       best_params_path ("best_params.json"),
       notes (list)

English comments/strings.
                """.trimIndent()
            )

            user(
                """
Task spec:
- inputType=${spec.inputType}
- outputType=${spec.outputType}
- trainingType=${spec.trainingType}
- splitStrategy=${spec.splitStrategy}
- metric=${spec.metric}
- dataPath=${spec.dataPath}

model.py preview:
$modelPreview
                """.trimIndent()
            )

            if (dataPreview != null) {
                user("Dataset preview:\n$dataPreview")
            }

            if (lastError != null) {
                user(
                    """
Previous tune.py FAILED with:
$lastError

Fix it and regenerate FULL tune.py.
                    """.trimIndent()
                )
            }

            if (previousTunePy != null) {
                user(
                    """
Previous tune.py (reference):
$previousTunePy
                    """.trimIndent()
                )
            }
        }

        val responses = executor.execute(prompt = p, model = llmModel)
        val assistant = responses.firstOrNull { it is Message.Assistant } as? Message.Assistant
            ?: error("LLM returned no assistant message")
        return assistant.content.trim()
    }
}

/** Shared helpers (module-wide) */
internal object ResultIo {
    private val json = Json { ignoreUnknownKeys = true }

    fun truncateForPrompt(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "\n\n...[TRUNCATED]..."

    fun smartPreview(text: String, headChars: Int, tailChars: Int): String {
        if (text.length <= headChars + tailChars + 50) return text
        val head = text.take(headChars)
        val tail = text.takeLast(tailChars)
        return buildString {
            appendLine(head)
            appendLine()
            appendLine("...[MIDDLE TRUNCATED]...")
            appendLine()
            appendLine(tail)
        }
    }

    fun tryParseMarkedJson(output: String, marker: String): JsonObject? {
        val line = output.lineSequence().firstOrNull { it.trim().startsWith(marker) } ?: return null
        val jsonPart = line.trim().removePrefix(marker).trim()
        return runCatching { json.parseToJsonElement(jsonPart).jsonObject }.getOrNull()
    }
}

internal object CsvPreview {
    fun preview(dataPathRaw: String, workDir: Path): String? {
        if (dataPathRaw.isBlank()) return null

        val candidates = listOf(
            runCatching { Path.of(dataPathRaw) }.getOrNull(),
            workDir.resolve(dataPathRaw),
            workDir.parent?.resolve(dataPathRaw)
        ).filterNotNull()

        val existing = candidates.firstOrNull { it.exists() } ?: return null

        val csvPath: Path? = if (existing.isDirectory()) {
            val files = existing.toFile()
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
                .orEmpty()
            files.firstOrNull { it.name.equals("iris.csv", ignoreCase = true) }?.toPath()
                ?: files.firstOrNull()?.toPath()
        } else existing

        if (csvPath == null || !csvPath.exists()) return null
        if (!csvPath.toString().endsWith(".csv", ignoreCase = true)) return null

        val lines = runCatching { csvPath.readLines().take(20) }.getOrNull() ?: return null
        return buildString {
            appendLine("Resolved CSV: $csvPath")
            appendLine("First 20 lines (each line truncated to 240 chars):")
            lines.forEach { appendLine(it.take(240)) }
        }.trim()
    }
}