/**
 * Roku Painel — orquestra câmera, gestos, interface e chamadas à API.
 */

import {
  GESTURES,
  DEFAULT_MAPPING,
  GestureStabilizer,
  HoldTrigger,
  analyzeHand,
  labelToKey,
  triggerLabel,
} from "./gesture-engine.js";
import { HandTracker } from "./tracker.js";

const $ = (id) => document.getElementById(id);

const KEY_LABELS = {
  Home: "Início",
  Back: "Voltar",
  Select: "OK",
  Up: "Direcional ↑",
  Down: "Direcional ↓",
  Left: "Direcional ←",
  Right: "Direcional →",
  Play: "Play / Pause",
  Rev: "Retroceder",
  Fwd: "Avançar",
  VolumeUp: "Volume +",
  VolumeDown: "Volume −",
  VolumeMute: "Mudo",
  Info: "Info",
  Search: "Buscar",
  InstantReplay: "Replay",
  PowerOff: "Desligar",
};

const STORAGE_KEY = "roku-painel.settings.v1";

const DEFAULT_SETTINGS = {
  holdMs: 550,
  repeatMs: 700,
  confidence: 0.6,
  showSkeleton: true,
  mirror: true,
  mode: null, // null = perguntar | camera | demo
  mapping: { ...DEFAULT_MAPPING },
};

const loadSettings = () => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULT_SETTINGS };
    const parsed = JSON.parse(raw);
    return {
      ...DEFAULT_SETTINGS,
      ...parsed,
      mapping: { ...DEFAULT_MAPPING, ...(parsed.mapping || {}) },
    };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
};

const saveSettings = (value) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
  } catch {
    /* modo privado pode bloquear o localStorage */
  }
};

const settings = loadSettings();

const ui = {
  video: $("video"),
  canvas: $("canvas"),
  startOverlay: $("start-overlay"),
  startHint: $("start-hint"),
  badge: $("gesture-badge"),
  emoji: $("gesture-emoji"),
  gestureName: $("gesture-name"),
  gestureHint: $("gesture-hint"),
  handLabel: $("hand-label"),
  latency: $("latency"),
  fpsChip: $("chip-fps"),
  cameraChip: $("chip-camera"),
  cameraChipText: $("chip-camera-text"),
  rokuChip: $("chip-roku"),
  rokuChipText: $("chip-roku-text"),
  tvMode: $("tv-mode"),
  tvScreen: $("tv-screen"),
  tvApp: $("tv-app"),
  tvKey: $("tv-key"),
  tvSource: $("tv-source"),
  apps: $("apps"),
  logList: $("log-list"),
  legend: $("legend-grid"),
  fingers: [...document.querySelectorAll(".finger")],
  pinchBar: $("pinch-bar"),
  toast: $("toast"),
  settingsModal: $("settings-modal"),
  connectModal: $("connect-modal"),
  discoverResult: $("discover-result"),
  deviceList: $("device-list"),
  mapGrid: $("map-grid"),
  inputHold: $("input-hold"),
  inputRepeat: $("input-repeat"),
  inputConf: $("input-conf"),
  inputSkeleton: $("input-skeleton"),
  inputMirror: $("input-mirror"),
  holdValue: $("hold-value"),
  repeatValue: $("repeat-value"),
  confValue: $("conf-value"),
  inputIp: $("input-ip"),
  connectIp: $("connect-ip"),
  modeCamera: $("mode-camera"),
  modeDemo: $("mode-demo"),
};

const stabilizer = new GestureStabilizer({ frames: 4 });
const trigger = new HoldTrigger({
  holdMs: settings.holdMs,
  cooldownMs: 650,
  repeatMs: settings.repeatMs,
});

let roku = { mode: "offline", connected: false, name: "—", app: null };
let toastTimer = null;
let firedTimer = null;
let activeGesture = "none";
let lastKeySent = 0;
let pendingLocal = null;

