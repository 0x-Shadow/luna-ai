# PAL9000 Desktop Robot — Loona AI Assistant

[![Demo Video](https://github.com/user-attachments/assets/94a80abf-6ac3-4fcf-b8c1-05329994e51a)](https://www.youtube.com/watch?v=wm2kRotLKiM)

A Python-driven 2-DOF gimbal desktop robot with a Pi Pico W "neck," an Android phone as its "brain" (Loona), face tracking, and a local-first voice assistant. Built by **SpaceMonkeyAlfa** — documentation published at [spacemonkeyalfa.com](https://spacemonkeyalfa.com).

---

## Overview

PAL9000 is a desktop robot that sits on your desk, follows you with its eyes and neck, listens to voice commands, and responds through an animated animated face and TTS speech. It combines:

- **Pi Pico W** — 2-DOF gimbal control (pan/tilt servos)
- **Android phone** — the "brain" running Loona, an AI assistant with wake-word detection, local intent parsing, app control, media sessions, and background operation
- **MacBook / laptop** — optional LLM server (Hermes / llama.cpp) for complex reasoning, Whisper STT, and calendar integration
- **ESP32** — optional motion controller for the base

The system is designed around a **three-layer architecture** so simple commands never wait on slow AI reasoning:

| Layer | Responsibility | Runs on |
|-------|---------------|---------|
| **Layer 1 — Local Brain** | Wake word, intents, app commands, media controls, fast execution | Android phone |
| **Layer 2 — Hermes Brain** | Reasoning, conversation, planning, complex requests | NUC / LAN (laptop) |
| **Layer 3 — Android Bridge** | Apps, notifications, media sessions, device actions | Android phone |

---

## Features

### Core
- **Face tracking** — camera + AprilTag, follows faces with pan/tilt neck
- **Animated face** — 15 hand-drawn expressions (white-on-black), blink, boil animation, mouth only while speaking
- **Wake word** — "Loona" detected via on-device speech recognizer + envelope shape matcher (Greek & English)
- **Voice responses** — Android TTS with Greek (Google engine) and English support

### Local commands (Layer 1 — instant, no cloud)
Reminders, alarms, timers; flashlight, brightness, volume; open apps; settings panels (wifi, bluetooth, sound, display); dial, search, YouTube, navigate; torch; battery, time, date; cancel scheduled items.

### Media control (Layer 3)
Real **MediaSession** control for Spotify and YouTube Music — play, pause, next, previous, stop, volume, status — with media-key fallback when no session is active.

### Complex reasoning (Layer 2 — Hermes)
Open-ended conversation, planning, and complex queries routed to a Hermes-compatible LLM server on your LAN, with streaming responses and live "thinking" captions.

### Background operation
- **BrainService** foreground service keeps the wake-word loop alive when the app is backgrounded
- Mic-failure recovery with Google recognizer fallback
- Battery monitoring with low-battery alerts

### LAN control panel
Web control panel served by the phone at `http://<phone-ip>:8899/` — configure Hermes API keys, voice language, view device status, and dispatch commands. Token-protected access.

---

## Hardware requirements

- Android phone (Termux-compatible) running as the robot's face/brain
- Pi Pico W (Micropython) for gimbal servo control
- 2 DOF gimbal with 2 servos (pan GP0, tilt GP1)
- Webcam (for face tracking on the laptop)
- 3D-printed mounting plate (`mount/mounting_plate.stl`)
- Optional: ESP32 for base motion control

---

## Software architecture

```
laptop/llm-server/   — Flask + SocketIO server, Whisper STT, optional Hermes client
laptop/robot-controller/ — optional desktop controller
phone-app/           — Android app (Kotlin + WebView), Loona brain + UI
phone/               — Termux server (legacy)
pico/main.py         — Micropython gimbal control
esp32/               — Optional motion controller firmware
```

### Communication
- Phone ↔ Laptop: Socket.IO (port 5000) for legacy frame/audio streaming
- Phone ↔ Hermes: Native HTTP (CORS-free) to `http://<nuc-ip>:8642/v1/chat/completions`
- Phone ↔ Pico: Serial over USB (GP0/GP1 servos)

---

## Quick start

### 1. Pico (gimbal control)
1. Flash **Micropython** onto the Pico W.
2. Add `pico/main.py` via Thonny or ViperIDE.
3. Wire servos: GP0 (pan), GP1 (tilt), with shared ground to your power source.
4. On power cycle, the head centers to (0,0).

### 2. Laptop LLM server (optional — for Hermes reasoning)
1. Install Homebrew, then: `brew install llama-cpp ffmpeg portaudio python@3.13`
2. Copy `laptop/llm-server` and `laptop/robot-controller` to your MacBook.
3. In `llm-server`: `python3 -m venv venv && source venv/bin/activate && pip3 install -r requirements.txt`
4. Build whisper.cpp (see [whisper-cpp quickstart](https://github.com/ggerganov/whisper.cpp)).
5. Start llama.cpp server:
   ```
   llama-server \
     -hf unsloth/gemma-4-12B-it-qat-GGUF:UD-Q4_K_XL \
     --jinja --ngl 999 -c 16384 --fa on --host 127.0.0.1 --port 8080
   ```
6. Get your MacBook's local IP: `ipconfig getifaddr en0`
7. Start the server: `cd llm-server && source venv/bin/activate && python3 app.py`

### 3. Android phone (Loona brain)
1. Install **Termux** (recommend F-Droid version).
2. Copy `phone-app/` into Termux.
3. `pkg install python3 && pip3 install flask`
4. Ensure phone and MacBook are on the same Wi-Fi.
5. Run: `python3 app.py <MacBook-IP>`

### 4. Calibrate face tracking
1. Print the AprilTag (tag36h11 #42).
2. Run `calibrate.py` on the laptop, update `SERIAL_PORT` to your Pico's port.
3. Hold the tag to your face, use WASD to center it in each grid cell, press Enter to advance.
4. Copy the two formula lines into `track.py` (under "LOBFs (edit these)").
5. Run `python3 track.py` — the robot follows your chest tag.

---

## Configuration

### Hermes AI (Layer 2 reasoning)
Loona contacts Hermes at `http://<nuc-ip>:8642/v1/chat/completions`. Configure in the phone app settings (hold bottom-left corner) or the LAN panel:

- **Server URL** — e.g. `http://192.168.1.62:8642`
- **API key** — your Hermes Bearer token
- **Fast profile** — `/p/loona-fast/` (lightweight model, attempted first with a separate key; root `/` is the fallback)

Keys are never logged or displayed in error messages.

### Voice language
Supports **Greek (el-GR)** and **English (en-US)**. Greek TTS uses the Google engine; English uses the system default. Set in settings or the LAN panel.

---

## Voice commands

Say "Loona" (or "Λούνα"), then your command:

| Category | Examples |
|----------|----------|
| Media | "play spotify", "pause", "skip", "previous", "volume 50" |
| Apps | "open spotify", "open youtube and play", "open google" |
| Search | "search google for linux tutorials", "use google" |
| Reminders | "remind me to water plants in 10 minutes" |
| Alarms/timers | "set an alarm for 7 am", "start a timer for 5 minutes" |
| Device | "flashlight on", "brightness up", "volume down", "what's my battery" |
| Navigation | "navigate to the nearest cafe" |
| Calls | "call mom" |
| Info | "what time is it", "what's the date" |

Local commands execute instantly without touching Hermes. Anything else is routed to Hermes for reasoning.

---

## LAN control panel

The phone serves a web panel at `http://<phone-ip>:8899/` (token-protected). Use it to:

- View device status (IP, battery, permissions, Hermes reachability)
- Configure Hermes URL, API key, model, fast profile
- Set voice language and whisper options
- Test Hermes connectivity
- Dispatch microphone commands remotely

Access from any device on the same Wi-Fi. Enter the panel token when prompted.

---

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Wake word doesn't fire | Tap the robot's face to start listening (press-to-talk fallback). Check mic permission. |
| "Can't reach my brain" | Hermes isn't configured. Set the server URL and API key in settings. |
| Panel won't load | Phone and viewer must be on the same Wi-Fi. Visit `http://<phone-ip>:8899/` and enter the token. |
| Tracking unresponsive | Verify Pico serial port in `calibrate.py`/`track.py`. Reconnect Pico. |
| TTS not speaking | Install the Google TTS engine for Greek; set correct language in settings. |
| App opens but doesn't play | Say "open [app] and play" — the app opens, then media play is sent after 2.5s. |

---

## Phase 2 roadmap

- [x] Hermes fast-profile auth with root fallback
- [x] Real MediaSession media control (Spotify / YouTube Music)
- [x] Mika-inspired automation bridge (verified UI actions, task cancellation)
- [x] Background wake-word foreground service (Android 14+ compatible)
- [x] Risk-based confirmations for destructive actions
- [x] Configurable Whisper endpointing + per-turn latency logging
- [ ] Full Mika accessibility automation integration
- [ ] Multi-step visual task execution

---

## License

MIT License. See [LICENSE.md](LICENSE.md).

---

*Built by SpaceMonkeyAlfa. Progress updates at [spacemonkeyalfa.com](https://spacemonkeyalfa.com).*
