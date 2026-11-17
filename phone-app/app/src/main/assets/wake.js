/*
 * PAL9000 wake detector — envelope dip matcher.
 *
 * Detects a short two-burst utterance ("loo - na") in a raw RMS envelope:
 *   voice burst ── dip (quieter, not necessarily silent) ── voice burst
 *
 * Design notes:
 *  - The noise floor only adapts while the signal is near the floor, so loud
 *    speech cannot inflate the threshold above the speech level itself.
 *  - Matching is shape-based (two bumps, dip depth, span, valley length) rather
 *    than "two segments separated by silence", which is what made the legacy
 *    detector miss a naturally spoken wake word and fire on "hello there".
 *  - Every utterance that ends is reported through onDiagnostic(), so real
 *    speech can be inspected over adb logcat and the constants tuned on-device.
 *
 * Loaded as:
 *   browser/WebView : window.PALWake.createWakeDetector({ onDiagnostic })
 *   Node (tests)   : const { createWakeDetector } = require('.../wake.js')
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.PALWake = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const DEFAULTS = {
    // level gate
    smooth: 0.35,        // envelope EMA (0..1, higher = snappier)
    minThresh: 0.014,    // absolute floor of the voiced gate
    floorK: 2.4,         // threshold = max(minThresh, noiseFloor * floorK)
    floorFast: 0.30,     // attack/release when signal drops below the floor
    floorSlow: 0.010,    // drift up while signal is near the floor (quiet only)
    floorNear: 1.8,      // only track noise up to floor * this; louder = speech
    floorMin: 0.0015,
    floorMax: 0.05,

    // shape matcher
    dipRatio: 0.72,      // a burst ends once the envelope falls below this fraction of its peak
    valleyRatio: 0.85,   // valley must fall below this fraction of the smaller peak
                         // (natural "LOO-na" dips only to ~0.78 of the 2nd peak;
                         //  must stay well under 1.0 so a flat sustain can't pass)
    peakRatio: 1.6,      // each peak must exceed threshold * peakRatio
    minBumpMs: 70,       // per-burst duration bounds
    maxBumpMs: 450,
    maxValleyMs: 300,    // dip+silence between bursts (word gaps are longer)
    minSpanMs: 220,      // first burst start -> second burst end
    maxSpanMs: 750,      // "hello there" spans ~820ms -> rejected
    endSilenceMs: 260,   // unvoiced this long ends the utterance
    cooldownMs: 2500,

    // diagnostics
    traceBinMs: 50,       // envelope trace resolution inside diagnostics
    traceMaxBins: 90      // ~4.5 s of context around an utterance
  };

  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }

  function createWakeDetector(options) {
    const C = Object.assign({}, DEFAULTS, options || {});
    const onDiagnostic = typeof options === 'function' ? options : (options && options.onDiagnostic);

    let floor = 0.008;
    let env = 0;
    let thr = Math.max(C.minThresh, floor * C.floorK);
    let phase = 'idle';        // 'idle' | 'bump' | 'dip'
    let bumps = [];            // completed bumps, each has .after (dip) when known
    let cur = null;            // bump in progress
    let dip = null;            // dip in progress
    let quietSince = null;
    let lastFire = -Infinity;
    let trace = [];             // downsampled envelope for post-mortem tuning
    let lastTraceT = -Infinity;

    function reset() {
      phase = 'idle';
      bumps = [];
      cur = null;
      dip = null;
      quietSince = null;
    }

    function evaluate(t) {
      const list = (phase === 'bump' && cur) ? bumps.concat([cur]) : bumps.slice();

      if (list.length >= 2) {
        const b1 = list[list.length - 2];
        const b2 = list[list.length - 1];
        const d1 = b2.start - b1.end;          // valley length
        const span = b2.end - b1.start;        // total span of the pair
        const dur1 = b1.end - b1.start;
        const dur2 = b2.end - b2.start;
        const valleyLow = b1.after ? b1.after.low : 0;
        const peaksOk = b1.peak > thr * C.peakRatio && b2.peak > thr * C.peakRatio;
        const shapeOk =
          dur1 >= C.minBumpMs && dur1 <= C.maxBumpMs &&
          dur2 >= C.minBumpMs && dur2 <= C.maxBumpMs &&
          d1 > 0 && d1 <= C.maxValleyMs &&
          span >= C.minSpanMs && span <= C.maxSpanMs &&
          valleyLow <= C.valleyRatio * Math.min(b1.peak, b2.peak);

        const summary = {
          bumps: list.map(b => ({ start: b.start, end: b.end, peak: +b.peak.toFixed(4) })),
          valleyMs: Math.round(d1),
          spanMs: Math.round(span),
          valleyLow: +valleyLow.toFixed(4),
          threshold: +thr.toFixed(4),
          trace: trace.slice(),
          fired: false
        };

        if (shapeOk && peaksOk && (t - lastFire) >= C.cooldownMs) {
          lastFire = t;
          summary.fired = true;
          if (onDiagnostic) onDiagnostic(summary);
          return { type: 'wake', spanMs: span, peak1: b1.peak, peak2: b2.peak };
        }
        if (onDiagnostic) onDiagnostic(summary);
      } else if (onDiagnostic && list.length === 1 && (t - list[0].start) > 350) {
        // single sustained burst: useful on-device evidence ("did it hear me at all?")
        onDiagnostic({
          bumps: [{ start: list[0].start, end: list[0].end, peak: +list[0].peak.toFixed(4) }],
          valleyMs: 0, spanMs: Math.round(list[0].end - list[0].start),
          valleyLow: 0, threshold: +thr.toFixed(4), trace: trace.slice(), fired: false
        });
      }
      return null;
    }

    function push(t, rms) {
      // noise floor: track only quiet signal, so speech never inflates the gate
      if (rms <= floor) {
        floor += (rms - floor) * C.floorFast;
      } else if (rms <= floor * C.floorNear) {
        floor += (rms - floor) * C.floorSlow;
      }
      floor = clamp(floor, C.floorMin, C.floorMax);
      thr = Math.max(C.minThresh, floor * C.floorK);

      env += (rms - env) * C.smooth;

      if (t - lastTraceT >= C.traceBinMs) {
        lastTraceT = t;
        trace.push(Math.round(env * 10000) / 10000);
        if (trace.length > C.traceMaxBins) trace.shift();
      }

      const events = [];

      if (env > thr) {
        quietSince = null;

        if (phase === 'idle') {
          phase = 'bump';
          cur = { start: t, end: t, peak: env, after: null };
        } else if (phase === 'bump') {
          cur.end = t;
          if (env > cur.peak) cur.peak = env;
          // fall below dipRatio of our own peak -> this burst is over
          if (env < C.dipRatio * cur.peak && cur.peak > thr * C.peakRatio) {
            bumps.push(cur);
            dip = { start: t, end: t, low: env };
            cur.after = dip;
            cur = null;
            phase = 'dip';
          }
        } else if (phase === 'dip') {
          // Rebound: the envelope climbs out of the valley again. Gate on the
          // valley depth (not a fixed multiple of it) so a quieter second burst
          // can still register — "Si-RI" and "LOO-na" both have shallow dips
          // where the second syllable barely exceeds 1.25x the valley floor.
          const rebound = env > Math.max(thr * 1.4, dip.low * 1.25);
          dip.end = t;
          if (env < dip.low) dip.low = env;
          if (rebound) {
            cur = { start: t, end: t, peak: env, after: null };
            phase = 'bump';
          }
        }
      } else {
        if (phase === 'bump' && cur) {
          if (env < C.dipRatio * cur.peak) {
            bumps.push(cur);
            dip = { start: t, end: t, low: env };
            cur.after = dip;
            cur = null;
            phase = 'dip';
          } else {
            cur.end = t;
          }
        }
        if (phase === 'dip') {
          dip.end = t;
          if (env < dip.low) dip.low = env;
          if (quietSince === null) quietSince = t;
          if (t - quietSince >= C.endSilenceMs) {
            const ev = evaluate(t);
            if (ev) events.push(ev);
            reset();
          }
        }
      }
      return events;
    }

    return {
      push: push,
      level: function () { return { floor: floor, threshold: thr, voiced: env > thr, env: env }; },
      reset: reset
    };
  }

  return { createWakeDetector: createWakeDetector, DEFAULTS: DEFAULTS };
}));
