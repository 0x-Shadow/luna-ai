/*
 * Loona brain tests — node tests/brain.test.js
 */
'use strict';
const path = require('path');
const B = require(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'brain.js'));

let pass = 0, fail = 0;
function eq(name, got, want) {
  const g = JSON.stringify(got), w = JSON.stringify(want);
  if (g === w) { pass++; }
  else { fail++; console.log(`FAIL ${name}\n  got  ${g}\n  want ${w}`); }
}
function ok(name, cond, detail) {
  if (cond) pass++;
  else { fail++; console.log(`FAIL ${name}${detail ? ' — ' + detail : ''}`); }
}

const NOW = new Date('2026-11-17T14:05:00').getTime();

/* ── wake split ─────────────────────────────────────────────────── */
eq('splitWake plain', B.splitWake('hello there'), { wake: false, rest: 'hello there' });
eq('splitWake command', B.splitWake('loona put a reminder'), { wake: true, rest: 'put a reminder' });
ok('splitWake detects luna typo', B.splitWake('luna what time is it').wake === true);

/* ── when parsing ───────────────────────────────────────────────── */
eq('when in 10 minutes', B.parseWhen('put a reminder to water plants in 10 minutes', NOW), NOW + 10 * 60000);
eq('when in an hour', B.parseWhen('remind me in an hour', NOW), NOW + 3600000);
eq('when in half an hour', B.parseWhen('remind me in half an hour', NOW), NOW + 30 * 60000);
eq('when in 30 seconds', B.parseWhen('timer in 30 seconds', NOW), NOW + 30000);

const clock = B.parseClock('set an alarm at 7:30 pm');
ok('clock 7:30 pm → 19:30', clock && clock.h === 19 && clock.min === 30, JSON.stringify(clock));
const clock2 = B.parseClock('at 19:05');
ok('clock 19:05 stays 24h', clock2 && clock2.h === 19 && clock2.min === 5, JSON.stringify(clock2));

const tomorrow = B.parseWhen('set an alarm tomorrow at 8 am', NOW);
{
  const d = new Date(tomorrow);
  ok('tomorrow 8am', d.getDate() === 18 && d.getHours() === 8, d.toString());
}
// today at a later hour → today; at an earlier hour → tomorrow
{
  const later = B.parseWhen('set an alarm at 20:00', NOW);
  const d = new Date(later);
  ok('today 20:00 (today, not past-rolled)', d.getDate() === 17 && d.getHours() === 20, d.toString());
}

/* ── intents ────────────────────────────────────────────────────── */

let a = B.parseIntent('put a reminder to water plants in 10 minutes', NOW);
eq('reminder kind', a && a.type, 'reminder');
eq('reminder when', a && a.atMs, NOW + 600000);
ok('reminder title', a && /water plants/i.test(a.title), a && a.title);

a = B.parseIntent('set a reminder to call mom at 7 pm', NOW);
eq('reminder at clock', a && a.type, 'reminder');
ok('reminder at clock time', a && new Date(a.atMs).getHours() === 19, a && String(a && a.atMs));
ok('reminder title stripped of time', a && !/7 pm|at 7/.test(a.title), a && a.title);

a = B.parseIntent('remind me to stretch my back', NOW);
eq('bare remind me needs when', a && a.needWhen, true);
eq('bare remind me title', a && a.partial.title, 'Stretch my back');

a = B.parseIntent('put a reminder to take the pizza out in 25 minutes', NOW);
eq('second reminder', a && a.atMs, NOW + 25 * 60000);

a = B.parseIntent('set an alarm for 7 am', NOW);
eq('alarm kind', a && a.type, 'alarm');
ok('alarm time 7:00', a && new Date(a.atMs).getHours() === 7, a && String(a.atMs));

a = B.parseIntent('set an alarm', NOW);
eq('alarm needs when', a && a.needWhen, true);

a = B.parseIntent('start a timer for 5 minutes', NOW);
eq('timer kind', a && a.type, 'timer');
eq('timer ms', a && a.ms, 5 * 60000);

a = B.parseIntent('turn on the flashlight', NOW);
eq('torch on', a, { type: 'torch', on: true });
a = B.parseIntent('turn off flashlight', NOW);
eq('torch off', a, { type: 'torch', on: false });

