#!/usr/bin/env python
"""Lean FunASR HTTP service for the voice-chat-transcriber Minecraft mod.

Why this file exists
--------------------
The official ``funasr-server`` hardcodes its pipelines: the ``paraformer`` alias
is always SeACo ASR + the 1.13 GB ``ct-punc`` punctuation model
(see ``funasr/bin/_server_app.py`` -> ``FALLBACK_CONFIGS``), and ``--model-path``
only replaces the ASR backbone. Measured on 2026-10-02, that pipeline wastes
space for no gain:

    punctuation  ct-punc  1,131.8 MB / 0.042s  ->  ct-punc-c  282.6 MB / 0.010s
    ASR          SeACo      952.7 MB / 1.05s   ->  vanilla     848.0 MB / 0.51s

Both substitutions produced byte-identical transcripts, so this service wires the
lean combination directly through ``AutoModel`` (about 1.26 GB total including
torch, versus 2.2 GB) and exposes the same OpenAI-compatible endpoint the mod
already speaks.

Endpoints
---------
POST /v1/audio/transcriptions   multipart: file, model, language, response_format
GET  /health                    readiness + which models are loaded
GET  /v1/models                 aliases this service accepts

Only run this bound to a loopback address: there is no authentication.
"""

# NOTE: deliberately no `from __future__ import annotations` here -- it turns the
# FastAPI endpoint annotations into strings, and Pydantic then fails to resolve
# `UploadFile` at request time ("not fully defined").
import argparse
import io
import logging
import os
import re
import sys
import tempfile
import threading
import time
import wave
from pathlib import Path
from typing import Any, Optional

# --- model alias registry ---------------------------------------------------
# Keys are the short names accepted from the CLI and from the request's "model"
# form field; values are ModelScope ids (or FunASR aliases for the pipeline parts).
ASR_ALIASES = {
    "paraformer": "iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch",
    "paraformer-vanilla": "iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch",
    "paraformer-seaco": "iic/speech_seaco_paraformer_large_asr_nat-zh-cn-16k-common-vocab8404-pytorch",
    "sensevoice": "iic/SenseVoiceSmall",
}
VAD_ALIASES = {"fsmn-vad": "fsmn-vad"}
PUNC_ALIASES = {
    # lean default (282.6 MB) -- measured identical output to the large one
    "ct-punc-c": "iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch",
    # the 1.13 GB variant, kept only for anyone who wants to compare
    "ct-punc": "iic/punc_ct-transformer_cn-en-common-vocab471067-large",
}
DISABLED = {"", "none", "off", "no", "false", "0"}

CONTROL_TOKENS = re.compile(r"<\|[^|]*\|>")
MAX_UPLOAD_BYTES = 32 * 1024 * 1024  # 32 MB: far beyond a 120 s 16 kHz mono clip

# Filled in by main() so the /shutdown endpoint can ask uvicorn to exit.
shutdown_state: dict[str, Any] = {"server": None}

logger = logging.getLogger("funasr-lean")


def resolve(alias_or_id: str, table: dict[str, str]) -> str:
    """Map a short alias to its full id; pass through anything unknown."""
    value = (alias_or_id or "").strip()
    return table.get(value, value)


def strip_control_tokens(text: str) -> str:
    """SenseVoice emits <|zh|><|NEUTRAL|>... markers; drop them."""
    return CONTROL_TOKENS.sub("", text or "").strip()


def normalize_chinese(text: str) -> str:
    """Mirror the mod's TranscriptNormalizer: drop spaces between Han characters."""
    if not text:
        return ""
    text = re.sub(r"(?<=[\u3400-\u4dbf\u4e00-\u9fff])\s+(?=[\u3400-\u4dbf\u4e00-\u9fff])", "", text)
    return re.sub(r"\s+", " ", text).strip()


def describe_wav(data: bytes) -> str:
    """Best-effort description of the uploaded WAV, for the log only."""
    try:
        with wave.open(io.BytesIO(data), "rb") as handle:
            return (f"{handle.getnchannels()}ch {handle.getframerate()}Hz "
                    f"{handle.getsampwidth() * 8}bit {handle.getnframes() / handle.getframerate():.2f}s")
    except Exception:  # noqa: BLE001 - diagnostics must never break a request
        return "unreadable wav header"


