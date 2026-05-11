package com.batuhan.esp32_mobile.network;

import android.os.Handler;
import android.os.Looper;

import com.batuhan.esp32_mobile.config.AppConfig;
import com.batuhan.esp32_mobile.model.ControlCommand;
import com.batuhan.esp32_mobile.model.DeviceState;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

// Android uygulamasının backend ile WebSocket üzerinden haberleşmesini sağlar
public class DeviceWebSocketClient {

    // WebSocket olaylarını MainActivity tarafına bildirmek için kullanılır
    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onStateReceived(DeviceState state);
        void onError(String message);
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());

    private OkHttpClient okHttpClient;
    private WebSocket webSocket;
    private boolean connected = false;

    // Bağlantı koparsa belirli süre sonra tekrar bağlanmayı dener
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!connected) {
                connect();
            }
        }
    };

    public DeviceWebSocketClient(Listener listener) {
        this.listener = listener;

        this.okHttpClient = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    // Backend WebSocket endpointine bağlantı başlatılır
    public void connect() {
        if (webSocket != null) {
            webSocket.cancel();
        }

        Request request = new Request.Builder()
                .url(AppConfig.WS_URL)
                .build();

        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;

                mainHandler.post(() -> listener.onConnected());
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject jsonObject = new JSONObject(text);
                    String type = jsonObject.optString("type", "");

                    // Backend cihaz durumunu type=state olarak gönderir
                    if ("state".equals(type)) {
                        DeviceState state = DeviceState.fromJson(jsonObject);
                        mainHandler.post(() -> listener.onStateReceived(state));
                    }

                } catch (Exception e) {
                    mainHandler.post(() -> listener.onError("WebSocket mesajı okunamadı"));
                }
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                connected = false;
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                mainHandler.post(() -> listener.onDisconnected());
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;

                mainHandler.post(() -> {
                    listener.onDisconnected();
                    listener.onError("WebSocket bağlantısı koptu");
                });

                scheduleReconnect();
            }
        });
    }

    // LED kontrol komutu WebSocket üzerinden backend'e gönderilir
    public boolean sendControl(ControlCommand command) {
        if (webSocket == null || !connected) {
            return false;
        }

        try {
            return webSocket.send(command.toJson().toString());
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isConnected() {
        return connected;
    }

    // Activity kapanırken bağlantı ve yeniden bağlanma işleri temizlenir
    public void close() {
        reconnectHandler.removeCallbacks(reconnectRunnable);

        if (webSocket != null) {
            webSocket.close(1000, "Activity destroyed");
        }
    }

    private void scheduleReconnect() {
        reconnectHandler.removeCallbacks(reconnectRunnable);
        reconnectHandler.postDelayed(reconnectRunnable, AppConfig.RECONNECT_DELAY_MS);
    }
}