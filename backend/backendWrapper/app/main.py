import asyncio
import os
from typing import Optional

from fastapi import FastAPI, HTTPException, Query

os.chdir('/home/william/IdeaProjects/fuzzy-disco')

KOTLIN_CMD = [
    "java",
    "-jar",
    "/home/william/IdeaProjects/fuzzy-disco/app/build/libs/app-all.jar"
]

# Optional: set working directory / env
KOTLIN_CWD: Optional[str] = None
KOTLIN_ENV = os.environ.copy()

app = FastAPI()

proc: Optional[asyncio.subprocess.Process] = None
io_lock = asyncio.Lock()  # ensure only one request talks to stdin/stdout at a time


async def start_kotlin():
    global proc
    if proc is not None and proc.returncode is None:
        print("NO")
        return

    proc = await asyncio.create_subprocess_exec(
        *KOTLIN_CMD,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,  # capture for debugging
        cwd=KOTLIN_CWD,
        env=KOTLIN_ENV,
    )

    # (Optional) You can also start a background task to drain stderr if it’s chatty.
    # If stderr fills up and you never read it, the child can block.
    app.state.stderr_task = asyncio.create_task(_drain_stderr(proc))


async def _drain_stderr(p: asyncio.subprocess.Process):
    assert p.stderr is not None
    while True:
        line = await p.stderr.readline()
        if not line:
            break
        # Replace with proper logging if you want:
        # print("[kotlin stderr]", line.decode(errors="replace").rstrip())
        pass


async def stop_kotlin():
    global proc
    if proc is None:
        return
    if proc.returncode is None:
        proc.terminate()
        try:
            await asyncio.wait_for(proc.wait(), timeout=2.0)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
    proc = None


@app.on_event("startup")
async def on_startup():
    await start_kotlin()


@app.on_event("shutdown")
async def on_shutdown():
    await stop_kotlin()


@app.get("/send")
async def send(q: str = Query(..., description="Input to Kotlin via stdin")):
    await start_kotlin()
    assert proc is not None
    assert proc.stdin is not None
    assert proc.stdout is not None

    if proc.returncode is not None:
        raise HTTPException(500, detail=f"Kotlin process exited with code {proc.returncode}")

    # One-at-a-time: stdin/stdout are a shared stream.
    async with io_lock:
        try:
            # Send as a line; Kotlin should readLine()
            payload = (q + "\n").encode("utf-8")
            proc.stdin.write(payload)
            await proc.stdin.drain()

            # Read one line of response (newline-terminated)
            line = await asyncio.wait_for(proc.stdout.readline(), timeout=5.0)
            while True:
                print(line)
                line += await asyncio.wait_for(proc.stdout.readline(), timeout=5.0)
            if not line:
                raise HTTPException(500, detail="Kotlin process produced no output (EOF).")

            return {"out": line.decode("utf-8", errors="replace").rstrip("\n")}

        except asyncio.TimeoutError: 
            return {"out": line.decode("utf-8", errors="replace").rstrip("\n")}
        except BrokenPipeError:
            raise HTTPException(500, detail="Kotlin process stdin closed (BrokenPipe).")
