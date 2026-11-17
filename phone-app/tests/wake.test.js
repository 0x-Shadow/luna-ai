#!/usr/bin/env node
/*
 * Wake-word detector tests.
 *
 * Section 1 replicates the LEGACY detector logic (from index.html before the
 * rewrite) and documents its failure modes with realistic speech envelopes.
 * Section 2 tests the shipped detector: ../app/src/main/assets/wake.js
 *
 * Run: node phone-app/tests/wake.test.js
 * Exit code reflects Section 2 only (Section 1 is historical evidence).
 */

'use strict';

/* ───────────────────────── synthetic audio envelopes ───────────────────────── */

const TICK = 1000 / 60; // voiceLoop runs on requestAnimationFrame ≈ 60 Hz

// Build an rms sample series from a piecewise envelope: [[t_ms, rms], ...]
function envelope(points, totalMs) {
  const samples = [];
  for (let t = 0; t <= totalMs; t += TICK) {
    let v = points[points.length - 1][1];
    for (let i = 0; i < points.length - 1; i++) {
      const [t0, v0] = points[i];
      const [t1, v1] = points[i + 1];
      if (t >= t0 && t <= t1) {
        const k = t1 === t0 ? 0 : (t - t0) / (t1 - t0);
        v = v0 + (v1 - v0) * k;
        break;
      }
    }
    samples.push({ t, rms: v });
  }
  return samples;
}

const NOISE = 0.006; // quiet room, below threshold

// Natural "Si-ri": two voiced bursts with an amplitude DIP between them, no silence
const naturalSiri = envelope([
  [0, NOISE], [180, NOISE],
  [210, 0.10], [380, 0.095],
  [470, 0.030],
  [560, 0.075], [760, 0.07],
  [830, NOISE], [1600, NOISE],
], 1600);

// Same word with an unnatural hard silence between syllables
const pausedSiri = envelope([
  [0, NOISE], [180, NOISE],
  [210, 0.10], [400, 0.10],
  [410, NOISE], [560, NOISE],
  [570, 0.08], [760, 0.08],
  [790, NOISE], [1600, NOISE],
], 1600);

// Ordinary greeting: "hello there" — two words, silence between
const twoWords = envelope([
  [0, NOISE], [200, NOISE],
  [230, 0.09], [500, 0.09],
  [520, NOISE], [750, NOISE],
  [770, 0.085], [1050, 0.085],
  [1080, NOISE], [1900, NOISE],
], 1900);

// "hey Siri" — lead-in word, then the two-syllable wake word
const heySiri = envelope([
  [0, NOISE], [180, NOISE],
  [210, 0.09], [430, 0.09],
  [490, 0.035],
  [530, 0.085], [690, 0.085],
  [740, 0.040],
  [770, 0.07], [950, 0.07],
  [990, NOISE], [1800, NOISE],
], 1800);

const quietRoom = envelope([[0, NOISE], [1500, NOISE]], 1500);

// Natural "loo-na" (the new wake word): stressed first syllable, light second
const naturalLoona = envelope([
  [0, NOISE], [180, NOISE],
  [210, 0.11], [430, 0.10],
  [500, 0.032],
  [560, 0.07], [740, 0.065],
  [800, NOISE], [1600, NOISE],
], 1600);

// "hey loona" — lead-in word, then both syllables of the wake word
const heyLoona = envelope([
  [0, NOISE], [180, NOISE],
  [210, 0.09], [400, 0.09],
  [450, 0.035],
  [490, 0.095], [640, 0.09],
  [690, 0.038],
  [730, 0.065], [870, 0.06],
  [920, NOISE], [1800, NOISE],
], 1800);

// Single-syllable shout ("no!") — one burst, must not wake
const singleWord = envelope([
  [0, NOISE], [300, NOISE],
  [330, 0.12], [560, 0.12],
  [620, NOISE], [1500, NOISE],
], 1500);

// A single cough / clap
const singleClap = envelope([
  [0, NOISE], [400, NOISE],
  [420, 0.18], [470, 0.18],
  [540, NOISE], [1200, NOISE],
], 1200);

// Same wake word twice in quick succession (second must be swallowed by cooldown).
// envelope() starts its clock at 0, so the second utterance is generated over its
// absolute timeline and then sliced after the first utterance's end.
const doubleSiriFixed = naturalSiri.concat(
  envelope([
    [1700, NOISE], [1880, NOISE],
    [1910, 0.10], [2080, 0.095],
    [2170, 0.030],
    [2260, 0.075], [2460, 0.07],
    [2530, NOISE], [3400, NOISE],
  ], 3400).filter(s => s.t > 1700)
);

