/**
 * Motor de gestos do Roku Painel.
 *
 * Módulo puro (sem DOM, sem MediaPipe) para poder ser testado no Node e
 * reaproveitado por qualquer cliente. Recebe os 21 landmarks da mão no formato
 * do MediaPipe Hands e devolve gestos, dedos estendidos, pinça e zona de
 * apontamento.
 *
 * Convenção de coordenadas: x/y normalizados em [0,1] relativos ao quadro da
 * câmera (x cresce para a direita da imagem, y cresce para baixo).
 */

/** Índices dos landmarks do MediaPipe Hands. */
export const LM = {
  WRIST: 0,
  THUMB_CMC: 1,
  THUMB_MCP: 2,
  THUMB_IP: 3,
  THUMB_TIP: 4,
  INDEX_MCP: 5,
  INDEX_PIP: 6,
  INDEX_DIP: 7,
  INDEX_TIP: 8,
  MIDDLE_MCP: 9,
  MIDDLE_PIP: 10,
  MIDDLE_DIP: 11,
  MIDDLE_TIP: 12,
  RING_MCP: 13,
  RING_PIP: 14,
  RING_DIP: 15,
  RING_TIP: 16,
  PINKY_MCP: 17,
  PINKY_PIP: 18,
  PINKY_DIP: 19,
  PINKY_TIP: 20,
};

/** Conexões do esqueleto da mão (para desenhar o overlay). */
export const HAND_CONNECTIONS = [
  [0, 1], [1, 2], [2, 3], [3, 4],
  [0, 5], [5, 6], [6, 7], [7, 8],
  [5, 9], [9, 10], [10, 11], [11, 12],
  [9, 13], [13, 14], [14, 15], [15, 16],
  [13, 17], [17, 18], [18, 19], [19, 20],
  [0, 17],
];

/** Dados de apresentação de cada gesto. */
export const GESTURES = {
  open_palm: { label: "Mão aberta", hint: "Home", emoji: "🖐️" },
  fist: { label: "Punho", hint: "Voltar", emoji: "✊" },
  point: { label: "Apontar", hint: "Direcional", emoji: "☝️" },
  peace: { label: "Dois dedos", hint: "Play / Pause", emoji: "✌️" },
  pinch: { label: "Pinça", hint: "OK / Selecionar", emoji: "🤏" },
  thumb_up: { label: "Joinha", hint: "Volume +", emoji: "👍" },
  thumb_down: { label: "Joinha para baixo", hint: "Volume −", emoji: "👎" },
  none: { label: "Sem mão", hint: "—", emoji: "🫥" },
  unknown: { label: "Gesto livre", hint: "—", emoji: "🤚" },
};

const FINGER_JOINTS = {
  index: [LM.INDEX_MCP, LM.INDEX_PIP, LM.INDEX_DIP, LM.INDEX_TIP],
  middle: [LM.MIDDLE_MCP, LM.MIDDLE_PIP, LM.MIDDLE_DIP, LM.MIDDLE_TIP],
  ring: [LM.RING_MCP, LM.RING_PIP, LM.RING_DIP, LM.RING_TIP],
  pinky: [LM.PINKY_MCP, LM.PINKY_PIP, LM.PINKY_DIP, LM.PINKY_TIP],
};

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

