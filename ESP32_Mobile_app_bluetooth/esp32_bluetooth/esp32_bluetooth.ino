#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ArduinoJson.h>
#include <Adafruit_NeoPixel.h>

#define DEVICE_NAME "ESP32-GAS-LED"

// Android uygulamasındaki UUID değerleriyle aynı olmalı
#define SERVICE_UUID   "12345678-1234-1234-1234-1234567890ab"
#define GAS_UUID       "12345678-1234-1234-1234-1234567890ac"
#define CONTROL_UUID   "12345678-1234-1234-1234-1234567890ad"

// MQ-2 gaz sensörü ve WS2812 RGB LED pinleri
const int GAS_PIN = 4;
const int WS2812_PIN = 48;
const int LED_COUNT = 1;

// ESP32'nin telefona durum bilgisini gönderme aralığı
const unsigned long SEND_INTERVAL_MS = 200;

Adafruit_NeoPixel strip(LED_COUNT, WS2812_PIN, NEO_GRB + NEO_KHZ800);

BLECharacteristic* stateCharacteristic;
bool phoneConnected = false;

unsigned long lastSendTime = 0;

// LED'in son durumu ve renk bilgileri burada tutulur
bool ledOn = false;
int red = 255;
int green = 0;
int blue = 0;
int brightness = 255;

// RGB ve parlaklık değerlerinin 0-255 aralığında kalmasını sağlamak için
int clampValue(int value) {
  if (value < 0) return 0;
  if (value > 255) return 255;
  return value;
}

// Parlaklık değerini renklere uygulamak için
int applyBrightness(int color, int brightnessValue) {
  return clampValue(color) * clampValue(brightnessValue) / 255;
}

// Güncel RGB ve parlaklık değerlerine göre ledi günceller
void updateLed() {
  int r = ledOn ? red : 0;
  int g = ledOn ? green : 0;
  int b = ledOn ? blue : 0;

  r = applyBrightness(r, brightness);
  g = applyBrightness(g, brightness);
  b = applyBrightness(b, brightness);

  strip.setPixelColor(0, strip.Color(r, g, b));
  strip.show();
}

// Telefonun bağlanma ve ayrılma durumları burada yakalanır
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) {
    phoneConnected = true;
    Serial.println("Telefon baglandi");
  }

  void onDisconnect(BLEServer* server) {
    phoneConnected = false;
    Serial.println("Telefon ayrildi");

    // Telefon ayrıldıktan sonra ESP32 tekrar görünür hale gelir
    BLEDevice::startAdvertising();
  }
};

// Android'den gelen led kontrol komutları burada işlenir
class ControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) {
    String data = characteristic->getValue();

    if (data.length() == 0) return;

    Serial.print("Komut geldi: ");
    Serial.println(data);

    StaticJsonDocument<256> json;
    DeserializationError error = deserializeJson(json, data);

    if (error) {
      Serial.println("JSON hatasi");
      return;
    }

    // Kısa alan adları ve uzun alan adları birlikte desteklenecek şekilde yapıldı
    if (json.containsKey("l")) {
      ledOn = json["l"].as<bool>();
    } else if (json.containsKey("ledOn")) {
      ledOn = json["ledOn"].as<bool>();
    }

    if (json.containsKey("r")) {
      red = clampValue(json["r"].as<int>());
    } else if (json.containsKey("red")) {
      red = clampValue(json["red"].as<int>());
    }

    if (json.containsKey("gr")) {
      green = clampValue(json["gr"].as<int>());
    } else if (json.containsKey("green")) {
      green = clampValue(json["green"].as<int>());
    }

    if (json.containsKey("b")) {
      blue = clampValue(json["b"].as<int>());
    } else if (json.containsKey("blue")) {
      blue = clampValue(json["blue"].as<int>());
    }

    if (json.containsKey("br")) {
      brightness = clampValue(json["br"].as<int>());
    } else if (json.containsKey("brightness")) {
      brightness = clampValue(json["brightness"].as<int>());
    }

    updateLed();
  }
};

void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(256);

  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new MyServerCallbacks());

  BLEService* service = server->createService(SERVICE_UUID);

  // Android bu characteristic üzerinden gaz ve led durum bilgisini alır
  stateCharacteristic = service->createCharacteristic(
    GAS_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  stateCharacteristic->addDescriptor(new BLE2902());

  // Android led kontrol komutlarını bu characteristic'e yazar
  BLECharacteristic* controlCharacteristic = service->createCharacteristic(
    CONTROL_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  controlCharacteristic->setCallbacks(new ControlCallbacks());

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);

  BLEDevice::startAdvertising();

  Serial.println("BLE basladi");
}

// MQ-2 gaz değeri ve led bilgileri JSON olarak Android'e gönderilir
void sendState() {
  int gasValue = analogRead(GAS_PIN);

  StaticJsonDocument<128> json;

  json["c"] = phoneConnected;
  json["g"] = gasValue;
  json["l"] = ledOn;
  json["r"] = red;
  json["gr"] = green;
  json["b"] = blue;
  json["br"] = brightness;

  char message[128];
  size_t length = serializeJson(json, message);

  stateCharacteristic->setValue((uint8_t*)message, length);

  if (phoneConnected) {
    stateCharacteristic->notify();
  }

  Serial.print("State: ");
  Serial.println(message);
}

void setup() {
  Serial.begin(115200);
  delay(1000);

  pinMode(GAS_PIN, INPUT);

  strip.begin();
  strip.clear();
  strip.show();

  updateLed();
  setupBle();
}

void loop() {
  unsigned long now = millis();

  // Belirlenen aralık dolduğunda yeni sensör durumu gönderilir
  if (now - lastSendTime >= SEND_INTERVAL_MS) {
    lastSendTime = now;
    sendState();
  }
}