a = B.parseIntent('set brightness to 40%', NOW);
eq('brightness 40%', a && a.value, 0.4);
a = B.parseIntent('brightness down', NOW);
eq('brightness down', a, { type: 'brightness', op: 'down' });

a = B.parseIntent('set volume to 60%', NOW);
eq('volume 60%', a && a.value, 0.6);
a = B.parseIntent('volume up', NOW);
eq('volume up', a, { type: 'volume', op: 'up' });

a = B.parseIntent('open whatsapp', NOW);
eq('open app', a && a.type, 'openApp');
ok('open app query', a && a.query === 'whatsapp', a && a.query);

a = B.parseIntent('open wifi settings', NOW);
eq('settings wifi', a, { type: 'settings', panel: 'wifi' });

a = B.parseIntent('what time is it', NOW);
eq('time', a && a.type, 'time');
a = B.parseIntent("what's the date", NOW);
eq('date', a && a.type, 'date');
a = B.parseIntent('how is my battery', NOW);
eq('battery', a && a.type, 'battery');

a = B.parseIntent('cancel all my reminders', NOW);
eq('cancel reminders', a && a.type, 'cancel');
eq('cancel kind', a && a.kind, 'reminder');

a = B.parseIntent('loona put a reminder to drink water in 30 minutes', NOW);
eq('wake-tail reminder', a && a.type, 'reminder');
eq('wake-tail when', a && a.atMs, NOW + 30 * 60000);

eq('chat passthrough', B.parseIntent('tell me a story about the sea', NOW), null);
eq('empty passthrough', B.parseIntent('', NOW), null);

/* ── confirmations ──────────────────────────────────────────────── */
ok('confirm reminder mentions when',
  /in 10 minutes/.test(B.confirmText({ type: 'reminder', title: 'Water plants', atMs: NOW + 600000 }, NOW)),
  B.confirmText({ type: 'reminder', title: 'Water plants', atMs: NOW + 600000 }, NOW));
ok('confirm torch', B.confirmText({ type: 'torch', on: true }) === 'Flashlight on.');

/* ── hermes request ─────────────────────────────────────────────── */
{
  const req = B.buildRequest({ url: 'http://hermes.test:8642/', key: 'SECRET', model: 'hermes-agent', pathPrefix: '' },
    [{ role: 'user', text: 'hi' }, { role: 'assistant', text: 'hello' }, { role: 'user', text: 'and now?' }]);
  eq('req url', req.url, 'http://hermes.test:8642/v1/chat/completions');
  eq('req auth', req.headers['Authorization'], 'Bearer SECRET');
  const body = JSON.parse(req.body);
  eq('req model', body.model, 'hermes-agent');
  eq('req system first', body.messages[0].role, 'system');
  eq('req history len', body.messages.length, 4);
  eq('req last message', body.messages[3].content, 'and now?');
}
{
  const r = B.parseReply(401, '{"error":{"message":"Invalid gateway API key"}}');
  eq('401 → key fallback', r.ok, false);
  ok('401 mentions key', /key/i.test(r.text), r.text);
  const r2 = B.parseReply(500, 'boom');
  eq('500 → unreachable', r2.ok, false);
  const r3 = B.parseReply(200, JSON.stringify({ choices: [{ message: { content: 'Hi Nick!' } }] }));
  eq('200 reply', r3, { ok: true, text: 'Hi Nick!' });
  const r4 = B.parseReply(200, 'not json');
  eq('garbled', r4.ok, false);
}

/* ── hermes fast profile ────────────────────────────────────────── */
{
  const fast = B.buildRequest({
    url: 'http://hermes.test:8642/', key: 'ROOT', keyFast: 'FAST',
    pathPrefix: '/p/loona-fast/', model: 'hermes-agent', modelFast: 'hermes-agent'
  }, [{ role: 'user', text: 'hi' }]);
  eq('fast url', fast.url, 'http://hermes.test:8642/p/loona-fast/v1/chat/completions');
  eq('fast auth', fast.headers['Authorization'], 'Bearer FAST');
}
{
  const root = B.buildRequest({
    url: 'http://hermes.test:8642/', key: 'ROOT', model: 'hermes-agent', pathPrefix: ''
  }, [{ role: 'user', text: 'hi' }]);
  eq('root url', root.url, 'http://hermes.test:8642/v1/chat/completions');
  eq('root auth', root.headers['Authorization'], 'Bearer ROOT');
}
{
  const noFastKey = B.buildRequest({
    url: 'http://hermes.test:8642/', key: 'ROOT',
    pathPrefix: '/p/loona-fast/', model: 'hermes-agent'
  }, [{ role: 'user', text: 'hi' }]);
  eq('no fast key uses root key', noFastKey.headers['Authorization'], 'Bearer ROOT');
  eq('no fast key url still prefixed', noFastKey.url, 'http://hermes.test:8642/p/loona-fast/v1/chat/completions');
}

