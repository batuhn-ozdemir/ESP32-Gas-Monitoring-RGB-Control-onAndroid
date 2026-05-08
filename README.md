# ESP32 Gas Monitoring and RGB LED Control

This repository contains an ESP32-based gas monitoring and RGB LED control project.  
The project demonstrates two different communication approaches between an ESP32 device and a mobile application:

- BLE communication
- Wi-Fi communication

The main purpose of the project is to read gas sensor data from an MQ-2 gas sensor, display the real-time value on an Android mobile application, and control a WS2812 RGB LED from the mobile app.

---

## Project Overview

The system consists of:

- ESP32-S3 development board
- MQ-2 gas sensor
- WS2812 RGB LED
- Android mobile application
- BLE and Wi-Fi based communication versions

The Android application can display the current gas sensor value and control the LED state, RGB color values, and brightness level.

---

## Repository Structure

```text
ESP32-Gas-RGB-Control/
│
├── README.md
├── .gitignore
│
├── ble-version/
│   ├── android-app/
│   └── esp32-ble/
│
├── wifi-version/
│   ├── android-app/
│   ├── esp32-wifi/
│   └── backend/
