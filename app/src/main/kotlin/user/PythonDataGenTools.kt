package org.example.app.user

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@LLMDescription(
    "Runs restricted Python code to generate a pandas DataFrame named `df`, writes it to a temp CSV, and returns the CSV path."
)
class PythonDataGenTools : ToolSet {

    private fun runProcess(cmd: List<String>, workDir: Path, timeoutSec: Long): String {
        val pb = ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(true)

        // tighten env a bit
        pb.environment()["PYTHONNOUSERSITE"] = "1"
        pb.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        pb.environment()["OMP_NUM_THREADS"] = "1"
        pb.environment()["OPENBLAS_NUM_THREADS"] = "1"
        pb.environment()["MKL_NUM_THREADS"] = "1"

        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()

        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            throw IllegalStateException("Python timed out after ${timeoutSec}s")
        }
        val code = p.exitValue()
        if (code != 0) throw IllegalStateException("Python failed (exit=$code):\n$out")
        return out.trim()
    }

    @Tool
    @LLMDescription(
        """
        Execute restricted python code that MUST define `df` as a pandas DataFrame.
        The harness will write df to a temp CSV and return JSON: {"output_path": "...", "rows": n, "cols": m}.
        
        Restrictions:
        - Your code must NOT import modules.
        - Use `pd` and `np` which are provided.
        - Must create a variable `df` (pandas DataFrame).
        - No file/network/subprocess access.
        """
    )
    fun generateCsvFromPython(
        @LLMDescription("Python code snippet that defines a pandas DataFrame `df`") code: String,
        @LLMDescription("Timeout seconds for python execution") timeoutSec: Long = 10,
        @LLMDescription("Optional random seed to make data generation deterministic") seed: Int? = 42
    ): String {
        val dir = Files.createTempDirectory("koog_datagen_")
        val outCsv = dir.resolve("generated_predict.csv").toAbsolutePath().toString()

        // Python harness: AST checks + restricted builtins + writes df to outCsv
        val harness = """
import ast, json, sys
import pandas as pd
import numpy as np
import random

USER_CODE = r'''$code'''
OUT_CSV = sys.argv[1]
SEED = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2] else None

# --- Basic safety checks (not a full sandbox) ---
tree = ast.parse(USER_CODE)

FORBIDDEN_NAMES = {
    "open","exec","eval","compile","__import__","input",
    "os","sys","subprocess","socket","shutil","pathlib","requests"
}
FORBIDDEN_NODES = (ast.Import, ast.ImportFrom)

class Checker(ast.NodeVisitor):
    def visit(self, node):
        if isinstance(node, FORBIDDEN_NODES):
            raise ValueError("Imports are not allowed in generated code.")
        if isinstance(node, ast.Name) and node.id in FORBIDDEN_NAMES:
            raise ValueError(f"Forbidden name used: {node.id}")
        if isinstance(node, ast.Attribute) and getattr(node, "attr", None) in FORBIDDEN_NAMES:
            raise ValueError(f"Forbidden attribute used: {node.attr}")
        return super().visit(node)

Checker().visit(tree)

if SEED is not None:
    random.seed(SEED)
    np.random.seed(SEED)

SAFE_BUILTINS = {
    "range": range, "len": len, "min": min, "max": max, "sum": sum, "abs": abs,
    "float": float, "int": int, "str": str, "bool": bool,
    "list": list, "dict": dict, "set": set, "tuple": tuple,
    "enumerate": enumerate, "zip": zip
}

g = {"__builtins__": SAFE_BUILTINS, "pd": pd, "np": np, "random": random}

exec(compile(USER_CODE, "<generated>", "exec"), g, g)

df = g.get("df", None)
if df is None:
    raise ValueError("Generated code must define a variable named `df`.")
if not isinstance(df, pd.DataFrame):
    raise ValueError("`df` must be a pandas DataFrame.")

df.to_csv(OUT_CSV, index=False)

print(json.dumps({
    "output_path": OUT_CSV,
    "rows": int(df.shape[0]),
    "cols": int(df.shape[1])
}, ensure_ascii=False))
""".trimIndent()

        val seedArg = seed?.toString() ?: ""
        val resultJson = runProcess(
            cmd = listOf("python3", "-c", harness, outCsv, seedArg),
            workDir = dir,
            timeoutSec = timeoutSec
        )
        return resultJson
    }
}
