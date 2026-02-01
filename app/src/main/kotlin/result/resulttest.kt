package org.example.app.result

import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking
import org.example.app.core.MlAutoGenCore
import org.example.app.core.PythonRunner
import org.example.app.intermediate.*
import org.example.app.intermediate.ModelAutoGenChecklist
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * IrisResultSmokeTest.kt
 *
 * Preconditions:
 * - data/iris/iris.csv exists
 * - generated_ml/model.py exists
 * - generated_ml/test.py exists and already passed once
 *
 * What it tests:
 * 1) Re-run existing test.py (sanity)
 * 2) run final test generator/executor (FinalTestOrchestrator) -> final_test.py + final_test_result.json
 * 3) explanation generator -> explanation.md
 * 4) report generator -> report.md + report.pdf
 *
 * Run:
 * - Set OPENAI_API_KEY in env
 * - Run main() from IDE or via a Kotlin run configuration at project root
 */
fun main() = runBlocking {
    // 0) API key
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: System.getProperty("OPENAI_API_KEY")
        ?: error("Missing OPENAI_API_KEY (env or system property).")

    val projectRoot = Path.of(System.getProperty("user.dir"))

    // 1) Validate expected files exist
    val dataDir = projectRoot.resolve("data").resolve("iris")
    val irisCsv = dataDir.resolve("iris.csv")

    val workDir = projectRoot.resolve("generated_ml")
    val modelPy = workDir.resolve("model.py")
    val testPy = workDir.resolve("test.py")

    require(irisCsv.exists()) { "Missing dataset: $irisCsv" }
    require(modelPy.exists()) { "Missing model.py: $modelPy" }
    require(testPy.exists()) { "Missing test.py: $testPy" }

    println("✅ Found iris.csv: $irisCsv (size=${Files.size(irisCsv)} bytes)")
    println("✅ Found model.py:  $modelPy (size=${Files.size(modelPy)} bytes)")
    println("✅ Found test.py:   $testPy (size=${Files.size(testPy)} bytes)")
    println()

    // 2) Build checklist spec (same as your Iris pipeline)
    val checklist = ModelAutoGenChecklist().apply {
        setInputType(InputType.TABULAR)
        setOutputType(OutputType.CLASSIFICATION)
        setTrainingType(TrainingType.SUPERVISED)

        // IMPORTANT: keep consistent with how your Iris pipeline used dataPath
        // Here we point to the directory that contains iris.csv
        setDataPath(dataDir.toString())

        setSplitStrategy(SplitStrategy.RANDOM)
        setMetric(Metric.ACCURACY)

        confirm(ModelAutoGenChecklist.Field.INPUT_TYPE)
        confirm(ModelAutoGenChecklist.Field.OUTPUT_TYPE)
        confirm(ModelAutoGenChecklist.Field.TRAINING_TYPE)
        confirm(ModelAutoGenChecklist.Field.DATA_PATH)
        confirm(ModelAutoGenChecklist.Field.SPLIT_STRATEGY)
        confirm(ModelAutoGenChecklist.Field.METRIC)
    }

    val check = checklist.check()
    require(check.ok) {
        "Checklist not ready: missing=${check.missing.map { it.label }}, unconfirmed=${check.unconfirmed.map { it.label }}\n${checklist.snapshot()}"
    }

    // 3) Create Koog executor and run the result pipeline checks
    val executor = simpleOpenAIExecutor(apiKey)
    executor.use { ex ->
        val core = MlAutoGenCore(executor = ex)
        val spec: MlAutoGenCore.ChecklistSpec = core.specFromSnapshot(checklist.snapshot())

        // 3.1) (Optional) re-run existing test.py as sanity check
        println("=== (1) Sanity: re-run existing test.py ===")
        val pythonRunner = PythonRunner()
        val baseTest = pythonRunner.runTest(workDir = workDir, testFileName = "test.py")
        println(baseTest.stdout)
        require(baseTest.exitCode == 0) {
            "Existing test.py FAILED.\nOutput:\n${baseTest.stdout}"
        }
        println("✅ Existing test.py passed again.")
        println()

        // 3.2) run.kt: generate + run final_test.py (retry on failure)
        println("=== (2) FinalTestOrchestrator: generate + run final_test.py ===")
        val orchestrator = FinalTestOrchestrator(executor = ex)
        val finalRes = orchestrator.runFinalTest(
            workDir = workDir,
            spec = spec,
            contract = MlAutoGenCore.MODEL_API_CONTRACT,
            maxRetries = 3,
            outFileName = "final_test.py"
        )

        val finalStdout: String
        val finalJson = when (finalRes) {
            is FinalTestOrchestrator.FinalTestResult.Success -> {
                println(finalRes.stdout)
                println("✅ final_test.py passed.")
                finalStdout = finalRes.stdout
                finalRes.parsedJson
            }
            is FinalTestOrchestrator.FinalTestResult.Failed -> {
                error("❌ final_test.py failed.\nreason=${finalRes.reason}\nlastOutput=\n${finalRes.lastOutput}")
            }
        }
        val finalTestPath = workDir.resolve("final_test.py")
        require(finalTestPath.exists()) { "final_test.py not found at: $finalTestPath" }
        println("✅ final_test.py exists: $finalTestPath (size=${Files.size(finalTestPath)} bytes)")

        val finalJsonPath = workDir.resolve("final_test_result.json")
        if (finalJsonPath.exists()) {
            println("✅ final_test_result.json exists: $finalJsonPath")
            println("content preview: " + finalJsonPath.readText().take(400))
        } else {
            println("⚠️ final_test_result.json not found (final_test may still have succeeded via stdout marker).")
        }
        println()

        // 3.3) explanation.kt: generate explanation.md
        println("=== (3) ExplanationGenerator: generate explanation.md ===")
        val explGen = ExplanationGenerator(executor = ex)
        val explPath = explGen.generate(
            workDir = workDir,
            spec = spec,
            family = null,
            concrete = null,
            contract = MlAutoGenCore.MODEL_API_CONTRACT,
            outFileName = "explanation.md"
        )
        require(explPath.exists()) { "explanation.md not created: $explPath" }
        require(Files.size(explPath) > 200) { "explanation.md too small, suspicious. size=${Files.size(explPath)}" }
        println("✅ explanation.md created: $explPath (size=${Files.size(explPath)} bytes)")
        println()

        // 3.4) report.kt: generate report.md + report.pdf
        println("=== (4) ReportGenerator: generate report.md + report.pdf ===")
        val repGen = ReportGenerator(executor = ex)
        val artifacts = repGen.generate(
            workDir = workDir,
            spec = spec,
            finalTestStdout = finalStdout,
            finalTestJson = finalJson,
            contract = MlAutoGenCore.MODEL_API_CONTRACT,
            outMarkdown = "report.md",
            outPdf = "report.pdf"
        )

        require(artifacts.markdownPath.exists()) { "report.md not created: ${artifacts.markdownPath}" }
        require(Files.size(artifacts.markdownPath) > 300) { "report.md too small. size=${Files.size(artifacts.markdownPath)}" }

        require(artifacts.pdfPath.exists()) { "report.pdf not created: ${artifacts.pdfPath}" }
        require(Files.size(artifacts.pdfPath) > 800) { "report.pdf too small. size=${Files.size(artifacts.pdfPath)}" }

        println("✅ report.md created:  ${artifacts.markdownPath} (size=${Files.size(artifacts.markdownPath)} bytes)")
        println("✅ report.pdf created: ${artifacts.pdfPath} (size=${Files.size(artifacts.pdfPath)} bytes)")
        println()

        println("🎉 All result-stage checks passed.")
        println("Outputs are under: $workDir")
    }
}