/** Distância euclidiana 2D entre dois landmarks. */
export function distance(a, b) {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

/**
 * Tamanho da palma, usado para normalizar medidas (independe da distância da
 * mão até a câmera).
 */
export function palmSize(landmarks) {
  const a = landmarks[LM.WRIST];
  const b = landmarks[LM.MIDDLE_MCP];
  return Math.max(1e-4, distance(a, b));
}

/** Centro da palma (média entre punho e as bases dos dedos). */
export function palmCenter(landmarks) {
  const ids = [LM.WRIST, LM.INDEX_MCP, LM.MIDDLE_MCP, LM.RING_MCP, LM.PINKY_MCP];
  const sum = ids.reduce(
    (acc, id) => ({ x: acc.x + landmarks[id].x, y: acc.y + landmarks[id].y }),
    { x: 0, y: 0 },
  );
  return { x: sum.x / ids.length, y: sum.y / ids.length };
}

const angleBetween = (a, b) => {
  const dot = a.x * b.x + a.y * b.y;
  const mag = Math.hypot(a.x, a.y) * Math.hypot(b.x, b.y) || 1;
  return (Math.acos(clamp(dot / mag, -1, 1)) * 180) / Math.PI;
};

/**
 * Um dedo está estendido quando (1) a articulação do meio está quase reta
 * (pouca curvatura entre as falanges) e (2) a ponta fica mais longe do punho
 * do que essa articulação. As duas regras são invariantes à rotação e ao
 * tamanho da mão.
 */
function isFingerExtended(landmarks, [mcp, pip, dip, tip]) {
  const wrist = landmarks[LM.WRIST];
  const first = {
    x: landmarks[pip].x - landmarks[mcp].x,
    y: landmarks[pip].y - landmarks[mcp].y,
  };
  const second = {
    x: landmarks[tip].x - landmarks[pip].x,
    y: landmarks[tip].y - landmarks[pip].y,
  };
  const pipAngle = angleBetween(first, second);
  const reachTip = distance(landmarks[tip], wrist);
  const reachPip = distance(landmarks[pip], wrist);
  const straightness =
    distance(landmarks[tip], landmarks[mcp]) /
    Math.max(1e-4, distance(landmarks[mcp], landmarks[pip]) + distance(landmarks[pip], landmarks[dip]) + distance(landmarks[dip], landmarks[tip]));

  return pipAngle < 52 && straightness > 0.86 && reachTip > reachPip * 1.02;
}

/**
 * O polegar está estendido quando a ponta se afasta do centro da palma e da
 * base do indicador (polegar "aberto" em vez de cruzado na palma).
 */
function isThumbExtended(landmarks, scale) {
  const palm = palmCenter(landmarks);
  const tipFromPalm = distance(landmarks[LM.THUMB_TIP], palm);
  const mcpFromPalm = distance(landmarks[LM.THUMB_MCP], palm);
  const spread = distance(landmarks[LM.THUMB_TIP], landmarks[LM.INDEX_MCP]) / scale;
  return tipFromPalm > mcpFromPalm * 1.2 && spread > 0.5;
}

/**
 * Analisa uma mão.
 * @param {Array<{x:number,y:number}>} landmarks 21 pontos normalizados
 * @param {{mirror?:boolean}} [options]
 */
export function analyzeHand(landmarks, options = {}) {
  const { mirror = false } = options;
  const empty = {
    ok: false,
    gesture: "none",
    fingers: { thumb: false, index: false, middle: false, ring: false, pinky: false },
    extendedCount: 0,
    pinch: 1,
    center: { x: 0.5, y: 0.5 },
    indexTip: { x: 0.5, y: 0.5 },
    zone: "center",
    palm: 0,
  };
  if (!Array.isArray(landmarks) || landmarks.length < 21) return empty;

  const scale = palmSize(landmarks);
  const fingers = { thumb: isThumbExtended(landmarks, scale) };
  for (const [name, joints] of Object.entries(FINGER_JOINTS)) {
    fingers[name] = isFingerExtended(landmarks, joints);
  }

  const extendedCount = ["index", "middle", "ring", "pinky"].filter((f) => fingers[f]).length;
  const pinch = distance(landmarks[LM.THUMB_TIP], landmarks[LM.INDEX_TIP]) / scale;

  const thumbTip = landmarks[LM.THUMB_TIP];
  const thumbMcp = landmarks[LM.THUMB_MCP];
  const thumbUp = fingers.thumb && thumbTip.y < thumbMcp.y - 0.02 * scale;
  const thumbDown = fingers.thumb && thumbTip.y > thumbMcp.y + 0.02 * scale;

  let gesture = "unknown";
  const indexOnly = fingers.index && !fingers.middle && !fingers.ring && !fingers.pinky;
  const twoFingers = fingers.index && fingers.middle && !fingers.ring && !fingers.pinky;
  const noFingers = extendedCount === 0;

  if (pinch < 0.42 && !fingers.ring && !fingers.pinky) {
    gesture = "pinch";
  } else if (extendedCount >= 3) {
    gesture = "open_palm";
  } else if (twoFingers) {
    gesture = "peace";
  } else if (indexOnly) {
    gesture = "point";
  } else if (noFingers && fingers.thumb) {
    gesture = thumbDown ? "thumb_down" : thumbUp ? "thumb_up" : "unknown";
  } else if (noFingers) {
    gesture = "fist";
  }

  const tip = landmarks[LM.INDEX_TIP];
  const center = palmCenter(landmarks);
  const x = mirror ? 1 - center.x : center.x;

  return {
    ok: true,
    gesture,
    fingers,
    extendedCount,
    pinch,
    center: { x, y: center.y },
    indexTip: { x: mirror ? 1 - tip.x : tip.x, y: tip.y },
    zone: zoneOf(x, center.y),
    palm: scale,
  };
}

/**
 * Converte a posição da mão em uma direção do direcional.
 * A zona morta central evita comandos acidentais.
 */
export function zoneOf(x, y, deadzone = 0.1) {
  const dx = x - 0.5;
  const dy = y - 0.5;
  if (Math.abs(dx) < deadzone && Math.abs(dy) < deadzone) return "center";
  if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? "right" : "left";
  return dy > 0 ? "down" : "up";
}

export const ZONE_TO_KEY = {
  up: "Up",
  down: "Down",
  left: "Left",
  right: "Right",
  center: "Select",
};

/**
 * Traduz gesto + posição em uma tecla do Roku.
 * @param {{gesture:string, zone:string}} analysis
 * @param {Record<string,string>} [mapping]
 */
export function gestureToKey(analysis, mapping = DEFAULT_MAPPING) {
  if (!analysis || analysis.gesture === "none") return null;
  if (analysis.gesture === "point") {
    return analysis.zone === "center" ? null : ZONE_TO_KEY[analysis.zone];
  }
  const key = mapping[analysis.gesture];
  return key && key !== "none" ? key : null;
}

/** Mapeamento padrão gesto → tecla do Roku. */
export const DEFAULT_MAPPING = {
  open_palm: "Home",
  fist: "Back",
  pinch: "Select",
  peace: "Play",
  thumb_up: "VolumeUp",
  thumb_down: "VolumeDown",
  point: "pointing", // tratado pela zona
};

/**
 * Suaviza o gesto entre quadros: só troca quando o mesmo gesto aparece em
 * quadros consecutivos, evitando comandos fantasma.
 */
export class GestureStabilizer {
  constructor({ frames = 4 } = {}) {
    this.frames = frames;
    this.candidate = "none";
    this.count = 0;
    this.stable = "none";
  }

  update(gesture) {
    if (gesture === this.candidate) {
      this.count += 1;
    } else {
      this.candidate = gesture;
      this.count = 1;
    }
    if (this.count >= this.frames) this.stable = this.candidate;
    return this.stable;
  }

  reset() {
    this.candidate = "none";
    this.count = 0;
    this.stable = "none";
  }
}

/**
 * Dispara um comando apenas quando o mesmo rótulo é mantido por um tempo
 * (dwell), com progresso de 0 a 1 para desenhar o anel de carregamento.
 * Depois do disparo há um tempo de espera; se `repeatMs` > 0, o comando se
 * repete enquanto o gesto for mantido (por padrão apenas no direcional).
 */
export class HoldTrigger {
  /**
   * @param {{holdMs?:number, cooldownMs?:number, repeatMs?:number}} [options]
   */
  constructor({ holdMs = 550, cooldownMs = 700, repeatMs = 0, repeatWhen = null } = {}) {
    this.holdMs = holdMs;
    this.cooldownMs = cooldownMs;
    this.repeatMs = repeatMs;
    // Só o direcional repete: repetir Home/Voltar seria um comando por segundo.
    this.repeatWhen = repeatWhen || ((label) => label.startsWith("point:"));
    this.reset();
  }

  configure({ holdMs, cooldownMs, repeatMs } = {}) {
    if (typeof holdMs === "number") this.holdMs = clamp(holdMs, 120, 3000);
    if (typeof cooldownMs === "number") this.cooldownMs = clamp(cooldownMs, 100, 5000);
    if (typeof repeatMs === "number") this.repeatMs = clamp(repeatMs, 0, 5000);
  }

  reset() {
    this.label = null;
    this.since = 0;
    this.lastFired = 0;
    this.fired = 0;
    this.armed = true;
  }

  /**
   * @param {string|null} label rótulo estável atual
   * @param {number} now timestamp em ms
   * @returns {{fired:boolean, progress:number, label:string|null}}
   */
  update(label, now = Date.now()) {
    if (!label || label === "none" || label === "unknown") {
      this.reset();
      return { fired: false, progress: 0, label: null };
    }

    if (label !== this.label) {
      this.label = label;
      this.since = now;
      this.armed = true;
      this.fired = 0;
    }

    const held = now - this.since;
    const progress = clamp(held / this.holdMs, 0, 1);
    const cooledDown = this.lastFired === 0 || now - this.lastFired >= this.cooldownMs;

    if (this.armed && cooledDown && held >= this.holdMs) {
      this.fired += 1;
      this.lastFired = now;
      this.armed = false;
      return { fired: true, progress: 1, label };
    }

    if (
      !this.armed &&
      this.repeatMs > 0 &&
      this.repeatWhen(label) &&
      now - this.lastFired >= this.repeatMs
    ) {
      this.lastFired = now;
      this.fired += 1;
      return { fired: true, progress: 1, label };
    }

    return { fired: false, progress, label };
  }
}

/**
 * Monta o rótulo usado pelo HoldTrigger, combinando gesto + zona quando o
 * gesto é o de apontar (para que cada direção tenha seu próprio tempo).
 */
export function triggerLabel(analysis) {
  if (!analysis || !analysis.ok || analysis.gesture === "none") return null;
  if (analysis.gesture === "point") {
    return analysis.zone === "center" ? null : `point:${analysis.zone}`;
  }
  return analysis.gesture;
}

/** Converte o rótulo do trigger em tecla do Roku. */
export function labelToKey(label, mapping = DEFAULT_MAPPING) {
  if (!label) return null;
  if (label.startsWith("point:")) {
    const zone = label.slice("point:".length);
    return ZONE_TO_KEY[zone] || null;
  }
  const key = mapping[label];
  return key && key !== "pointing" ? key : null;
}
