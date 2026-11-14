/**
 * Mão sintética usada no modo demonstração.
 *
 * Permite testar todo o pipeline (classificação, dwell, comandos no Roku) sem
 * câmera — útil em preview, em máquina sem webcam ou para gravar demonstrações.
 * Gera 21 landmarks no mesmo formato do MediaPipe Hands.
 */

const BASE = [
  [0.50, 0.90], // 0 punho
  [0.40, 0.82], [0.32, 0.74], [0.27, 0.66], [0.22, 0.58], // polegar 1-4
  [0.44, 0.66], [0.44, 0.54], [0.44, 0.45], [0.44, 0.36], // indicador 5-8
  [0.50, 0.64], [0.50, 0.51], [0.50, 0.42], [0.50, 0.33], // médio 9-12
  [0.56, 0.65], [0.57, 0.53], [0.58, 0.45], [0.58, 0.37], // anelar 13-16
  [0.62, 0.69], [0.64, 0.60], [0.65, 0.53], [0.66, 0.46], // mínimo 17-20
];

const FINGER_CHAINS = {
  index: [5, 6, 7, 8],
  middle: [9, 10, 11, 12],
  ring: [13, 14, 15, 16],
  pinky: [17, 18, 19, 20],
};

const THUMB_CHAIN = [1, 2, 3, 4];
const PALM_CENTER = [0.52, 0.72];

const sub = (a, b) => [a[0] - b[0], a[1] - b[1]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1]];
const mul = (a, k) => [a[0] * k, a[1] * k];
const norm = (a) => {
  const len = Math.hypot(a[0], a[1]) || 1;
  return [a[0] / len, a[1] / len];
};
const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];

/** Curva uma cadeia de dedos em direção à palma (fecha o dedo no meio). */
function curlChain(points, chain, amount) {
  const mcp = points[chain[0]];
  const tip = points[chain[chain.length - 1]];
  const direction = norm(sub(tip, mcp));
  const towardPalm = norm(sub(PALM_CENTER, mcp));
  const length = Math.hypot(...sub(tip, mcp));
  const targets = [
    add(mcp, mul(direction, length * 0.5)),
    add(add(mcp, mul(direction, length * 0.66)), mul(towardPalm, length * 0.3)),
    add(add(mcp, mul(direction, length * 0.44)), mul(towardPalm, length * 0.55)),
  ];
  for (let i = 1; i < chain.length; i += 1) {
    points[chain[i]] = lerp(points[chain[i]], targets[i - 1], Math.min(1, amount));
  }
}

/** Fecha o polegar sobre a palma. */
function curlThumb(points, amount) {
  for (let i = 1; i < THUMB_CHAIN.length; i += 1) {
    const id = THUMB_CHAIN[i];
    const target = lerp(points[id], PALM_CENTER, 0.55 * amount);
    points[id] = lerp(points[id], target, Math.min(1, amount));
  }
}

/**
 * Formas de mão disponíveis no modo demo, em ordem de apresentação.
 */
export const DEMO_SEQUENCE = [
  "open_palm",
  "point",
  "pinch",
  "fist",
  "peace",
  "thumb_up",
  "thumb_down",
];

/**
 * Gera os 21 landmarks de uma mão sintética.
 * @param {string} gesture
 * @param {{x?:number,y?:number,scale?:number,rotation?:number,noise?:number}} [options]
 * @returns {Array<{x:number,y:number,z:number}>}
 */
