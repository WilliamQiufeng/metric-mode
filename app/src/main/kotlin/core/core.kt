package org.example.app.core

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import ai.koog.prompt.structure.StructureFixingParser
import ai.koog.prompt.structure.executeStructured
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.app.intermediate.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Core orchestrator:
 * 1) Validate checklist is fully confirmed.
 * 2) Derive ChecklistSpec from checklist.snapshot() (no need to modify checklist class).
 * 3) Ask LLM to pick a model family category (cluster category only).
 * 4) Ask LLM to pick 3 concrete models under that family (library + model id).
 * 5) For each model (in its own directory):
 *    - generate model.py and test.py
 *    - execute test.py; if failed, triage TEST vs MODEL:
 *        - if TEST: fix test.py and retry (same model.py)
 *        - else: feed error back to regenerate model.py and retry
 *
 * Diff:
 * - Only keep diffs (what added/removed) in each candidateDir/diff.log (+ console preview).
 */
class MlAutoGenCore(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4oMini,
    private val fixerModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4o
) {
    companion object {
        const val MODEL_API_CONTRACT: String = """
- File: model.py
- Must define:
  1) class ModelWrapper:
       - __init__(self, config: dict | None = None)
       - fit(self, X, y=None) -> self
       - predict(self, X)
       - (optional) save(self, path: str)
       - (optional) @classmethod load(cls, path: str) -> "ModelWrapper"
  2) def build_model(config: dict | None = None) -> ModelWrapper

Notes:
- X can be numpy arrays / torch tensors / list[str] depending on input type.
- predict(...) should return a sensible output for the declared output type.
"""
    }

    interface ChecklistLike {
        fun check(): Boolean
        fun snapshot(): String
    }

    // ------------------------- metric parsing / selection -------------------------

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class MetricReport(
        val metric: String,
        val value: Double,
        val n_train: Int? = null,
        val n_test: Int? = null,
        val note: String? = null
    )

    private fun metricHigherIsBetter(metric: Metric): Boolean {
        val m = metric.name.uppercase()
        // loss/error metrics -> lower is better
        if (m.contains("MSE") || m.contains("RMSE") || m.contains("MAE") || m.contains("LOSS") || m.contains("ERROR")) return false
        // otherwise: accuracy/f1/r2/silhouette etc -> higher is better
        return true
    }

    private fun readMetricFromDir(dir: Path): MetricReport? {
        val candidates = listOf(
            dir.resolve("metric.json"),
            dir.resolve("metrics.json") // tolerant to "metrics.json" typo
        )
        val p = candidates.firstOrNull { Files.exists(it) } ?: return null
        val raw = runCatching { p.readText() }.getOrNull() ?: return null
        return runCatching { json.decodeFromString<MetricReport>(raw) }.getOrNull()
    }

    private fun extractMetricFromStdout(stdout: String): MetricReport? {
        val re = Regex("""^__METRIC_JSON__=(\{.*\})\s*$""", setOf(RegexOption.MULTILINE))
        val m = re.find(stdout) ?: return null
        val jsonStr = m.groupValues[1]
        return runCatching { json.decodeFromString<MetricReport>(jsonStr) }.getOrNull()
    }

    // ------------------------- LLM structured picks -------------------------

    @Serializable
    data class ModelFamilyPick(
        val family: ModelFamilyCategory,
        val shortRationale: String
    )

    @Serializable
    data class ConcreteModelChoice(
        val library: String,
        val modelId: String,
        val extraDependencies: List<String> = emptyList(),
        val shortRationale: String
    )

    @Serializable
    data class ConcreteModelPickList(
        val choices: List<ConcreteModelChoice>
    )

    data class ChecklistSpec(
        val inputType: InputType,
        val outputType: OutputType,
        val trainingType: TrainingType,
        val splitStrategy: SplitStrategy,
        val metric: Metric,
        val dataPath: String
    )

    fun specFromSnapshot(snapshot: String): ChecklistSpec {
        fun findValue(label: String): String {
            val regex = Regex("""^\s*$label\s*:\s*(.+?)\s*$""", RegexOption.MULTILINE)
            val m = regex.find(snapshot) ?: error("Cannot find '$label' in checklist.snapshot()")
            return m.groupValues[1].trim()
        }

        fun stripTrailingStatus(raw: String): String =
            raw.replace(Regex("""\s*\([^()]*\)\s*$"""), "").trim()

        fun enumName(raw: String): String {
            val v = stripTrailingStatus(raw)
            val idx = v.indexOf('(')
            return if (idx >= 0) v.substring(0, idx).trim() else v
        }

        val inputType = InputType.valueOf(enumName(findValue("Input type")))
        val outputType = OutputType.valueOf(enumName(findValue("Output type")))
        val trainingType = TrainingType.valueOf(enumName(findValue("Training type")))
        val splitStrategy = SplitStrategy.valueOf(enumName(findValue("Split strategy")))
        val metric = Metric.valueOf(enumName(findValue("Metric")))
        val dataPath = stripTrailingStatus(findValue("Data path"))

        return ChecklistSpec(
            inputType = inputType,
            outputType = outputType,
            trainingType = trainingType,
            splitStrategy = splitStrategy,
            metric = metric,
            dataPath = dataPath
        )
    }

    suspend fun pickModelFamily(spec: ChecklistSpec): ModelFamilyPick {
        val fixingParser = StructureFixingParser(model = fixerModel, retries = 2)

        val p = prompt("pick-model-family") {
            system(
                """
You are a pragmatic ML architect.
Pick ONLY ONE model family category from the given enum based on the task spec.
Keep rationale short (1-2 sentences). No extra text outside structured output.
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

Candidates:
- LINEAR_BASELINE
- TREE_BOOSTING
- CNN_VISION
- TRANSFORMER_TEXT
- SEQUENCE_MODEL
- CLUSTERING_UNSUPERVISED
- RL_POLICY
                """.trimIndent()
            )
        }

        val result = executor.executeStructured<ModelFamilyPick>(
            prompt = p,
            model = llmModel,
            fixingParser = fixingParser
        )
        return result.getOrThrow().data
    }

    /**
     * pick EXACTLY 3 concrete models under the picked family.
     */
    suspend fun pickConcreteModels(spec: ChecklistSpec, family: ModelFamilyCategory): List<ConcreteModelChoice> {
        val fixingParser = StructureFixingParser(model = fixerModel, retries = 2)

        val p = prompt("pick-3-concrete-models") {
            system(
                """
You are a senior ML engineer.
Choose EXACTLY 3 different concrete model implementations under the given family.

Hard rules:
- Output ONLY structured JSON per schema: { "choices": [ {..}, {..}, {..} ] }
- choices length MUST be 3.
- Each (library, modelId) must be distinct.
- Prefer widely available libraries; avoid exotic dependencies unless necessary.
- extraDependencies must be pip-installable strings (e.g., "xgboost", "torch").

No extra text outside structured output.
                """.trimIndent()
            )
            user(
                """
Task spec:
- inputType = ${spec.inputType}
- outputType = ${spec.outputType}
- trainingType = ${spec.trainingType}
- metric = ${spec.metric}
- family = $family

Examples:
- library="sklearn", modelId="LogisticRegression"
- library="sklearn", modelId="RandomForestClassifier"
- library="xgboost", modelId="XGBClassifier"
- library="torch", modelId="small_mlp"

Now return 3 choices.
                """.trimIndent()
            )
        }

        val result = executor.executeStructured<ConcreteModelPickList>(
            prompt = p,
            model = llmModel,
            fixingParser = fixingParser
        )
        val list = result.getOrThrow().data.choices

        if (list.size != 3) error("LLM must return exactly 3 model choices, got ${list.size}")
        val distinct = list.map { it.library.trim() + "::" + it.modelId.trim() }.distinct()
        if (distinct.size != 3) error("LLM must return 3 distinct (library, modelId) pairs.")
        return list
    }

    // ------------------------- triage + test-fix -------------------------

    @Serializable
    enum class Culprit { TEST, MODEL, UNKNOWN }

    @Serializable
    data class FailureTriage(
        val culprit: Culprit,
        val confidence: Double,
        val rationale: String
    )

    private suspend fun triageFailure(
        spec: ChecklistSpec,
        contract: String,
        modelPy: String,
        testPy: String,
        lastOutput: String
    ): FailureTriage {
        val fixingParser = StructureFixingParser(model = fixerModel, retries = 2)

        val p = prompt("triage-test-failure") {
            system(
                """
You are a senior ML infra engineer.
Decide whether the failure is caused primarily by:
- TEST: test.py is wrong/brittle
- MODEL: model.py violates the contract or crashes / produces invalid outputs
- UNKNOWN: cannot tell

Rules:
- If traceback points to test.py logic => TEST
- If traceback points to model.py import/runtime, missing methods, wrong shapes/types => MODEL
- If missing deps is the root cause => MODEL (not TEST)
Return ONLY structured output.
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

Contract:
$contract

model.py:
$modelPy

test.py:
$testPy

Last run output (stdout/stderr merged):
$lastOutput
                """.trimIndent()
            )
        }

        val result = executor.executeStructured<FailureTriage>(
            prompt = p,
            model = llmModel,
            fixingParser = fixingParser
        )
        return result.getOrThrow().data
    }

    private suspend fun fixTestPyViaLLM(
        spec: ChecklistSpec,
        contract: String,
        previousTestPy: String,
        currentModelPy: String,
        lastOutput: String
    ): String {
        val p = prompt("fix-test-py") {
            system(
                """
You are a strict Python test engineer.
Produce a FIXED, SINGLE FILE test.py that still:
- validates the contract
- runs fit/predict smoke test
- tests serialization save/load
- writes metric.json (or metrics.json)
- prints __METRIC_JSON__=<single-line json> on success
- uses only stdlib + numpy (no pandas/sklearn)

Output ONLY valid Python code. No markdown fences. No explanations.
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

Contract:
$contract

=== model.py ===
$currentModelPy

=== previous test.py ===
$previousTestPy

=== last run output ===
$lastOutput
                """.trimIndent()
            )
        }

        val responses = executor.execute(prompt = p, model = llmModel)
        val assistant = responses.firstOrNull { it is Message.Assistant } as? Message.Assistant
            ?: error("LLM returned no assistant message")
        return assistant.content.trim()
    }

    // ------------------------- DIFF helpers -------------------------

    private data class DiffResult(
        val label: String,
        val added: Int,
        val removed: Int,
        val unified: String
    )

    private fun computeUnifiedDiff(
        workDir: Path,
        label: String,
        oldText: String?,
        newText: String
    ): DiffResult? {
        if (oldText == null) return null
        if (oldText == newText) return DiffResult(label, 0, 0, "")

        val oldFile = Files.createTempFile(workDir, "old_", ".txt")
        val newFile = Files.createTempFile(workDir, "new_", ".txt")
        try {
            oldFile.writeText(oldText)
            newFile.writeText(newText)

            val pb = ProcessBuilder(listOf("diff", "-u", oldFile.toString(), newFile.toString()))
                .directory(workDir.toFile())
                .redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()

            val unified = when (code) {
                0, 1 -> out
                else -> {
                    buildString {
                        appendLine("diff command failed (exit=$code).")
                        appendLine("oldLines=${oldText.lines().size}, newLines=${newText.lines().size}")
                    }
                }
            }

            var added = 0
            var removed = 0
            unified.lineSequence().forEach { line ->
                when {
                    line.startsWith("+++ ") || line.startsWith("--- ") -> Unit
                    line.startsWith("+") -> added++
                    line.startsWith("-") -> removed++
                }
            }

            return DiffResult(label = label, added = added, removed = removed, unified = unified)
        } finally {
            runCatching { Files.deleteIfExists(oldFile) }
            runCatching { Files.deleteIfExists(newFile) }
        }
    }

    private fun appendDiffLog(workDir: Path, r: DiffResult, attemptIdx: Int) {
        val diffLog = workDir.resolve("diff.log")
        val header = "===== DIFF ${r.label} (attempt=${attemptIdx + 1}) +${r.added} -${r.removed} =====\n"
        val body = if (r.unified.isBlank()) "(no diff)\n\n" else r.unified + "\n\n"

        Files.writeString(
            diffLog,
            header + body,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )

        // console preview (avoid spam)
        println(header.trimEnd())
        if (r.unified.isNotBlank()) {
            val lines = r.unified.lines()
            println(lines.take(80).joinToString("\n"))
            if (lines.size > 80) println("...(truncated, full diff in diff.log)")
        }
        println()
    }

    // ------------------------- Candidate runner -------------------------

    private fun sanitizeForDir(s: String): String =
        s.lowercase().replace(Regex("""[^a-z0-9._-]+"""), "_").trim('_')

    private fun candidateDir(base: Path, idx: Int, c: ConcreteModelChoice): Path {
        val name = "candidate_${(idx + 1).toString().padStart(2, '0')}_${sanitizeForDir(c.library)}_${sanitizeForDir(c.modelId)}"
        return base.resolve(name)
    }

    @Serializable
    data class CandidateOutcome(
        val index: Int,
        val workDir: String,
        val family: ModelFamilyPick,
        val concrete: ConcreteModelChoice,
        val result: CandidateResult
    )

    @Serializable
    sealed class CandidateResult {
        @Serializable
        data class Success(
            val modelPy: String,
            val testPy: String,
            val stdout: String,
            val stderr: String
        ) : CandidateResult()

        @Serializable
        data class Failed(
            val reason: String,
            val lastError: String? = null
        ) : CandidateResult()
    }

    private suspend fun runOneCandidate(
        spec: ChecklistSpec,
        familyPick: ModelFamilyPick,
        concrete: ConcreteModelChoice,
        candidateWorkDir: Path,
        maxModelRetries: Int,
        globalInstalledDeps: MutableSet<String>
    ): CandidateResult {
        Files.createDirectories(candidateWorkDir)

        // generate test (per candidate; may be fixed later)
        val testGen = TestGenerator(executor, llmModel)
        var testPy = testGen.generateTestPy(spec, MODEL_API_CONTRACT)
        val testPath = candidateWorkDir.resolve("test.py")
        testPath.writeText(testPy)

        val pythonRunner = PythonRunner()

        val depManager = PythonDependencyManager(
            executor = executor,
            llmModel = llmModel,
            fixerModel = fixerModel
        )

        // preinstall candidate extra deps
        if (concrete.extraDependencies.isNotEmpty()) {
            val pre = concrete.extraDependencies.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val toInstall = pre.filterNot { globalInstalledDeps.contains(it) }
            if (toInstall.isNotEmpty()) {
                val r = depManager.pipInstall(toInstall, candidateWorkDir)
                if (r.exitCode == 0) globalInstalledDeps.addAll(toInstall)
            }
        }

        val maxTestFixes = 2
        var testFixCount = 0

        var lastErr: String? = null
        var lastModelPy: String? = null

        repeat(maxModelRetries) { attemptIdx ->
            val modelPy = ModelGenerator(executor, llmModel).generateModelPy(
                spec = spec,
                family = familyPick.family,
                concrete = concrete,
                contract = MODEL_API_CONTRACT,
                previousModelPy = lastModelPy,
                lastError = lastErr
            )

            val modelPath = candidateWorkDir.resolve("model.py")
            modelPath.writeText(modelPy)

            // diff: model vs last model (attempt>=2)
            computeUnifiedDiff(
                workDir = candidateWorkDir,
                label = "model.py",
                oldText = lastModelPy,
                newText = modelPy
            )?.let { appendDiffLog(candidateWorkDir, it, attemptIdx) }

            // run test
            var runResult = pythonRunner.runTest(workDir = candidateWorkDir, testFileName = "test.py")

            suspend fun inferAndInstallIfNeeded(): Boolean {
                val plan = depManager.proposeDependencies(
                    spec = spec,
                    family = familyPick.family,
                    concrete = concrete,
                    modelPy = modelPy,
                    testPy = testPy,
                    lastStdout = runResult.stdout,
                    lastStderr = runResult.stderr
                )

                val toInstall = plan.pipPackages
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .filterNot { globalInstalledDeps.contains(it) }
                    .distinct()

                if (toInstall.isEmpty()) return false

                val pipRes = depManager.pipInstall(toInstall, candidateWorkDir)
                if (pipRes.exitCode == 0) {
                    globalInstalledDeps.addAll(toInstall)
                    return true
                }

                lastErr = buildString {
                    appendLine(runResult.stderr.ifBlank { runResult.stdout })
                    appendLine()
                    appendLine("pip install failed:")
                    appendLine(pipRes.stderr.ifBlank { pipRes.stdout })
                }.trim()

                return false
            }

            val installedSomething = inferAndInstallIfNeeded()
            if (installedSomething) {
                runResult = pythonRunner.runTest(workDir = candidateWorkDir, testFileName = "test.py")
                inferAndInstallIfNeeded()
            }

            if (runResult.exitCode == 0) {
                return CandidateResult.Success(
                    modelPy = modelPy,
                    testPy = testPy,
                    stdout = runResult.stdout,
                    stderr = runResult.stderr
                )
            }

            val output = runResult.stderr.ifBlank { runResult.stdout }

            val triage = triageFailure(
                spec = spec,
                contract = MODEL_API_CONTRACT,
                modelPy = modelPy,
                testPy = testPy,
                lastOutput = output
            )

            val shouldFixTest =
                triage.culprit == Culprit.TEST &&
                        triage.confidence >= 0.55 &&
                        testFixCount < maxTestFixes

            if (shouldFixTest) {
                val oldTestPy = testPy

                val fixedTestPy = fixTestPyViaLLM(
                    spec = spec,
                    contract = MODEL_API_CONTRACT,
                    previousTestPy = testPy,
                    currentModelPy = modelPy,
                    lastOutput = output
                )
                testPy = fixedTestPy
                testPath.writeText(testPy)
                testFixCount++

                // diff: test fix
                computeUnifiedDiff(
                    workDir = candidateWorkDir,
                    label = "test.py (fix #$testFixCount)",
                    oldText = oldTestPy,
                    newText = testPy
                )?.let { appendDiffLog(candidateWorkDir, it, attemptIdx) }

                runResult = pythonRunner.runTest(workDir = candidateWorkDir, testFileName = "test.py")

                val installedAfterFix = inferAndInstallIfNeeded()
                if (installedAfterFix) {
                    runResult = pythonRunner.runTest(workDir = candidateWorkDir, testFileName = "test.py")
                }

                if (runResult.exitCode == 0) {
                    return CandidateResult.Success(
                        modelPy = modelPy,
                        testPy = testPy,
                        stdout = runResult.stdout,
                        stderr = runResult.stderr
                    )
                }

                // still failed -> treat as model issue for next attempt
                lastErr = runResult.stderr.ifBlank { runResult.stdout }
                lastModelPy = modelPy
                return@repeat
            }

            // default: model issue
            lastErr = output
            lastModelPy = modelPy
        }

        return CandidateResult.Failed(
            reason = "model.py failed tests after $maxModelRetries retries.",
            lastError = lastErr
        )
    }

    // ------------------------------------------------------------------------

    /**
     * Run 3 models in different directories, then finalize best into workDir root.
     */
    suspend fun run(
        checklist: ChecklistLike,
        workDir: Path,
        maxModelRetries: Int = 10
    ): PipelineResult {
        if (!checklist.check()) {
            return PipelineResult.Failed(
                reason = "Checklist is not fully confirmed.",
                checklistSnapshot = checklist.snapshot()
            )
        }

        val spec = specFromSnapshot(checklist.snapshot())
        Files.createDirectories(workDir)

        val familyPick = pickModelFamily(spec)
        val concretes = pickConcreteModels(spec, familyPick.family)

        val globalInstalledDeps = mutableSetOf<String>()
        val outcomes = mutableListOf<CandidateOutcome>()

        concretes.forEachIndexed { idx, concrete ->
            val cDir = candidateDir(workDir, idx, concrete)
            val result = runOneCandidate(
                spec = spec,
                familyPick = familyPick,
                concrete = concrete,
                candidateWorkDir = cDir,
                maxModelRetries = maxModelRetries,
                globalInstalledDeps = globalInstalledDeps
            )
            outcomes += CandidateOutcome(
                index = idx + 1,
                workDir = cDir.toString(),
                family = familyPick,
                concrete = concrete,
                result = result
            )
        }

        // ------------------------- FINALIZE BEST INTO ROOT -------------------------
        val successes = outcomes.filter { it.result is CandidateResult.Success }
        if (successes.isNotEmpty()) {
            val higherBetter = metricHigherIsBetter(spec.metric)

            data class Scored(val o: CandidateOutcome, val dir: Path, val metric: MetricReport?)

            val scored = successes.map { o ->
                val dir = Path.of(o.workDir)
                val metric =
                    readMetricFromDir(dir) ?: extractMetricFromStdout((o.result as CandidateResult.Success).stdout)
                Scored(o, dir, metric)
            }

            val withMetric = scored.filter { it.metric != null && it.metric.value.isFinite() }

            val best = if (withMetric.isNotEmpty()) {
                if (higherBetter) withMetric.maxBy { it.metric!!.value } else withMetric.minBy { it.metric!!.value }
            } else {
                // If no usable metric, fallback to first success.
                scored.first()
            }

            // Copy best model/test into generated_ml root
            Files.copy(best.dir.resolve("model.py"), workDir.resolve("model.py"), StandardCopyOption.REPLACE_EXISTING)
            Files.copy(best.dir.resolve("test.py"), workDir.resolve("test.py"), StandardCopyOption.REPLACE_EXISTING)

            // Copy metric.json if exists; else write one from parsed metric (if available)
            val bestMetricFile = listOf(best.dir.resolve("metric.json"), best.dir.resolve("metrics.json"))
                .firstOrNull { Files.exists(it) }

            if (bestMetricFile != null) {
                Files.copy(bestMetricFile, workDir.resolve("metric.json"), StandardCopyOption.REPLACE_EXISTING)
            } else if (best.metric != null) {
                workDir.resolve("metric.json").writeText(json.encodeToString(best.metric))
            }

            // Optional: also copy best diff log for quick review
            val diffLog = best.dir.resolve("diff.log")
            if (Files.exists(diffLog)) {
                Files.copy(diffLog, workDir.resolve("diff_best.log"), StandardCopyOption.REPLACE_EXISTING)
            }

            // Record selection
            workDir.resolve("selected_best.txt").writeText(
                buildString {
                    appendLine("bestCandidateIndex=${best.o.index}")
                    appendLine("candidateDir=${best.o.workDir}")
                    appendLine("family=${best.o.family.family}")
                    appendLine("library=${best.o.concrete.library}")
                    appendLine("modelId=${best.o.concrete.modelId}")
                    appendLine("metric=${best.metric?.metric}")
                    appendLine("value=${best.metric?.value}")
                    appendLine("directionHigherIsBetter=$higherBetter (spec.metric=${spec.metric})")
                }
            )
        }
        // ------------------------- END FINALIZE -------------------------

        return PipelineResult.Multi(
            checklistSnapshot = checklist.snapshot(),
            outcomes = outcomes
        )
    }
}

/**
 * Pipeline result types.
 */
sealed class PipelineResult {
    data class Multi(
        val checklistSnapshot: String,
        val outcomes: List<MlAutoGenCore.CandidateOutcome>
    ) : PipelineResult()

    data class Failed(
        val reason: String,
        val checklistSnapshot: String,
        val lastError: String? = null
    ) : PipelineResult()
}

/**
 * Minimal demo entrypoint.
 */
fun main() = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: error("Missing OPENAI_API_KEY env var")

    val executor = simpleOpenAIExecutor(apiKey)
    executor.use {
        val core = MlAutoGenCore(executor = it)

        val checklist = DummyChecklist(
            snapshotText = """
Input type: IMAGE
Output type: CATEGORY
Training type: SUPERVISED
Split strategy: HOLDOUT
Metric: ACCURACY
Data path: /tmp/data
            """.trimIndent(),
            ok = true
        )

        val result = core.run(
            checklist = checklist,
            workDir = Path.of("generated_ml"),
            maxModelRetries = 5
        )

        println(result)
    }
}

data class DummyChecklist(
    private val snapshotText: String,
    private val ok: Boolean
) : MlAutoGenCore.ChecklistLike {
    override fun check(): Boolean = ok
    override fun snapshot(): String = snapshotText
}