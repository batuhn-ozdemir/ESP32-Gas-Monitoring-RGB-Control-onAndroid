#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <Adafruit_NeoPixel.h>

// Wi-Fi bilgileri
// Kendi wifi adınızı ve şifrenizi yazın
const char* WIFI_SSID = "SSID";
const char* WIFI_PASSWORD = "PASSWORD";

// MQTT bilgileri
// Kendi backend ip'nizi ve mqtt portunuzu yazın
const char* MQTT_HOST = "111.11.111.111";
const int MQTT_PORT = 1883;

const char* MQTT_USERNAME = "user";
const char* MQTT_PASSWORD = "password";

const char* DEVICE_ID = "esp32-s3-001";

// ESP32 sensör verisini telemetry topic'ine gönderir
// Backend LED komutlarını control topic'i üzerinden ESP32'ye yollar
const char* TELEMETRY_TOPIC = "iot/esp32-s3-001/telemetry";
const char* CONTROL_TOPIC = "iot/esp32-s3-001/control";

// MQ-2 gaz sensörü
const int GAS_PIN = 4;

// Kart üstündeki WS2812 RGB LED
const int WS2812_PIN = 48;
const int WS2812_COUNT = 1;

Adafruit_NeoPixel strip(
  WS2812_COUNT,
  WS2812_PIN,
  NEO_GRB + NEO_KHZ800
);

// Telemetry gönderme aralığı
const unsigned long TELEMETRY_INTERVAL_MS = 200;
unsigned long nextTelemetryTime = 0;

unsigned long lastDebugPrintTime = 0;

// ESP32 üzerinde uygulanan son led durumu
bool currentLedOn = false;
int currentRed = 255;
int currentGreen = 0;
int currentBlue = 0;
int currentBrightness = 255;

WiFiClient wifiClient;
PubSubClient mqttClient(wifiClient);

// Değerleri 0-255 aralığında tutmak için
int clampValue(int value) {
  if (value < 0) return 0;
  if (value > 255) return 255;
  return value;
}

// Renk kanalına parlaklık oranını uygular
int applyBrightness(int colorValue, int brightness) {
  colorValue = clampValue(colorValue);
  brightness = clampValue(brightness);

  return (colorValue * brightness) / 255;
}

// WS2812 RGB LED
void setRgbLed(bool ledOn, int red, int green, int blue, int brightness) {
  currentLedOn = ledOn;
  currentRed = clampValue(red);
  currentGreen = clampValue(green);
  currentBlue = clampValue(blue);
  currentBrightness = clampValue(brightness);

  int outRed = currentRed;
  int outGreen = currentGreen;
  int outBlue = currentBlue;

  if (!currentLedOn) {
    outRed = 0;
    outGreen = 0;
    outBlue = 0;
  }

  outRed = applyBrightness(outRed, currentBrightness);
  outGreen = applyBrightness(outGreen, currentBrightness);
  outBlue = applyBrightness(outBlue, currentBrightness);

  strip.setPixelColor(0, strip.Color(outRed, outGreen, outBlue));
  strip.show();
}

// LED başlangıç ayarları yapılır
void setupRgbLed() {
  strip.begin();
  strip.clear();
  strip.show();

  setRgbLed(false, 255, 0, 0, 255);
}

// ESP32 Wi-Fi ağına bağlanır
void connectWiFi() {
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  Serial.print("WiFi baglaniyor");

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  WiFi.setSleep(false);

  Serial.println();
  Serial.println("WiFi baglandi");
  Serial.print("ESP32 IP: ");
  Serial.println(WiFi.localIP());
}