export function syntheticHand(gesture, options = {}) {
  const { x = 0.5, y = 0.55, scale = 0.85, rotation = 0, noise = 0.0015 } = options;

  const points = BASE.map((p) => [...p]);
  const folded = { index: false, middle: false, ring: false, pinky: false };
  let thumbFolded = false;

  switch (gesture) {
    case "fist":
      Object.keys(folded).forEach((key) => (folded[key] = true));
      thumbFolded = true;
      break;
    case "point":
      folded.middle = folded.ring = folded.pinky = true;
      thumbFolded = true;
      break;
    case "peace":
      folded.ring = folded.pinky = true;
      break;
    case "pinch":
      folded.ring = folded.pinky = true;
      break;
    case "thumb_up":
    case "thumb_down":
      Object.keys(folded).forEach((key) => (folded[key] = true));
      break;
    case "open_palm":
    default:
      break;
  }

  for (const [name, chain] of Object.entries(FINGER_CHAINS)) {
    if (folded[name]) curlChain(points, chain, 1);
  }
  if (thumbFolded) {
    curlThumb(points, gesture === "point" ? 0.85 : 1);
  }

  if (gesture === "pinch") {
    const target = add(mul(points[8], 0.65), mul(points[4], 0.35));
    points[8] = [...target];
    points[4] = lerp(points[4], target, 0.92);
    points[7] = lerp(points[7], target, 0.8);
    points[6] = lerp(points[6], target, 0.35);
    points[3] = lerp(points[3], target, 0.45);
    points[2] = lerp(points[2], target, 0.15);
  }

  if (gesture === "thumb_up" || gesture === "thumb_down") {
    const sign = gesture === "thumb_up" ? -1 : 1;
    points[1] = [0.45, 0.76];
    points[2] = [0.42, 0.76 + sign * 0.11];
    points[3] = [0.39, 0.76 + sign * 0.21];
    points[4] = [0.37, 0.76 + sign * 0.31];
  }

  // Normaliza a forma para o centro da palma e aplica escala/rotação/posição.
  const cx = (points[0][0] + points[9][0] + points[17][0]) / 3;
  const cy = (points[0][1] + points[9][1] + points[17][1]) / 3;
  const cos = Math.cos(rotation);
  const sin = Math.sin(rotation);

  return points.map(([px, py]) => {
    const dx = (px - cx) * scale;
    const dy = (py - cy) * scale;
    return {
      x: x + dx * cos - dy * sin + (Math.random() - 0.5) * noise,
      y: y + dx * sin + dy * cos + (Math.random() - 0.5) * noise,
      z: 0,
    };
  });
}

/**
 * Anima a mão sintética percorrendo a sequência de gestos e passeando pelo
 * quadro (para exercitar o direcional).
 */
export class DemoHandAnimator {
  constructor({ intervalMs = 3200, mapping = null } = {}) {
    this.intervalMs = intervalMs;
    this.started = performance.now();
    this.current = DEMO_SEQUENCE[0];
    this.forced = null;
  }

  /** Força um gesto específico (usado por botões da interface). */
  force(gesture) {
    this.forced = gesture;
    if (gesture) this.current = gesture;
    this.started = performance.now();
  }

  /** @returns {{gesture:string, landmarks:Array}} */
  sample(now = performance.now()) {
    const elapsed = now - this.started;
    const index = this.forced
      ? DEMO_SEQUENCE.indexOf(this.forced)
      : Math.floor(elapsed / this.intervalMs) % DEMO_SEQUENCE.length;
    this.current = this.forced || DEMO_SEQUENCE[Math.max(0, index)];

    // Passeia devagar pelo quadro: útil para testar as zonas do direcional.
    const t = elapsed / 1000;
    let x = 0.5 + Math.sin(t * 0.55) * 0.22;
    let y = 0.52 + Math.cos(t * 0.4) * 0.14;

    if (this.current === "point") {
      // Quando aponta, visita as quatro zonas de forma previsível.
      const phase = Math.floor(t / 1.6) % 4;
      const spots = [
        [0.5, 0.26],
        [0.5, 0.78],
        [0.26, 0.52],
        [0.78, 0.52],
      ];
      const [tx, ty] = spots[phase];
      x = x * 0.25 + tx * 0.75;
      y = y * 0.25 + ty * 0.75;
    }

    return {
      gesture: this.current,
      landmarks: syntheticHand(this.current, {
        x,
        y,
        rotation: Math.sin(t * 0.7) * 0.08,
        scale: 0.82 + Math.sin(t * 1.3) * 0.04,
      }),
    };
  }
}
