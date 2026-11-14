"""Testes do controle do Roku (modo simulado, ECP e API HTTP)."""

from __future__ import annotations

import asyncio

import pytest
from fastapi.testclient import TestClient

from server.main import app
from server.roku import KEYPRESS, RokuController, RokuError


def scenario(coro_factory):
    """Roda um cenário async com um controlador novo e fecha o cliente HTTP."""

    async def runner():
        controller = RokuController()
        try:
            return await coro_factory(controller)
        finally:
            await controller.close()

    return asyncio.run(runner())


def test_connect_force_mock():
    async def run(controller: RokuController):
        state = await controller.connect(force_mock=True)
        assert state.mode == "mock"
        assert state.connected is True
        assert state.error
        return state

    assert scenario(run).name == "Roku (simulado)"


def test_key_records_history_and_counter():
    async def run(controller: RokuController):
        await controller.connect(force_mock=True)
        await controller.key("Home")
        await controller.key("Down", source="gesture")
        state = controller.state
        assert state.sent == 2
        assert state.last_key == "Down"
        assert [item["key"] for item in state.history] == ["Home", "Down"]
        assert state.history[-1]["source"] == "gesture"
        return state

    scenario(run)


def test_invalid_key_rejected():
    async def run(controller: RokuController):
        await controller.connect(force_mock=True)
        with pytest.raises(RokuError):
            await controller.key("Explode")
        with pytest.raises(RokuError):
            await controller.key("")

    scenario(run)


def test_key_requires_connection():
    async def run(controller: RokuController):
        with pytest.raises(RokuError):
            await controller.key("Home")

    scenario(run)


def test_all_whitelisted_keys_accepted():
    async def run(controller: RokuController):
        await controller.connect(force_mock=True)
        for key in sorted(KEYPRESS):
            await controller.key(key)
        assert controller.state.sent == len(KEYPRESS)

    scenario(run)


def test_apps_and_launch_in_mock():
    async def run(controller: RokuController):
        await controller.connect(force_mock=True)
        apps = await controller.apps()
        assert any(app["name"] == "Netflix" for app in apps)
        state = await controller.launch("12")
        assert state.app == "Netflix"
        with pytest.raises(RokuError):
            await controller.launch("")

    scenario(run)


def test_text_in_mock():
    async def run(controller: RokuController):
        await controller.connect(force_mock=True)
        state = await controller.text("oi")
        assert state.history[-1]["key"] == "text:oi"
        with pytest.raises(RokuError):
            await controller.text("")

    scenario(run)


def test_disconnect_resets_state():
    async def run(controller: RokuController):
        await controller.connect(force_mock=True)
        await controller.key("Select")
        state = await controller.disconnect()
        assert state.mode == "offline"
        assert state.connected is False
        with pytest.raises(RokuError):
            await controller.key("Select")

    scenario(run)


def test_discovery_returns_list_without_device():
    async def run(controller: RokuController):
        devices = await controller.discover(timeout=0.4)
        assert isinstance(devices, list)
        for device in devices:
            assert {"ip", "location"} <= set(device)

    scenario(run)


def test_events_are_broadcast():
    async def run(controller: RokuController):
        events: list[dict] = []

        async def sink(message: dict) -> None:
            events.append(message)

        controller._emit = sink  # type: ignore[attr-defined]
        await controller.connect(force_mock=True)
        await controller.key("Play", source="gesture", label="peace")
        kinds = [event["kind"] for event in events]
        assert "connected" in kinds and "key" in kinds
        key_event = next(event for event in events if event["kind"] == "key")
        assert key_event["key"] == "Play"
        assert key_event["label"] == "peace"

    scenario(run)


# --------------------------------------------------------------------- API
client = TestClient(app)


def connect_mock():
    response = client.post("/api/roku/connect", json={"mock": True})
    assert response.status_code == 200, response.text
    return response.json()["roku"]


def test_api_health_and_state():
    assert client.get("/api/health").json()["ok"] is True
    connect_mock()
    payload = client.get("/api/state").json()
    assert payload["roku"]["mode"] == "mock"
    assert "Home" in payload["keys"]


def test_api_key_endpoint():
    connect_mock()
    ok = client.post("/api/roku/key", json={"key": "Home", "source": "gesture", "label": "fist"})
    assert ok.status_code == 200
    assert ok.json()["roku"]["last_key"] == "Home"

    bad = client.post("/api/roku/key", json={"key": "Nope"})
    assert bad.status_code == 400


def test_api_apps_endpoint():
    connect_mock()
    response = client.get("/api/roku/apps")
    assert response.status_code == 200
    assert response.json()["apps"]

    launched = client.post("/api/roku/app", json={"id": "837"})
    assert launched.status_code == 200
    assert launched.json()["roku"]["app"] == "YouTube"


def test_api_serves_index():
    response = client.get("/")
    assert response.status_code == 200
    assert "Roku Painel" in response.text


def test_api_serves_mediapipe_assets():
    response = client.get("/vendor/mediapipe/hands/hands.js")
    assert response.status_code == 200
    assert len(response.content) > 10_000
