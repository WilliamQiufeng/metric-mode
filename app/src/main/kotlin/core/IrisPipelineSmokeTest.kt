package org.example.app.core

// IrisPipelineSmokeTest.kt
// Put this next to result/final_train.kt and reuse the same `package` line as final_train.kt.

import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

// IMPORTANT: adjust these imports to match your repo packages
import org.example.app.core.*
import org.example.app.intermediate.InputType
import org.example.app.intermediate.OutputType
import org.example.app.intermediate.TrainingType
import org.example.app.intermediate.SplitStrategy
import org.example.app.intermediate.Metric
import org.example.app.intermediate.ModelAutoGenChecklist

private const val IRIS_CSV_URL =
    "https://raw.githubusercontent.com/mwaskom/seaborn-data/master/iris.csv"

private fun downloadIfMissing(url: String, dest: Path) {
    if (dest.exists()) return
    dest.parent.createDirectories()
    val bytes = URL(url).openStream().use { it.readBytes() }
    dest.writeBytes(bytes)
}

fun ModelAutoGenChecklist.asCoreChecklistLike(): MlAutoGenCore.ChecklistLike =
    object : MlAutoGenCore.ChecklistLike {
        override fun check(): Boolean = this@asCoreChecklistLike.check().ok
        override fun snapshot(): String = this@asCoreChecklistLike.snapshot()
    }

fun main() = runBlocking {
    try {
        println("OPENAI_API_KEY present? " + !System.getenv("OPENAI_API_KEY").isNullOrBlank())

        // 你原来的 main 内容从这里开始
        val apiKey =
            System.getenv("OPENAI_API_KEY")
                ?: System.getProperty("OPENAI_API_KEY")
                ?: error("Missing OPENAI_API_KEY")

        val executor = simpleOpenAIExecutor(apiKey)
        executor.use {
            val core = MlAutoGenCore(executor = it)
            // ... 你的 checklist + core.run(...)
        }

    } catch (t: Throwable) {
        t.printStackTrace()   // 关键：把真正错误打印出来
        throw t               // 让 Gradle 仍然失败，但你能看到原因
    }
    // 1) Ensure API key exists (Koog OpenAI executor needs it)
    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("Missing OPENAI_API_KEY env var. Set it before running this test.")

    // 2) Download dataset into your repo (under ./data/iris/)
    val projectRoot = Path.of(System.getProperty("user.dir"))
    val dataDir = projectRoot.resolve("data").resolve("iris")
    val irisCsv = dataDir.resolve("iris.csv")
    downloadIfMissing(IRIS_CSV_URL, irisCsv)

    // quick sanity check
    val size = Files.size(irisCsv)
    require(size > 1000) { "Downloaded iris.csv looks too small ($size bytes)." }

    // 3) Build checklist
    val cl = ModelAutoGenChecklist().apply {
        setInputType(InputType.TABULAR)
        setOutputType(OutputType.CLASSIFICATION)
        setTrainingType(TrainingType.SUPERVISED)

        // dataset path points to the directory containing iris.csv
        setDataPath(dataDir.toString())

        // keep it simple; your data agent can do proper split later
        setSplitStrategy(SplitStrategy.RANDOM)
        setMetric(Metric.ACCURACY)

        // confirm required fields
        confirm(ModelAutoGenChecklist.Field.INPUT_TYPE)
        confirm(ModelAutoGenChecklist.Field.OUTPUT_TYPE)
        confirm(ModelAutoGenChecklist.Field.TRAINING_TYPE)
        confirm(ModelAutoGenChecklist.Field.DATA_PATH)
        confirm(ModelAutoGenChecklist.Field.SPLIT_STRATEGY)
        confirm(ModelAutoGenChecklist.Field.METRIC)
    }

    val check = cl.check()
    require(check.ok) {
        "Checklist not ready: missing=${check.missing.map { it.label }}, unconfirmed=${check.unconfirmed.map { it.label }}\n${cl.snapshot()}"
    }

    // 4) Run your pipeline (core -> generate model.py, test -> generate test.py, then run python test.py)
    val executor = simpleOpenAIExecutor(apiKey)
    executor.use {
        val core = MlAutoGenCore(executor = it) // if your Core constructor differs, adjust here

        val workDir = projectRoot.resolve("generated_iris_pipeline")
        val result = core.run(
            checklist = cl.asCoreChecklistLike(),
            workDir = Path.of("generated_ml")
        )

        // 5) Assert success
        when (result) {
            is PipelineResult.Success -> {
                println("✅ Pipeline success")
                println("workDir = ${result.workDir}")
                println("stdout:\n${result.stdout}")
                if (result.stderr.isNotBlank()) println("stderr:\n${result.stderr}")
            }
            is PipelineResult.Failed -> {
                error("❌ Pipeline failed: ${result.reason}\nLastError=${result.lastError}\n")
            }
        }
    }
}