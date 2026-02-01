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
 * - generated_ml/model.py exists (generated from model.kt)
 * - generated_ml/test.py exists (generated from test.kt) and already passed once
 *
 * What it tests:
 * 0) Build derived datasets (under generated_ml):
 *    - train_dataset.csv (labeled) for tune + final_train
 *    - prediction_dataset.csv (unlabeled) for predict
 * 1) Re-run existing test.py (sanity gate: ensure we still use the same model.py contract)
 * 2) Tune stage (TuneOrchestrator) -> tune.py + tune_result.json + best_params.json
 * 3) Final-train stage (FinalTrainOrchestrator) -> final_train.py + final_train_result.json + trained_model.json
 * 4) Prediction stage (PredictionOrchestrator) -> predict.py + predictions.csv + prediction_result.json
 * 5) Explanation generator -> explanation.md
 * 6) Report generator -> report.md + report.pdf
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

    // === NEW (0): split iris.csv into two derived files under generated_ml ===
    // train_dataset.csv: labeled (features + y) for tune/final_train
    // prediction_dataset.csv: unlabeled (features only) for prediction
    val trainCsv = workDir.resolve("train_dataset.csv")
    val predCsv = workDir.resolve("prediction_dataset.csv")

    val allLines = irisCsv.readText()
        .lineSequence()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .toList()

    require(allLines.size >= 3) { "iris.csv too small to split: lines=${allLines.size}" }

    val header = allLines.first()
    val rows = allLines.drop(1)

    fun splitCsvLine(line: String): List<String> = line.split(",")

    val headerParts = splitCsvLine(header)
    require(headerParts.size >= 2) { "CSV must have at least 2 columns (X + y). header=$header" }

    // Assume last column is y (consistent with your Python rules)
    val featureHeader = headerParts.dropLast(1).joinToString(",")
    val labeledHeader = headerParts.joinToString(",")

    // deterministic shuffle for stable smoke test
    val rnd = java.util.Random(42)
    val shuffled = rows.toMutableList()
    java.util.Collections.shuffle(shuffled, rnd)

    // 80/20 split: train / prediction
    val trainN = (shuffled.size * 0.8).toInt()
        .coerceAtLeast(1)
        .coerceAtMost(shuffled.size - 1)

    val trainRows = shuffled.take(trainN)
    val predRows = shuffled.drop(trainN)

    // Write train (with y)
    trainCsv.toFile().writeText(
        (listOf(labeledHeader) + trainRows).joinToString("\n")
    )

    // Write prediction (without y)
    fun dropLastCol(line: String): String {
        val p = splitCsvLine(line)
        require(p.size >= 2) { "Bad CSV row: $line" }
        return p.dropLast(1).joinToString(",")
    }

    predCsv.toFile().writeText(
        (listOf(featureHeader) + predRows.map { dropLastCol(it) }).joinToString("\n")
    )

    println("✅ Built train dataset:      $trainCsv (rows=${trainRows.size})")
    println("✅ Built prediction dataset: $predCsv (rows=${predRows.size})")
    println()

    // 2) Build checklist spec (now points to train_dataset.csv)
    val checklist = ModelAutoGenChecklist().apply {
        setInputType(InputType.TABULAR)
        setOutputType(OutputType.CLASSIFICATION)
        setTrainingType(TrainingType.SUPERVISED)

        // IMPORTANT: train/tune must use the labeled TRAIN dataset we just wrote
        setDataPath(trainCsv.toString())

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

        // 3.1) Re-run existing test.py as sanity gate (ensures we still use model.kt-generated model.py)
        println("=== (1) Sanity gate: re-run existing test.py ===")
        val pythonRunner = PythonRunner()
        val baseTest = pythonRunner.runTest(workDir = workDir, testFileName = "test.py")
        println(baseTest.stdout)
        require(baseTest.exitCode == 0) {
            "Existing test.py FAILED (sanity gate). Stop here.\nOutput:\n${baseTest.stdout}"
        }
        println("✅ Existing test.py passed again.")
        println()

        // 3.2) TUNE: generate + run tune.py -> best_params.json + tune_result.json
        println("=== (2) TuneOrchestrator: generate + run tune.py ===")
        val tuner = TuneOrchestrator(executor = ex)
        val tuneRes = tuner.runTune(
            workDir = workDir,
            spec = spec,
            contract = MlAutoGenCore.MODEL_API_CONTRACT,
            maxRetries = 3,
            outFileName = "tune.py",
            sanityTestFileName = "test.py"
        )

        val tuneStdout: String
        val tuneJson = when (tuneRes) {
            is TuneOrchestrator.TuneResult.Success -> {
                println(tuneRes.stdout)
                println("✅ tune.py passed.")
                tuneStdout = tuneRes.stdout
                tuneRes.parsedJson
            }
            is TuneOrchestrator.TuneResult.Failed -> {
                error("❌ tune.py failed.\nreason=${tuneRes.reason}\nlastOutput=\n${tuneRes.lastOutput}")
            }
        }

        val tunePyPath = workDir.resolve("tune.py")
        require(tunePyPath.exists()) { "tune.py not found at: $tunePyPath" }
        println("✅ tune.py exists: $tunePyPath (size=${Files.size(tunePyPath)} bytes)")

        val tuneResult = workDir.resolve("tune_result.json")
        require(tuneResult.exists()) { "tune_result.json not found at: $tuneResult" }
        println("✅ tune_result.json exists: $tuneResult")
        println("content preview: " + tuneResult.readText().take(400))

        val bestParams = workDir.resolve("best_params.json")
        require(bestParams.exists()) { "best_params.json not found at: $bestParams" }
        println("✅ best_params.json exists: $bestParams")
        println("content preview: " + bestParams.readText().take(400))
        println()

        // 3.3) FINAL TRAIN: generate + run final_train.py -> trained_model.json + final_train_result.json
        println("=== (3) FinalTrainOrchestrator: generate + run final_train.py ===")
        val fin = FinalTrainOrchestrator(executor = ex)
        val finRes = fin.runFinalTrain(
            workDir = workDir,
            spec = spec,
            contract = MlAutoGenCore.MODEL_API_CONTRACT,
            maxRetries = 3,
            outFileName = "final_train.py",
            sanityTestFileName = "test.py",
            bestParamsFileName = "best_params.json",
            modelArtifactFileName = "trained_model.json"
        )

        val finalTrainStdout: String
        val finalTrainJson = when (finRes) {
            is FinalTrainOrchestrator.FinalTrainResult.Success -> {
                println(finRes.stdout)
                println("✅ final_train.py passed.")
                finalTrainStdout = finRes.stdout
                finRes.parsedJson
            }
            is FinalTrainOrchestrator.FinalTrainResult.Failed -> {
                error("❌ final_train.py failed.\nreason=${finRes.reason}\nlastOutput=\n${finRes.lastOutput}")
            }
        }

        val finalTrainPy = workDir.resolve("final_train.py")
        require(finalTrainPy.exists()) { "final_train.py not found: $finalTrainPy" }
        println("✅ final_train.py exists: $finalTrainPy (size=${Files.size(finalTrainPy)} bytes)")

        val finalTrainResult = workDir.resolve("final_train_result.json")
        require(finalTrainResult.exists()) { "final_train_result.json not found: $finalTrainResult" }
        println("✅ final_train_result.json exists: $finalTrainResult")
        println("content preview: " + finalTrainResult.readText().take(400))

        val trainedModel = workDir.resolve("trained_model.json")
        require(trainedModel.exists()) { "trained_model.json not found at: $trainedModel" }
        println("✅ trained_model.json exists: $trainedModel (size=${Files.size(trainedModel)} bytes)")
        println()

        // 3.4) PREDICT: generate + run predict.py -> predictions.csv + prediction_result.json
        println("=== (4) PredictionOrchestrator: generate + run predict.py ===")
        val predOrchestrator = PredictionOrchestrator(executor = ex)

        // IMPORTANT: use the unlabeled prediction dataset we created (no y column)
        val predictionPath = predCsv.toString()

        val predRes = predOrchestrator.runPrediction(
            workDir = workDir,
            spec = spec,
            predictionDataPath = predictionPath,
            modelArtifactFileName = "trained_model.json",
            outFileName = "predict.py",
            maxRetries = 2
        )

        val predStdout: String = when (predRes) {
            is PredictionOrchestrator.PredictionResult.Success -> {
                println(predRes.stdout)
                println("✅ predict.py passed.")
                predRes.stdout
            }
            is PredictionOrchestrator.PredictionResult.Failed -> {
                error("❌ predict.py failed.\nreason=${predRes.reason}\nlastOutput=\n${predRes.lastOutput}")
            }
        }

        val predictPy = workDir.resolve("predict.py")
        require(predictPy.exists()) { "predict.py not found: $predictPy" }
        println("✅ predict.py exists: $predictPy (size=${Files.size(predictPy)} bytes)")

        val predsCsv = workDir.resolve("predictions.csv")
        require(predsCsv.exists()) { "predictions.csv not found: $predsCsv" }
        println("✅ predictions.csv exists: $predsCsv (size=${Files.size(predsCsv)} bytes)")

        val predResultJson = workDir.resolve("prediction_result.json")
        require(predResultJson.exists()) { "prediction_result.json not found: $predResultJson" }
        println("✅ prediction_result.json exists: $predResultJson")
        println("content preview: " + predResultJson.readText().take(400))
        println()

        // 3.5) Explanation
        println("=== (5) ExplanationGenerator: generate explanation.md ===")
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

        // 3.6) Report
        println("=== (6) ReportGenerator: generate report.md + report.pdf ===")
        val repGen = ReportGenerator(executor = ex)

        // Feed FINAL TRAIN stdout/json as the "final run",
        // and append tune+prediction stdout for context.
        val reportStdout =
            finalTrainStdout +
                    "\n\n__TUNE_STAGE_STDOUT__\n" + tuneStdout +
                    "\n\n__PREDICTION_STAGE_STDOUT__\n" + predStdout

        val artifacts = repGen.generate(
            workDir = workDir,
            spec = spec,
            finalTestStdout = reportStdout,
            finalTestJson = finalTrainJson,
            contract = MlAutoGenCore.MODEL_API_CONTRACT,
            outMarkdown = "report.md",
            outPdf = "report.pdf"
        )

        require(artifacts.markdownPath.exists()) { "report.md not created: ${artifacts.markdownPath}" }
        require(Files.size(artifacts.markdownPath) > 300) { "report.md too small. size=${Files.size(artifacts.markdownPath)}" }

        require(artifacts.pdfPath.exists()) { "report.pdf not created: ${artifacts.pdfPath}" }
        require(Files.size(artifacts.pdfPath) > 800) { "report.pdf too small. size=${Files.size(artifacts.pdfPath)} bytes)" }

        println("✅ report.md created:  ${artifacts.markdownPath} (size=${Files.size(artifacts.markdownPath)} bytes)")
        println("✅ report.pdf created: ${artifacts.pdfPath} (size=${Files.size(artifacts.pdfPath)} bytes)")
        println()

        println("🎉 All result-stage checks passed.")
        println("Outputs are under: $workDir")
    }
}