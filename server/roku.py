"""Cliente de controle do Roku (protocolo ECP) com descoberta SSDP e modo simulado.

O Roku expõe o External Control Protocol na porta 8060, sem autenticação, desde
que "Controle de rede" esteja habilitado nas configurações do aparelho
(Configurações > Sistema > Configurações avançadas > Controle por rede).
"""

from __future__ import annotations

import asyncio
import socket
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from typing import Any, Awaitable, Callable

import httpx

ECP_PORT = 8060
SSDP_ADDR = "239.255.255.250"
SSDP_PORT = 1900
SSDP_TARGET = "urn:roku:ecp:device:1"

# Teclas suportadas pelo ECP do Roku.
KEYPRESS = {
    "Home",
    "Rev",
    "Fwd",
    "Play",
    "Select",
    "Left",
    "Right",
    "Down",
    "Up",
    "Back",
    "InstantReplay",
    "Info",
    "Backspace",
    "Search",
    "Enter",
    "VolumeDown",
    "VolumeUp",
    "VolumeMute",
    "PowerOff",
    "PowerOn",
    "ChannelUp",
    "ChannelDown",
}

# Teclas que fazem sentido como botão físico (mantido pressionado).
KEYDOWN_SUPPORTED = {"Left", "Right", "Up", "Down", "Select", "Rev", "Fwd", "Play"}

# Aplicativos "de fábrica" usados no modo simulado.
MOCK_APPS = [
    {"id": "12", "name": "Netflix", "type": "appl"},
    {"id": "13", "name": "Prime Video", "type": "appl"},
    {"id": "837", "name": "YouTube", "type": "appl"},
    {"id": "2285", "name": "Globoplay", "type": "appl"},
    {"id": "19977", "name": "Disney+", "type": "appl"},
    {"id": "551012", "name": "Spotify", "type": "appl"},
]


class RokuError(RuntimeError):
    """Erro de comunicação com o Roku."""


EventSink = Callable[[dict[str, Any]], Awaitable[None]]