// MQTT control mesajı geldiğinde çalışır
void onMqttMessage(char* topic, byte* payload, unsigned int length) {
  char message[512];

  if (length >= sizeof(message)) {
    Serial.println("MQTT mesajı fazla büyük, yok sayıldı");
    return;
  }

  memcpy(message, payload, length);
  message[length] = '\0';

  Serial.print("MQTT mesaj geldi. Topic: ");
  Serial.println(topic);
  Serial.print("Payload: ");
  Serial.println(message);

  StaticJsonDocument<256> json;
  DeserializationError error = deserializeJson(json, message);

  if (error) {
    Serial.print("Control JSON parse hatasi: ");
    Serial.println(error.c_str());
    return;
  }

  bool ledOn = false;

  if (json["ledOn"].is<bool>()) {
    ledOn = json["ledOn"].as<bool>();
  } else if (json["ledOn"].is<int>()) {
    ledOn = json["ledOn"].as<int>() == 1;
  }

  int red = json["red"] | currentRed;
  int green = json["green"] | currentGreen;
  int blue = json["blue"] | currentBlue;
  int brightness = json["brightness"] | currentBrightness;

  // Backend'den gelen komut lede uygulanır
  setRgbLed(ledOn, red, green, blue, brightness);
}

// MQTT broker bağlantısı yapılır ve control topic'ine abone olunur
void connectMqtt() {
  while (!mqttClient.connected()) {
    Serial.print("MQTT baglaniyor... ");

    String clientId = String("esp32-client-") + DEVICE_ID;

    bool connected = mqttClient.connect(
      clientId.c_str(),
      MQTT_USERNAME,
      MQTT_PASSWORD
    );

    if (connected) {
      Serial.println("basarili");

      mqttClient.subscribe(CONTROL_TOPIC);
      Serial.print("Subscribe olunan topic: ");
      Serial.println(CONTROL_TOPIC);

    } else {
      Serial.print("basarisiz. State: ");
      Serial.println(mqttClient.state());
      delay(1000);
    }
  }
}

// Gaz değeri ve led durumu MQTT telemetry topic'ine gönderilir
void publishTelemetry() {
  int gasValue = analogRead(GAS_PIN);

  StaticJsonDocument<256> json;
  json["deviceId"] = DEVICE_ID;
  json["gasValue"] = gasValue;

  // ESP32'nin gerçekten uyguladığı son LED durumu
  json["ledOn"] = currentLedOn;
  json["red"] = currentRed;
  json["green"] = currentGreen;
  json["blue"] = currentBlue;
  json["brightness"] = currentBrightness;

  char payload[256];
  size_t payloadLength = serializeJson(json, payload);

  boolean ok = mqttClient.publish(
    TELEMETRY_TOPIC,
    payload,
    payloadLength
  );

  unsigned long now = millis();

  // Seri port çıktısı her 1 saniyede bir yazdırılır
  if (now - lastDebugPrintTime >= 1000) {
    lastDebugPrintTime = now;

    Serial.print("MQTT publish: ");
    Serial.print(ok ? "OK" : "FAIL");
    Serial.print(" | Gaz: ");
    Serial.print(gasValue);
    Serial.print(" | LED: ");
    Serial.print(currentLedOn ? "ACIK" : "KAPALI");
    Serial.print(" | RGB: ");
    Serial.print(currentRed);
    Serial.print(",");
    Serial.print(currentGreen);
    Serial.print(",");
    Serial.print(currentBlue);
    Serial.print(" | Parlaklik: ");
    Serial.println(currentBrightness);
  }
}

// Setup
void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(GAS_PIN, INPUT);

  setupRgbLed();

  connectWiFi();

  mqttClient.setServer(MQTT_HOST, MQTT_PORT);
  mqttClient.setCallback(onMqttMessage);
  mqttClient.setBufferSize(512);

  connectMqtt();

  nextTelemetryTime = millis();
}

// Loop
void loop() {
  if (WiFi.status() != WL_CONNECTED) {
    connectWiFi();
  }

  if (!mqttClient.connected()) {
    connectMqtt();
  }

  mqttClient.loop();

  unsigned long now = millis();

  // Telemetry gönderimi millis tabanlı kontrol edilir
  if ((long)(now - nextTelemetryTime) >= 0) {
    nextTelemetryTime += TELEMETRY_INTERVAL_MS;

    publishTelemetry();

    if ((long)(millis() - nextTelemetryTime) > TELEMETRY_INTERVAL_MS) {
      nextTelemetryTime = millis() + TELEMETRY_INTERVAL_MS;
    }
  }
}