package org.example.app.core

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.structure.StructureFixingParser
import ai.koog.prompt.structure.executeStructured
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import org.example.app.intermediate.*


/**
 * LLM-based dependency resolver + pip installer.
 *
 * Goal:
 * - After each python test run (success/failure), ask LLM to infer required pip packages.
 * - Install missing packages automatically.
 * - (Optionally) re-run tests after installing.
 */
class PythonDependencyManager(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel,
    private val fixerModel: ai.koog.prompt.llm.LLModel
) {
    @Serializable
    data class DependencyPlan(
        val pipPackages: List<String> = emptyList(),
        val shortRationale: String = ""
    )

    data class CmdResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    /**
     * Ask LLM to infer minimal pip deps from code + latest error output.
     * - Must return pip installable names (e.g. "numpy", "pandas", "scikit-learn", "torch", "xgboost")
     * - Must NOT include stdlib modules.
     * - Return empty list if none needed.
     */
    suspend fun proposeDependencies(
        spec: MlAutoGenCore.ChecklistSpec,
        family: ModelFamilyCategory,
        concrete: MlAutoGenCore.ConcreteModelChoice,
        modelPy: String,
        testPy: String,
        lastStdout: String,
        lastStderr: String
    ): DependencyPlan {
        val fixingParser = StructureFixingParser(model = fixerModel, retries = 2)

        val p = prompt("infer-python-deps") {
            system(
                """
You are a senior Python engineer.
Infer the MINIMAL set of pip-installable dependencies required to run the given model.py + test.py successfully.

Rules:
- Output ONLY structured JSON (per schema).
- List must contain pip package spec strings (e.g., "scikit-learn", "numpy", "pandas", "torch", "xgboost").
- Do NOT include Python standard library modules.
- If no extra dependencies are needed, return an empty list.
- Prefer widely available packages; avoid exotic ones unless necessary.
- Do not include shell commands.
                """.trimIndent()
            )
            user(
                """
Task spec:
- inputType=${spec.inputType}
- outputType=${spec.outputType}
- trainingType=${spec.trainingType}
- metric=${spec.metric}
- family=$family
- chosenLibrary=${concrete.library}
- chosenModelId=${concrete.modelId}
- extraDependenciesFromPicker=${concrete.extraDependencies}

model.py:
```python
${modelPy}
```
test.py:
```python
${testPy}
```
Last run stdout:
```code
${lastStdout}
```
Last run stderr:
```code
${lastStderr}
```
            """.trimIndent()
            )
        }

        val result = executor.executeStructured<DependencyPlan>(
            prompt = p,
            model = llmModel,
            fixingParser = fixingParser
        )
        // Safety: cap list length
        val plan = result.getOrThrow().data
        val capped = plan.pipPackages.distinct().take(15)
        return plan.copy(pipPackages = capped)
    }

    /**
     * Install packages via pip (python -m pip install ...).
     * This uses whichever python executable can be found (python3 -> python).
     */
    fun pipInstall(packages: List<String>, workDir: Path): CmdResult {
        if (packages.isEmpty()) return CmdResult(0, "", "")

        val python = detectPythonCommand()
        val cmd = python + listOf("-m", "pip", "install", "--upgrade") + packages

        return runCommand(cmd, workDir)
    }

    private fun detectPythonCommand(): List<String> {
        // Prefer python3 when available
        val candidates = listOf(listOf("python3"), listOf("python"))
        for (c in candidates) {
            val r = runCommand(c + listOf("--version"), Path.of("."))
            if (r.exitCode == 0) return c
        }
        // Last resort (will fail with a clear error)
        return listOf("python3")
    }

    private fun runCommand(cmd: List<String>, workDir: Path): CmdResult {
        val pb = ProcessBuilder(cmd)
            .directory(File(workDir.toString()))
        val p = pb.start()

        val stdoutBytes = p.inputStream.readBytes()
        val stderrBytes = p.errorStream.readBytes()
        val code = p.waitFor()

        val charset = Charset.defaultCharset()
        return CmdResult(
            exitCode = code,
            stdout = stdoutBytes.toString(charset),
            stderr = stderrBytes.toString(charset)
        )
    }

}