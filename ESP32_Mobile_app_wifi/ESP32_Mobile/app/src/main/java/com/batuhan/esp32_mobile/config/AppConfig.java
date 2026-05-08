package com.batuhan.esp32_mobile.config;

public final class AppConfig {

    // Add your backend ip and port which you opened
    public static final String BASE_URL = "http://111.11.111.111:9090";
    public static final String WS_URL = "ws://111.11.111.111:9090/ws/device";

    public static final int HISTORY_REFRESH_MS = 30000;
    public static final int CONTROL_DEBOUNCE_MS = 100;
    public static final int RECONNECT_DELAY_MS = 2000;

    private AppConfig() {
    }
}
