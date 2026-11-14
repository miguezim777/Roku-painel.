"""Servidor do Roku Painel: rastreamento de mão no navegador + controle do Roku.

Sobe API e arquivos estáticos na mesma porta (o preview do Arena publica uma
única porta), portanto o front-end usa apenas caminhos relativos.
"""

from __future__ import annotations

import asyncio
import json
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import Body, FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .roku import KEYPRESS, RokuController, RokuError

BASE_DIR = Path(__file__).resolve().parent.parent
PUBLIC_DIR = BASE_DIR / "public"

@asynccontextmanager
async def lifespan(application: FastAPI):
    """Tenta conectar ao Roku na subida; sem aparelho, cai no modo simulado."""
    try:
        await asyncio.wait_for(roku.connect(), timeout=8)
    except Exception:  # nunca impede o servidor de subir
        pass
    yield
    await roku.close()


app = FastAPI(title="Roku Painel", version="1.0.0", docs_url="/api/docs", lifespan=lifespan)


class Hub:
    """Distribui eventos (teclas, gestos, estado) para todos os painéis abertos."""

    def __init__(self) -> None:
        self.clients: set[WebSocket] = set()

    async def broadcast(self, message: dict[str, Any]) -> None:
        dead: list[WebSocket] = []
        for ws in list(self.clients):
            try:
                await ws.send_text(json.dumps(message))
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.clients.discard(ws)


hub = Hub()
roku = RokuController(emit=hub.broadcast)


# --------------------------------------------------------------------- estado
@app.get("/api/health")
async def health() -> dict[str, Any]:
    return {"ok": True, "service": "roku-painel", "roku": roku.state.mode}


@app.get("/api/state")
async def state() -> dict[str, Any]:
    return {"roku": roku.state.to_dict(), "keys": sorted(KEYPRESS)}


@app.post("/api/roku/connect")
async def connect(payload: dict[str, Any] = Body(default={})) -> dict[str, Any]:
    ip = (payload.get("ip") or "").strip() or None
    force_mock = bool(payload.get("mock"))
    try:
        await asyncio.wait_for(roku.connect(ip=ip, force_mock=force_mock), timeout=12)
    except asyncio.TimeoutError:
        raise HTTPException(status_code=504, detail="Tempo esgotado ao conectar ao Roku")
    return {"roku": roku.state.to_dict()}


@app.post("/api/roku/discover")
async def discover() -> dict[str, Any]:
    devices = await roku.discover()
    return {"devices": devices}


@app.post("/api/roku/key")
async def key(payload: dict[str, Any] = Body(...)) -> dict[str, Any]:
    name = str(payload.get("key", ""))
    source = str(payload.get("source", "api"))
    label = payload.get("label")
    try:
        state = await roku.key(name, source=source, label=label)
    except RokuError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"roku": state.to_dict()}


@app.get("/api/roku/apps")
async def apps() -> dict[str, Any]:
    try:
        return {"apps": await roku.apps()}
    except RokuError as exc:
        raise HTTPException(status_code=400, detail=str(exc))


@app.post("/api/roku/app")
async def launch(payload: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        state = await roku.launch(str(payload.get("id", "")), source=str(payload.get("source", "api")))
    except RokuError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"roku": state.to_dict()}


@app.post("/api/roku/text")
async def text(payload: dict[str, Any] = Body(...)) -> dict[str, Any]:
    try:
        state = await roku.text(str(payload.get("text", "")), source=str(payload.get("source", "api")))
    except RokuError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"roku": state.to_dict()}


@app.post("/api/roku/disconnect")
async def disconnect() -> dict[str, Any]:
    return {"roku": (await roku.disconnect()).to_dict()}


@app.post("/api/gesture")
async def gesture(payload: dict[str, Any] = Body(...)) -> dict[str, Any]:
    """Registra um gesto detectado no navegador (telemetria) e o mapeia, se pedido."""
    name = str(payload.get("gesture", ""))
    await hub.broadcast(
        {
            "kind": "gesture",
            "gesture": name,
            "hand": payload.get("hand"),
            "confidence": payload.get("confidence"),
            "roku": roku.state.to_dict(),
        }
    )
    return {"ok": True, "gesture": name}


# ------------------------------------------------------------------ websocket
@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket) -> None:
    await ws.accept()
    hub.clients.add(ws)
    try:
        await ws.send_text(json.dumps({"kind": "hello", "roku": roku.state.to_dict()}))
        while True:
            message = await ws.receive_text()
            if message == "ping":
                await ws.send_text(json.dumps({"kind": "pong"}))
    except WebSocketDisconnect:
        hub.clients.discard(ws)
    except Exception:
        hub.clients.discard(ws)


# --------------------------------------------------------------------- estático
@app.get("/")
async def index() -> FileResponse:
    return FileResponse(PUBLIC_DIR / "index.html")


@app.exception_handler(404)
async def not_found(request, exc):  # type: ignore[no-untyped-def]
    if request.url.path.startswith("/api"):
        return JSONResponse({"detail": "Rota não encontrada"}, status_code=404)
    return FileResponse(PUBLIC_DIR / "index.html")


app.mount("/", StaticFiles(directory=PUBLIC_DIR, html=True), name="public")
