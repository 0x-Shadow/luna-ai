/*
 * Loona brain — intent parsing + Hermes client plumbing.
 *
 * Pure JS (no DOM) so it runs under node tests:
 *   node tests/brain.test.js
 *
 * Loaded in the WebView as window.PALBrain, required in node as
 * module.exports. The actual network hop happens through the native
 * bridge (CORS-free); hermesRequest() only *builds* the request.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.PALBrain = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // Speech recognition renders "Loona" in several ways, in English and Greek.
  // Greek ASR returns "Λούνα" (with the accent), "Λουνα" or "Λούνα," — none of
  // which a \b-anchored latin regex catches, because JS \b is ASCII-only.
  const WAKE_RE = /\b(loona|luna|lona|lonna|loana|loonia|looner|loonah)\b/i;

  const WAKE_TOKENS = ['loona', 'luna', 'lona', 'lonna', 'loana', 'loonia',
    'looner', 'loonah', 'lunah', 'λοuνα', 'λουνα', 'λουνας', 'λουννα', 'λοννα', 'λουναa'];

  function deaccent(s) {
    return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '');
  }

  function lev(a, b) {
    a = String(a); b = String(b);
    const m = a.length, n = b.length;
    if (!m) return n;
    if (!n) return m;
    let prev = Array.from({ length: n + 1 }, (_, j) => j);
    for (let i = 1; i <= m; i++) {
      const cur = [i];
      for (let j = 1; j <= n; j++) {
        cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1,
          prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      }
      prev = cur;
    }
    return prev[n];
  }

  // Is this single word (or a near-miss of it) the wake word?
  function isWakeToken(word) {
    const t = deaccent(String(word || '').toLowerCase()).replace(/[^\p{L}]/gu, '');
    if (!t) return false;
    if (WAKE_TOKENS.indexOf(t) >= 0) return true;
    // Loose match so "Loonah", "Luna", "Λούναα", "Λούνα" all land
    if (t.length >= 4 && t.length <= 8) {
      if (/^(loo|lu|lo|λου|λο|λουν)/.test(t)) {
        if (lev(t, 'loona') <= 2) return true;
        if (lev(t, 'λουνα') <= 2) return true;
      }
    }
    return false;
  }

  /* ── wake-word helpers ──────────────────────────────────────── */

  // "loona put a reminder ..." → { wake: true, rest: "put a reminder ..." }
  // Also handles "Hey Loona, …" and Greek forms.
  function splitWake(text) {
    const raw = String(text || '');
    const re = /\S+/g;
    let m;
    while ((m = re.exec(raw)) !== null) {
      if (!isWakeToken(m[0])) continue;
      const rest = raw.slice(m.index + m[0].length).replace(/^[\s,.!·—\-:;]+/, '');
      return { wake: true, rest: rest };
    }
    return { wake: false, rest: raw.trim() };
  }

  /* ── time parsing ──────────────────────────────────────────────── */

  const UNIT_MS = { sec: 1e3, second: 1e3, min: 6e4, minute: 6e4, hour: 36e5, day: 864e5 };

  function numWord(w) {
    const n = parseInt(w, 10);
    if (!isNaN(n)) return n;
    const words = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6,
      seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
      fifteen: 15, twenty: 20, thirty: 30, forty: 40, fortyfive: 45 };
    return words[String(w).toLowerCase()] || null;
  }

  // "in 10 minutes" | "in an hour" | "in half an hour" | "in 30 seconds"
  function parseRelative(text) {
    const half = /\bin\s+half\s+an?\s+(hour|hour)s?\b/i.exec(text);
    if (half) return 30 * 6e4;
    const m = /\bin\s+(\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty|forty)\s+(sec|secs|second|seconds|min|mins|minute|minutes|hour|hours|day|days)\b/i.exec(text);
    if (!m) return null;
    const n = numWord(m[1]);
    if (n == null) return null;
    let unit = m[2].toLowerCase();
    const key = unit.startsWith('sec') ? 'sec' : unit.startsWith('min') ? 'min'
      : unit.startsWith('hour') ? 'hour' : 'day';
    return n * UNIT_MS[key];
  }

  // "at 7" "at 7:30 pm" "at 19:30" "at 7 a.m." "quarter past seven" "half past six"
  function parseClock(text) {
    const m = /\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?\b/i.exec(text);
    if (!m) return null;
    let h = parseInt(m[1], 10);
    const min = m[2] ? parseInt(m[2], 10) : 0;
    const mer = (m[3] || '').toLowerCase().replace(/\./g, '');
    if (mer === 'pm' && h < 12) h += 12;
    if (mer === 'am' && h === 12) h = 0;
    if (mer === '' && h <= 7 && !/\b\d{2}:\d{2}\b/.test(text)) {
      // bare "at 7" in a scheduling phrase reads as the next occurrence;
      // keep 0-23 as given when it's already 24h-ish (>=8 stays as-is)
      if (h < 4) h += 12;   // "at 1", "at 2" … unlikely as 24h in speech
    }
    if (h > 23 || min > 59) return null;
    return { h: h, min: min };
  }

  function atClockTodayOrTomorrow(baseMs, clock) {
    const d = new Date(baseMs);
    d.setSeconds(0, 0);
    d.setHours(clock.h, clock.min, 0, 0);
    if (d.getTime() <= baseMs + 5000) d.setDate(d.getDate() + 1);
    return d.getTime();
  }

  // → ms epoch or null
  function parseWhen(text, nowMs) {
    const now = nowMs == null ? Date.now() : nowMs;
    const rel = parseRelative(text);
    if (rel != null) return now + rel;
    if (/\btomorrow\b/i.test(text)) {
      const c = parseClock(text);
      const d = new Date(now);
      d.setDate(d.getDate() + 1);
      if (c) { d.setHours(c.h, c.min, 0, 0); return d.getTime(); }
      d.setHours(9, 0, 0, 0);
      return d.getTime();
    }
    if (/\b(today|tonight|this (morning|afternoon|evening))\b/i.test(text) || /\bat\s+\d/i.test(text)) {
      const c = parseClock(text);
      if (c) return atClockTodayOrTomorrow(now, c);
    }
    const c2 = parseClock(text);
    if (c2 && /\b(am|pm|a\.m\.|p\.m\.)\b/i.test(text)) return atClockTodayOrTomorrow(now, c2);
    return null;
  }

  /* ── Greek: time, durations, commands, confirmations ───────────── */

  const GR_NUM = { 'ένα': 1, 'ενα': 1, 'μία': 1, 'μια': 1, 'δύο': 2, 'δυο': 2, 'τρία': 3, 'τρια': 3,
    'τέσσερα': 4, 'τεσσερα': 4, 'πέντε': 5, 'πεντε': 5, 'έξι': 6, 'εξι': 6, 'επτά': 7, 'επτα': 7,
    'οκτώ': 8, 'οκτω': 8, 'εννιά': 9, 'εννια': 9, 'δέκα': 10, 'δεκα': 10, 'δεκαπέντε': 15, 'δεκαπεντε': 15,
    'είκοσι': 20, 'εικοσι': 20, 'τριάντα': 30, 'τριαντα': 30, 'σαράντα': 40, 'σαραντα': 40,
    'πενήντα': 50, 'πενηντα': 50 };

  function grNum(w) {
    const n = parseInt(w, 10);
    if (!isNaN(n)) return n;
    return GR_NUM[String(w).toLowerCase()] || null;
  }

  // "σε 10 λεπτά", "για 5 λεπτά", "μετά από μισή ώρα", bare "10 λεπτά"
  function parseRelativeGr(text) {
    const s = String(text || '').toLowerCase();
    if (/(?:σε|για|μετ[άα]\s+απ[όο])?\s*μισ[ήη]\s+(ώρα|ωρα)/.test(s)) return 30 * 6e4;
    const NUM = '(\\d+|ένα|ενα|μία|μια|δύο|δυο|τρία|τρια|τέσσερα|τεσσερα|πέντε|πεντε|έξι|εξι|επτά|επτα|οκτώ|οκτω|εννιά|εννια|δέκα|δεκα|δεκαπέντε|δεκαπεντε|είκοσι|εικοσι|τριάντα|τριαντα|σαράντα|σαραντα|πενήντα|πενηντα)';
    const UNIT = '(δευτερόλεπτα|δευτερολεπτα|δευτερόλεπτο|δευτερολεπτο|λεπτά|λεπτα|λεπτό|λεπτο|ώρες|ωρες|ώρα|ωρα|μέρες|μερες|μέρα|μερα)';
    const prefixed = new RegExp('(?:σε|για|μετ[άα]\\s+απ[όο])\\s*' + NUM + '\\s*' + UNIT).exec(s);
    const bare = prefixed ? null : new RegExp('^\\s*' + NUM + '\\s*' + UNIT).exec(s);
    const m = prefixed || bare;
    if (!m) return null;
    const n = grNum(m[1]);
    if (n == null) return null;
    const u = m[2];
    const key = /^δευτ/.test(u) ? 'sec' : /^λεπ/.test(u) ? 'min' : /^ωρ/.test(u) ? 'hour' : 'day';
    return n * UNIT_MS[key];
  }

  function atClockTodayOrTomorrowGr(baseMs, clock) {
    return atClockTodayOrTomorrow(baseMs, clock);
  }

  function parseWhenGr(text, nowMs) {
    const now = nowMs == null ? Date.now() : nowMs;
    const s = String(text || '').toLowerCase();
    const rel = parseRelativeGr(s);
    if (rel != null) return now + rel;
    const clockM = /στις\s+(\d{1,2})(?::(\d{2}))?\s*(μμ|πμ|μ\.μ|π\.μ)?/.exec(s);
    if (clockM) {
      let h = parseInt(clockM[1], 10);
      const min = clockM[2] ? parseInt(clockM[2], 10) : 0;
      const mer = (clockM[3] || '').replace(/\./g, '');
      if ((mer === 'μμ' || mer === 'pm') && h < 12) h += 12;
      if ((mer === 'πμ' || mer === 'am') && h === 12) h = 0;
      if (/απόγευμα|απογευμα|βράδυ|βραδυ|βραδι/.test(s) && h < 12) h += 12;
      if (h <= 23 && min <= 59) {
        if (/αύριο|αυριο/.test(s)) {
          const d = new Date(now);
          d.setDate(d.getDate() + 1);
          d.setHours(h, min, 0, 0);
          return d.getTime();
        }
        return atClockTodayOrTomorrowGr(now, { h: h, min: min });
      }
    }
    if (/αύριο|αυριο/.test(s)) {
      const d = new Date(now);
      d.setDate(d.getDate() + 1);
      d.setHours(9, 0, 0, 0);
      return d.getTime();
    }
    return null;
  }

  // "Just play something" phrasing, English + Greek. A named track or artist is
  // a YouTube search; these generic ones should start playback instead of
  // leaving the user staring at a page of search results.
  const GENERIC_MUSIC = /^(?:(?:some|any(?:\s+kind\s+of)?|a\s+bit\s+of)\s+|(?:οποιαδ[ήη]ποτε|οποιαδηποτε|κάθε|καθε|ό,?τι|οτιδ[ήη]ποτε|λ[ίι]γ[ηο]|καπ[οό]ια|χαλαρ[ήη])\s+)?(?:μουσικ[ήη]?|τραγο[ύυ]δια|τραγουδια|music|songs?|something|anything|κάτι|κατι)$/;

  function isGenericMusic(q) {
    return GENERIC_MUSIC.test(String(q || '').toLowerCase().trim());
  }

  // Which music app the utterance names, if any ("play X on spotify").
  function musicApp(s) {
    const t = String(s || '').toLowerCase();
    if (/spotify|σποτιφ[άα]ι|σποτιφαι/.test(t)) return 'spotify';
    if (/youtube|you tube|yt music|youtube music|γιουτιο[ύυ]μπ|γιουτιουμπ|γιου ?τιουμπ/.test(t)) return 'youtube';
    return null;
  }

  // Greek command understanding (works offline, no model round-trip).
  function parseGreek(t, nowMs) {
    const s = String(t || '').toLowerCase();
    const now = nowMs == null ? Date.now() : nowMs;

    if (/(τι ώρα|τι ωρα|ώρα είναι|ωρα ειναι|την ώρα)/.test(s)) return { type: 'time' };
    if (/(τι μέρα|τι μερα|ημερομηνία|ημερομηνια|τι ημερομηνία)/.test(s)) return { type: 'date' };
    if (/μπαταρ/.test(s)) return { type: 'battery' };

    if (/(φακ[όο]|φακός|φακος|φλας)/.test(s)) {
      const off = /(σβ[ήη]σε|σβησε|κλε[ίι]σε|κλεισε|απενεργοποίησε|απενεργοποιησε|κλειστ)/.test(s);
      return { type: 'torch', on: !off };
    }

    let m;

    // ── Home Assistant: lights (before openApp/play, else "άνοιξε τα φώτα"
    //    and "βάλε το φως" were swallowed by those rules) ──
    if (/(φώτα|φωτα|φως|φωσ|φωτισμ)/.test(s)) {
      const off = /(σβ[ήη]σε|σβησε|κλε[ίι]σε|κλεισε|απενεργοποίησε|απενεργοποιησε)/.test(s);
      const tail = s.split(/(?:φώτα|φωτα|φως|φωσ|φωτισμ)/).pop() || '';
      // no \b here: Greek letters aren't \w, so a boundary never matches
      const tm = /(?:στ(?:ην|η|ο|ις|α|ων)|σε)\s+(?:τ[οηα]\s+)?(.+)$/.exec(tail);
      return { type: 'ha', domain: 'light', service: off ? 'turn_off' : 'turn_on',
               target: tm ? tm[1].trim() : null };
    }

    // ── Home Assistant: climate / heating ──
    if (/(κλιματισμ|κλιματιστ|θερμοκρασ|θερμανσ|θέρμανσ)/.test(s)) {
      const num = /(\d{1,2})\s*(?:βαθμ|°|c\b)?/.exec(s);
      const wantsTemp = num && /(βάλε|βαλε|στήσ|στισ|σε\s|προς|ρύθμ)/.test(s);
      if (wantsTemp) return { type: 'ha', domain: 'climate', service: 'set_temperature',
                              target: null, value: parseInt(num[1], 10) };
      const off = /(σβ[ήη]σε|σβησε|κλε[ίι]σε|κλεισε|απενεργοποίησε|απενεργοποιησε)/.test(s);
      return { type: 'ha', domain: 'climate', service: off ? 'turn_off' : 'turn_on', target: null };
    }

    m = /(?:[έε]νταση|ενταση)\s*(?:στο|σε|στο\s+)?(\d{1,3})\s*%?/.exec(s);
    if (m) return { type: 'volume', value: Math.min(100, parseInt(m[1], 10)) / 100 };
    if (/([έε]νταση|ενταση)/.test(s)) {
      if (/(π[άα]νω|πανω|δυνατ[άα]|δυνατα|αύξησε|αυξησε)/.test(s)) return { type: 'volume', op: 'up' };
      if (/(κ[άα]τω|κατω|σιγ[άα]|σιγα|μείωσε|μειωσε|σίγαση|σιγαση)/.test(s)) return { type: 'volume', op: 'down' };
    }

    if (/(χρονόμετρο|χρονομετρο|χρονομέτρου)/.test(s)) {
      const ms = parseRelativeGr(s);
      if (ms) return { type: 'timer', ms: ms, label: 'Χρονόμετρο' };
      return { type: 'timer', needWhen: true, partial: { label: 'Χρονόμετρο' } };
    }

    if (/(ξυπνητήρι|ξυπνητηρι|ξυπνητήρι)/.test(s)) {
      const at = parseWhenGr(s, now);
      if (at) return { type: 'alarm', atMs: at, label: 'Ξυπνητήρι' };
      return { type: 'alarm', needWhen: true, partial: { label: 'Ξυπνητήρι' } };
    }

    // "βάλε/παίξε X" — μουσική, τραγούδι, ή κάτι συγκεκριμένο στο YouTube
    const playWord = /(?:πα[ίι]ξε|β[άα]λε|μουσικ|τραγο[ύυ]δι)/.test(s);
    const otherThing = /(?:έντ[αα]ση|ενταση|φωτειν|φακ|ξυπνητ|υπενθ|χρον[όο]μ|θερμοκρ|μπαταρ)/.test(s);
    if (playWord && !otherThing) {
      if (/(επόμενο|επομενο|next)/.test(s) && /(τραγο[ύυ]δι|κομμ[άα]τι|μουσικ)/.test(s)) {
        return { type: 'media', op: 'next' };
      }
      if (/(προηγο[ύυ]μενο|προηγουμενο)/.test(s) && /(τραγο[ύυ]δι|κομμ[άα]τι|μουσικ)/.test(s)) {
        return { type: 'media', op: 'prev' };
      }
      if (/(σταμ[άα]τα|πα[ύυ]σε|παυσε)/.test(s) && /(μουσικ|τραγο[ύυ]δι)/.test(s)) {
        return { type: 'media', op: 'pause' };
      }
      m = /(?:πα[ίι]ξε|β[άα]λε|ξεκ[ίι]να)\s+(.+)$/.exec(s);
      let q = m ? m[1].replace(/\s+(?:στο|στη|στην|σε)\s+(?:youtube|you tube|spotify|yt music|yt)\s*$/, '').trim() : '';
      q = q.replace(/^(?:το\s+|τη\s+|την\s+|τον\s+|ένα\s+|ενα\s+)?/, '')
        .replace(/^(?:τραγο[ύυ]δι|κομμ[άα]τι)\s+/, '')
        .replace(/\b(?:σε παρακαλ[ώω]|τώρα|τωρα|για μ[έε]να)\b/g, '')
        .trim();
      q = q.replace(/^(?:λ[ίι]γ[ηο]|καπ[οό]ια|χαλαρ[ήη]|χαλαρη)\s+/, '').trim();
      const app = musicApp(s);
      if (!q || isGenericMusic(q) || /^(?:αυτ[όο]|αυτο|χαλαρ[άα]|χαλαρα)$/.test(q)) {
        return app ? { type: 'music', app: app } : { type: 'music' };
      }
      if (app === 'spotify') return { type: 'music', app: 'spotify', query: q };
      return { type: 'youtube', query: q, play: true, app: app || undefined };
    }

    if (/(υπενθύμιση|υπενθυμιση|θύμισ[εέ]|θυμισε|θυμήσου|θυμησου)/.test(s)) {
      m = /(?:να|για|ότι|οτι)\s+(.+)$/.exec(s);
      let what = m ? m[1] : s.replace(/.*(?:υπενθύμιση|υπενθυμιση|θύμισε|θυμισε|θυμήσου|θυμησου)\s*/, '');
      what = what.replace(/\s+(σε|στις|αύριο|αυριο|μετ[άα]\s+απ[όο]|το\s+πρ[ωω]ί|το\s+βρ[άα]δυ)\s.*$/, '').trim();
      if (!what) what = 'Υπενθύμιση';
      const at = parseWhenGr(s, now);
      if (at) return { type: 'reminder', atMs: at, title: cap(what), message: cap(what) };
      return { type: 'reminder', needWhen: true, partial: { title: cap(what), message: cap(what) } };
    }

    if (/(ξεκ[ίι]να|πα[ίι]ξε|β[άα]λε)/.test(s) && /(μουσικ|music)/.test(s)) return { type: 'music' };

    m = /(?:[άα]νοιξε|ανοιξε|τρ[έε]ξε|τρεξε)\s+(?:το\s+|την\s+|τη\s+)?(.+)$/.exec(s);
    if (m) {
      const q = m[1].trim();
      if (q && !/^(αυτό|αυτο|εκείνο|εκεινο)$/.test(q)) return { type: 'openApp', query: q };
    }

    if (/(ακ[ύυ]ρωσε|ακυρωσε|σβ[ήη]σε|σβησε|δι[άα]γραψε|διαγραψε)/.test(s) &&
        /(υπενθ[ύυ]μιση|υπενθυμιση|ξυπνητ[ήη]ρι|ξυπνητηρι|χρον[όο]μετρο|χρονομετρο)/.test(s)) {
      const kind = /ξυπνητ/.test(s) ? 'alarm' : /χρον[όο]μετρ|χρονομετρ/.test(s) ? 'timer' : 'reminder';
      return { type: 'cancel', kind: kind, all: true };
    }

    return null;
  }

  /* ── intent parser ─────────────────────────────────────────────── */
  // Returns one of:
  //   null                       → not a local command, send to Hermes
  //   {type, ...}                → executable action
  //   {type, needWhen, partial}  → scheduling action missing its time

  function parseIntent(text, nowMs) {
    const t = String(text || '').trim();
    const s = t.toLowerCase();
    if (!s) return null;
    const now = nowMs == null ? Date.now() : nowMs;

    // Greek input goes to the Greek rules first, then falls through to English
    if (/[\u0370-\u03ff\u1f00-\u1fff]/.test(t)) {
      const gr = parseGreek(t, now);
      if (gr) return gr;
    }

    // ── wake tail may itself be a command ──
    const sw = splitWake(t);
    const body = sw.wake && sw.rest ? sw.rest : t;

    // Declared once here: the rules below all assign to `m`, and a `let` further
    // down would put them in the temporal dead zone (ReferenceError killed every
    // English utterance before it reached a single rule).
    let m;

    // simple on/off commands for torch, brightness, volume
    m = /\b(turn|switch|set)\s+(on|off)\s+(the\s+)?(torch|flashlight|flash)\b/.exec(s);
    if (m) return { type: 'torch', on: m[2] === 'on' };
    m = /\b(turn|switch|set)\s+(on|off)\s+(the\s+)?(brightness|screen)\b/.exec(s);
    if (m) return { type: 'brightness', op: m[2] === 'on' ? 'up' : 'down' };
    m = /\b(turn|switch|set)\s+(on|off)\s+(the\s+)?(volume|sound|music)\b/.exec(s);
    if (m) return { type: 'volume', op: m[2] === 'on' ? 'up' : 'down' };

    // flashlight / torch
    if (/\b(flashlight|torch|flash)\b/.test(s)) {
      const off = /\b(off|disable|stop)\b/.test(s);
      if (/\b(on|turn|start|enable)\b/.test(s) || !off) return { type: 'torch', on: !off };
      return { type: 'torch', on: false };
    }

    // ── Home Assistant: lights ──
    // "turn on/off the (kitchen) lights", "lights off", "turn on the lamp"
    m = /\b(?:turn|switch)\s+(on|off)\s+(?:the\s+)?(?:(.+?)\s+)?(?:lights?|lamps?|bulbs?)\b/.exec(s);
    if (m) return { type: 'ha', domain: 'light', service: m[1] === 'on' ? 'turn_on' : 'turn_off',
                    target: (m[2] || '').replace(/\b(?:the|in|at|of)\b/g, '').trim() || null };
    // "close/shut the bedroom light", "open the kitchen light" — people say
    // this for lights as often as "turn off".
    m = /\b(close|shut|kill|switch\s+off)\s+(?:the\s+)?(?:(.+?)\s+)?(?:lights?|lamps?|bulbs?)\b/.exec(s);
    if (m) return { type: 'ha', domain: 'light', service: 'turn_off',
                    target: (m[2] || '').replace(/\b(?:the|in|at|of)\b/g, '').trim() || null };
    m = /\b(?:open|switch\s+on)\s+(?:the\s+)?(?:(.+?)\s+)?(?:lights?|lamps?|bulbs?)\b/.exec(s);
    if (m) return { type: 'ha', domain: 'light', service: 'turn_on',
                    target: (m[2] || '').replace(/\b(?:the|in|at|of)\b/g, '').trim() || null };
    m = /^(?:(.+?)\s+)?(?:lights?|lamps?|bulbs?)\s+(?:on|off)\b/.exec(s);
    if (m && /\b(on|off)\b/.test(s)) {
      const onOff = /\boff\b/.test(s) ? 'turn_off' : 'turn_on';
      return { type: 'ha', domain: 'light', service: onOff,
               target: (m[1] || '').replace(/\b(?:the|in|at|of)\b/g, '').trim() || null };
    }

    // ── Home Assistant: climate ──
    // "set temperature to 22", "set the thermostat to 21"
    m = /\b(?:set|change)?\s*(?:the\s+)?(?:temperature|thermostat)\s*(?:to)?\s*(\d{1,2})\s*(?:degrees?|°|c\b)?/.exec(s);
    if (m) return { type: 'ha', domain: 'climate', service: 'set_temperature',
                    target: null, value: parseInt(m[1], 10) };
    if (/\b(?:turn|switch)\s+(?:on|off)?\s*(?:the\s+)?(?:heating|thermostat|ac|air conditioning|air conditioner)\b/.test(s)) {
      const off = /\boff\b/.test(s);
      return { type: 'ha', domain: 'climate', service: off ? 'turn_off' : 'turn_on', target: null };
    }

    // brightness
    m = /\bbrightness\s+(?:to\s+)?(\d{1,3})\s*%?\b/.exec(s);
    if (m) return { type: 'brightness', value: Math.min(100, parseInt(m[1], 10)) / 100 };
    if (/\bbrightness\s+(up|higher|brighter)\b/.test(s)) return { type: 'brightness', op: 'up' };
    if (/\bbrightness\s+(down|lower|dimmer|dim)\b/.test(s)) return { type: 'brightness', op: 'down' };

    // volume
    m = /\bvolume\s+(?:to\s+)?(\d{1,3})\s*%?\b/.exec(s);
    if (m) {
      const stream = /\balarm\b/.test(s) ? 'alarm' : /\bring(er)?\b/.test(s) ? 'ring' : 'media';
      return { type: 'volume', value: Math.min(100, parseInt(m[1], 10)) / 100, stream: stream };
    }
    if (/\bvolume\s+(up|louder|higher)\b/.test(s)) return { type: 'volume', op: 'up' };
    if (/\bvolume\s+(down|quieter|lower|mute|silence)\b/.test(s)) {
      return { type: 'volume', op: /mute|silence/.test(s) ? 'mute' : 'down' };
    }

    // settings panels
    m = /\b(?:open|show|go to)\s+(?:the\s+)?(wifi|wi-fi|wireless|bluetooth|sound|audio|display|screen|battery|apps?)\s*(?:settings)?\b/.exec(s);
    if (m && (/\bsettings\b/.test(s) || /\b(open|show|go to)\b/.test(s))) {
      const panel = m[1].replace('wi-fi', 'wifi').replace('wireless', 'wifi').replace('audio', 'sound');
      return { type: 'settings', panel: panel === 'screen' ? 'display' : panel };
    }

    // "open youtube and play X" / "open spotify and play" — a named app must be
    // honoured (the old rules answered "music" and opened YT Music whatever the
    // user asked for).
    m = /^(?:please\s+)?(?:open|launch|start)\s+(?:the\s+)?(yt music|youtube music|you\s*tube|youtube|spotify)\b(.*)$/.exec(body.toLowerCase());
    if (m) {
      const name = m[1].replace(/\s+/g, ' ');
      const app = /spotify/.test(name) ? 'spotify' : /music/.test(name) ? 'ytmusic' : 'youtube';
      const rest = (m[2] || '').replace(/^\s*(?:,|and|then|και)\s*/, '').trim();
      // "open youtube.com" is a link, not an app launch
      if (/^[./]/.test(rest)) return { type: 'url', url: 'https://' + name.replace(/\s+/g, '') + rest };
      if (!rest) return { type: 'openApp', query: name };
      const wantsSearch = /^(?:and\s+)?(?:search|find|look for)\b/.test(rest);
      const verb = /^(?:and\s+)?(?:play|put on|queue up|search(?:\s+for)?|find|look for)\s+(.*)$/.exec(rest);
      // "open spotify and play" (no song) leaves a bare verb behind — that is
      // a generic request, not a track called "play".
      let q = (verb ? verb[1] : rest).trim();
      if (/^(?:play|put on|queue up|search|find|look for|playing)$/.test(q)) q = '';
      // "open spotify and play despacito" must stay in Spotify: search there
      // and tap the result instead of falling back to YouTube.
      if (app === 'spotify') {
        return q && !isGenericMusic(q)
          ? { type: 'music', app: 'spotify', query: q }
          : { type: 'music', app: 'spotify' };
      }
      if (app === 'ytmusic') return { type: 'music', app: 'ytmusic' };
      if (!q || isGenericMusic(q)) return { type: 'music', app: 'youtube' };
      return { type: 'youtube', query: q, play: !wantsSearch };
    }

    // "start playing (on yt music / spotify / youtube)" → start the music app.
    // Only for generic requests: "play some lofi music" names a track and must
    // fall through to the YouTube search below.
    const playTail = (/(?:start|play|put on|resume)\s+(?:playing\s+)?(?:on\s+)?(.+)$/.exec(s) || [])[1] || '';
    if (/\b(start|play|put on|resume)\b/.test(s) && /\bmusic\b/.test(s) && isGenericMusic(playTail)) {
      return { type: 'music', app: musicApp(s) || undefined };
    }
    if (/^(?:start|play|put on|resume)\s+(?:playing\s+)?(?:on\s+)?(?:yt|youtube|spotify|yt music|youtube music)\s*$/.test(s)) {
      return { type: 'music', app: musicApp(s) || undefined };
    }
    if (/^(?:start|play)\s+playing\s*$/.test(s)) return { type: 'music' };
    if (/\b(stop|pause)\s+music\b/.test(s)) return { type: 'music', op: 'pause' };
    if (/\b(resume|continue)\s+music\b/.test(s)) return { type: 'music', op: 'play' };

    // "open google" / "use google" → open Google search
    if (/\b(?:open|go to|use)\s+google\b/.test(s)) return { type: 'search', query: '' };

    // open an app
    m = /\b(?:open|launch|start|run)\s+(?:the\s+)?(.+?)\s*$/.exec(body.toLowerCase());
    if (m && !/^(settings\b)/.test(m[1]) && !/\b(a|the)?\s*(timer|alarm|reminder)\b/.test(m[1])) {
      const q = m[1].trim();
      // "open youtube.com" is a link, not an app launch
      if (/^(?:https?:\/\/)?[\w-]+(?:\.[\w-]+)+\/?\S*$/.test(q)) {
        return { type: 'url', url: /^https?:\/\//.test(q) ? q : 'https://' + q };
      }
      if (q && !/^(it|this|that|one)\b/.test(q)) return { type: 'openApp', query: q };
    }

    // cancel
    if (/\b(cancel|clear|delete|remove)\b.*\b(reminders?|alarms?|timers?)\b/.test(s)) {
      const kind = /\balarms?\b/.test(s) ? 'alarm' : /\btimers?\b/.test(s) ? 'timer' : 'reminder';
      return { type: 'cancel', kind: kind, all: !/\bthe\s+(last|most recent)\b/.test(s) };
    }

    // timer (duration only, no clock)
    m = /\b(?:start|set|run)?\s*a?\s*timer\s*(?:for)?\s*(.+)$/.exec(body.toLowerCase());
    if (m) {
      const ms = parseRelative('in ' + m[1]);
      if (ms != null) return { type: 'timer', ms: ms, label: 'Timer' };
    }

    // alarm — scheduling phrase using alarm word
    if (/\balarm\b/.test(s)) {
      const at = parseWhen(body, now);
      let title = (/\bfor\s+(.+)$/.exec(body.toLowerCase()) || [])[1];
      if (title) title = title.replace(/\s+(in|at|on|tomorrow|today)\s.*$/, '').trim();
      if (title && /^\d{1,2}(:\d{2})?\s*(am|pm)$/.test(title)) title = '';
      if (at != null) return { type: 'alarm', atMs: at, label: title ? cap(title) : 'Alarm' };
      return { type: 'alarm', needWhen: true, partial: { label: title ? cap(title) : 'Alarm' } };
    }

    // reminder — "put/set/create a reminder (to|for) X (when)"
    m = /\b(?:put|set|create|make|add)\s+(?:me\s+)?(?:a\s+)?reminders?\s*(?:to|for|about|of)?\s*(.*)$/.exec(body.toLowerCase());
    if (m) {
      const what = m[1].replace(/\s+(in|at|on|tomorrow|today)\s.*$/, '').trim() || 'Reminder';
      const at = parseWhen(body, now);
      if (at != null) return { type: 'reminder', atMs: at, title: cap(what), message: cap(what) };
      return { type: 'reminder', needWhen: true, partial: { title: cap(what), message: cap(what) } };
    }

    // bare "remind me to X (in ...)" without the word reminder
    m = /\bremind\s+me\s+(?:to|about|that)\s+(.+)$/.exec(body.toLowerCase());
    if (m) {
      const what = m[1].replace(/\s+(in|at|on|tomorrow|today)\s.*$/, '').trim();
      const at = parseWhen(body, now);
      if (at != null) return { type: 'reminder', atMs: at, title: cap(what), message: cap(what) };
      return { type: 'reminder', needWhen: true, partial: { title: cap(what), message: cap(what) } };
    }

    // time / date / battery (answered locally, no brain round-trip)
    if (/^(what'?s? (the )?time|what time is it|tell me the time)\b/.test(s)) return { type: 'time' };
    if (/^(what'?s? (the )?date|what day is it|what'?s? today)\b/.test(s)) return { type: 'date' };
    if (/\b(battery|charge level|how much power)\b/.test(s)) return { type: 'battery' };

    // ── phone surface: calls, web, video, navigation, media keys ──

    // media transport (checked before "play X on youtube")
    if (/\b(pause|resume)\b/.test(s) && /\b(music|song|video|it|that|playback)\b/.test(s)) {
      return { type: 'media', op: /\bpause\b/.test(s) ? 'pause' : 'play' };
    }
    if (/\b(next|skip)\b\s*(song|track|one)?\b/.test(s) && /\b(song|track|music|it)\b/.test(s)) {
      return { type: 'media', op: 'next' };
    }
    if (/\b(previous|last)\s+(song|track)\b/.test(s)) return { type: 'media', op: 'prev' };

    // "play X on youtube" / "youtube X"
    m = /\b(?:play|put on|find)\s+(.+?)\s+on\s+(?:youtube|you tube)\b/.exec(body.toLowerCase());
    if (m) return { type: 'youtube', query: m[1].trim(), play: true };
    m = /^(?:open\s+)?youtube\s+(?:and\s+)?(?:play|search|find)?\s*(.*)$/.exec(body.toLowerCase());
    if (m) {
      return { type: 'youtube', query: (m[1] || '').trim(), play: !/\bsearch\b/.test(s) };
    }

    // "play X" / "put on X" → queue it up on YouTube. A plain phone can't
    // start Spotify playback by voice, but this at least opens the song.
    m = /^(?:play|put on|queue up|throw on)\s+(.+)$/.exec(body.toLowerCase());
    if (m) {
      const q = m[1]
        .replace(/\s+on\s+(?:youtube|you tube|yt|yt music|spotify)\s*$/, '')
        .replace(/^(?:the\s+)?(?:song|track|video)\s+/, '')
        .replace(/\b(?:please|now|for me)\b/g, '')
        .trim();
      if (!q || isGenericMusic(q) || /^(?:that|it|this|one|a song|the music)$/.test(q)) {
        return isGenericMusic(q) || /^(?:a song|the music)$/.test(q)
          ? { type: 'music', app: musicApp(s) || undefined }
          : { type: 'media', op: 'play' };
      }
      // "play X on spotify" names the player — keep it out of YouTube.
      if (musicApp(s) === 'spotify') return { type: 'music', app: 'spotify', query: q };
      return { type: 'youtube', query: q, play: true, app: musicApp(s) || undefined };
    }

    // "navigate to X" / "directions to X" / "take me to X"
    m = /\b(?:navigate|drive|directions?|take me)\s+to\s+(.+)$/.exec(body.toLowerCase());
    if (m) return { type: 'navigate', query: m[1].replace(/\b(?:please|now)\b/g, '').trim() };

    // "call X"
    m = /^(?:call|phone|dial)\s+(.+)$/.exec(body.toLowerCase());
    if (m) {
      const who = m[1].replace(/\b(please|now|for me)\b/g, '').trim();
      if (who && !/^(it|this|that|back)$/.test(who)) {
        const digits = who.replace(/[^\d+]/g, '');
        return { type: 'dial', number: digits.length >= 3 ? digits : '', contact: who };
      }
    }

    // "search (google) for X" / "look up X"
    m = /\b(?:search|google|look up|look for)\s+(?:for\s+)?(.+)$/.exec(body.toLowerCase());
    if (m) return { type: 'search', query: m[1].trim() };

    // "open example.com" / "open youtube.com"
    m = /\b(?:open|go to)\s+((?:https?:\/\/)?[\w-]+\.[a-z]{2,}\S*)\s*$/.exec(body.toLowerCase());
    if (m) {
      const raw = m[1];
      return { type: 'url', url: /^https?:\/\//.test(raw) ? raw : 'https://' + raw };
    }

    // "notify me that X" (a one-off notification, no time)
    m = /\bnotify\s+me\s+(?:that|about)?\s*(.+)$/.exec(body.toLowerCase());
    if (m && m[1].trim()) return { type: 'notify', title: cap(m[1].trim()) };

    return null;
  }

  function cap(s) {
    s = String(s || '').trim();
    return s ? s[0].toUpperCase() + s.slice(1) : s;
  }

  /* ── spoken confirmations ──────────────────────────────────────── */

  // Home Assistant confirmations (entity target is echoed back if given).
  const HA_DOMAIN = {
    light:    { gr: 'φώτα',    en: 'lights' },
    climate:  { gr: 'κλιματισμός', en: 'heating' },
    switch:   { gr: 'συσκευή', en: 'device' },
    cover:    { gr: 'πόρτα',   en: 'cover' },
    fan:      { gr: 'αεριστήρας', en: 'fan' },
    media_player: { gr: 'μουσική', en: 'media' }
  };

  function haConfirm(a) {
    const d = HA_DOMAIN[a.domain] || { en: a.domain || 'device' };
    const where = a.target ? ' in ' + a.target : '';
    switch (a.service) {
      case 'turn_on':    return 'Turning on the ' + d.en + where + '.';
      case 'turn_off':   return 'Turning off the ' + d.en + where + '.';
      case 'set_temperature': return 'Temperature set to ' + a.value + ' degrees.';
      case 'toggle':     return 'Toggling the ' + d.en + where + '.';
      default:           return 'Okay.';
    }
  }

  function haConfirmGr(a) {
    const d = HA_DOMAIN[a.domain] || { gr: a.domain || 'συσκευή' };
    const where = a.target ? ' στο ' + a.target : '';
    switch (a.service) {
      case 'turn_on':    return a.domain === 'light' ? 'Άνοιξα τα φώτα' + where + '.' : 'Ενεργοποίησα τον ' + d.gr + where + '.';
      case 'turn_off':   return a.domain === 'light' ? 'Έκλεισα τα φώτα' + where + '.' : 'Απενεργοποίησα τον ' + d.gr + where + '.';
      case 'set_temperature': return 'Η θερμοκρασία στους ' + a.value + ' βαθμούς.';
      default:           return 'Εντάξει.';
    }
  }

  // ── Greek spoken confirmations ──
  function durGr(ms) {
    const s = Math.round(ms / 1000);
    if (s < 60) return s + (s === 1 ? ' δευτερόλεπτο' : ' δευτερόλεπτα');
    const m = Math.round(s / 60);
    if (m < 60) return m + (m === 1 ? ' λεπτό' : ' λεπτά');
    const h = Math.floor(m / 60), rm = m % 60;
    return h + (h === 1 ? ' ώρα' : ' ώρες') + (rm ? ' και ' + rm + ' λεπτά' : '');
  }

  function whenGr(atMs, now) {
    const diff = atMs - now;
    if (diff > 0 && diff < 3 * 36e5) return 'σε ' + durGr(diff);
    const d = new Date(atMs);
    const time = d.toLocaleTimeString('el-GR', { hour: '2-digit', minute: '2-digit' });
    const sameDay = d.toDateString() === new Date(now).toDateString();
    return (sameDay ? 'σήμερα στις ' : 'αύριο στις ') + time;
  }

  // Greek locale strings already end in "μ.μ." — don't stack two periods.
  function sentence(s) {
    s = String(s || '').trim();
    return /[.!;·]$/.test(s) ? s : s + '.';
  }

  function confirmTextGr(action, nowMs) {
    const now = nowMs == null ? Date.now() : nowMs;
    const title = String(action.title || '').toLowerCase();
    switch (action.type) {
      case 'reminder': return sentence('Έγινε — θα σου θυμίσω να ' + title + ' ' + whenGr(action.atMs, now));
      case 'alarm':    return 'Ξυπνητήρι ' + whenGr(action.atMs, now) + '.';
      case 'timer':    return 'Χρονόμετρο για ' + durGr(action.ms) + '.';
      case 'torch':    return action.on ? 'Ο φακός άνοιξε.' : 'Ο φακός έκλεισε.';
      case 'ha':       return haConfirmGr(action);
      case 'brightness': return action.op === 'up' ? 'Φωτεινότητα πάνω.' :
        action.op === 'down' ? 'Φωτεινότητα κάτω.' :
        'Φωτεινότητα στο ' + Math.round(action.value * 100) + ' τοις εκατό.';
      case 'volume':   return action.op === 'mute' ? 'Σε σίγαση.' :
        action.op === 'up' ? 'Ένταση πάνω.' : action.op === 'down' ? 'Ένταση κάτω.' :
        'Ένταση στο ' + Math.round(action.value * 100) + ' τοις εκατό.';
      case 'openApp':  return 'Ανοίγω ' + action.query + '.';
      case 'settings': return 'Ανοίγω τις ρυθμίσεις ' + action.panel + '.';
      case 'dial':     return action.number ? 'Καλώ ' + action.number + '.' : 'Ανοίγω τον dialer.';
      case 'search':   return 'Ψάχνω ' + action.query + '.';
      case 'youtube':  return !action.query ? 'Ανοίγω το YouTube.'
        : action.play !== false ? 'Παίζω ' + action.query + ' στο YouTube.'
        : 'Βρίσκω ' + action.query + ' στο YouTube.';
      case 'navigate': return 'Οδηγίες για ' + action.query + '.';
      case 'notify':   return 'Το σημείωσα.';
      case 'media':    return action.op === 'next' ? 'Επόμενο.' :
        action.op === 'prev' ? 'Προηγούμενο.' :
        action.op === 'pause' ? 'Παύση.' : 'Παίζει.';
      case 'music':    return action.query ? 'Παίζω ' + action.query + '.' : 'Βάζω μουσική.';
      case 'time':     return sentence('Είναι ' + new Date(now).toLocaleTimeString('el-GR', { hour: '2-digit', minute: '2-digit' }));
      case 'date':     return sentence('Σήμερα είναι ' + new Date(now).toLocaleDateString('el-GR', { weekday: 'long', day: 'numeric', month: 'long' }));
      case 'battery':  return 'Η μπαταρία είναι ' + (action.percent != null ? action.percent + ' τοις εκατό.' : 'κάτι που θα τσεκάρω.');
      case 'cancel':   return 'Ακύρωσα ' + (action.all ? 'όλα τα ' : 'το τελευταίο ') + 'ό,τι είχες προγραμματίσει.';
      default:         return 'Εντάξει.';
    }
  }

  // The "…when?" / "…how long?" question, in the active language.
  function askWhenText(type, lang) {
    const el = String(lang || '').toLowerCase().startsWith('el');
    if (el) {
      return type === 'alarm' ? 'Τι ώρα να χτυπήσει το ξυπνητήρι;'
        : type === 'timer' ? 'Για πόση ώρα να μετρήσω;'
          : 'Πότε να σου το θυμίσω;';
    }
    return type === 'alarm' ? 'When should the alarm go off?'
      : type === 'timer' ? 'How long should I time?'
        : 'When should I remind you?';
  }

  function confirmText(action, nowMs, lang) {
    const now = nowMs == null ? Date.now() : nowMs;
    if (String(lang || '').toLowerCase().startsWith('el')) return confirmTextGr(action, now);
    switch (action.type) {
      case 'reminder': return 'Done — I\'ll remind you to ' + action.title.toLowerCase() + ' ' + friendlyWhen(action.atMs, now) + '.';
      case 'alarm':    return 'Alarm set ' + friendlyWhen(action.atMs, now) + '.';
      case 'timer':    return 'Timer started for ' + friendlyDuration(action.ms) + '.';
      case 'torch':    return action.on ? 'Flashlight on.' : 'Flashlight off.';
      case 'ha':       return haConfirm(action);
      case 'brightness': return action.op === 'up' ? 'Brightness up.' :
        action.op === 'down' ? 'Brightness down.' :
        'Brightness set to ' + Math.round(action.value * 100) + ' percent.';
      case 'volume':   return action.op === 'mute' ? 'Muted.' :
        action.op === 'up' ? 'Volume up.' : action.op === 'down' ? 'Volume down.' :
        'Volume set to ' + Math.round(action.value * 100) + ' percent.';
      case 'openApp':  return 'Opening ' + action.query + '.';
      case 'settings': return 'Opening ' + action.panel + ' settings.';
      case 'dial':     return action.number ? 'Calling ' + action.number + '.' :
        'Bringing up the dialer for ' + (action.contact || 'you') + '.';
      case 'search':   return 'Searching for ' + action.query + '.';
      case 'youtube':  return !action.query ? 'Opening YouTube.'
        : action.play !== false ? 'Playing ' + action.query + ' on YouTube.'
        : 'Looking for ' + action.query + ' on YouTube.';
      case 'navigate': return 'Getting directions to ' + action.query + '.';
      case 'url':      return 'Opening that page.';
      case 'notify':   return 'Noted.';
      case 'media':    return action.op === 'next' ? 'Next one.' :
        action.op === 'prev' ? 'Going back.' :
        action.op === 'pause' ? 'Paused.' : 'Playing.';
      case 'music':    return action.query ? 'Playing ' + action.query + '.' : 'Music on.';
      case 'time':     return 'It\'s ' + new Date(now).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + '.';
      case 'date':     return 'Today is ' + new Date(now).toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' }) + '.';
      case 'battery':  return 'Battery is ' + (action.percent != null ? action.percent + ' percent.' : 'something I\'ll check.');
      case 'cancel':   return 'Cancelled your ' + (action.all ? 'remaining ' : 'last ') + action.kind + 's.';
      default:         return 'Okay.';
    }
  }

  function friendlyDuration(ms) {
    const s = Math.round(ms / 1000);
    if (s < 60) return s + ' seconds';
    const m = Math.round(s / 60);
    if (m < 60) return m + (m === 1 ? ' minute' : ' minutes');
    const h = Math.floor(m / 60), rm = m % 60;
    return h + (h === 1 ? ' hour' : ' hours') + (rm ? ' ' + rm + ' minutes' : '');
  }

  function friendlyWhen(atMs, now) {
    const diff = atMs - now;
    if (diff > 0 && diff < 3 * 36e5) return 'in ' + friendlyDuration(diff);
    const d = new Date(atMs);
    const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const sameDay = d.toDateString() === new Date(now).toDateString();
    return (sameDay ? 'today' : d.toLocaleDateString([], { weekday: 'long' })) + ' at ' + time;
  }

  /* ── Hermes client ─────────────────────────────────────────────── */

  const ACTION_DOC = [
    'You control the Android phone you run on. When the person asks you to DO something',
    'with the phone, first write one short spoken sentence, then on its own line write',
    'exactly one action tag and nothing after it:',
    '  @@ACTION {"type":"reminder","title":"buy milk","when":"in 20 minutes"}',
    'Types and fields:',
    '  reminder|alarm: {title, when}   when = "in 10 minutes", "at 7:30 pm", "tomorrow at 9"',
    '  timer: {minutes|seconds}',
    '  torch: {on:true|false}',
    '  ha: {domain:"light"|"climate"|"switch", service:"turn_on"|"turn_off"|"set_temperature", target:"room or device name"|null, value:degrees}',
    '  volume: {level:0-100} or {op:"up"|"down"|"mute"}',
    '  brightness: {level:0-100} or {op:"up"|"down"}',
    '  openApp: {query}      search: {query}      youtube: {query}',
    '  navigate: {query}     dial: {number}       url: {url}',
    '  media: {op:"play"|"pause"|"next"|"prev"}   music: {}  (start music)',
    '  cancel: {kind:"reminder"|"alarm"|"timer"|"all"}',
    'Only include @@ACTION when an action is genuinely needed; never invent one.'
  ].join('\n');

  const DEFAULT_CONFIG = {
    // Configure your Hermes URL in settings (localStorage 'pal.hermes'). Empty = not configured.
    url: '',
    key: '',
    model: 'hermes-agent',
    // Fast profile (lightweight model) attempted first; root is the fallback.
    pathPrefix: '/p/loona-fast/',
    keyFast: '',
    modelFast: 'hermes-agent',
    system: 'You are Loona, a small desk-mate robot living on Nick\'s phone. ' +
            'You are warm, playful and a little cheeky. Answer as spoken dialogue: ' +
            'max two short sentences, plain text, no markdown, no lists, no emoji.\n' +
            'Always answer in the language the person spoke to you in — Greek in, ' +
            'Greek out (natural, spoken Greek, not transliterated).\n' +
            ACTION_DOC
  };

  function buildRequest(cfg, history, opts) {
    const c = Object.assign({}, DEFAULT_CONFIG, cfg || {});
    const o = opts || {};
    // Fast profile first: prefix + fast key when both are configured; otherwise root.
    const prefix = (c.pathPrefix || '').replace(/\/+$/, '');
    const key = (prefix && c.keyFast) ? c.keyFast : c.key;
    const model = (prefix && c.modelFast) ? c.modelFast : c.model;
    const messages = [{ role: 'system', content: c.system }].concat(
      (history || []).slice(-12).map(h => ({ role: h.role, content: h.text }))
    );
    return {
      url: c.url.replace(/\/+$/, '') + prefix + '/v1/chat/completions',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + (key || '')
      },
      body: JSON.stringify({
        model: model,
        messages: messages,
        stream: o.stream === true,
        temperature: o.temperature == null ? 0.7 : o.temperature
      })
    };
  }

  /* ── turning a model action tag into a local action ─────────────── */

  // "@@ACTION {...}" → {action, text}; tolerates code fences and stray prose.
  function splitAction(replyText) {
    const raw = String(replyText || '');
    const m = /@@ACTION\s*(\{[\s\S]*?\})\s*$/m.exec(raw);
    if (!m) return { action: null, text: raw.trim() };
    let obj = null;
    try { obj = JSON.parse(m[1]); } catch (e) {
      try { obj = JSON.parse(m[1].replace(/\n/g, ' ')); } catch (e2) { obj = null; }
    }
    const text = raw.replace(m[0], '').replace(/```[a-z]*\n?/gi, '').trim();
    return { action: obj, text: text };
  }

  // Model action → the exact shape executeLocal() understands.
  function normalizeAction(a, nowMs) {
    if (!a || typeof a !== 'object') return null;
    const now = nowMs == null ? Date.now() : nowMs;
    const type = String(a.type || '').toLowerCase();
    const pct = v => (v == null ? null : (v > 1 ? v / 100 : v));
    switch (type) {
      case 'reminder':
      case 'alarm': {
        const at = a.atMs ? Number(a.atMs) : (a.when ? parseWhen(String(a.when), now) : null);
        const title = cap(String(a.title || a.message || (type === 'alarm' ? 'Alarm' : 'Reminder')));
        if (at == null) return { type: type, needWhen: true, partial: { title: title, message: title } };
        return type === 'alarm'
          ? { type: 'alarm', atMs: at, label: title, message: title }
          : { type: 'reminder', atMs: at, title: title, message: title };
      }
      case 'timer': {
        let ms = null;
        if (a.ms) ms = Number(a.ms);
        else if (a.minutes) ms = Number(a.minutes) * 6e4;
        else if (a.seconds) ms = Number(a.seconds) * 1e3;
        else if (a.when) { const at = parseWhen(String(a.when), now); if (at) ms = at - now; }
        if (!ms || ms <= 0) return null;
        return { type: 'timer', ms: ms, label: cap(String(a.title || 'Timer')) };
      }
      case 'torch': return { type: 'torch', on: a.on !== false };
      case 'ha': {
        const svc = String(a.service || (a.on === false ? 'turn_off' : 'turn_on'));
        const out = { type: 'ha', domain: String(a.domain || 'light'), service: svc,
                      target: a.target ? String(a.target) : null };
        if (svc === 'set_temperature') out.value = Number(a.value);
        return out;
      }
      case 'volume':
        if (a.op) return { type: 'volume', op: String(a.op), stream: a.stream };
        return { type: 'volume', value: pct(a.level != null ? a.level : a.value) };
      case 'brightness':
        if (a.op) return { type: 'brightness', op: String(a.op) };
        return { type: 'brightness', value: pct(a.level != null ? a.level : a.value) };
      case 'openapp': return { type: 'openApp', query: String(a.query || a.app || '') };
      case 'settings': return { type: 'settings', panel: String(a.panel || 'wifi') };
      case 'search': return { type: 'search', query: String(a.query || '') };
      case 'youtube': return { type: 'youtube', query: String(a.query || '') };
      case 'navigate': return { type: 'navigate', query: String(a.query || a.to || '') };
      case 'dial': return { type: 'dial', number: String(a.number || ''), contact: String(a.contact || '') };
      case 'url': return { type: 'url', url: String(a.url || '') };
      case 'media': return { type: 'media', op: String(a.op || 'toggle') };
      case 'music': return { type: 'music' };
      case 'notify': return { type: 'notify', title: cap(String(a.title || a.message || 'Noted')) };
      case 'cancel': return { type: 'cancel', kind: String(a.kind || 'reminder'), all: a.all !== false };
      case 'battery': return { type: 'battery' };
      case 'time': return { type: 'time' };
      default: return null;
    }
  }

  // status/body from the native bridge → reply text (or a fallback string)
  function parseReply(status, bodyText) {
    if (status === 401 || status === 403) {
      return { ok: false, text: 'My brain key isn\'t set yet — add the Hermes API key in settings.', code: status };
    }
    if (status !== 200 && status !== 201) {
      return { ok: false, text: 'I can\'t reach my brain right now.', code: status };
    }
    try {
      const data = JSON.parse(bodyText);
      const choice = data && data.choices && data.choices[0];
      let text = choice && choice.message && (choice.message.content || choice.message.reasoning_content);
      if (!text && data && data.output_text) text = data.output_text;
      if (typeof text === 'object' && text && Array.isArray(text.parts)) {
        text = text.parts.map(p => p.text || '').join(' ');
      }
      text = String(text || '').trim();
      if (!text) return { ok: false, text: 'My brain answered but said nothing.', code: 200 };
      return { ok: true, text: text };
    } catch (e) {
      return { ok: false, text: 'I got a garbled answer from my brain.', code: 200 };
    }
  }

  return {
    WAKE_RE, isWakeToken, deaccent, splitWake, parseRelative, parseRelativeGr, parseClock, parseWhen, parseWhenGr, parseGreek,
    parseIntent, confirmText, friendlyDuration, friendlyWhen,
    DEFAULT_CONFIG, buildRequest, parseReply, splitAction, normalizeAction, askWhenText
  };
}));
