/**
 * Testes do motor de gestos (Node: `node --test tests/`).
 * Rodam sem navegador e sem câmera, usando a mão sintética do modo demo.
 */

import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_MAPPING,
  GESTURES,
  GestureStabilizer,
  HoldTrigger,
  analyzeHand,
  gestureToKey,
  labelToKey,
  palmCenter,
  triggerLabel,
  zoneOf,
} from "../public/js/gesture-engine.js";
import { DEMO_SEQUENCE, DemoHandAnimator, syntheticHand } from "../public/js/demo-hand.js";

const at = (gesture, options = {}) =>
  analyzeHand(syntheticHand(gesture, { x: 0.5, y: 0.5, noise: 0, ...options }));

test("reconhece cada gesto da mão sintética", () => {
  const expected = {
    open_palm: "open_palm",
    fist: "fist",
    point: "point",
    peace: "peace",
    pinch: "pinch",
    thumb_up: "thumb_up",
    thumb_down: "thumb_down",
  };
  for (const [input, wanted] of Object.entries(expected)) {
    assert.equal(at(input).gesture, wanted, `gesto ${input}`);
  }
});

test("conta dedos estendidos da mão aberta", () => {
  const hand = at("open_palm");
  assert.equal(hand.extendedCount, 4);
  assert.equal(hand.fingers.thumb, true);
  assert.equal(hand.fingers.pinky, true);
});

test("punho não tem dedos estendidos", () => {
  const hand = at("fist");
  assert.equal(hand.extendedCount, 0);
  assert.deepEqual(Object.values(hand.fingers), [false, false, false, false, false]);
});

test("pinça aproxima polegar e indicador", () => {
  const hand = at("pinch");
  assert.ok(hand.pinch < 0.25, `pinch medido: ${hand.pinch}`);
  assert.ok(at("open_palm").pinch > 0.8);
});

test("classificação é invariante a escala e rotação", () => {
  for (const scale of [0.4, 0.7, 1.1]) {
    for (const rotation of [-0.5, 0, 0.6]) {
      const hand = analyzeHand(syntheticHand("open_palm", { scale, rotation, noise: 0 }));
      assert.equal(hand.gesture, "open_palm", `escala ${scale} rotação ${rotation}`);
    }
  }
});

test("landmarks inválidos não quebram a análise", () => {
  for (const value of [null, undefined, [], [1, 2, 3]]) {
    const hand = analyzeHand(value);
    assert.equal(hand.ok, false);
    assert.equal(hand.gesture, "none");
    assert.equal(hand.extendedCount, 0);
  }
});

test("zona morta central do direcional", () => {
  assert.equal(zoneOf(0.5, 0.5), "center");
  assert.equal(zoneOf(0.53, 0.48), "center");
  assert.equal(zoneOf(0.2, 0.5), "left");
  assert.equal(zoneOf(0.8, 0.5), "right");
  assert.equal(zoneOf(0.5, 0.15), "up");
  assert.equal(zoneOf(0.5, 0.85), "down");
  assert.equal(zoneOf(0.75, 0.62), "right");
});

test("apontar para cada borda gera a tecla certa", () => {
  const cases = [
    [{ x: 0.5, y: 0.2 }, "Up"],
    [{ x: 0.5, y: 0.85 }, "Down"],
    [{ x: 0.2, y: 0.5 }, "Left"],
    [{ x: 0.85, y: 0.5 }, "Right"],
  ];
  for (const [position, expected] of cases) {
    const hand = at("point", position);
    assert.equal(hand.gesture, "point");
    assert.equal(labelToKey(triggerLabel(hand)), expected);
  }
  const center = at("point", { x: 0.51, y: 0.5 });
  assert.equal(triggerLabel(center), null, "zona central não dispara");
});

test("mapeamento padrão cobre todos os gestos", () => {
  for (const gesture of Object.keys(GESTURES)) {
    if (["none", "unknown", "point"].includes(gesture)) continue;
    assert.ok(DEFAULT_MAPPING[gesture], `mapeamento ausente para ${gesture}`);
  }
  assert.equal(gestureToKey(at("fist")), "Back");
  assert.equal(gestureToKey(at("open_palm")), "Home");
  assert.equal(gestureToKey(at("pinch")), "Select");
  assert.equal(gestureToKey({ gesture: "none" }), null);
});

test("mapeamento personalizado é respeitado", () => {
  const custom = { ...DEFAULT_MAPPING, fist: "Info" };
  assert.equal(labelToKey("fist", custom), "Info");
  const point = at("point", { x: 0.15, y: 0.5 });
  assert.equal(labelToKey(triggerLabel(point), custom), "Left");
});

