package com.batuhan.esp32_mobile.util;

import com.batuhan.esp32_mobile.model.HistoryItem;

import java.util.List;

// Geçmiş kayıtları ekranda okunabilir tablo formatına çevirir
public final class HistoryFormatter {

    private HistoryFormatter() {
    }

    public static String format(List<HistoryItem> items) {
        if (items == null || items.isEmpty()) {
            return "Geçmiş kayıt yok";
        }

        StringBuilder builder = new StringBuilder();

        builder.append(String.format(
                "%-19s | %-5s | %-11s | %-9s | %-6s\n",
                "Zaman",
                "Gaz",
                "RGB",
                "Parlaklık",
                "LED"
        ));

        builder.append("----------------------------------------------------------------\n");

        int limit = Math.min(items.size(), 20);

        for (int i = 0; i < limit; i++) {
            HistoryItem item = items.get(i);

            String rgb = item.getRed() + "," + item.getGreen() + "," + item.getBlue();
            String ledText = item.isLedOn() ? "Açık" : "Kapalı";

            builder.append(String.format(
                    "%-19s | %-5d | %-11s | %-9d | %-6s\n",
                    item.getRecordedAt(),
                    item.getGasValue(),
                    rgb,
                    item.getBrightness(),
                    ledText
            ));
        }

        return builder.toString();
    }
}