class Pipeline:
    """Lazily loaded ASR (+VAD, +punctuation) stack."""

    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.asr_model_id = resolve(args.model, ASR_ALIASES)
        self.vad_model_id = resolve(args.vad_model, VAD_ALIASES) if args.vad_model not in DISABLED else None
        self.punc_model_id = resolve(args.punc_model, PUNC_ALIASES) if args.punc_model not in DISABLED else None
        self.asr: Any = None
        self.punc: Any = None

    def warm_asr(self) -> None:
        if self.asr is None:
            from funasr import AutoModel

            started = time.perf_counter()
            logger.info("loading ASR %s (device=%s)", self.asr_model_id, self.args.device)
            self.asr = AutoModel(
                model=self.asr_model_id,
                vad_model=self.vad_model_id,
                device=self.args.device,
                disable_update=True,
                disable_pbar=True,
                disable_log=True,
            )
            logger.info("ASR ready in %.1fs", time.perf_counter() - started)

    def warm_punc(self) -> None:
        if self.punc is None and self.punc_model_id:
            from funasr import AutoModel

            started = time.perf_counter()
            logger.info("loading punctuation model %s", self.punc_model_id)
            self.punc = AutoModel(
                model=self.punc_model_id,
                device=self.args.device,
                disable_update=True,
                disable_pbar=True,
                disable_log=True,
            )
            logger.info("punctuation ready in %.1fs", time.perf_counter() - started)

    def loaded(self) -> list[str]:
        names = []
        if self.asr is not None:
            names.append(Path(self.asr_model_id).name)
        if self.punc is not None:
            names.append(Path(self.punc_model_id).name if self.punc_model_id else "")
        return [name for name in names if name]

    def transcribe(self, audio: bytes, requested_model: Optional[str]) -> tuple[str, dict[str, float]]:
        """Return (text, timings). ``requested_model`` may override the ASR alias."""
        timings: dict[str, float] = {}
        if requested_model and resolve(requested_model, ASR_ALIASES) != self.asr_model_id:
            # A per-request swap would mean loading a second network; honour it by
            # switching the primary model instead of holding two in memory.
            logger.warning("request asked for model=%s; loading it as the active model", requested_model)
            self.asr = None
            self.asr_model_id = resolve(requested_model, ASR_ALIASES)
            self.warm_asr()

        self.warm_asr()

        suffix = ".wav"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as handle:
            handle.write(audio)
            path = handle.name
        try:
            started = time.perf_counter()
            result = self.asr.generate(input=path, batch_size_s=self.args.batch_size_s)
            timings["asr_s"] = time.perf_counter() - started
            text = strip_control_tokens(result[0].get("text", ""))
        finally:
            try:
                os.unlink(path)
            except OSError:
                pass

        if text and self.punc_model_id:
            try:
                self.warm_punc()
                started = time.perf_counter()
                punctuated = self.punc.generate(input=text)[0].get("text", "")
                timings["punc_s"] = time.perf_counter() - started
                text = strip_control_tokens(punctuated) or text
            except Exception as error:  # noqa: BLE001 - punctuation is optional
                logger.warning("punctuation failed, returning raw text: %r", error)

        return normalize_chinese(text), timings


