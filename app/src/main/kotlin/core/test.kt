package org.example.app.core

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * test.kt:
 * 1) Ask LLM to generate test.py that validates MODEL_API_CONTRACT and basic runtime.
 * 2) Run python to execute test.py.
 * 3) Return stdout/stderr/exitCode to core, so core can feed errors back to model generator.
 */
class TestGenerator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel
) {
    suspend fun generateTestPy(
        spec: MlAutoGenCore.ChecklistSpec,
        contract: String
    ): String {
        val p = prompt("generate-test-py") {
            system(
                """
You are a strict Python test engineer.
Generate a SINGLE FILE named test.py.

Hard requirements:
1) The test MUST validate this contract (treat it as source-of-truth):
$contract

2) Output ONLY valid Python code. No markdown fences. No explanations.

3) The test MUST:
   - import model.py
   - call build_model(config) and verify it returns a ModelWrapper
   - run an end-to-end smoke test: fit(...) (if appropriate) then predict(...)
   - validate that predict output is sensible for the declared outputType
   - exit code 0 on success; non-zero on failure

Additionally MUST test serialization:
   - wrapper.save("tmp_model.json") then ModelWrapper.load("tmp_model.json")
   - loaded model can predict with correct length/type
If save/load are missing, fail with a clear AssertionError message.

Data handling rules (VERY IMPORTANT):
- Prefer using the real dataset at data_path if it exists.
  * If data_path is a directory: look for the first *.csv inside.
  * If data_path is a file: use it directly.
  * Read CSV using ONLY Python stdlib (csv) + numpy. Do NOT require pandas.
  * If a header exists: choose y column by name preference (case-insensitive):
      ["target", "label", "y", "class", "species"]
    Otherwise, if the last column is non-numeric, use it as y.
  * X must be a numeric numpy.ndarray of shape (n_samples, n_features).
  * y must be a 1D array-like of length n_samples when supervised.
  * For classification, if y is string labels, map them to ints 0..K-1.
- If dataset cannot be loaded, fall back to synthetic data appropriate for inputType/outputType.

Output validation rules:
- Classification: accept predictions as numpy array/list of length n_samples.
  * If predictions are numeric: ensure they are finite, integer-like, and within the set of known labels if available.
  * If predictions are strings: ensure they are within the set of known labels.
- Regression: predictions must be numeric (float/int), finite, length n_samples.

Robustness & debuggability:
- Set numpy random seed.
- On any exception, print full traceback.
- If serialization fails, print diagnostics about tmp_model.json (file size + first 200 bytes/chars if readable).
- Always clean up tmp_model.json on success.

Task spec:
- inputType = ${spec.inputType}
- outputType = ${spec.outputType}
- trainingType = ${spec.trainingType}
- splitStrategy = ${spec.splitStrategy}
- metric = ${spec.metric}
- dataPath = ${spec.dataPath}
                """.trimIndent()
            )
        }

        val responses = executor.execute(prompt = p, model = llmModel)
        val assistant = responses.firstOrNull { it is Message.Assistant } as? Message.Assistant
            ?: error("LLM returned no assistant message")
        return assistant.content.trim()
    }
}

data class PythonRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

class PythonRunner {
    fun runTest(workDir: Path, testFileName: String): PythonRunResult {
        val candidates = listOf("python3", "python")

        var lastErr: Exception? = null
        for (cmd in candidates) {
            try {
                return runProcess(
                    command = listOf(cmd, "-u", testFileName), // -u: unbuffered，保证 print/traceback 立即刷出
                    workDir = workDir
                )
            } catch (e: Exception) {
                lastErr = e
            }
        }

        return PythonRunResult(
            exitCode = 127,
            stdout = "",
            stderr = "Failed to run python (tried python3/python). Last error: ${lastErr?.message}"
        )
    }

    private fun runProcess(command: List<String>, workDir: Path): PythonRunResult {
        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true) // ✅ stderr 合并到 stdout，避免死锁
        pb.environment()["PYTHONUNBUFFERED"] = "1"

        val process = pb.start()
        val combined = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exit = process.waitFor()

        // stderr 留空即可（已合并）
        return PythonRunResult(exitCode = exit, stdout = combined, stderr = "")
    }
}