package com.batuhan.esp32_mobile.model;

import org.json.JSONObject;

// Kullanıcının uygulamadan gönderdiği led kontrol komutunu temsil eder
public class ControlCommand {

    private final boolean ledOn;
    private final int red;
    private final int green;
    private final int blue;
    private final int brightness;

    public ControlCommand(boolean ledOn, int red, int green, int blue, int brightness) {
        this.ledOn = ledOn;
        this.red = clamp(red);
        this.green = clamp(green);
        this.blue = clamp(blue);
        this.brightness = clamp(brightness);
    }

    // Komut backend'in beklediği JSON formatına çevrilir
    public JSONObject toJson() throws Exception {
        JSONObject jsonObject = new JSONObject();

        jsonObject.put("type", "control");
        jsonObject.put("ledOn", ledOn);
        jsonObject.put("red", red);
        jsonObject.put("green", green);
        jsonObject.put("blue", blue);
        jsonObject.put("brightness", brightness);

        return jsonObject;
    }

    public boolean isLedOn() {
        return ledOn;
    }

    public int getRed() {
        return red;
    }

    public int getGreen() {
        return green;
    }

    public int getBlue() {
        return blue;
    }

    public int getBrightness() {
        return brightness;
    }

    // RGB ve parlaklık değerlerini güvenli aralıkta tutar
    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }
}