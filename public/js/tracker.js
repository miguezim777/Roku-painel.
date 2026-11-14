/**
 * Rastreamento de mão no navegador (MediaPipe Hands, arquivos locais).
 *
 * O tracker cuida de câmera, inferência e desenho do esqueleto. A decisão de
 * gesto fica em gesture-engine.js, mantendo este arquivo focado em vídeo.
 *
 * Os arquivos do MediaPipe vivem em /vendor/mediapipe/hands, então nada é
 * baixado de CDN — funciona offline e em rede restrita.
 */

import { HAND_CONNECTIONS } from "./gesture-engine.js";
import { DemoHandAnimator } from "./demo-hand.js";

const DEFAULTS = {
  mirror: true,
  showSkeleton: true,
  maxHands: 1,
  minConfidence: 0.6,
  modelComplexity: 1,
  demoIntervalMs: 3200,
};

export class HandTracker {
  /**
   * @param {{
   *   video: HTMLVideoElement,
   *   canvas: HTMLCanvasElement,
   *   onResult?: (result: {landmarks:Array|null, handedness:string|null, score:number, demo:boolean}) => void,
   *   onStats?: (stats: {fps:number, latency:number}) => void,
   *   onStatus?: (status: {mode:string, message?:string}) => void,
   * }} options
   */
  constructor({ video, canvas, onResult, onStats, onStatus }) {
    this.video = video;
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.onResult = onResult || (() => {});
    this.onStats = onStats || (() => {});
    this.onStatus = onStatus || (() => {});

    this.config = { ...DEFAULTS };
    this.mode = "idle"; // idle | camera | demo | error
    this.hands = null;
    this.stream = null;
    this.running = false;
    this.lastVideoTime = -1;
    this.lastLandmarks = null;
    this.lastAnalysis = null;
    this.overlay = { progress: 0, label: null, gesture: null };
    this.demo = new DemoHandAnimator();
    this.frames = 0;
    this.fps = 0;
    this.fpsStamp = performance.now();
    this.latency = 0;
    this.rafId = null;
    this.mediapipeReady = false;
    this.sendErrors = 0;
  }

  setConfig(patch) {
    this.config = { ...this.config, ...patch };
    if (this.hands) {
      this.hands.setOptions({
        maxNumHands: this.config.maxHands,
        modelComplexity: this.config.modelComplexity,
        minDetectionConfidence: this.config.minConfidence,
        minTrackingConfidence: this.config.minConfidence,
      });
    }
  }

  /** Carrega o MediaPipe (script global `Hands`) uma única vez. */
  async loadMediaPipe() {
    if (this.hands) return this.hands;
    if (!window.Hands) {
      throw new Error("MediaPipe Hands não carregado");
    }
    const hands = new window.Hands({
      locateFile: (file) => `/vendor/mediapipe/hands/${file}`,
    });
    hands.setOptions({
      maxNumHands: this.config.maxHands,
      modelComplexity: this.config.modelComplexity,
      minDetectionConfidence: this.config.minConfidence,
      minTrackingConfidence: this.config.minConfidence,
      selfieMode: false,
    });
    hands.onResults((results) => this.handleResults(results));
    this.hands = hands;
    this.mediapipeReady = true;
    return hands;
  }

