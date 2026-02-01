package org.example.app.core

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.writeText

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
1) The test MUST validate this contract:
$contract

2) Output ONLY valid Python code. No markdown fences. No explanations.
3) The test should:
   - import model.py
   - instantiate build_model()
   - create dummy input X matching inputType
   - call fit(...) (if appropriate) and predict(...)
   - verify predict output type/shape is sensible for outputType
   - exit code 0 on success; non-zero on failure (raise AssertionError or SystemExit(1))

Task spec:
- inputType = ${spec.inputType}
- outputType = ${spec.outputType}
- trainingType = ${spec.trainingType}
- metric = ${spec.metric}
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
            .redirectErrorStream(true) // ✅ 关键：stderr 合并到 stdout，避免死锁
        pb.environment()["PYTHONUNBUFFERED"] = "1"

        val process = pb.start()
        val combined = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exit = process.waitFor()

        // stderr 留空即可（已合并）
        return PythonRunResult(exitCode = exit, stdout = combined, stderr = "")
    }
}