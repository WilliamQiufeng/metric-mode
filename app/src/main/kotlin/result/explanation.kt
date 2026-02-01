package org.example.app.result

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.structure.StructureFixingParser
import ai.koog.prompt.structure.executeStructured
import kotlinx.serialization.Serializable
import org.example.app.core.MlAutoGenCore
import org.example.app.intermediate.ModelFamilyCategory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * explanation.kt
 *
 * Uses an LLM (via Koog) to explain the generated model.py as a step-by-step document.
 * Output: workDir/explanation.md
 */
class ExplanationGenerator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4oMini,
    private val fixerModel: ai.koog.prompt.llm.LLModel = OpenAIModels.Chat.GPT4o
) {

    @Serializable
    data class ExplanationDoc(
        val title: String,
        val overview: String,
        val modelApiContractSummary: String,
        val steps: List<Step>,
        val dataHandling: String,
        val trainingLogic: String,
        val inferenceLogic: String,
        val evaluationLogic: String,
        val assumptions: List<String>,
        val limitations: List<String>,
        val reproducibility: List<String>
    ) {
        @Serializable
        data class Step(
            val name: String,
            val detail: String
        )
    }

    /**
     * Generate explanation.md in workDir.
     */
    suspend fun generate(
        workDir: Path,
        spec: MlAutoGenCore.ChecklistSpec,
        family: ModelFamilyCategory? = null,
        concrete: MlAutoGenCore.ConcreteModelChoice? = null,
        contract: String = MlAutoGenCore.MODEL_API_CONTRACT,
        outFileName: String = "explanation.md"
    ): Path {
        val modelPath = workDir.resolve("model.py")
        require(modelPath.exists()) { "model.py not found at: $modelPath" }

        val modelPy = modelPath.readText()
        val modelPreview = ResultIo.truncateForPrompt(
            ResultIo.smartPreview(modelPy, headChars = 6000, tailChars = 2500),
            maxChars = 9000
        )

        val fixingParser = StructureFixingParser(model = fixerModel, retries = 2)

        val p = prompt("explain-model-steps") {
            system(
                """
You are a senior ML engineer and technical writer.
Create an explanation document for the Python model implementation.

Output ONLY valid JSON following the schema of ExplanationDoc.
No markdown fences. No extra commentary.

Constraints:
- Keep it accurate and grounded in the provided model.py.
- Keep it practical: what the code does, step-by-step, and how to run/reproduce.
- Write in English (to ensure PDF compatibility later).
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

Chosen model family = ${family ?: "UNKNOWN"}
Chosen concrete model = ${concrete?.let { "${it.library} / ${it.modelId}" } ?: "UNKNOWN"}

Model API contract:
$contract

model.py preview (may be truncated):
$modelPreview
                """.trimIndent()
            )
        }

        val result = executor.executeStructured<ExplanationDoc>(
            prompt = p,
            model = llmModel,
            fixingParser = fixingParser
        ).getOrThrow().data

        val md = renderMarkdown(result)
        val outPath = workDir.resolve(outFileName)
        outPath.writeText(md)
        return outPath
    }

    private fun renderMarkdown(doc: ExplanationDoc): String = buildString {
        appendLine("# ${doc.title}".trim())
        appendLine()
        appendLine(doc.overview.trim())
        appendLine()
        appendLine("## Model API contract")
        appendLine(doc.modelApiContractSummary.trim())
        appendLine()
        appendLine("## Step-by-step")
        doc.steps.forEachIndexed { idx, s ->
            appendLine("### ${idx + 1}. ${s.name}".trim())
            appendLine(s.detail.trim())
            appendLine()
        }
        appendLine("## Data handling")
        appendLine(doc.dataHandling.trim())
        appendLine()
        appendLine("## Training logic")
        appendLine(doc.trainingLogic.trim())
        appendLine()
        appendLine("## Inference logic")
        appendLine(doc.inferenceLogic.trim())
        appendLine()
        appendLine("## Evaluation logic")
        appendLine(doc.evaluationLogic.trim())
        appendLine()
        appendLine("## Assumptions")
        doc.assumptions.forEach { appendLine("- ${it.trim()}") }
        appendLine()
        appendLine("## Limitations")
        doc.limitations.forEach { appendLine("- ${it.trim()}") }
        appendLine()
        appendLine("## Reproducibility")
        doc.reproducibility.forEach { appendLine("- ${it.trim()}") }
        appendLine()
    }
}