  async startCamera() {
    if (!navigator.mediaDevices?.getUserMedia) {
      this.onStatus({
        mode: "error",
        message: "Câmera indisponível: o navegador exige HTTPS e permissão de câmera.",
      });
      return false;
    }
    this.onStatus({ mode: "loading", message: "Pedindo permissão da câmera…" });
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: {
          facingMode: "user",
          width: { ideal: 960 },
          height: { ideal: 540 },
          frameRate: { ideal: 30, max: 30 },
        },
      });
    } catch (error) {
      this.onStatus({
        mode: "error",
        message:
          error?.name === "NotAllowedError"
            ? "Permissão de câmera negada. Libere o acesso ou use o modo demo."
            : `Não foi possível abrir a câmera (${error?.name || "erro"}). Use o modo demo.`,
      });
      return false;
    }

    this.video.srcObject = this.stream;
    this.video.muted = true;
    this.video.playsInline = true;
    try {
      await this.video.play();
    } catch {
      /* alguns navegadores só tocam após o primeiro frame */
    }
    this.mode = "camera";
    this.onStatus({ mode: "camera", message: "Câmera ativa" });
    return true;
  }

  startDemo() {
    this.stopStream();
    this.mode = "demo";
    this.onStatus({ mode: "demo", message: "Modo demo (mão sintética)" });
  }

  stopStream() {
    if (this.stream) {
      this.stream.getTracks().forEach((track) => track.stop());
      this.stream = null;
    }
    this.video.srcObject = null;
  }

  async start(mode = "camera") {
    this.running = true;
    if (mode === "demo") {
      this.startDemo();
    } else {
      const ok = await this.startCamera();
      if (!ok) this.startDemo();
    }
    this.loop();
  }

  stop() {
    this.running = false;
    if (this.rafId) cancelAnimationFrame(this.rafId);
    this.stopStream();
    this.mode = "idle";
  }

  /** Executa um gesto específico no modo demo (botões da interface). */
  forceDemoGesture(gesture) {
    this.demo.force(gesture);
  }

  handleResults(results) {
    const landmarks = results.multiHandLandmarks?.[0] || null;
    const handedness = results.multiHandedness?.[0]?.label || null;
    const score = results.multiHandedness?.[0]?.score ?? 0;
    this.lastLandmarks = landmarks;
    this.onResult({ landmarks, handedness, score, demo: false });
  }

  /** Loop principal: alimenta o MediaPipe e desenha o overlay. */
  loop = () => {
    if (!this.running) return;
    const now = performance.now();

    if (this.mode === "camera" && this.hands && this.video.readyState >= 2) {
      if (this.video.currentTime !== this.lastVideoTime) {
        this.lastVideoTime = this.video.currentTime;
        const started = performance.now();
        this.hands
          .send({ image: this.video })
          .then(() => {
            this.latency = performance.now() - started;
            this.sendErrors = 0;
          })
          .catch((error) => {
            this.sendErrors += 1;
            if (this.sendErrors === 5) {
              this.onStatus({
                mode: "error",
                message:
                  "O MediaPipe não conseguiu processar o vídeo. Recarregue a página ou use o modo demo.",
              });
              console.warn("Falha no MediaPipe Hands:", error);
            }
          });
      }
      this.drawCameraFrame();
    } else if (this.mode === "demo") {
      const sample = this.demo.sample(now);
      this.lastLandmarks = sample.landmarks;
      this.onResult({
        landmarks: sample.landmarks,
        handedness: "Right",
        score: 1,
        demo: true,
      });
      this.drawDemoFrame(sample.landmarks, sample.gesture);
    } else if (this.mode === "camera") {
      this.drawPlaceholder("Preparando a câmera…");
    } else {
      this.drawPlaceholder("Câmera desligada");
    }

    this.frames += 1;
    if (now - this.fpsStamp >= 500) {
      this.fps = Math.round((this.frames * 1000) / (now - this.fpsStamp));
      this.frames = 0;
      this.fpsStamp = now;
      this.onStats({ fps: this.fps, latency: Math.round(this.latency) });
    }

    this.rafId = requestAnimationFrame(this.loop);
  };

  /** Guarda o estado do gesto para desenhar o anel de progresso. */
  setOverlay(overlay) {
    this.overlay = { ...this.overlay, ...overlay };
  }

  resizeCanvas(width, height) {
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
    }
  }

  drawCameraFrame() {
    const { video, ctx } = this;
    const width = video.videoWidth || 960;
    const height = video.videoHeight || 540;
    this.resizeCanvas(width, height);

    ctx.save();
    if (this.config.mirror) {
      ctx.translate(width, 0);
      ctx.scale(-1, 1);
    }
    ctx.drawImage(video, 0, 0, width, height);
    ctx.restore();

    if (this.config.showSkeleton && this.lastLandmarks) {
      this.drawSkeleton(this.lastLandmarks, width, height);
    }
    this.drawGuides(width, height);
  }

  drawDemoFrame(landmarks, gesture) {
    const { ctx } = this;
    const width = 960;
    const height = 540;
    this.resizeCanvas(width, height);

    const gradient = ctx.createLinearGradient(0, 0, width, height);
    gradient.addColorStop(0, "#161428");
    gradient.addColorStop(0.5, "#1f1b3a");
    gradient.addColorStop(1, "#120f20");
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, width, height);

    ctx.strokeStyle = "rgba(255,255,255,0.045)";
    ctx.lineWidth = 1;
    for (let x = 0; x < width; x += 48) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, height);
      ctx.stroke();
    }
    for (let y = 0; y < height; y += 48) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(width, y);
      ctx.stroke();
    }

    ctx.fillStyle = "rgba(255,255,255,0.35)";
    ctx.font = "600 15px 'Inter', system-ui, sans-serif";
    ctx.fillText("DEMO · mão sintética", 22, 34);

    if (this.config.showSkeleton) this.drawSkeleton(landmarks, width, height);
    this.drawGuides(width, height);
    void gesture;
  }

  drawPlaceholder(text) {
    const { ctx } = this;
    const width = 960;
    const height = 540;
    this.resizeCanvas(width, height);
    ctx.fillStyle = "#141223";
    ctx.fillRect(0, 0, width, height);
    ctx.fillStyle = "rgba(255,255,255,0.55)";
    ctx.font = "600 18px 'Inter', system-ui, sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(text, width / 2, height / 2);
    ctx.textAlign = "start";
  }

  project(point) {
    const width = this.canvas.width;
    const height = this.canvas.height;
    const x = this.config.mirror ? 1 - point.x : point.x;
    return { x: x * width, y: point.y * height };
  }

  drawSkeleton(landmarks, width, height) {
    const { ctx } = this;
    const points = landmarks.map((lm) => this.project(lm));

    ctx.save();
    ctx.lineCap = "round";
    ctx.shadowColor = "rgba(124,92,255,0.65)";
    ctx.shadowBlur = 14;

    // Palma destacada
    ctx.strokeStyle = "rgba(124,92,255,0.85)";
    ctx.lineWidth = Math.max(3, width * 0.005);
    for (const [a, b] of HAND_CONNECTIONS) {
      ctx.beginPath();
      ctx.moveTo(points[a].x, points[a].y);
      ctx.lineTo(points[b].x, points[b].y);
      ctx.stroke();
    }

    const tips = new Set([4, 8, 12, 16, 20]);
    for (let i = 0; i < points.length; i += 1) {
      const radius = tips.has(i) ? Math.max(6, width * 0.008) : Math.max(3.5, width * 0.005);
      ctx.beginPath();
      ctx.fillStyle = tips.has(i) ? "#c8b6ff" : "#ffffff";
      ctx.arc(points[i].x, points[i].y, radius, 0, Math.PI * 2);
      ctx.fill();
    }

    // Anel de progresso do dwell sobre a ponta do indicador.
    if (this.overlay.progress > 0.02) {
      const tip = points[8];
      ctx.shadowBlur = 0;
      ctx.lineWidth = Math.max(4, width * 0.005);
      ctx.strokeStyle = "rgba(255,255,255,0.18)";
      ctx.beginPath();
      ctx.arc(tip.x, tip.y, 26, 0, Math.PI * 2);
      ctx.stroke();

      ctx.strokeStyle = this.overlay.progress >= 1 ? "#4ade80" : "#7c5cff";
      ctx.beginPath();
      ctx.arc(tip.x, tip.y, 26, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * this.overlay.progress);
      ctx.stroke();
    }
    ctx.restore();
    void width;
    void height;
  }

  /** Zonas do direcional desenhadas de forma discreta. */
  drawGuides(width, height) {
    const { ctx } = this;
    const active = this.overlay.gesture === "point";
    if (!active) return;

    ctx.save();
    ctx.strokeStyle = "rgba(255,255,255,0.14)";
    ctx.setLineDash([6, 8]);
    ctx.lineWidth = 2;
    const cx = width / 2;
    const cy = height / 2;
    const dead = Math.min(width, height) * 0.1;
    ctx.beginPath();
    ctx.arc(cx, cy, dead, 0, Math.PI * 2);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.restore();
  }
}