def build_app(pipeline: Pipeline):
    from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
    from fastapi.responses import JSONResponse

    app = FastAPI(title="FunASR lean service", version="1.0")

    @app.get("/health")
    async def health():
        # "ready" is what the mod waits for: with --preload the models are built before
        # uvicorn starts serving, so this endpoint only answers once transcription will
        # actually be fast. Without --preload it flips to true after the first request.
        loaded = pipeline.loaded()
        return {
            "status": "ok",
            "ready": pipeline.asr is not None,
            "device": pipeline.args.device,
            "models_loaded": loaded,
            "asr": Path(pipeline.asr_model_id).name,
            "punc": pipeline.punc_model_id or "disabled",
        }

    @app.get("/v1/models")
    async def models():
        names = ["paraformer", "paraformer-seaco", "sensevoice"]
        return {"object": "list", "data": [{"id": name, "object": "model"} for name in names]}

    @app.post("/v1/audio/transcriptions")
    async def transcribe(
        file: UploadFile = File(...),
        model: Optional[str] = Form(default=None),
        language: Optional[str] = Form(default=None),  # noqa: ARG001 - accepted for compatibility
        response_format: Optional[str] = Form(default="json"),
    ):
        audio = await file.read()
        if not audio:
            raise HTTPException(status_code=400, detail="uploaded audio is empty")
        if len(audio) > MAX_UPLOAD_BYTES:
            raise HTTPException(status_code=413, detail="uploaded audio is too large")

        logger.info("transcribe: %s bytes (%s), model=%s",
                    len(audio), describe_wav(audio), model or pipeline.asr_model_id)
        try:
            text, timings = pipeline.transcribe(audio, model)
        except Exception as error:  # noqa: BLE001 - surface the reason to the client
            logger.exception("transcription failed")
            raise HTTPException(status_code=500, detail=f"{type(error).__name__}: {error}") from error

        logger.info("done in %.2fs (%s): %.60s", sum(timings.values()), timings, text)
        if response_format == "text":
            return JSONResponse(text)
        return JSONResponse({"text": text})

    @app.post("/shutdown")
    async def shutdown(request: Request):
        """Ask the service to exit, so the mod can clean up when the game closes.

        Loopback-only by design: the service is only ever bound to a loopback
        address, and a non-loopback caller is refused even so.
        """
        client = request.client.host if request.client else ""
        if client not in ("127.0.0.1", "::1", "localhost"):
            raise HTTPException(status_code=403, detail="shutdown is loopback-only")
        logger.info("shutdown requested by %s; stopping", client)

        def stop() -> None:
            # Set the flag after the response has been sent.
            time.sleep(0.2)
            if shutdown_state["server"] is not None:
                shutdown_state["server"].should_exit = True

        threading.Thread(target=stop, name="funasr-shutdown", daemon=True).start()
        return JSONResponse({"status": "stopping"})

    return app


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Lean FunASR service for the voice-chat-transcriber mod")
    parser.add_argument("--host", default="127.0.0.1", help="bind address (default: 127.0.0.1, loopback only)")
    parser.add_argument("--port", type=int, default=8321, help="port (default: 8321)")
    parser.add_argument("--device", default="cpu", help="cpu / cuda / cuda:0 / mps (default: cpu)")
    parser.add_argument("--model", default="paraformer", help="ASR alias or ModelScope id")
    parser.add_argument("--vad-model", default="fsmn-vad", help="VAD alias, or 'none' to disable")
    parser.add_argument("--punc-model", default="ct-punc-c", help="punctuation alias, or 'none' to disable")
    parser.add_argument("--batch-size-s", type=int, default=60, help="VAD batching window in seconds")
    parser.add_argument("--lazy", action="store_true",
                        help="build the models on the first request instead of before serving "
                             "(faster to accept requests, but that request waits ~1-2 minutes)")
    parser.add_argument("--log-file", default="", help="also append logs to this file (detached runs need it)")
    parser.add_argument("--status-file", default="",
                        help="write '<pid> <port>' here on start and delete it on exit")
    parser.add_argument("--verbose", action="store_true", help="debug logging")
    return parser.parse_args(argv)


def configure_logging(args: argparse.Namespace) -> None:
    """Log to stderr, and to a file when asked.

    A detached process's inherited stdout/stderr handles can stay buffered for a
    long time, which makes "the service won't start" impossible to diagnose, so a
    real file handler is the reliable path.
    """
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stderr)]
    if args.log_file:
        path = Path(args.log_file)
        path.parent.mkdir(parents=True, exist_ok=True)
        handlers.append(logging.FileHandler(path, encoding="utf-8", delay=False))
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        handlers=handlers,
        force=True,
    )


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    configure_logging(args)
    # Keep jieba (used by the punctuation tokenizer) from writing its cache into
    # the current working directory.
    os.environ.setdefault("JIEBA_CACHE_DIR", str(Path(tempfile.gettempdir()) / "funasr-jieba"))

    pipeline = Pipeline(args)
    logger.info("lean FunASR service: asr=%s vad=%s punc=%s device=%s",
                pipeline.asr_model_id, pipeline.vad_model_id or "disabled",
                pipeline.punc_model_id or "disabled", args.device)
    logger.info("model cache: %s", os.environ.get("MODELSCOPE_CACHE", "(ModelScope default)"))

    # Default is to build the models *before* serving. That way /health only starts
    # answering once transcription is genuinely fast, and the caller can show honest
    # progress instead of being told "ready" and then waiting minutes for a request.
    # The punctuation model is included because it used to load lazily and cost the
    # first real recording an extra ~30 s.
    if not args.lazy:
        pipeline.warm_asr()
        pipeline.warm_punc()

    # Record pid/port so the launcher (and the mod) can stop exactly this process.
    if args.status_file:
        try:
            status_path = Path(args.status_file)
            status_path.parent.mkdir(parents=True, exist_ok=True)
            status_path.write_text(f"{os.getpid()} {args.port}\n", encoding="utf-8")
        except OSError as error:
            logger.warning("could not write the status file: %r", error)

    import uvicorn

    config = uvicorn.Config(build_app(pipeline), host=args.host, port=args.port, log_level="warning")
    server = uvicorn.Server(config)
    shutdown_state["server"] = server
    server.run()
    logger.info("service stopped")
    if args.status_file:
        try:
            Path(args.status_file).unlink()
        except OSError:
            pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
