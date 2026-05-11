package com.batuhan.esp32_mobile.model;

import org.json.JSONObject;

// Backend'den gelen anlık cihaz durumunu temsil eder
public class DeviceState {

    private final boolean deviceConnected;
    private final int gasValue;
    private final boolean ledOn;
    private final int red;
    private final int green;
    private final int blue;
    private final int brightness;
    private final String updatedAt;

    public DeviceState(
            boolean deviceConnected,
            int gasValue,
            boolean ledOn,
            int red,
            int green,
            int blue,
            int brightness,
            String updatedAt
    ) {
        this.deviceConnected = deviceConnected;
        this.gasValue = gasValue;
        this.ledOn = ledOn;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.brightness = brightness;
        this.updatedAt = updatedAt;
    }

    // WebSocket'ten gelen JSON mesajı DeviceState nesnesine dönüştürülür
    public static DeviceState fromJson(JSONObject jsonObject) {
        boolean deviceConnected = jsonObject.optBoolean("deviceConnected", false);

        int gasValue = jsonObject.optInt("gasValue", 0);
        boolean ledOn = jsonObject.optInt("ledOn", 0) == 1;

        int red = jsonObject.optInt("red", 255);
        int green = jsonObject.optInt("green", 0);
        int blue = jsonObject.optInt("blue", 0);
        int brightness = jsonObject.optInt("brightness", 255);

        String updatedAt = jsonObject.optString(
                "stateUpdatedAt",
                jsonObject.optString("updatedAt", "-")
        );

        return new DeviceState(
                deviceConnected,
                gasValue,
                ledOn,
                red,
                green,
                blue,
                brightness,
                updatedAt
        );
    }

    public boolean isDeviceConnected() {
        return deviceConnected;
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

    public String getUpdatedAt() {
        return updatedAt;
    }
}