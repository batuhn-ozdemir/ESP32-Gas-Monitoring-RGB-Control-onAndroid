package com.batuhan.esp32_backend.mqtt;

import com.batuhan.esp32_backend.service.DeviceService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

// ESP32'den gelen telemetry mesajlarını MQTT üzerinden dinler
@Component
public class MqttTelemetrySubscriber {

    private final DeviceService deviceService;

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.telemetry-topic}")
    private String telemetryTopic;

    private MqttClient client;

    public MqttTelemetrySubscriber(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    // Uygulama açıldığında MQTT broker'a bağlanılır ve telemetry topic'i dinlenir
    @PostConstruct
    public void connectAndSubscribe() throws Exception {
        String clientId = "spring-subscriber-" + UUID.randomUUID();

        client = new MqttClient(
                broker,
                clientId,
                new MemoryPersistence()
        );

        MqttConnectOptions options = new MqttConnectOptions();
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);

        if (username != null && !username.isBlank()) {
            options.setUserName(username);
            options.setPassword(password.toCharArray());
        }

        client.setCallback(new MqttCallback() {

            @Override
            public void connectionLost(Throwable cause) {
                System.out.println("MQTT bağlantısı koptu: " + cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                String payload = new String(
                        message.getPayload(),
                        StandardCharsets.UTF_8
                );

                System.out.println("MQTT telemetry geldi. Topic: " + topic);
                System.out.println("Payload: " + payload);

                // Gelen telemetry verisi ana servis tarafında işlenir
                deviceService.receiveTelemetryPayload(payload);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // Subscriber tarafında genelde kullanılmıyor
            }
        });

        client.connect(options);
        client.subscribe(telemetryTopic, 0);

        System.out.println("MQTT Subscriber bağlandı: " + broker);
        System.out.println("Dinlenen topic: " + telemetryTopic);
    }

    // Uygulama kapanırken MQTT bağlantısı düzgün şekilde kapatılır
    @PreDestroy
    public void disconnect() throws Exception {
        if (client != null && client.isConnected()) {
            client.disconnect();
            client.close();
        }
    }
}