/* ─────────────────────── LEGACY detector (verbatim logic) ─────────────────────── */

function legacyWake(samples) {
  let noiseFloor = 0.008;
  let segs = [];
  let seg = null;
  let lastLoudAt = 0;
  let result = false;

  for (const { t, rms } of samples) {
    if (rms < noiseFloor) noiseFloor += (rms - noiseFloor) * 0.30;
    else noiseFloor += (rms - noiseFloor) * 0.004;
    noiseFloor = Math.max(0.0015, Math.min(0.05, noiseFloor));

    const threshold = Math.max(0.014, noiseFloor * 2.4);
    const loud = rms > threshold;

    if (loud) {
      lastLoudAt = t;
      if (!seg) seg = { start: t, end: t, peak: rms };
      seg.end = t;
      seg.peak = Math.max(seg.peak, rms);
    } else {
      if (seg && t - lastLoudAt > 110) {
        const dur = seg.end - seg.start;
        if (dur > 70 && dur < 700) segs.push({ start: seg.start, end: seg.end, peak: seg.peak });
        seg = null;
        segs = segs.filter(s => t - s.end < 1800);
      }
    }

    const recent = segs.filter(s => t - s.end < 1400);
    if (recent.length >= 2 && !result) {
      const a = recent[recent.length - 2];
      const b = recent[recent.length - 1];
      const gap = b.start - a.end;
      const span = b.end - a.start;
      const strong = a.peak > threshold * 1.8 && b.peak > threshold * 1.8;
      if (strong && gap > 35 && gap < 450 && span < 1300) result = true;
    }
  }
  return result;
}

/* ────────────────────────── shipped detector helpers ────────────────────────── */

const { createWakeDetector } = require('../app/src/main/assets/wake.js');

function runNew(samples, opts) {
  const events = [];
  const diagnostics = [];
  const det = createWakeDetector(Object.assign({
    cooldownMs: 0,
    onDiagnostic: s => diagnostics.push(s)
  }, opts));
  for (const { t, rms } of samples) {
    for (const ev of det.push(t, rms)) events.push(ev);
  }
  return { events, diagnostics };
}

/* ────────────────────────────────── assertions ────────────────────────────────── */

let failures = 0;
let legacyNotes = 0;
function check(name, actual, expected, legacy) {
  const ok = actual === expected;
  if (!ok) { if (legacy) legacyNotes++; else failures++; }
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}  (expected ${expected}, got ${actual})`);
}

console.log('\n── SECTION 1: LEGACY detector (historical evidence, does not affect exit code) ──');
check('legacy: natural "Siri" detected', legacyWake(naturalSiri), true, true);
check('legacy: paused "Si-ri" detected', legacyWake(pausedSiri), true, true);
check('legacy: "hello there" rejected', legacyWake(twoWords), false, true);
check('legacy: quiet room rejected', legacyWake(quietRoom), false, true);

console.log('\n── SECTION 2: shipped detector (wake.js) ──');
check('natural "Siri" (dip, no silence) -> wake', runNew(naturalSiri).events.length, 1);
check('paused "Si-ri" (hard silence) -> wake', runNew(pausedSiri).events.length, 1);
check('"hey Siri" (lead-in word) -> wake', runNew(heySiri).events.length, 1);
check('"hello there" -> no wake', runNew(twoWords).events.length, 0);
check('quiet room -> no wake', runNew(quietRoom).events.length, 0);
check('single clap -> no wake', runNew(singleClap).events.length, 0);

console.log('\n── SECTION 2b: wake word "loona" ──');
check('natural "loo-na" -> wake', runNew(naturalLoona).events.length, 1);
check('"hey loona" (lead-in) -> wake', runNew(heyLoona).events.length, 1);
check('single-syllable shout -> no wake', runNew(singleWord).events.length, 0);
check('quiet room (loona gate) -> no wake', runNew(quietRoom).events.length, 0);

const dup = runNew(doubleSiriFixed, { cooldownMs: 2500 });
check('repeated wake within cooldown -> exactly 1 wake', dup.events.length, 1);

const diag = runNew(naturalSiri).diagnostics;
check('diagnostics reported for the utterance', diag.length >= 1, true);
check('diagnostics describe two bumps', diag.length && diag[0].bumps.length, 2);

process.exitCode = failures ? 1 : 0;
console.log(legacyNotes ? `note: legacy detector demonstrated ${legacyNotes} known failure(s) above (historical)\n` : '');
console.log(failures ? `${failures} test(s) FAILED\n` : 'all shipped-detector tests passed\n');