/* ── regression: local intents still route locally (no Hermes) ─────── */
{
  // harmless commands execute locally, Hermes not involved
  ok('volume up is local intent', B.parseIntent('volume up', NOW) !== null);
  ok('torch on is local intent', B.parseIntent('turn on the flashlight', NOW) !== null);
  ok('media play is local intent', B.parseIntent('pause the music', NOW) !== null);
  ok('battery is local intent', B.parseIntent('how is my battery', NOW) !== null);
  // destructive commands are local intents too
  ok('alarm is local intent', B.parseIntent('set an alarm for 7 am', NOW) !== null);
  ok('timer is local intent', B.parseIntent('start a timer for 5 minutes', NOW) !== null);
  ok('cancel is local intent', B.parseIntent('cancel all my reminders', NOW) !== null);
  // Greek commands are local intents
  ok('Greek timer', B.parseIntent('βαλε χρονόμετρο 10 λεπτά', NOW) !== null);
  ok('Greek alarm', B.parseIntent('Ξυπνητήρι στα 7 το απόγευμα', NOW) !== null);
  // non-commands still go to Hermes
  eq('chat passthrough to Hermes', B.parseIntent('tell me a story about the sea', NOW), null);
}

/* ── device-based recovery tests (run on instrumented device/emulator) ──
   WS1 Hermes auth: fast 401 → root fallback; fast 403 → root fallback;
   both down → "can't reach brain"; max 2 attempts, no endless loop.
   WS2 Automation: task cancellation aborts remaining steps; per-step timeout;
   password field rejected for typeText.
   WS3 Media: no active session → key-event fallback; multiple sessions → no
   crash, connects to preferred; unknown op → error, no crash.
   WS4 Confirmations: harmless (volume/torch/media) → no card; destructive
   (alarm/timer/cancel/reminder) → card at default tier; confirmLevel=none →
   no confirmations.
   WS5 Background: app backgrounded → wake word still fires; mic failure →
   recovery banner + Google fallback; BrainService starts with microphone type.
   Latency: [Lat] timestamps logged per turn; whisper endpointing config
   values applied; low-battery chip at <20%.
*/

/* ── named music app + "play" routing ───────────────────────────── */
{
  // The app the user named is kept, instead of silently opening YT Music.
  eq('open spotify and play → spotify', B.parseIntent('open spotify and play', NOW),
    { type: 'music', app: 'spotify' });
  eq('play music on spotify → spotify', B.parseIntent('play music on spotify', NOW),
    { type: 'music', app: 'spotify' });
  eq('greek spotify + play', B.parseIntent('άνοιξε το spotify και παίξε μουσική', NOW),
    { type: 'music', app: 'spotify' });

  // "play some music" on YouTube must play something, not just search.
  eq('greek youtube music', B.parseIntent('άνοιξε το youtube και παίξε οποιαδήποτε μουσική', NOW),
    { type: 'music', app: 'youtube' });
  eq('youtube generic music', B.parseIntent('open youtube and play any kind of music', NOW),
    { type: 'music', app: 'youtube' });

  // A named track goes to YouTube search WITH autoplay.
  eq('play lofi on youtube', B.parseIntent('play lofi on youtube', NOW),
    { type: 'youtube', query: 'lofi', play: true });
  eq('open youtube and play lofi', B.parseIntent('open youtube and play lofi', NOW),
    { type: 'youtube', query: 'lofi', play: true });
  // "search" explicitly means: do not autoplay.
  eq('open youtube and search for lofi', B.parseIntent('open youtube and search for lofi', NOW),
    { type: 'youtube', query: 'lofi', play: false });
  // "play some lofi music" names content — it is a search, not the music app.
  eq('play some lofi music', B.parseIntent('play some lofi music', NOW),
    { type: 'youtube', query: 'some lofi music', play: true });
}

console.log(`\nbrain.test: ${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
