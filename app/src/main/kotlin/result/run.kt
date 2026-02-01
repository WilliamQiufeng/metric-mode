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
 * run.kt
 *
 * 1) Use LLM to generate final_test.py (stronger / more "final" validation).
 * 2) Run it; if it fails, feed error back and regenerate final_test.py.
 *
 * Outputs:
 * - workDir/final_test.py
 * - (optional) workDir/final_test_result.json (written by the python final test)
 */
class FinalTestOrchestrator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4oMini
) {
    sealed class FinalTestResult {
        data class Success(
            val workDir: String,
            val finalTestPy: String,
            val stdout: String,
            val parsedJson: JsonObject?
        ) : FinalTestResult()

        data class Failed(
            val reason: String,
            val workDir: String,
            val finalTestPy: String?,
            val lastOutput: String
        ) : FinalTestResult()
    }

    /**
     * Generate & run final_test.py with retries on failure.
     *
     * Assumes model.py already exists and passed the earlier test.py.
     */
    suspend fun runFinalTest(
        workDir: Path,
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String = MlAutoGenCore.MODEL_API_CONTRACT,
        maxRetries: Int = 3,
        outFileName: String = "final_test.py"
    ): FinalTestResult {
        val modelPath = workDir.resolve("model.py")
        require(modelPath.exists()) { "model.py not found at: $modelPath" }

        val modelPy = modelPath.readText()
        val modelPreview = ResultIo.truncateForPrompt(
            ResultIo.smartPreview(modelPy, headChars = 6000, tailChars = 2500),
            maxChars = 9000
        )

        // ✅ NEW: preview dataset for LLM (first 20 lines)
        val dataPreview = spec.dataPath?.let { CsvPreview.preview(it, workDir) }

        val pythonRunner = PythonRunner()

        var lastErr: String? = null
        var previousFinalTest: String? = null

        repeat(maxRetries) {
            val finalTestPy = generateFinalTestPy(
                spec = spec,
                contract = contract,
                modelPreview = modelPreview,
                dataPreview = dataPreview,
                previousFinalTestPy = previousFinalTest,
                lastError = lastErr
            )

            val finalTestPath = workDir.resolve(outFileName)
            finalTestPath.writeText(finalTestPy)

            val run = pythonRunner.runTest(workDir = workDir, testFileName = outFileName)
            val combinedOut = run.stderr.ifBlank { run.stdout }.ifBlank { run.stdout }

            if (run.exitCode == 0) {
                val parsed = ResultIo.tryParseMarkedJson(combinedOut)
                return FinalTestResult.Success(
                    workDir = workDir.toString(),
                    finalTestPy = finalTestPy,
                    stdout = combinedOut,
                    parsedJson = parsed
                )
            }

            lastErr = combinedOut
            previousFinalTest = finalTestPy
        }

        return FinalTestResult.Failed(
            reason = "final_test.py failed after $maxRetries retries",
            workDir = workDir.toString(),
            finalTestPy = previousFinalTest,
            lastOutput = lastErr ?: ""
        )
    }

    private suspend fun generateFinalTestPy(
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String,
        modelPreview: String,
        dataPreview: String?,
        previousFinalTestPy: String?,
        lastError: String?
    ): String {
        val p = prompt("generate-final-test-py") {
            system(
                """
You are a strict Python test engineer.
Generate a SINGLE FILE named final_test.py.

Hard requirements:
1) Validate this contract against model.py:
$contract

2) Output ONLY valid Python code. No markdown fences. No explanations.

3) The test MUST be robust and dependency-minimal:
   - Prefer Python stdlib and numpy only.
   - If numpy is unavailable, fall back to pure Python lists.
   - Do NOT use pandas/sklearn unless strictly necessary.

4) Dataset loading rules (CRITICAL):
   - spec.dataPath may be a directory OR a file path.
   - If directory: search for a .csv inside (prefer iris.csv if present), else pick the first .csv.
   - If file: load it if it's .csv.
   - Must handle BOTH headered and headerless CSV.

   For SUPERVISED classification/regression:
   - If header exists, try target column names in this priority:
     ["label","target","y","class","species","outcome"]
   - If none found OR no header: ALWAYS USE THE LAST COLUMN AS y.
   - NEVER raise an error just because target column name cannot be identified.

5) The test should:
   - import model
   - build_model(), instantiate ModelWrapper
   - call fit(...) when trainingType suggests it is appropriate
   - call predict(...)
   - compute at least one metric consistent with spec.metric when possible
     * For classification: implement accuracy in pure Python/numpy.
     * For regression: implement MSE/MAE in pure Python/numpy.

6) The test must produce machine-readable summary:
   - Print EXACTLY one line starting with: __FINAL_TEST_RESULT__
   - After the marker, print a single-line JSON object, e.g.:
     __FINAL_TEST_RESULT__{"status":"ok","metric":"ACCURACY","value":0.93,"notes":["..."]}
   - Also write the same JSON to final_test_result.json in the current working directory.

If the previous attempt failed, fix the root cause.
Write in English comments/strings.
                """.trimIndent()
            )

            user(
                """
Task spec:
- inputType = ${spec.inputType}
- outputType = ${spec.outputType}
- trainingType = ${spec.trainingType}
- splitStrategy = ${spec.splitStrategy}
- metric = ${spec.metric}
- dataPath = ${spec.dataPath}

model.py preview (may be truncated):
$modelPreview
                """.trimIndent()
            )

            // ✅ NEW: feed dataset preview to avoid guessing columns
            if (dataPreview != null) {
                user(
                    """
Dataset preview (may be truncated):
$dataPreview
                    """.trimIndent()
                )
            }

            if (lastError != null) {
                user(
                    """
The last final_test.py FAILED with this output:
$lastError

Fix it and regenerate the FULL final_test.py.
                    """.trimIndent()
                )
            }

            if (previousFinalTestPy != null) {
                user(
                    """
Previous final_test.py (for reference). You may rewrite completely:
$previousFinalTestPy
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

/**
 * Shared IO + prompt helpers for org.example.app.result package.
 */
internal object ResultIo {
    private val json = Json { ignoreUnknownKeys = true }

    fun truncateForPrompt(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n\n...[TRUNCATED]..."
    }

    /**
     * A "smart" preview: head + tail to preserve imports and main logic.
     */
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

    /**
     * Extract JSON object printed by final_test.py:
     * __FINAL_TEST_RESULT__{...json...}
     */
    fun tryParseMarkedJson(output: String): JsonObject? {
        val marker = "__FINAL_TEST_RESULT__"
        val line = output.lineSequence().firstOrNull { it.trim().startsWith(marker) } ?: return null
        val jsonPart = line.trim().removePrefix(marker).trim()
        return runCatching { json.parseToJsonElement(jsonPart).jsonObject }.getOrNull()
    }
}

/**
 * CSV sniffer: reads the first ~20 lines of the resolved CSV so the LLM can see real structure.
 */
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
        } else {
            existing
        }

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