test("estabilizador exige quadros consecutivos", () => {
  const stabilizer = new GestureStabilizer({ frames: 3 });
  assert.equal(stabilizer.update("fist"), "none");
  assert.equal(stabilizer.update("fist"), "none");
  assert.equal(stabilizer.update("fist"), "fist");
  assert.equal(stabilizer.update("open_palm"), "fist");
  assert.equal(stabilizer.update("open_palm"), "fist");
  assert.equal(stabilizer.update("open_palm"), "open_palm");
});

test("dwell só dispara após o tempo configurado", () => {
  const trigger = new HoldTrigger({ holdMs: 500, cooldownMs: 400 });
  assert.equal(trigger.update("fist", 1000).fired, false);
  assert.equal(trigger.update("fist", 1200).fired, false);
  const half = trigger.update("fist", 1300);
  assert.equal(half.fired, false);
  assert.ok(Math.abs(half.progress - 0.6) < 1e-6);
  assert.equal(trigger.update("fist", 1500).fired, true);
  // sem trocar de gesto não repete
  assert.equal(trigger.update("fist", 1600).fired, false);
  // trocar de gesto rearma
  assert.equal(trigger.update("open_palm", 1700).fired, false);
  assert.equal(trigger.update("open_palm", 2200).fired, true);
});

test("dwell respeita o cooldown entre gestos diferentes", () => {
  const trigger = new HoldTrigger({ holdMs: 100, cooldownMs: 1000 });
  assert.equal(trigger.update("fist", 0).fired, false);
  assert.equal(trigger.update("fist", 150).fired, true);
  assert.equal(trigger.update("open_palm", 200).fired, false);
  assert.equal(trigger.update("open_palm", 400).fired, false, "cooldown ativo");
  assert.equal(trigger.update("open_palm", 1300).fired, true);
});

test("repetição automática mantém o direcional útil", () => {
  const trigger = new HoldTrigger({ holdMs: 200, cooldownMs: 0, repeatMs: 300 });
  const events = [];
  for (let ms = 0; ms <= 1000; ms += 50) {
    if (trigger.update("point:left", ms).fired) events.push(ms);
  }
  assert.ok(events.length >= 3, `disparos: ${events}`);
  assert.equal(events[0], 200);
  assert.deepEqual(events.slice(1), events.slice(1).filter((value) => value >= 500));
});

test("repetição automática não vale para teclas de ação", () => {
  const trigger = new HoldTrigger({ holdMs: 100, cooldownMs: 0, repeatMs: 200 });
  let fires = 0;
  for (let ms = 0; ms <= 2000; ms += 50) {
    if (trigger.update("fist", ms).fired) fires += 1;
  }
  assert.equal(fires, 1, "punho deve disparar uma única vez por gesto");
});

test("sem mão o trigger zera", () => {
  const trigger = new HoldTrigger({ holdMs: 100 });
  trigger.update("fist", 0);
  const idle = trigger.update(null, 500);
  assert.equal(idle.fired, false);
  assert.equal(idle.progress, 0);
});

test("mão sintética respeita os limites do quadro", () => {
  for (const gesture of DEMO_SEQUENCE) {
    const hand = syntheticHand(gesture, { x: 0.5, y: 0.5, scale: 0.8, noise: 0 });
    assert.equal(hand.length, 21);
    for (const point of hand) {
      assert.ok(Number.isFinite(point.x) && Number.isFinite(point.y));
      assert.ok(point.x > -0.2 && point.x < 1.2, `x fora do quadro: ${point.x}`);
      assert.ok(point.y > -0.2 && point.y < 1.2, `y fora do quadro: ${point.y}`);
    }
  }
});

test("centro da palma fica próximo do meio da mão", () => {
  const hand = syntheticHand("open_palm", { x: 0.5, y: 0.5, noise: 0 });
  const center = palmCenter(hand);
  assert.ok(Math.abs(center.x - 0.5) < 0.1);
  assert.ok(Math.abs(center.y - 0.5) < 0.1);
});

test("animador do modo demo percorre os gestos", () => {
  const animator = new DemoHandAnimator({ intervalMs: 1000 });
  const seen = new Set();
  for (let ms = 0; ms < 8000; ms += 100) {
    const sample = animator.sample(ms);
    seen.add(sample.gesture);
    assert.equal(sample.landmarks.length, 21);
  }
  assert.deepEqual([...seen].sort(), [...DEMO_SEQUENCE].sort());

  animator.force("pinch");
  assert.equal(animator.sample(9000).gesture, "pinch");
});
