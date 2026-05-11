package com.batuhan.esp32_mobile.model;

import org.json.JSONObject;

// Veritabanından gelen tek bir geçmiş kayıt satırını temsil eder
public class HistoryItem {

    private final String recordedAt;
    private final int gasValue;
    private final boolean ledOn;
    private final int red;
    private final int green;
    private final int blue;
    private final int brightness;

    public HistoryItem(
            String recordedAt,
            int gasValue,
            boolean ledOn,
            int red,
            int green,
            int blue,
            int brightness
    ) {
        this.recordedAt = recordedAt;
        this.gasValue = gasValue;
        this.ledOn = ledOn;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.brightness = brightness;
    }

    // REST API'den gelen JSON kayıt nesneye çevrilir
    public static HistoryItem fromJson(JSONObject jsonObject) {
        return new HistoryItem(
                jsonObject.optString("recordedAt", "-"),
                jsonObject.optInt("gasValue", 0),
                jsonObject.optInt("ledOn", 0) == 1,
                jsonObject.optInt("red", 0),
                jsonObject.optInt("green", 0),
                jsonObject.optInt("blue", 0),
                jsonObject.optInt("brightness", 0)
        );
    }

    public String getRecordedAt() {
        return recordedAt;
    }

    public int getGasValue() {
        return gasValue;
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
}