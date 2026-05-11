package com.batuhan.esp32_backend.websocket;

import com.batuhan.esp32_backend.dto.ControlRequest;
import com.batuhan.esp32_backend.service.DeviceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

// Android uygulaması ile backend arasındaki WebSocket mesajlarını yönetir
@Component
public class DeviceWebSocketHandler extends TextWebSocketHandler {

    private final DeviceWebSocketBroadcaster broadcaster;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DeviceWebSocketHandler(
            DeviceWebSocketBroadcaster broadcaster,
            DeviceService deviceService
    ) {
        this.broadcaster = broadcaster;
        this.deviceService = deviceService;
    }

    // Android bağlanır bağlanmaz aktif session listesine eklenir ve son cihaz durumu gönderilir
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        broadcaster.addSession(session);

        // Android bağlanır bağlanmaz son state'i gönder
        broadcaster.sendToSession(session, deviceService.getLatestState());
    }

    // Android'den gelen WebSocket mesajları burada işlenir
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode json = objectMapper.readTree(message.getPayload());

            String type = json.path("type").asText("");

            if ("control".equals(type)) {
                ControlRequest request = new ControlRequest();

                request.setLedOn(json.path("ledOn").asBoolean(false));
                request.setRed(json.path("red").asInt(255));
                request.setGreen(json.path("green").asInt(0));
                request.setBlue(json.path("blue").asInt(0));
                request.setBrightness(json.path("brightness").asInt(255));

                deviceService.updateLed(request);

                return;
            }

            // Basit bağlantı kontrolü için ping mesajına pong cevabı döndürülür
            if ("ping".equals(type)) {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            }

        } catch (Exception e) {
            System.out.println("WebSocket mesaj işleme hatası: " + e.getMessage());
        }
    }

    // Bağlantı kapandığında session listeden çıkarılır
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.removeSession(session);
    }

    // WebSocket taşıma hatalarında session temizlenir
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.out.println("WebSocket transport hatası: " + exception.getMessage());
        broadcaster.removeSession(session);
    }
}