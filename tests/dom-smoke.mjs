/**
 * Smoke test da interface sem navegador (jsdom).
 *
 * Valida que o HTML contém todos os elementos usados pelo app, que o boot()
 * roda sem exceções e que o painel reage a eventos do WebSocket.
 *
 * Uso: npm install && npm run test:dom
 */

import { readFile } from "node:fs/promises";
import { JSDOM } from "jsdom";
import { syntheticHand } from "../public/js/demo-hand.js";

const html = await readFile(new URL("../public/index.html", import.meta.url), "utf8");

const dom = new JSDOM(html, {
  url: "http://localhost:8000/",
  pretendToBeVisual: true,
});

const { window } = dom;
const errors = [];

// --- stubs mínimos do ambiente do navegador
window.HTMLCanvasElement.prototype.getContext = function getContext() {
  return new Proxy(
    {},
    {
      get: (target, prop) => {
        if (prop === "canvas") return this;
        if (prop === "createLinearGradient")
          return () => ({ addColorStop: () => {} });
        return () => {};
      },
      set: () => true,
    },
  );
};

const state = {
  mode: "mock",
  connected: true,
  ip: null,
  name: "Roku (simulado)",
  app: null,
  power: "PowerOn",
  last_key: null,
  history: [],
  sent: 0,
};

const requests = [];
window.fetch = async (url, options = {}) => {
  requests.push({ url: String(url), options });
  const path = String(url);
  const body =
    path.includes("/api/state")
      ? { roku: state, keys: ["Home", "Back", "Select"] }
      : path.includes("/api/roku/apps")
        ? { apps: [{ id: "12", name: "Netflix", type: "appl" }] }
        : path.includes("/api/roku/key")
          ? { roku: { ...state, last_key: JSON.parse(options.body || "{}").key } }
          : { ok: true };
  return {
    ok: true,
    status: 200,
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
};

class FakeSocket {
  constructor(url) {
    this.url = url;
    this.listeners = {};
    FakeSocket.last = this;
    setTimeout(() => this.emit("open", {}), 0);
  }
  addEventListener(type, handler) {
    (this.listeners[type] ||= []).push(handler);
  }
  emit(type, event) {
    for (const handler of this.listeners[type] || []) {
      try {
        handler(event);
      } catch (error) {
        errors.push(error);
      }
    }
  }
  send() {}
  close() {}
}
window.WebSocket = FakeSocket;

globalThis.window = window;
globalThis.document = window.document;
Object.defineProperty(globalThis, "navigator", { value: window.navigator, configurable: true });
globalThis.location = window.location;
globalThis.localStorage = window.localStorage;
globalThis.requestAnimationFrame = (fn) => setTimeout(() => fn(performance.now()), 16);
globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
globalThis.WebSocket = FakeSocket;
globalThis.fetch = window.fetch;

window.addEventListener("error", (event) => errors.push(event.error || event.message));

await import("../public/js/app.js");
await new Promise((resolve) => setTimeout(resolve, 120));

const $ = (id) => window.document.getElementById(id);
const problems = [];

const legendItems = window.document.querySelectorAll(".legend-item").length;
if (legendItems !== 7) problems.push(`esperava 7 itens na legenda, achei ${legendItems}`);

if (!requests.some((request) => request.url.includes("/api/state"))) {
  problems.push("o app não consultou /api/state no boot");
}
if (!requests.some((request) => request.url.includes("/api/roku/apps"))) {
  problems.push("o app não carregou a lista de apps");
}
if (FakeSocket.last?.url !== "ws://localhost:8000/ws") {
  problems.push(`socket aberto em URL inesperada: ${FakeSocket.last?.url}`);
}

// Evento de tecla vindo do servidor deve aparecer no histórico e na TV.
FakeSocket.last.emit("message", {
  data: JSON.stringify({ kind: "key", key: "Home", source: "gesture", label: "open_palm", roku: state }),
});
await new Promise((resolve) => setTimeout(resolve, 40));

const firstLog = window.document.querySelector(".log-item");
if (!firstLog) problems.push("histórico vazio depois do evento de tecla");
if (firstLog && !firstLog.textContent.includes("Início")) {
  problems.push(`histórico não traduziu a tecla: "${firstLog.textContent}"`);
}
if (!$("tv-key").textContent.includes("Início")) {
  problems.push(`TV não mostrou a tecla: "${$("tv-key").textContent}"`);
}
if ($("chip-roku").dataset.state !== "warn") {
  problems.push(`chip do Roku deveria indicar simulado, veio "${$("chip-roku").dataset.state}"`);
}

// Clique no controle remoto precisa chamar a API.
window.document.querySelector('[data-key="Left"]').dispatchEvent(new window.Event("click", { bubbles: true }));
await new Promise((resolve) => setTimeout(resolve, 60));
const keyCall = requests.find(
  (request) => request.url.includes("/api/roku/key") && request.options.body.includes("Left"),
);
if (!keyCall) problems.push("clique no direcional não chamou /api/roku/key");

// Ajustes: abrir modal e alternar mapeamento.
$("btn-settings").dispatchEvent(new window.Event("click", { bubbles: true }));
if ($("settings-modal").hidden) problems.push("modal de ajustes não abriu");
const selects = window.document.querySelectorAll("#map-grid select");
if (selects.length !== 6) problems.push(`esperava 6 mapeamentos, achei ${selects.length}`);
$("close-settings").dispatchEvent(new window.Event("click", { bubbles: true }));

// Pipeline completo: landmarks -> gesto -> dwell -> tecla no Roku.
const tracker = window.rokuPainel.tracker;
const fist = syntheticHand("fist", { x: 0.5, y: 0.5, noise: 0 });
const deadline = Date.now() + 1200;
while (Date.now() < deadline) {
  tracker.onResult({ landmarks: fist, handedness: "Right", score: 1, demo: true });
  await new Promise((resolve) => setTimeout(resolve, 40));
}
await new Promise((resolve) => setTimeout(resolve, 80));

const gestureCall = requests.find(
  (request) =>
    request.url.includes("/api/roku/key") &&
    JSON.parse(request.options.body).key === "Back" &&
    JSON.parse(request.options.body).source === "gesture",
);
if (!gestureCall) problems.push("o gesto de punho não enviou a tecla Back ao Roku");
if (!window.document.querySelector('[data-gesture="fist"]').classList.contains("is-active")) {
  problems.push("a legenda não destacou o gesto de punho");
}
if (!window.document.querySelector(".log-item")?.textContent.includes("Voltar")) {
  problems.push("o histórico não registrou o comando vindo do gesto");
}

if (errors.length) problems.push(`exceções durante o boot: ${errors.map((e) => e.message).join(" | ")}`);

if (problems.length) {
  console.error("FALHOU\n- " + problems.join("\n- "));
  process.exit(1);
}

console.log("OK — DOM, boot, histórico, remoto e ajustes funcionando");
process.exit(0);
