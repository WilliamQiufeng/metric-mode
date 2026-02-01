package org.example.app.core

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.message.Message
import org.example.app.core.*
import org.example.app.intermediate.*
/**
 * model.kt:
 * Ask LLM to generate model.py that follows the stable MODEL_API_CONTRACT.
 * It uses:
 * - ChecklistSpec (input/output/training/metric/split/dataPath)
 * - Chosen model family category
 * - Chosen concrete model (library + modelId)
 */
class ModelGenerator(
    private val executor: PromptExecutor,
    private val llmModel: ai.koog.prompt.llm.LLModel
) {
    suspend fun generateModelPy(
        spec: MlAutoGenCore.ChecklistSpec,
        family: ModelFamilyCategory,
        concrete: MlAutoGenCore.ConcreteModelChoice,
        contract: String,
        previousModelPy: String?,
        lastError: String?
    ): String {
        val p = prompt("generate-model-py") {
            system(
                """
You are a senior Python ML engineer.
Generate a SINGLE FILE named model.py.

Hard requirements:
1) Follow this contract exactly:
$contract

2) Output ONLY valid Python code. No markdown fences. No explanations.
3) The code must be runnable even if some ML libraries are missing:
   - If your chosen library cannot be imported, fallback to a minimal numpy baseline model
     while preserving the same ModelWrapper interface.

Task spec:
- inputType = ${spec.inputType}
- outputType = ${spec.outputType}
- trainingType = ${spec.trainingType}
- metric = ${spec.metric}
- splitStrategy = ${spec.splitStrategy}
- dataPath = ${spec.dataPath}

Chosen model family = $family
Chosen concrete model:
- library = ${concrete.library}
- modelId = ${concrete.modelId}
- extraDependencies = ${concrete.extraDependencies}

Implementation guidance:
- Keep dependencies minimal (prefer: numpy, sklearn; optional: torch).
- Provide deterministic behavior with fixed random seeds where reasonable.
- Include a lightweight self-check in `if __name__ == "__main__":` that runs without dataset.
                """.trimIndent()
            )

            if (lastError != null) {
                user(
                    """
The last generated code FAILED the tests with this error output:
$lastError

Fix the root cause and regenerate the FULL model.py.
                    """.trimIndent()
                )
            }

            if (previousModelPy != null) {
                user(
                    """
Previous model.py (for reference). You may rewrite it completely if needed:
$previousModelPy
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