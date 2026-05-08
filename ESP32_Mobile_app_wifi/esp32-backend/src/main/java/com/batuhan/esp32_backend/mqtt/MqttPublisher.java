package com.batuhan.esp32_backend.mqtt;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class MqttPublisher {

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.control-topic}")
    private String controlTopic;

    private MqttClient client;

    @PostConstruct
    public void connect() throws Exception {
        String clientId = "spring-publisher-" + UUID.randomUUID();

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

        client.connect(options);

        System.out.println("MQTT Publisher bağlandı: " + broker);
    }

    public synchronized void publishControl(String payload) {
        try {
            if (client == null || !client.isConnected()) {
                return;
            }

            MqttMessage message = new MqttMessage(
                    payload.getBytes(StandardCharsets.UTF_8)
            );

            message.setQos(0);
            message.setRetained(true);

            client.publish(controlTopic, message);

            System.out.println("MQTT control publish: " + payload);

        } catch (Exception e) {
            System.out.println("MQTT publish hatası: " + e.getMessage());
        }
    }

    @PreDestroy
    public void disconnect() throws Exception {
        if (client != null && client.isConnected()) {
            client.disconnect();
            client.close();
        }
    }
}
