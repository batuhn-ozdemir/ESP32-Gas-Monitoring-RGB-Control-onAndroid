package com.batuhan.esp32_backend.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

// Aktif WebSocket client'larına cihaz durumunu göndermek için kullanılır
@Component
public class DeviceWebSocketBroadcaster {

    // Aynı anda bağlı olabilecek Android client'ları burada tutulur
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void addSession(WebSocketSession session) {
        sessions.add(session);
        System.out.println("WebSocket client bağlandı. Aktif client: " + sessions.size());
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
        System.out.println("WebSocket client ayrıldı. Aktif client: " + sessions.size());
    }

    // Tek bir client'a mesaj göndermek için kullanılır
    public void sendToSession(WebSocketSession session, Map<String, Object> payload) {
        try {
            if (session == null || !session.isOpen()) {
                return;
            }

            String json = objectMapper.writeValueAsString(payload);

            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }

        } catch (Exception e) {
            System.out.println("WebSocket tek client gönderim hatası: " + e.getMessage());
            removeSession(session);
        }
    }

    // Cihaz state mesajını WebSocket formatına uygun hale getirir
    public void broadcastState(Map<String, Object> state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "state");
        payload.putAll(state);

        broadcast(payload);
    }

    // Hazırlanan mesaj tüm aktif WebSocket client'larına gönderilir
    public void broadcast(Map<String, Object> payload) {
        String json;

        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            System.out.println("WebSocket JSON oluşturma hatası: " + e.getMessage());
            return;
        }

        for (WebSocketSession session : sessions) {
            try {
                if (!session.isOpen()) {
                    removeSession(session);
                    continue;
                }

                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }

            } catch (Exception e) {
                System.out.println("WebSocket broadcast hatası: " + e.getMessage());
                removeSession(session);
            }
        }
    }
}