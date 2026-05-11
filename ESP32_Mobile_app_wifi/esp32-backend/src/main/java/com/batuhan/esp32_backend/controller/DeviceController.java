package com.batuhan.esp32_backend.controller;

import com.batuhan.esp32_backend.dto.ControlRequest;
import com.batuhan.esp32_backend.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// Android uygulamasının HTTP üzerinden ulaştığı cihaz endpointlerini içerir
@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    // Kayıtlı geçmiş telemetry verilerini döndürür
    @GetMapping("/history")
    public List<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return deviceService.getHistory(limit);
    }
}