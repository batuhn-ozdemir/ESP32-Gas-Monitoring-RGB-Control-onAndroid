package com.batuhan.esp32_mobile.network;

import com.batuhan.esp32_mobile.config.AppConfig;
import com.batuhan.esp32_mobile.model.HistoryItem;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

// Backend'deki REST endpointlerine HTTP istekleri gönderen sınıftır
public class DeviceApiClient {

    // Backend'den geçmiş cihaz kayıtlarını alır
    public List<HistoryItem> getHistory(int limit) throws Exception {
        String response = sendGet("/api/device/history?limit=" + limit);

        JSONArray jsonArray = new JSONArray(response);
        List<HistoryItem> items = new ArrayList<>();

        for (int i = 0; i < jsonArray.length(); i++) {
            items.add(HistoryItem.fromJson(jsonArray.getJSONObject(i)));
        }

        return items;
    }

    // Verilen endpoint'e GET isteği atar
    private String sendGet(String endpoint) throws Exception {
        URL url = new URL(AppConfig.BASE_URL + endpoint);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);

        int responseCode = connection.getResponseCode();
        String responseBody = readResponse(connection, responseCode);

        connection.disconnect();

        if (responseCode < 200 || responseCode >= 300) {
            throw new RuntimeException("HTTP " + responseCode + " - " + responseBody);
        }

        return responseBody;
    }

    // HTTP cevabının gövdesini String olarak okur
    private String readResponse(HttpURLConnection connection, int responseCode) throws Exception {
        InputStream inputStream;

        if (responseCode >= 200 && responseCode < 300) {
            inputStream = connection.getInputStream();
        } else {
            inputStream = connection.getErrorStream();
        }

        if (inputStream == null) {
            return "";
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
        StringBuilder builder = new StringBuilder();

        String line;

        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }

        reader.close();

        return builder.toString();
    }
}