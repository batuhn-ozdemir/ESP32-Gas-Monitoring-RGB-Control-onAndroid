package com.batuhan.esp32bluetoothmobileapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class MainActivity extends Activity {

    // ESP32 mac adresi
    // ESP32'nin MAC adresini koy
    private static final String TARGET_MAC_ADDRESS = "AA:BB:12:34:56:78";

    private static final UUID SERVICE_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    private static final UUID GAS_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ac");

    private static final UUID CONTROL_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ad");

    // standart BLE notification descriptor UUID’si
    // xxxx2902 - Client Characteristic Configuration
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int CONTROL_DEBOUNCE_MS = 100;
    private static final int REQUESTED_MTU = 256;
    private static final long LOCAL_CONTROL_PROTECT_MS = 700;
    private static final long GAS_UI_UPDATE_INTERVAL_MS = 200;

    private long sonLocalKontrolDegisimSuresiMs = 0;
    private long sonUiDurumGuncellemeZamaniMs = 0;
    private boolean kullaniciKaydiriyormuKontrol = false;

    private boolean esp32Baglimi = false;
    private boolean esp32denmiGuncelleniyor = false;
    private boolean bluetoothAlicisiKayitlimi = false;
    private boolean servisAramaBasladimi = false;

    private TextView connectionTextView;
    private TextView gasTextView;
    private TextView redValueTextView;
    private TextView greenValueTextView;
    private TextView blueValueTextView;
    private TextView brightnessValueTextView;

    private SeekBar kirmiziAyarlamaBari;
    private SeekBar yesilAyarlamaBari;
    private SeekBar maviAyarlamaBari;
    private SeekBar parlaklikAyarlamaBari;

    private Switch ledSwitch;
    private View colorPreviewView;

    private Button kirmiziButon;
    private Button yesilButon;
    private Button maviButon;
    private Button beyazButon;
    private Button sariButon;
    private Button morButon;
    private Button ledKapatmaButon;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic controlCharacteristic;

    private boolean baglimiKontrol = false;
    private boolean baglaniyormuKontrol = false;

    private boolean ledOn = false;
    private int red = 255;
    private int green = 0;
    private int blue = 0;
    private int brightness = 255;
    private int lastGasValue = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler controlHandler = new Handler(Looper.getMainLooper());

    private final Runnable yenidenBaglanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!baglimiKontrol && !baglaniyormuKontrol && tumIzinlerSaglandimiKontrol() && bluetoothHazirmiKontrol()) {
                esp32Baglan();
            }
        }
    };

    private final Runnable komutGonderRunnable = new Runnable() {
        @Override
        public void run() {
            komutGonder();
        }
    };

    private final BroadcastReceiver bluetoothDurumAlicisi = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            int state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
            );

            if (state == BluetoothAdapter.STATE_TURNING_OFF ||
                    state == BluetoothAdapter.STATE_OFF) {

                handler.removeCallbacks(yenidenBaglanRunnable);
                controlHandler.removeCallbacks(komutGonderRunnable);

                baglimiKontrol = false;
                baglaniyormuKontrol = false;
                esp32Baglimi = false;
                servisAramaBasladimi = false;

                uiBaglantiTextVeButonEnablingGuncelle(false);

                durumTextAyarla(
                        "Durum: Bağlı değil" +
                                "\nBluetooth kapalı" +
                                "\nGaz Değeri: -" +
                                "\nLED: Son bilinen durum" +
                                "\nRGB: " + red + ", " + green + ", " + blue +
                                "\nParlaklık: " + brightness
                );

                closeGatt();
            } else if (state == BluetoothAdapter.STATE_TURNING_ON) {
                baglimiKontrol = false;
                baglaniyormuKontrol = false;
                esp32Baglimi = false;
                servisAramaBasladimi = false;

                uiBaglantiTextVeButonEnablingGuncelle(false);
                durumTextAyarla("Bluetooth açılıyor...");
            } else if (state == BluetoothAdapter.STATE_ON) {
                baglimiKontrol = false;
                baglaniyormuKontrol = false;
                esp32Baglimi = false;
                servisAramaBasladimi = false;

                uiBaglantiTextVeButonEnablingGuncelle(false);
                durumTextAyarla("Bluetooth açıldı\nESP32'ye tekrar bağlanılıyor...");

                handler.removeCallbacks(yenidenBaglanRunnable);
                handler.postDelayed(yenidenBaglanRunnable, 1500);
            }
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        // "Call requires permission which may be rejected by user..."
        // uyarısını susturmak için @SuppressLint("MissingPermission")
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (!baglantiIziniVarmiKontrol()) {
                return;
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                baglimiKontrol = false;
                baglaniyormuKontrol = false;
                esp32Baglimi = false;
                servisAramaBasladimi = false;

                handler.post(() -> uiBaglantiTextVeButonEnablingGuncelle(false));

                durumTextAyarla(
                        "Durum: Bağlı değil" +
                                "\nGATT hata: " + status +
                                "\nTekrar bağlanılacak..."
                );

                closeGatt();

                if (bluetoothHazirmiKontrol()) {
                    yenidenBaglan();
                }

                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                baglimiKontrol = true;
                baglaniyormuKontrol = false;
                esp32Baglimi = true;
                servisAramaBasladimi = false;

                handler.post(() -> uiBaglantiTextVeButonEnablingGuncelle(true));

                durumTextAyarla("ESP32 bağlandı\nMTU ayarlanıyor...");
                ekrandaAlttaBildirimGoster("ESP32 bağlandı");

                boolean mtuRequestStarted = false;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mtuRequestStarted = gatt.requestMtu(REQUESTED_MTU);
                }

                if (!mtuRequestStarted) {
                    handler.postDelayed(() -> servisAramaBaslat(gatt), 500);
                }

                handler.postDelayed(() -> servisAramaBaslat(gatt), 1200);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                baglimiKontrol = false;
                baglaniyormuKontrol = false;
                esp32Baglimi = false;
                servisAramaBasladimi = false;

                handler.post(() -> uiBaglantiTextVeButonEnablingGuncelle(false));

                durumTextAyarla(
                        "Durum: Bağlı değil" +
                                "\nBağlantı koptu" +
                                "\nGaz Değeri: -" +
                                "\nLED: Son bilinen durum" +
                                "\nRGB: " + red + ", " + green + ", " + blue +
                                "\nParlaklık: " + brightness
                );

                closeGatt();

                if (bluetoothHazirmiKontrol()) {
                    yenidenBaglan();
                }
            }
        }

        // "Call requires permission which may be rejected by user..."
        // uyarısını susturmak için @SuppressLint("MissingPermission")
        @Override
        @SuppressLint("MissingPermission")
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            durumTextAyarla(
                    "ESP32 bağlandı" +
                            "\nMTU: " + mtu +
                            "\nServisler aranıyor..."
            );

            servisAramaBaslat(gatt);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            BluetoothGattService service = gatt.getService(SERVICE_UUID);

            BluetoothGattCharacteristic gasCharacteristic = service.getCharacteristic(GAS_UUID);
            controlCharacteristic = service.getCharacteristic(CONTROL_UUID);

            durumTextAyarla("Servisler bulundu\nGaz verisi bekleniyor...");
            gazNotificationAktifEt(gatt, gasCharacteristic);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            if (GAS_UUID.equals(characteristic.getUuid())) {
                String payload = new String(value, StandardCharsets.UTF_8);
                handler.post(() -> gelenPayloadiOkuVeGuncelle(payload));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (GAS_UUID.equals(characteristic.getUuid())) {
                String payload = new String(characteristic.getValue(), StandardCharsets.UTF_8);
                handler.post(() -> gelenPayloadiOkuVeGuncelle(payload));
            }
        }
    };

    // activity ilk açıldığında çalışır
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        uiBirimleriniBagla();
        uiBirimleriniBaslat();
        renkButonlariniBaslat();

        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);

        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        bluetoothAlicisinaKaydol();
        uiBaglantiTextVeButonEnablingGuncelle(false);

        ihtiyacVarsaYetkiIste();
    }

    // uygulama tekrar ekrana geldiğinde çalışır
    @Override
    protected void onResume() {
        super.onResume();

        if (tumIzinlerSaglandimiKontrol() && bluetoothHazirmiKontrol()) {
            esp32Baglan();
        }
    }

    // uygulama arka plana gidince çalışır
    @Override
    protected void onPause() {
        super.onPause();
        controlHandler.removeCallbacks(komutGonderRunnable);
    }

    // activity kapanırken çalışır
    // bekleyen işler iptal edilir
    @Override
    protected void onDestroy() {
        super.onDestroy();

        controlHandler.removeCallbacks(komutGonderRunnable);
        handler.removeCallbacks(yenidenBaglanRunnable);

        closeGatt();
        bluetoothAlicisindanCikisYap();
    }

    // yetki izinleri cevabı sonrasında çalıştırılır
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 100) {
            if (tumIzinlerSaglandimiKontrol()) {
                esp32Baglan();
            } else {
                durumTextAyarla("Bluetooth izinleri verilmedi");
                ekrandaAlttaBildirimGoster("Bluetooth izinleri verilmedi");
            }
        }
    }

    private void uiBirimleriniBagla() {
        connectionTextView = findViewById(R.id.connectionTextView);
        gasTextView = findViewById(R.id.gasTextView);

        redValueTextView = findViewById(R.id.redValueTextView);
        greenValueTextView = findViewById(R.id.greenValueTextView);
        blueValueTextView = findViewById(R.id.blueValueTextView);
        brightnessValueTextView = findViewById(R.id.brightnessValueTextView);

        kirmiziAyarlamaBari = findViewById(R.id.redSeekBar);
        yesilAyarlamaBari = findViewById(R.id.greenSeekBar);
        maviAyarlamaBari = findViewById(R.id.blueSeekBar);
        parlaklikAyarlamaBari = findViewById(R.id.brightnessSeekBar);

        ledSwitch = findViewById(R.id.ledSwitch);
        colorPreviewView = findViewById(R.id.colorPreviewView);

        kirmiziButon = findViewById(R.id.redButton);
        yesilButon = findViewById(R.id.greenButton);
        maviButon = findViewById(R.id.blueButton);
        beyazButon = findViewById(R.id.whiteButton);
        sariButon = findViewById(R.id.yellowButton);
        morButon = findViewById(R.id.purpleButton);
        ledKapatmaButon = findViewById(R.id.offButton);
    }

    private void uiBirimleriniBaslat() {
        kirmiziAyarlamaBari.setMax(255);
        yesilAyarlamaBari.setMax(255);
        maviAyarlamaBari.setMax(255);
        parlaklikAyarlamaBari.setMax(255);

        kirmiziAyarlamaBari.setProgress(red);
        yesilAyarlamaBari.setProgress(green);
        maviAyarlamaBari.setProgress(blue);
        parlaklikAyarlamaBari.setProgress(brightness);

        ledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (esp32denmiGuncelleniyor) return;
            if (!esp32Baglimi) return;

            localSureyiGuncelle();

            ledOn = isChecked;
            uiRenkBirimleriniGuncelle();
            kontroleGondermePlanla();
        });

        kirmiziAyarlamaBari.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = true;
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (esp32denmiGuncelleniyor) return;
                if (!esp32Baglimi) return;
                if (!fromUser) return;

                localSureyiGuncelle();

                red = progress;
                uiRenkBirimleriniGuncelle();
                kontroleGondermePlanla();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = false;
                localSureyiGuncelle();
                komutGonder();
            }
        });

        yesilAyarlamaBari.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = true;
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (esp32denmiGuncelleniyor) return;
                if (!esp32Baglimi) return;
                if (!fromUser) return;

                localSureyiGuncelle();

                green = progress;
                uiRenkBirimleriniGuncelle();
                kontroleGondermePlanla();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = false;
                localSureyiGuncelle();
                komutGonder();
            }
        });

        maviAyarlamaBari.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = true;
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (esp32denmiGuncelleniyor) return;
                if (!esp32Baglimi) return;
                if (!fromUser) return;

                localSureyiGuncelle();

                blue = progress;
                uiRenkBirimleriniGuncelle();
                kontroleGondermePlanla();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = false;
                localSureyiGuncelle();
                komutGonder();
            }
        });

        parlaklikAyarlamaBari.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = true;
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (esp32denmiGuncelleniyor) return;
                if (!esp32Baglimi) return;
                if (!fromUser) return;

                localSureyiGuncelle();

                brightness = progress;
                uiRenkBirimleriniGuncelle();
                kontroleGondermePlanla();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                kullaniciKaydiriyormuKontrol = false;
                localSureyiGuncelle();
                komutGonder();
            }
        });

        uiRenkBirimleriniGuncelle();
    }

    private void renkButonlariniBaslat() {
        kirmiziButon.setBackgroundColor(Color.rgb(220, 40, 40));
        yesilButon.setBackgroundColor(Color.rgb(40, 160, 70));
        maviButon.setBackgroundColor(Color.rgb(40, 90, 220));
        beyazButon.setBackgroundColor(Color.rgb(210, 210, 210));
        sariButon.setBackgroundColor(Color.rgb(230, 190, 40));
        morButon.setBackgroundColor(Color.rgb(150, 60, 180));
        ledKapatmaButon.setBackgroundColor(Color.rgb(40, 40, 40));

        kirmiziButon.setTextColor(Color.WHITE);
        yesilButon.setTextColor(Color.WHITE);
        maviButon.setTextColor(Color.WHITE);
        beyazButon.setTextColor(Color.BLACK);
        sariButon.setTextColor(Color.BLACK);
        morButon.setTextColor(Color.WHITE);
        ledKapatmaButon.setTextColor(Color.WHITE);

        kirmiziButon.setOnClickListener(v -> renkleriUygula(true, 255, 0, 0));
        yesilButon.setOnClickListener(v -> renkleriUygula(true, 0, 255, 0));
        maviButon.setOnClickListener(v -> renkleriUygula(true, 0, 0, 255));
        beyazButon.setOnClickListener(v -> renkleriUygula(true, 255, 255, 255));
        sariButon.setOnClickListener(v -> renkleriUygula(true, 255, 255, 0));
        morButon.setOnClickListener(v -> renkleriUygula(true, 255, 0, 255));

        ledKapatmaButon.setOnClickListener(v -> {
            if (!esp32Baglimi) return;

            localSureyiGuncelle();

            ledOn = false;

            esp32denmiGuncelleniyor = true;
            if (ledSwitch.isChecked()) {
                ledSwitch.setChecked(false);
            }
            esp32denmiGuncelleniyor = false;

            uiRenkBirimleriniGuncelle();
            komutGonder();
        });
    }

    private void renkleriUygula(boolean turnOn, int r, int g, int b) {
        if (!esp32Baglimi) return;

        localSureyiGuncelle();

        ledOn = turnOn;
        red = renkMenzilSaglama(r);
        green = renkMenzilSaglama(g);
        blue = renkMenzilSaglama(b);

        esp32denmiGuncelleniyor = true;

        if (ledSwitch.isChecked() != ledOn) {
            ledSwitch.setChecked(ledOn);
        }

        kirmiziAyarlamaBari.setProgress(red);
        yesilAyarlamaBari.setProgress(green);
        maviAyarlamaBari.setProgress(blue);

        esp32denmiGuncelleniyor = false;

        uiRenkBirimleriniGuncelle();
        komutGonder();
    }

    private void localSureyiGuncelle() {
        sonLocalKontrolDegisimSuresiMs = System.currentTimeMillis();
    }

    // 700 ms içinde kullanıcı bir kontrolü değiştirdiyse veya değiştirme işlemi devam ediyorsa,
    // gelen ESP32 verisiyle yerel kontrolleri güncelletmemek istiyoruz
    private boolean lokalAyarlariKorumalimiKontrol() {
        long now = System.currentTimeMillis();
        return kullaniciKaydiriyormuKontrol || (now - sonLocalKontrolDegisimSuresiMs < LOCAL_CONTROL_PROTECT_MS);
    }

    private void esp32denGelenLedDegerleriyleUiGuncelle() {
        esp32denmiGuncelleniyor = true;

        if (ledSwitch.isChecked() != ledOn) {
            ledSwitch.setChecked(ledOn);
        }

        if (kirmiziAyarlamaBari.getProgress() != red) {
            kirmiziAyarlamaBari.setProgress(red);
        }

        if (yesilAyarlamaBari.getProgress() != green) {
            yesilAyarlamaBari.setProgress(green);
        }

        if (maviAyarlamaBari.getProgress() != blue) {
            maviAyarlamaBari.setProgress(blue);
        }

        if (parlaklikAyarlamaBari.getProgress() != brightness) {
            parlaklikAyarlamaBari.setProgress(brightness);
        }

        esp32denmiGuncelleniyor = false;

        uiRenkBirimleriniGuncelle();
    }

    private void TextViewDegerleriniGuncelle(boolean stateConnected, int gasValue) {
        long now = System.currentTimeMillis();

        if (now - sonUiDurumGuncellemeZamaniMs < GAS_UI_UPDATE_INTERVAL_MS) {
            return;
        }

        sonUiDurumGuncellemeZamaniMs = now;

        gasTextView.setText(
                "Durum: " + (stateConnected ? "Bağlı" : "Bağlı değil") +
                        "\nGaz Değeri: " + gasValue +
                        "\nLED: " + (ledOn ? "Açık" : "Kapalı") +
                        "\nRGB: " + red + ", " + green + ", " + blue +
                        "\nParlaklık: " + brightness
        );
    }

    private void uiBaglantiTextVeButonEnablingGuncelle(boolean isConnected) {
        esp32Baglimi = isConnected;

        if (connectionTextView != null) {
            connectionTextView.setText(isConnected ? "Durum: Bağlı" : "Durum: Bağlı değil");
            connectionTextView.setTextColor(
                    isConnected ? Color.rgb(0, 150, 0) : Color.rgb(200, 0, 0)
            );
        }

        ledSwitch.setEnabled(isConnected);
        kirmiziAyarlamaBari.setEnabled(isConnected);
        yesilAyarlamaBari.setEnabled(isConnected);
        maviAyarlamaBari.setEnabled(isConnected);
        parlaklikAyarlamaBari.setEnabled(isConnected);

        kirmiziButon.setEnabled(isConnected);
        yesilButon.setEnabled(isConnected);
        maviButon.setEnabled(isConnected);
        beyazButon.setEnabled(isConnected);
        sariButon.setEnabled(isConnected);
        morButon.setEnabled(isConnected);
        ledKapatmaButon.setEnabled(isConnected);
    }

    private void kontroleGondermePlanla() {
        controlHandler.removeCallbacks(komutGonderRunnable);
        controlHandler.postDelayed(komutGonderRunnable, CONTROL_DEBOUNCE_MS);
    }

    @SuppressLint("MissingPermission")
    private void esp32Baglan() {
        if (baglimiKontrol || baglaniyormuKontrol) {
            return;
        }

        if (!baglantiIziniVarmiKontrol()) {
            durumTextAyarla("Bluetooth bağlantı izni yok");
            return;
        }

        if (bluetoothAdapter == null) {
            durumTextAyarla("Bluetooth adaptörü yok");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            uiBaglantiTextVeButonEnablingGuncelle(false);
            durumTextAyarla("Bluetooth kapalı");
            ekrandaAlttaBildirimGoster("Bluetooth açık değil");
            return;
        }

        try {
            durumTextAyarla("ESP32'ye bağlanılıyor...\nAdres: " + TARGET_MAC_ADDRESS);

            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(TARGET_MAC_ADDRESS);

            baglaniyormuKontrol = true;
            servisAramaBasladimi = false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothGatt = device.connectGatt(
                        this,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                );
            } else {
                bluetoothGatt = device.connectGatt(this, false, gattCallback);
            }

        } catch (Exception e) {
            baglaniyormuKontrol = false;
            durumTextAyarla("Bağlantı başlatılamadı:\n" + e.getMessage());
            ekrandaAlttaBildirimGoster("Bağlantı başlatılamadı");
        }
    }

    @SuppressLint("MissingPermission")
    private void servisAramaBaslat(BluetoothGatt gatt) {
        if (gatt == null) return;
        if (!baglantiIziniVarmiKontrol()) return;
        if (servisAramaBasladimi) return;

        servisAramaBasladimi = true;
        gatt.discoverServices();
    }

    @SuppressLint("MissingPermission")
    private void gazNotificationAktifEt(BluetoothGatt gatt,
                                       BluetoothGattCharacteristic characteristic) {
        if (!baglantiIziniVarmiKontrol()) {
            return;
        }

        gatt.setCharacteristicNotification(characteristic, true);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);

        // android 13 ve üzeri için
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            );
        } else {
            writeDescriptorLegacy(gatt, descriptor);
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private void writeDescriptorLegacy(BluetoothGatt gatt,
                                       BluetoothGattDescriptor descriptor) {
        if (!baglantiIziniVarmiKontrol()) {
            return;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        gatt.writeDescriptor(descriptor);
    }

    private void gelenPayloadiOkuVeGuncelle(String payload) {
        try {
            JSONObject json = new JSONObject(payload);

            boolean stateConnected =
                    json.has("c")
                            ? json.optBoolean("c", baglimiKontrol)
                            : json.optBoolean("connected", baglimiKontrol);

            int gasValue =
                    json.has("g")
                            ? json.optInt("g", lastGasValue)
                            : json.optInt("gasValue", lastGasValue);

            boolean serverLedOn =
                    json.has("l")
                            ? json.optBoolean("l", ledOn)
                            : json.optBoolean("ledOn", ledOn);

            int serverRed =
                    json.has("r")
                            ? renkMenzilSaglama(json.optInt("r", red))
                            : renkMenzilSaglama(json.optInt("red", red));

            int serverGreen =
                    json.has("gr")
                            ? renkMenzilSaglama(json.optInt("gr", green))
                            : renkMenzilSaglama(json.optInt("green", green));

            int serverBlue =
                    json.has("b")
                            ? renkMenzilSaglama(json.optInt("b", blue))
                            : renkMenzilSaglama(json.optInt("blue", blue));

            int serverBrightness =
                    json.has("br")
                            ? renkMenzilSaglama(json.optInt("br", brightness))
                            : renkMenzilSaglama(json.optInt("brightness", brightness));

            lastGasValue = gasValue;

            if (esp32Baglimi != stateConnected) {
                uiBaglantiTextVeButonEnablingGuncelle(stateConnected);
            }

            boolean lokalAyarlariKorumaKontrol = lokalAyarlariKorumalimiKontrol();

            if (!lokalAyarlariKorumaKontrol) {
                ledOn = serverLedOn;
                red = serverRed;
                green = serverGreen;
                blue = serverBlue;
                brightness = serverBrightness;

                esp32denGelenLedDegerleriyleUiGuncelle();
            }

            TextViewDegerleriniGuncelle(stateConnected, gasValue);

        } catch (Exception e) {
            durumTextAyarla(
                    "State verisi eksik/yarım geldi." +
                            "\nGelen parça:\n" + payload
            );
        }
    }

    @SuppressLint("MissingPermission")
    private void komutGonder() {
        if (!baglimiKontrol) {
            return;
        }

        if (bluetoothGatt == null || controlCharacteristic == null) {
            return;
        }

        if (!baglantiIziniVarmiKontrol()) {
            return;
        }

        try {
            JSONObject json = new JSONObject();

            // Kısa key kullanıyoruz. ESP32 tarafında da bunları okuyacak.
            json.put("l", ledOn);
            json.put("r", red);
            json.put("gr", green);
            json.put("b", blue);
            json.put("br", brightness);

            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);

            // android 13 ve üzeri için
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt.writeCharacteristic(
                        controlCharacteristic,
                        data,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                );
            } else {
                writeCharacteristicLegacy(data);
            }

        } catch (Exception e) {
            ekrandaAlttaBildirimGoster("Komut gönderilemedi");
        }
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private void writeCharacteristicLegacy(byte[] data) {
        if (bluetoothGatt == null || controlCharacteristic == null) {
            return;
        }

        if (!baglantiIziniVarmiKontrol()) {
            return;
        }

        controlCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        controlCharacteristic.setValue(data);
        bluetoothGatt.writeCharacteristic(controlCharacteristic);
    }

    private void uiRenkBirimleriniGuncelle() {
        redValueTextView.setText("Kırmızı: " + red);
        greenValueTextView.setText("Yeşil: " + green);
        blueValueTextView.setText("Mavi: " + blue);
        brightnessValueTextView.setText("Parlaklık: " + brightness);

        int r = ledOn ? red : 0;
        int g = ledOn ? green : 0;
        int b = ledOn ? blue : 0;

        colorPreviewView.setBackgroundColor(Color.rgb(r, g, b));
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (bluetoothGatt != null && baglantiIziniVarmiKontrol()) {
            bluetoothGatt.close();
        }

        bluetoothGatt = null;
        controlCharacteristic = null;
        baglimiKontrol = false;
        baglaniyormuKontrol = false;
        servisAramaBasladimi = false;
    }

    private void yenidenBaglan() {
        handler.removeCallbacks(yenidenBaglanRunnable);

        if (!bluetoothHazirmiKontrol()) {
            uiBaglantiTextVeButonEnablingGuncelle(false);
            durumTextAyarla("Durum: Bağlı değil\nBluetooth kapalı");
            return;
        }

        handler.postDelayed(yenidenBaglanRunnable, 2000);
    }

    private boolean bluetoothHazirmiKontrol() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private void bluetoothAlicisinaKaydol() {
        if (bluetoothAlicisiKayitlimi) {
            return;
        }

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothDurumAlicisi, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(bluetoothDurumAlicisi, filter);
        }

        bluetoothAlicisiKayitlimi = true;
    }

    private void bluetoothAlicisindanCikisYap() {
        if (!bluetoothAlicisiKayitlimi) {
            return;
        }

        unregisterReceiver(bluetoothDurumAlicisi);
        bluetoothAlicisiKayitlimi = false;
    }

    private void ihtiyacVarsaYetkiIste() {
        if (tumIzinlerSaglandimiKontrol()) {
            esp32Baglan();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    100
            );
        } else {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    100
            );
        }
    }

    private boolean tumIzinlerSaglandimiKontrol() {
        return baglantiIziniVarmiKontrol() && taramaIziniVarmiKontrol();
    }

    private boolean baglantiIziniVarmiKontrol() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    private boolean taramaIziniVarmiKontrol() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }

        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static int renkMenzilSaglama(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    private void durumTextAyarla(String text) {
        handler.post(() -> gasTextView.setText(text));
    }

    private void ekrandaAlttaBildirimGoster(String message) {
        handler.post(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
        );
    }

    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}