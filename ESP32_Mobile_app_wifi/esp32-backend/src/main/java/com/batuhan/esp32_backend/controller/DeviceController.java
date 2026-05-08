package com.batuhan.esp32_backend.controller;

import com.batuhan.esp32_backend.dto.ControlRequest;
import com.batuhan.esp32_backend.service.DeviceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/device")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping("/history")
    public List<Map<String, Object>> getHistory(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return deviceService.getHistory(limit);
    }
}