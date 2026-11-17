/*
 * PAL9000 Desktop Robot — ESP32 Firmware
 * Motion controller for 2x TT gear motors via L298N H-bridge.
 * Connects as WebSocket client to the NUC robot server.
 *
 * Wiring (L298N → ESP32):
 *   Motor A (LR / pan):  IN1 → GPIO26, IN2 → GPIO27, ENA → GPIO25 (PWM)
 *   Motor B (UD / tilt): IN3 → GPIO14, IN4 → GPIO12, ENB → GPIO13 (PWM)
 *   L298N 12V → motor power, GND → ESP32 GND
 *
 * WebSocket: ws://<NUC_IP>:5000
 */

#include <WiFi.h>
#include <WebSocketsClient.h>
#include <ArduinoJson.h>

// ── WiFi settings ──
const char* WIFI_SSID     = "YOUR_WIFI_SSID";
const char* WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// ── NUC robot server ──
const char* WS_HOST = "YOUR_NUC_IP";
const uint16_t WS_PORT = 5000;
const char* WS_PATH = "/";

// ── Motor A: Left/Right (pan) ──
#define MOTOR_A_IN1 26
#define MOTOR_A_IN2 27
#define MOTOR_A_ENA 25   // PWM

// ── Motor B: Up/Down (tilt) ──
#define MOTOR_B_IN3 14
#define MOTOR_B_IN4 12
#define MOTOR_B_ENB 13   // PWM

// PWM config
const int PWM_FREQ = 1000;
const int PWM_RES  = 8;   // 0-255
const int PWM_CH_A = 0;
const int PWM_CH_B = 1;

// ── WebSocket client ──
WebSocketsClient webSocket;

// ── Motor state ──
struct MotorState {
  int8_t  direction;  // 0=stop, 1=CW, -1=CCW
  uint8_t speed;      // 0-255
  uint32_t startMs;   // when movement started
  uint32_t durationMs; // 0 = continuous
};

MotorState motorA = {0, 0, 0, 0};
MotorState motorB = {0, 0, 0, 0};

// ── Helpers ──

void setMotorA(int8_t dir, uint8_t speed) {
  motorA.direction = dir;
  motorA.speed = speed;
  motorA.startMs = millis();

  if (dir == 0) {
    digitalWrite(MOTOR_A_IN1, LOW);
    digitalWrite(MOTOR_A_IN2, LOW);
    ledcWrite(PWM_CH_A, 0);
  } else if (dir == 1) {
    digitalWrite(MOTOR_A_IN1, HIGH);
    digitalWrite(MOTOR_A_IN2, LOW);
    ledcWrite(PWM_CH_A, speed);
  } else { // -1
    digitalWrite(MOTOR_A_IN1, LOW);
    digitalWrite(MOTOR_A_IN2, HIGH);
    ledcWrite(PWM_CH_A, speed);
  }
}

void setMotorB(int8_t dir, uint8_t speed) {
  motorB.direction = dir;
  motorB.speed = speed;
  motorB.startMs = millis();

  if (dir == 0) {
    digitalWrite(MOTOR_B_IN3, LOW);
    digitalWrite(MOTOR_B_IN4, LOW);
    ledcWrite(PWM_CH_B, 0);
  } else if (dir == 1) {
    digitalWrite(MOTOR_B_IN3, HIGH);
    digitalWrite(MOTOR_B_IN4, LOW);
    ledcWrite(PWM_CH_B, speed);
  } else { // -1
    digitalWrite(MOTOR_B_IN3, LOW);
    digitalWrite(MOTOR_B_IN4, HIGH);
    ledcWrite(PWM_CH_B, speed);
  }
}

void stopAll() {
  setMotorA(0, 0);
  setMotorB(0, 0);
}