@dataclass
class RokuState:
    mode: str = "offline"  # live | mock | offline
    connected: bool = False
    ip: str | None = None
    name: str = "Sem dispositivo"
    model: str = ""
    serial: str = ""
    app: str | None = None
    power: str = "on"
    last_key: str | None = None
    last_key_at: float | None = None
    error: str | None = None
    sent: int = 0
    history: list[dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        data["history"] = self.history[-40:]
        return data


def sniff_model(model: str) -> str:
    """Extrai um apelido curto a partir do modelo informado pelo Roku."""
    model = (model or "").strip()
    for prefix in ("3810", "3820", "3821", "3930", "3940", "3941", "4800", "9101", "9102"):
        if model.startswith(prefix):
            return f"Roku {model}"
    return model or "Roku"


class RokuController:
    """Controla um Roku real (ECP) ou um aparelho simulado, com a mesma API."""

    def __init__(self, emit: EventSink | None = None) -> None:
        self.state = RokuState()
        self._emit = emit
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(3.0, connect=1.5))
        self._lock = asyncio.Lock()
        self._mock_apps = [dict(app) for app in MOCK_APPS]
        self._mock_app = None

    # ------------------------------------------------------------------ utils
    async def _broadcast(self, kind: str, payload: dict[str, Any] | None = None) -> None:
        if self._emit is None:
            return
        try:
            await self._emit({"kind": kind, "state": self.state.to_dict(), **(payload or {})})
        except Exception:  # broadcast nunca deve derrubar o controlador
            pass

    def _record(self, key: str, source: str) -> None:
        self.state.last_key = key
        self.state.last_key_at = time.time()
        self.state.sent += 1
        self.state.history.append(
            {"key": key, "source": source, "at": time.time(), "mode": self.state.mode}
        )
        if len(self.state.history) > 80:
            del self.state.history[:-80]

    async def close(self) -> None:
        await self._client.aclose()

    # -------------------------------------------------------------- discovery
    async def discover(self, timeout: float = 2.5) -> list[dict[str, str]]:
        """Descobre Rokus na rede local via SSDP (M-SEARCH)."""
        return await asyncio.to_thread(self._discover_blocking, timeout)

    @staticmethod
    def _discover_blocking(timeout: float) -> list[dict[str, str]]:
        payload = "\r\n".join(
            [
                "M-SEARCH * HTTP/1.1",
                f"HOST: {SSDP_ADDR}:{SSDP_PORT}",
                'MAN: "ssdp:discover"',
                "MX: 2",
                f"ST: {SSDP_TARGET}",
                "USER-AGENT: Roku-Painel/1.0 UPnP/1.1",
                "",
                "",
            ]
        ).encode()

        found: dict[str, dict[str, str]] = {}
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
            sock.settimeout(0.4)
            sock.sendto(payload, (SSDP_ADDR, SSDP_PORT))
            deadline = time.time() + timeout
            while time.time() < deadline:
                try:
                    raw, addr = sock.recvfrom(4096)
                except socket.timeout:
                    continue
                except OSError:
                    break
                headers: dict[str, str] = {}
                for line in raw.decode("utf-8", "replace").split("\r\n")[1:]:
                    if ":" in line:
                        name, _, value = line.partition(":")
                        headers[name.strip().lower()] = value.strip()
                ip = addr[0]
                if not headers.get("location"):
                    continue
                found[ip] = {
                    "ip": ip,
                    "location": headers["location"],
                    "usn": headers.get("usn", ""),
                    "server": headers.get("server", ""),
                }
        finally:
            sock.close()
        return list(found.values())

    # ------------------------------------------------------------------- ECP
    def _url(self, path: str) -> str:
        return f"http://{self.state.ip}:{ECP_PORT}{path}"

    async def _ecp_post(self, path: str) -> None:
        if not self.state.ip:
            raise RokuError("Nenhum Roku conectado")
        try:
            response = await self._client.post(self._url(path))
        except httpx.HTTPError as exc:  # rede caiu / IP errado
            raise RokuError(f"Falha ao falar com o Roku: {exc.__class__.__name__}") from exc
        if response.status_code >= 400:
            raise RokuError(f"Roku respondeu HTTP {response.status_code}")

    async def _device_info(self) -> dict[str, str]:
        if not self.state.ip:
            raise RokuError("Nenhum Roku conectado")
        try:
            response = await self._client.get(self._url("/query/device-info"))
        except httpx.HTTPError as exc:
            raise RokuError(f"Falha ao falar com o Roku: {exc.__class__.__name__}") from exc
        if response.status_code >= 400:
            raise RokuError(f"Roku respondeu HTTP {response.status_code}")
        try:
            root = ET.fromstring(response.text)
        except ET.ParseError as exc:
            raise RokuError("Resposta do Roku inválida") from exc
        return {child.tag: (child.text or "") for child in root}

    # ------------------------------------------------------------------ setup
    async def connect(self, ip: str | None = None, force_mock: bool = False) -> RokuState:
        """Conecta ao Roku informado, descobre na rede ou cai no modo simulado."""
        async with self._lock:
            self.state.error = None
            if force_mock:
                self._use_mock("Modo simulado ativado manualmente")
                await self._broadcast("connected")
                return self.state

            candidates: list[str] = []
            if ip:
                candidates.append(ip.strip())
            else:
                try:
                    devices = await self.discover()
                except Exception:
                    devices = []
                candidates.extend(dev["ip"] for dev in devices)

            if not candidates:
                self._use_mock("Nenhum Roku encontrado na rede — modo simulado")
                await self._broadcast("connected")
                return self.state

            last_error: str | None = None
            for candidate in candidates:
                self.state.ip = candidate
                try:
                    info = await self._device_info()
                except RokuError as exc:
                    last_error = str(exc)
                    continue
                self.state.mode = "live"
                self.state.connected = True
                self.state.name = info.get("friendly-device-name") or info.get(
                    "user-device-name", "Roku"
                )
                self.state.model = sniff_model(
                    info.get("model-name") or info.get("vendor-name", "")
                )
                self.state.serial = info.get("serial-number", "")
                self.state.power = info.get("power-mode", "PowerOn")
                self.state.error = None
                await self._broadcast("connected")
                return self.state

            self.state.ip = None
            self._use_mock(
                f"Não foi possível conectar ({last_error}) — modo simulado"
                if last_error
                else "Nenhum Roku encontrado — modo simulado"
            )
            await self._broadcast("connected")
            return self.state

    def _use_mock(self, reason: str) -> None:
        self.state.mode = "mock"
        self.state.connected = True
        self.state.ip = None
        self.state.name = "Roku (simulado)"
        self.state.model = "Simulador local"
        self.state.serial = "MOCK-0001"
        self.state.power = "PowerOn"
        self.state.error = reason

    # ---------------------------------------------------------------- actions
    async def key(self, key: str, source: str = "api", label: str | None = None) -> RokuState:
        key = (key or "").strip()
        if key not in KEYPRESS:
            raise RokuError(f"Tecla desconhecida: {key!r}")

        if self.state.mode == "live":
            try:
                await self._ecp_post(f"/keypress/{key}")
            except RokuError as exc:
                self.state.error = str(exc)
                await self._broadcast("error", {"message": str(exc)})
                raise
        elif self.state.mode == "mock":
            await asyncio.sleep(0.02)
        else:
            raise RokuError("Conecte a um Roku antes de enviar comandos")

        self._record(key, source)
        if key == "PowerOff":
            self.state.power = "PowerOff"
        await self._broadcast("key", {"key": key, "source": source, "label": label})
        return self.state

    async def apps(self) -> list[dict[str, str]]:
        if self.state.mode == "mock":
            return list(self._mock_apps)
        if self.state.mode != "live":
            raise RokuError("Conecte a um Roku para listar aplicativos")
        try:
            response = await self._client.get(self._url("/query/apps"))
        except httpx.HTTPError as exc:
            raise RokuError(f"Falha ao listar apps: {exc.__class__.__name__}") from exc
        if response.status_code >= 400:
            raise RokuError(f"Roku respondeu HTTP {response.status_code}")
        try:
            root = ET.fromstring(response.text)
        except ET.ParseError as exc:
            raise RokuError("Lista de apps inválida") from exc
        return [
            {"id": app.get("id", ""), "name": (app.text or "").strip(), "type": app.get("type", "appl")}
            for app in root
        ]

    async def launch(self, app_id: str, source: str = "api") -> RokuState:
        app_id = (app_id or "").strip()
        if not app_id:
            raise RokuError("Informe o id do aplicativo")
        if self.state.mode == "live":
            try:
                await self._ecp_post(f"/launch/{app_id}")
            except RokuError as exc:
                self.state.error = str(exc)
                raise
        elif self.state.mode != "mock":
            raise RokuError("Conecte a um Roku para abrir aplicativos")

        name = next((a["name"] for a in self._mock_apps if a["id"] == app_id), app_id)
        self.state.app = name
        self._record(f"launch:{name}", source)
        await self._broadcast("launch", {"app": name})
        return self.state

    async def text(self, text: str, source: str = "api") -> RokuState:
        """Digita texto no Roku usando keypress Lit_<char>."""
        text = (text or "")[:120]
        if not text:
            raise RokuError("Texto vazio")
        if self.state.mode == "live":
            for char in text:
                encoded = "%20" if char == " " else char
                try:
                    await self._ecp_post(f"/keypress/Lit_{encoded}")
                except RokuError as exc:
                    self.state.error = str(exc)
                    raise
        elif self.state.mode != "mock":
            raise RokuError("Conecte a um Roku para enviar texto")
        self._record(f"text:{text}", source)
        await self._broadcast("text", {"text": text})
        return self.state

    async def disconnect(self) -> RokuState:
        self.state.mode = "offline"
        self.state.connected = False
        self.state.ip = None
        self.state.name = "Sem dispositivo"
        self.state.model = ""
        self.state.serial = ""
        self.state.error = None
        await self._broadcast("disconnected")
        return self.state