/* ------------------------------------------------------------------ helpers */
function toast(message, { error = false } = {}) {
  ui.toast.textContent = message;
  ui.toast.classList.toggle("is-error", error);
  ui.toast.classList.add("is-visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ui.toast.classList.remove("is-visible"), 2400);
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  let data = null;
  try {
    data = await response.json();
  } catch {
    data = null;
  }
  if (!response.ok) {
    throw new Error(data?.detail || `Falha na requisição (${response.status})`);
  }
  return data;
}

function keyLabel(key) {
  return KEY_LABELS[key] || key;
}

function timeLabel(date = new Date()) {
  return date.toLocaleTimeString("pt-BR", { hour12: false });
}

/* ---------------------------------------------------------------------- UI */
function setCameraChip(state, text) {
  ui.cameraChip.dataset.state = state;
  ui.cameraChipText.textContent = text;
}

function setRokuChip() {
  if (roku.mode === "live") {
    ui.rokuChip.dataset.state = "on";
    ui.rokuChipText.textContent = roku.name || "Roku conectado";
    ui.rokuChip.title = "Controle real via ECP";
  } else if (roku.mode === "mock") {
    ui.rokuChip.dataset.state = "warn";
    ui.rokuChipText.textContent = "Roku simulado";
    ui.rokuChip.title = "Nenhum aparelho na rede — comandos simulados";
  } else {
    ui.rokuChip.dataset.state = "off";
    ui.rokuChipText.textContent = "Roku desconectado";
    ui.rokuChip.title = "";
  }
  ui.tvMode.textContent =
    roku.mode === "live" ? "ao vivo" : roku.mode === "mock" ? "simulado" : "offline";
}

function applyRokuState(next) {
  if (!next) return;
  roku = next;
  setRokuChip();
  if (next.app) ui.tvApp.textContent = next.app;
  if (next.mode === "live" && next.ip) ui.inputIp.value = next.ip;
}

function flashTv(key, source, label) {
  ui.tvKey.textContent = keyLabel(key);
  ui.tvSource.textContent = source === "gesture" && label ? `gesto ${label}` : "controle remoto";
  ui.tvScreen.classList.add("is-live");
  clearTimeout(firedTimer);
  firedTimer = setTimeout(() => ui.tvScreen.classList.remove("is-live"), 420);
}

function renderLegend() {
  ui.legend.innerHTML = "";
  const order = ["open_palm", "fist", "point", "pinch", "peace", "thumb_up", "thumb_down"];
  for (const gesture of order) {
    const meta = GESTURES[gesture];
    const key = settings.mapping[gesture];
    const item = document.createElement("button");
    item.type = "button";
    item.className = "legend-item";
    item.dataset.gesture = gesture;
    item.title =
      gesture === "point"
        ? "Aponte para as bordas do quadro para usar o direcional"
        : `Tecla: ${keyLabel(key)}`;
    item.innerHTML = `
      <span class="emoji" aria-hidden="true">${meta.emoji}</span>
      <span class="txt">
        <strong>${meta.label}</strong>
        <small>${gesture === "point" ? "direcional" : keyLabel(key)}</small>
      </span>`;
    item.addEventListener("click", () => {
      if (tracker.mode === "demo") {
        tracker.forceDemoGesture(gesture);
        toast(`Demo: ${meta.label}`);
      } else {
        toast(`Faça o gesto "${meta.label}" e segure ${Math.round(settings.holdMs / 100) / 10}s`);
      }
    });
    ui.legend.appendChild(item);
  }
}

function highlightLegend(gesture) {
  for (const item of ui.legend.children) {
    item.classList.toggle("is-active", item.dataset.gesture === gesture);
  }
}

function renderFingers(fingers) {
  for (const node of ui.fingers) {
    const value = Boolean(fingers?.[node.dataset.finger]);
    node.classList.toggle("is-extended", value);
  }
}

function updateGestureBadge(gesture) {
  const meta = GESTURES[gesture] || GESTURES.none;
  ui.badge.dataset.gesture = gesture;
  ui.emoji.textContent = meta.emoji;
  ui.gestureName.textContent = meta.label;
  const mapped = settings.mapping[gesture];
  ui.gestureHint.textContent =
    gesture === "point"
      ? "Aponte para uma das bordas"
      : mapped && mapped !== "pointing"
        ? `Tecla: ${keyLabel(mapped)}`
        : meta.hint;
  ui.badge.classList.toggle("is-active", gesture !== "none" && gesture !== "unknown");
  highlightLegend(gesture);
}

function addLog(entry) {
  const empty = ui.logList.querySelector(".log-empty");
  if (empty) empty.remove();

  const item = document.createElement("li");
  item.className = "log-item";
  item.dataset.source = entry.source || "api";
  if (entry.kind) item.dataset.kind = entry.kind;
  const detail =
    entry.detail ||
    (entry.source === "gesture" ? "reconhecido pela câmera" : "enviado pelo painel");
  item.innerHTML = `
    <span class="log-time">${timeLabel(entry.at || new Date())}</span>
    <span class="log-main">
      <span class="log-key">${entry.key === "error" ? "Erro" : keyLabel(entry.key)}</span>
      <span class="log-detail">${detail}</span>
    </span>
    <span class="log-tag">${entry.source === "gesture" ? "gesto" : entry.source === "app" ? "app" : "painel"}</span>`;

  ui.logList.prepend(item);
  while (ui.logList.children.length > 40) ui.logList.lastElementChild.remove();
}

/* -------------------------------------------------------------------- roku */
async function sendKey(key, source = "gesture", label = null) {
  const now = performance.now();
  if (source === "gesture" && now - lastKeySent < 260) return;
  lastKeySent = now;
  pendingLocal = { key, at: Date.now() };
  addLog({
    key,
    source,
    detail: label && GESTURES[label] ? `gesto: ${GESTURES[label].label}` : "enviado pelo painel",
  });
  try {
    const data = await api("/api/roku/key", {
      method: "POST",
      body: JSON.stringify({ key, source, label }),
    });
    applyRokuState(data.roku);
  } catch (error) {
    toast(error.message, { error: true });
    addLog({ key: "error", source: "api", kind: "error", detail: error.message });
  }
}

async function loadApps() {
  try {
    const data = await api("/api/roku/apps");
    ui.apps.innerHTML = "";
    for (const app of (data.apps || []).slice(0, 8)) {
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "app-chip";
      chip.textContent = app.name;
      chip.addEventListener("click", async () => {
        try {
          const res = await api("/api/roku/app", {
            method: "POST",
            body: JSON.stringify({ id: app.id, source: "app" }),
          });
          applyRokuState(res.roku);
          ui.tvApp.textContent = app.name;
          addLog({ key: `launch:${app.name}`, source: "app", detail: "app aberto" });
        } catch (error) {
          toast(error.message, { error: true });
        }
      });
      ui.apps.appendChild(chip);
    }
  } catch {
    ui.apps.innerHTML = "";
  }
}

/* ------------------------------------------------------------- websocket */
function connectSocket() {
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${location.host}/ws`);

  socket.addEventListener("message", (event) => {
    let message = null;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }
    if (message.roku) applyRokuState(message.roku);

    if (message.kind === "key") {
      const gestureLabel =
        message.label && GESTURES[message.label] ? GESTURES[message.label].label : null;
      flashTv(message.key, message.source, gestureLabel);
      // Evita duplicar no histórico o comando que este painel acabou de enviar.
      const isOwnEcho =
        pendingLocal && pendingLocal.key === message.key && Date.now() - pendingLocal.at < 900;
      if (!isOwnEcho) {
        addLog({
          key: message.key,
          source: message.source,
          detail: gestureLabel ? `gesto: ${gestureLabel}` : "enviado pelo painel",
        });
      }
      if (message.source === "gesture") pulseFired();
    }

    if (message.kind === "launch") {
      ui.tvApp.textContent = message.app;
      ui.tvKey.textContent = "App aberto";
    }

    if (message.kind === "error" && message.message) {
      addLog({ key: "error", source: "api", kind: "error", detail: message.message });
    }
  });

  socket.addEventListener("close", () => setTimeout(connectSocket, 2500));
  socket.addEventListener("error", () => socket.close());
}

function pulseFired() {
  ui.badge.classList.add("is-fired");
  setTimeout(() => ui.badge.classList.remove("is-fired"), 320);
}

/* ---------------------------------------------------------------- tracker */
const tracker = new HandTracker({
  video: ui.video,
  canvas: ui.canvas,
  onResult: handleResult,
  onStats: ({ fps, latency }) => {
    ui.fpsChip.textContent = `${fps} fps`;
    ui.latency.textContent = `${latency} ms`;
  },
  onStatus: ({ mode, message }) => {
    if (mode === "camera") {
      setCameraChip("on", "Câmera ativa");
      ui.startOverlay.hidden = true;
    } else if (mode === "demo") {
      setCameraChip("warn", "Modo demo");
      ui.startOverlay.hidden = true;
      toast("Modo demo ativo — mão sintética");
    } else if (mode === "loading") {
      setCameraChip("warn", "Abrindo câmera…");
    } else if (mode === "error") {
      setCameraChip("error", "Sem câmera");
      ui.startHint.textContent = message || "";
    }
    if (mode === "camera" || mode === "demo") {
      ui.modeCamera.classList.toggle("is-active", mode === "camera");
      ui.modeDemo.classList.toggle("is-active", mode === "demo");
      settings.mode = mode;
      saveSettings(settings);
    }
  },
});

tracker.setConfig({
  mirror: settings.mirror,
  showSkeleton: settings.showSkeleton,
  minConfidence: settings.confidence,
});

function handleResult({ landmarks, handedness, score, demo }) {
  if (!landmarks) {
    activeGesture = "none";
    stabilizer.update("none");
    trigger.update(null, performance.now());
    tracker.setOverlay({ progress: 0, gesture: "none" });
    updateGestureBadge("none");
    renderFingers(null);
    ui.pinchBar.style.width = "0%";
    ui.handLabel.textContent = "—";
    return;
  }

  const analysis = analyzeHand(landmarks, { mirror: settings.mirror });
  const stable = stabilizer.update(analysis.gesture);
  const stableAnalysis = { ...analysis, gesture: stable };
  const label = triggerLabel(stableAnalysis);
  const { fired, progress } = trigger.update(label, performance.now());

  tracker.setOverlay({ progress, gesture: stable });
  activeGesture = stable;

  updateGestureBadge(stable);
  renderFingers(analysis.fingers);
  ui.pinchBar.style.width = `${Math.min(100, Math.max(0, (1 - analysis.pinch) * 100)).toFixed(0)}%`;
  ui.handLabel.textContent = demo
    ? "demo"
    : handedness === "Left"
      ? "mão esquerda"
      : handedness === "Right"
        ? "mão direita"
        : `${Math.round((score || 0) * 100)}%`;

  if (fired) {
    const key = labelToKey(label, settings.mapping);
    if (key) {
      pulseFired();
      sendKey(key, "gesture", label.split(":")[0]);
    }
  }
}

async function startTracking(mode) {
  ui.startHint.textContent = "";
  settings.mode = mode;
  saveSettings(settings);
  try {
    await tracker.loadMediaPipe();
  } catch (error) {
    ui.startHint.textContent =
      "Não foi possível carregar o MediaPipe local. Verifique /vendor/mediapipe/hands.";
    toast(error.message, { error: true });
    return;
  }
  await tracker.start(mode);
}

/* --------------------------------------------------------------- controles */
ui.modeCamera.addEventListener("click", () => startTracking("camera"));
ui.modeDemo.addEventListener("click", () => startTracking("demo"));
$("btn-start-camera").addEventListener("click", () => startTracking("camera"));
$("btn-start-demo").addEventListener("click", () => startTracking("demo"));

for (const button of document.querySelectorAll("[data-key]")) {
  button.addEventListener("click", () => {
    const key = button.dataset.key;
    button.classList.add("is-pressed");
    setTimeout(() => button.classList.remove("is-pressed"), 160);
    sendKey(key, "api");
  });
}

$("btn-clear-log").addEventListener("click", () => {
  ui.logList.innerHTML = '<li class="log-empty">Nenhum comando ainda</li>';
});

/* --------------------------------------------------------------- modais */
function openModal(modal) {
  modal.hidden = false;
  modal.querySelector("input, button")?.focus();
}

function closeModal(modal) {
  modal.hidden = true;
}

$("btn-settings").addEventListener("click", () => {
  ui.inputHold.value = settings.holdMs;
  ui.inputRepeat.value = settings.repeatMs;
  ui.inputConf.value = Math.round(settings.confidence * 100);
  ui.inputSkeleton.checked = settings.showSkeleton;
  ui.inputMirror.checked = settings.mirror;
  ui.holdValue.textContent = `${settings.holdMs} ms`;
  ui.repeatValue.textContent = settings.repeatMs ? `${settings.repeatMs} ms` : "desligada";
  ui.confValue.textContent = `${Math.round(settings.confidence * 100)}%`;
  ui.inputIp.value = roku.ip || "";
  renderMappingInputs();
  openModal(ui.settingsModal);
});

$("close-settings").addEventListener("click", () => closeModal(ui.settingsModal));
$("btn-connect").addEventListener("click", () => openModal(ui.connectModal));
$("close-connect").addEventListener("click", () => closeModal(ui.connectModal));

for (const modal of [ui.settingsModal, ui.connectModal]) {
  modal.addEventListener("click", (event) => {
    if (event.target === modal) closeModal(modal);
  });
}

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    closeModal(ui.settingsModal);
    closeModal(ui.connectModal);
    return;
  }
  const typing = ["INPUT", "SELECT", "TEXTAREA"].includes(document.activeElement?.tagName);
  if (typing || !ui.settingsModal.hidden || !ui.connectModal.hidden) return;

  const shortcuts = {
    ArrowUp: "Up",
    ArrowDown: "Down",
    ArrowLeft: "Left",
    ArrowRight: "Right",
    Enter: "Select",
    " ": "Play",
    Backspace: "Back",
    h: "Home",
    H: "Home",
    "+": "VolumeUp",
    "-": "VolumeDown",
    m: "VolumeMute",
  };
  const key = shortcuts[event.key];
  if (key) {
    event.preventDefault();
    sendKey(key, "api");
  }
});

function renderMappingInputs() {
  ui.mapGrid.innerHTML = "";
  const gestures = ["open_palm", "fist", "pinch", "peace", "thumb_up", "thumb_down"];
  for (const gesture of gestures) {
    const meta = GESTURES[gesture];
    const wrapper = document.createElement("label");
    wrapper.className = "map-item";
    const select = document.createElement("select");
    select.dataset.gesture = gesture;
    for (const [key, label] of Object.entries(KEY_LABELS)) {
      const option = document.createElement("option");
      option.value = key;
      option.textContent = label;
      if (settings.mapping[gesture] === key) option.selected = true;
      select.appendChild(option);
    }
    select.addEventListener("change", () => {
      settings.mapping[gesture] = select.value;
    });
    wrapper.innerHTML = `<span>${meta.emoji} ${meta.label}</span>`;
    wrapper.appendChild(select);
    ui.mapGrid.appendChild(wrapper);
  }
}

ui.inputHold.addEventListener("input", () => {
  ui.holdValue.textContent = `${ui.inputHold.value} ms`;
});
ui.inputRepeat.addEventListener("input", () => {
  ui.repeatValue.textContent = Number(ui.inputRepeat.value) ? `${ui.inputRepeat.value} ms` : "desligada";
});
ui.inputConf.addEventListener("input", () => {
  ui.confValue.textContent = `${ui.inputConf.value}%`;
});

$("btn-save").addEventListener("click", () => {
  settings.holdMs = Number(ui.inputHold.value);
  settings.repeatMs = Number(ui.inputRepeat.value);
  settings.confidence = Number(ui.inputConf.value) / 100;
  settings.showSkeleton = ui.inputSkeleton.checked;
  settings.mirror = ui.inputMirror.checked;

  trigger.configure({
    holdMs: settings.holdMs,
    cooldownMs: 650,
    repeatMs: settings.repeatMs,
  });
  tracker.setConfig({
    mirror: settings.mirror,
    showSkeleton: settings.showSkeleton,
    minConfidence: settings.confidence,
  });
  saveSettings(settings);
  renderLegend();
  closeModal(ui.settingsModal);
  toast("Ajustes salvos");
});

$("btn-reset").addEventListener("click", () => {
  Object.assign(settings, { ...DEFAULT_SETTINGS, mapping: { ...DEFAULT_MAPPING }, mode: settings.mode });
  saveSettings(settings);
  trigger.configure({ holdMs: settings.holdMs, repeatMs: settings.repeatMs, cooldownMs: 650 });
  tracker.setConfig({
    mirror: settings.mirror,
    showSkeleton: settings.showSkeleton,
    minConfidence: settings.confidence,
  });
  renderLegend();
  closeModal(ui.settingsModal);
  toast("Padrões restaurados");
});

/* ------------------------------------------------------------- conexão */
async function doConnect(ip, forceMock = false) {
  const status = ui.discoverResult;
  status.classList.remove("is-error", "is-ok");
  status.textContent = ip
    ? `Conectando em ${ip}…`
    : forceMock
      ? "Ativando modo simulado…"
      : "Procurando Roku na rede (SSDP)…";
  try {
    const data = await api("/api/roku/connect", {
      method: "POST",
      body: JSON.stringify({ ip: ip || null, mock: forceMock }),
    });
    applyRokuState(data.roku);
    if (data.roku.mode === "live") {
      status.textContent = `Conectado a ${data.roku.name} (${data.roku.ip})`;
      status.classList.add("is-ok");
      toast(`Conectado: ${data.roku.name}`);
    } else {
      status.textContent = data.roku.error || "Modo simulado ativo";
      status.classList.add("is-ok");
      toast("Modo simulado ativo", {});
    }
    await loadApps();
    closeModal(ui.connectModal);
  } catch (error) {
    status.textContent = error.message;
    status.classList.add("is-error");
    toast(error.message, { error: true });
  }
}

$("btn-do-connect").addEventListener("click", () => doConnect(ui.inputIp.value.trim()));
$("btn-mock").addEventListener("click", () => doConnect(null, true));
$("connect-go").addEventListener("click", () => doConnect(ui.connectIp.value.trim()));

$("btn-discover").addEventListener("click", async () => {
  const button = $("btn-discover");
  button.disabled = true;
  button.textContent = "Procurando…";
  try {
    const data = await api("/api/roku/discover", { method: "POST", body: "{}" });
    if (!data.devices?.length) {
      ui.discoverResult.textContent = "Nenhum Roku encontrado. Confira se está na mesma rede.";
      ui.discoverResult.classList.add("is-error");
    } else {
      ui.discoverResult.textContent = `${data.devices.length} aparelho(s) encontrado(s)`;
      ui.discoverResult.classList.add("is-ok");
      for (const device of data.devices) {
        const li = document.createElement("li");
        li.innerHTML = `<span>📺 ${device.ip}</span>`;
        const use = document.createElement("button");
        use.className = "btn btn-ghost";
        use.type = "button";
        use.textContent = "Usar";
        use.addEventListener("click", () => {
          ui.inputIp.value = device.ip;
          doConnect(device.ip);
        });
        li.appendChild(use);
        ui.deviceList.appendChild(li);
      }
    }
  } catch (error) {
    ui.discoverResult.textContent = error.message;
    ui.discoverResult.classList.add("is-error");
  } finally {
    button.disabled = false;
    button.textContent = "Descobrir na rede";
  }
});

/* ------------------------------------------------------------------- boot */
async function boot() {
  renderLegend();
  updateGestureBadge("none");
  setRokuChip();
  ui.logList.innerHTML = '<li class="log-empty">Nenhum comando ainda</li>';

  try {
    const data = await api("/api/state");
    applyRokuState(data.roku);
  } catch {
    setRokuChip();
  }
  connectSocket();
  loadApps();

  if (settings.mode === "camera" || settings.mode === "demo") {
    startTracking(settings.mode);
  }
}

boot();

// Explorador de testes: expõe peças para depuração no console.
window.rokuPainel = { tracker, settings, trigger, stabilizer, api, get gesture() { return activeGesture; } };