void sendStatus(const char* motor, const char* state) {
  StaticJsonDocument<128> doc;
  doc["ok"] = true;
  doc["motor"] = motor;
  doc["state"] = state;
  doc["ts"] = millis();
  String payload;
  serializeJson(doc, payload);
  webSocket.sendTXT(payload);
}

// ── WebSocket event handler ──
void webSocketEvent(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_DISCONNECTED:
      Serial.println("[WS] Disconnected");
      stopAll();
      break;

    case WStype_CONNECTED:
      Serial.println("[WS] Connected to NUC");
      sendStatus("SYSTEM", "connected");
      break;

    case WStype_TEXT: {
      String msg = String((char*)payload);
      Serial.printf("[WS] RX: %s\n", msg.c_str());

      StaticJsonDocument<256> doc;
      DeserializationError err = deserializeJson(doc, msg);
      if (err) {
        Serial.printf("[WS] JSON parse error: %s\n", err.c_str());
        return;
      }

      const char* m = doc["m"];   // motor: "LR" or "UD"
      int8_t  d = doc["d"];       // direction: 0/1/-1
      uint8_t s = doc["s"];       // speed 0-255
      uint32_t t = doc["t"];      // duration ms (0 = continuous)

      if (m == nullptr) {
        Serial.println("[WS] Missing 'm' field");
        return;
      }

      if (strcmp(m, "LR") == 0) {
        motorA.durationMs = t;
        setMotorA(d, s);
        sendStatus("LR", d == 0 ? "stopped" : (d == 1 ? "cw" : "ccw"));
      } else if (strcmp(m, "UD") == 0) {
        motorB.durationMs = t;
        setMotorB(d, s);
        sendStatus("UD", d == 0 ? "stopped" : (d == 1 ? "cw" : "ccw"));
      } else if (strcmp(m, "STOP") == 0 || strcmp(m, "ALL") == 0) {
        stopAll();
        sendStatus("ALL", "stopped");
      } else {
        Serial.printf("[WS] Unknown motor: %s\n", m);
      }
      break;
    }

    case WStype_BIN:
      Serial.println("[WS] Binary received (ignored)");
      break;

    default:
      break;
  }
}

// ── Setup ──
void setup() {
  Serial.begin(115200);
  Serial.println("\n[BOOT] PAL9000 ESP32 Motion Controller");

  // ── Motor pins ──
  pinMode(MOTOR_A_IN1, OUTPUT);
  pinMode(MOTOR_A_IN2, OUTPUT);
  pinMode(MOTOR_B_IN3, OUTPUT);
  pinMode(MOTOR_B_IN4, OUTPUT);

  ledcSetup(PWM_CH_A, PWM_FREQ, PWM_RES);
  ledcAttachPin(MOTOR_A_ENA, PWM_CH_A);
  ledcSetup(PWM_CH_B, PWM_FREQ, PWM_RES);
  ledcAttachPin(MOTOR_B_ENB, PWM_CH_B);

  stopAll();

  // ── WiFi ──
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.print("[WiFi] Connecting");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  Serial.printf("[WiFi] Connected. IP: %s\n", WiFi.localIP().toString().c_str());

  // ── WebSocket ──
  webSocket.begin(WS_HOST, WS_PORT, WS_PATH);
  webSocket.onEvent(webSocketEvent);
  webSocket.setReconnectInterval(5000);

  Serial.println("[BOOT] Ready. Waiting for commands...");
}

// ── Loop ──
void loop() {
  webSocket.loop();

  // ── Auto-stop motors after duration ──
  uint32_t now = millis();

  if (motorA.direction != 0 && motorA.durationMs > 0) {
    if (now - motorA.startMs >= motorA.durationMs) {
      setMotorA(0, 0);
      sendStatus("LR", "stopped");
    }
  }

  if (motorB.direction != 0 && motorB.durationMs > 0) {
    if (now - motorB.startMs >= motorB.durationMs) {
      setMotorB(0, 0);
      sendStatus("UD", "stopped");
    }
  }

  delay(10);
}
