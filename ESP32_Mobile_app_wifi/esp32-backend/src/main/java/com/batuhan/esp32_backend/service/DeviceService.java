package com.batuhan.esp32_backend.service;

import com.batuhan.esp32_backend.dto.ControlRequest;
import com.batuhan.esp32_backend.mqtt.MqttPublisher;
import com.batuhan.esp32_backend.websocket.DeviceWebSocketBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeviceService {

    private final JdbcTemplate jdbcTemplate;
    private final MqttPublisher mqttPublisher;
    private final DeviceWebSocketBroadcaster webSocketBroadcaster;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final ZoneId TURKEY_ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final long DEVICE_TIMEOUT_MS = 2000;
    private static final long HISTORY_INTERVAL_MS = 60_000;

    private int gasValue = 0;
    private boolean ledOn = false;
    private int red = 255;
    private int green = 0;
    private int blue = 0;
    private int brightness = 255;

    private String stateUpdatedAt = "-";

    private long lastTelemetryAtMs = 0;
    private long lastHistorySaveMs = 0;
    private boolean lastBroadcastConnected = false;

    public DeviceService(
            JdbcTemplate jdbcTemplate,
            MqttPublisher mqttPublisher,
            DeviceWebSocketBroadcaster webSocketBroadcaster
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.mqttPublisher = mqttPublisher;
        this.webSocketBroadcaster = webSocketBroadcaster;
    }

    @PostConstruct
    public void initDatabase() {
        jdbcTemplate.execute("PRAGMA journal_mode=WAL");
        jdbcTemplate.execute("PRAGMA busy_timeout=5000");

        createTelemetryHistoryTable();
    }

    private void createTelemetryHistoryTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS telemetry_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recorded_at TEXT NOT NULL,
                gas_value INTEGER NOT NULL,
                led_on INTEGER NOT NULL,
                red INTEGER NOT NULL,
                green INTEGER NOT NULL,
                blue INTEGER NOT NULL,
                brightness INTEGER NOT NULL
            )
        """);
    }

    public synchronized void receiveTelemetryPayload(String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);

            int incomingGasValue = json.path("gasValue").asInt(0);

            boolean incomingLedOn = false;
            JsonNode ledNode = json.path("ledOn");

            if (ledNode.isBoolean()) {
                incomingLedOn = ledNode.asBoolean();
            } else if (ledNode.isNumber()) {
                incomingLedOn = ledNode.asInt() == 1;
            }

            int incomingRed = json.path("red").asInt(red);
            int incomingGreen = json.path("green").asInt(green);
            int incomingBlue = json.path("blue").asInt(blue);
            int incomingBrightness = json.path("brightness").asInt(brightness);

            receiveTelemetry(
                    incomingGasValue,
                    incomingLedOn,
                    incomingRed,
                    incomingGreen,
                    incomingBlue,
                    incomingBrightness
            );

        } catch (Exception e) {
            System.out.println("Telemetry JSON parse hatası: " + e.getMessage());
        }
    }

    // Bu metoda sadece ESP32'den gerçek MQTT telemetry gelince giriyoruz
    // Eğer veri geliyorsa bağlı demektir, buradan anlıyoruz
    // Sonra da websocketle broadcast ediyoruz mobile'e
    public synchronized void receiveTelemetry(
            int incomingGasValue,
            boolean incomingLedOn,
            int incomingRed,
            int incomingGreen,
            int incomingBlue,
            int incomingBrightness
    ) {
        long nowMs = System.currentTimeMillis();

        gasValue = incomingGasValue;
        ledOn = incomingLedOn;
        red = clamp(incomingRed);
        green = clamp(incomingGreen);
        blue = clamp(incomingBlue);
        brightness = clamp(incomingBrightness);

        stateUpdatedAt = nowText();
        lastTelemetryAtMs = nowMs;

        saveHistoryIfConnectedAndDue(nowMs);

        Map<String, Object> latestState = getLatestState();
        webSocketBroadcaster.broadcastState(latestState);

        lastBroadcastConnected = isDeviceConnected(nowMs);
    }

    public synchronized Map<String, Object> updateLed(ControlRequest request) {
        int commandRed = clamp(request.getRed());
        int commandGreen = clamp(request.getGreen());
        int commandBlue = clamp(request.getBlue());
        int commandBrightness = clamp(request.getBrightness());

        // Sadece ESP32'ye MQTT control mesajı gönderir
        publishControlToMqtt(
                request.isLedOn(),
                commandRed,
                commandGreen,
                commandBlue,
                commandBrightness
        );

        Map<String, Object> latestState = getLatestState();
        webSocketBroadcaster.broadcastState(latestState);

        return latestState;
    }

    public synchronized Map<String, Object> getLatestState() {
        boolean connected = isDeviceConnected(System.currentTimeMillis());

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("type", "state");
        result.put("id", 1);
        result.put("deviceConnected", connected);

        if (!connected) {
            result.put("gasValue", null);
            result.put("ledOn", null);
            result.put("red", null);
            result.put("green", null);
            result.put("blue", null);
            result.put("brightness", null);
            result.put("stateUpdatedAt", "-");
            result.put("updatedAt", "-");
            result.put("lastTelemetryAtMs", lastTelemetryAtMs);
            return result;
        }

        result.put("gasValue", gasValue);
        result.put("ledOn", ledOn ? 1 : 0);
        result.put("red", red);
        result.put("green", green);
        result.put("blue", blue);
        result.put("brightness", brightness);
        result.put("stateUpdatedAt", stateUpdatedAt);
        result.put("updatedAt", stateUpdatedAt);
        result.put("lastTelemetryAtMs", lastTelemetryAtMs);

        return result;
    }

    public List<Map<String, Object>> getHistory(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);

        return jdbcTemplate.queryForList("""
            SELECT
                id,
                recorded_at AS recordedAt,
                gas_value AS gasValue,
                led_on AS ledOn,
                red,
                green,
                blue,
                brightness
            FROM telemetry_history
            ORDER BY id DESC
            LIMIT ?
        """, safeLimit);
    }

    private void saveHistoryIfConnectedAndDue(long nowMs) {
        if (!isDeviceConnected(nowMs)) {
            return;
        }

        if (!shouldSaveHistory(nowMs)) {
            return;
        }

        saveHistory();
        lastHistorySaveMs = nowMs;
    }

    private boolean shouldSaveHistory(long nowMs) {
        if (lastHistorySaveMs <= 0) {
            return true;
        }

        return nowMs - lastHistorySaveMs >= HISTORY_INTERVAL_MS;
    }

    private void saveHistory() {
        jdbcTemplate.update("""
            INSERT INTO telemetry_history (
                recorded_at,
                gas_value,
                led_on,
                red,
                green,
                blue,
                brightness
            )
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
                nowText(),
                gasValue,
                ledOn ? 1 : 0,
                red,
                green,
                blue,
                brightness
        );
    }

    private void publishControlToMqtt(
            boolean commandLedOn,
            int commandRed,
            int commandGreen,
            int commandBlue,
            int commandBrightness
    ) {
        String payload = String.format(
                "{\"ledOn\":%s,\"red\":%d,\"green\":%d,\"blue\":%d,\"brightness\":%d}",
                commandLedOn ? "true" : "false",
                commandRed,
                commandGreen,
                commandBlue,
                commandBrightness
        );

        mqttPublisher.publishControl(payload);
    }

    private boolean isDeviceConnected(long nowMs) {
        if (lastTelemetryAtMs <= 0) {
            return false;
        }

        return nowMs - lastTelemetryAtMs <= DEVICE_TIMEOUT_MS;
    }

    @Scheduled(fixedRate = 1000)
    public synchronized void checkDeviceConnectionStatus() {
        boolean connected = isDeviceConnected(System.currentTimeMillis());

        if (connected != lastBroadcastConnected) {
            lastBroadcastConnected = connected;
            webSocketBroadcaster.broadcastState(getLatestState());
        }
    }

    private int clamp(int value) {
        if (value < 0) {
            return 0;
        }

        if (value > 255) {
            return 255;
        }

        return value;
    }

    private static String nowText() {
        return ZonedDateTime.now(TURKEY_ZONE).format(DATE_FORMATTER);
    }
}