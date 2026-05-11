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

// Bluetooth sürümündeki ana ekran sınıfıdır
// ESP32 ile BLE üzerinden bağlantı kurar, gaz verisini alır ve LED kontrol komutlarını gönderir
public class MainActivity extends Activity {

    // ESP32 mac adresi
    // ESP32'nin MAC adresini koy
    private static final String TARGET_MAC_ADDRESS = "AA:BB:12:34:56:78";

    // ESP32 tarafında tanımlanan BLE servis UUID değeridir
    private static final UUID SERVICE_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    // Gaz ve state bilgisinin alındığı characteristic UUID değeridir
    private static final UUID GAS_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ac");

    // LED kontrol komutlarının gönderildiği characteristic UUID değeridir
    private static final UUID CONTROL_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ad");

    // standart BLE notification descriptor UUID’si
    // xxxx2902 - Client Characteristic Configuration
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // Kontrol komutları çok sık gitmesin diye kısa gecikme uygulanır
    private static final int CONTROL_DEBOUNCE_MS = 100;

    // BLE paket boyutunu artırmak için istenen MTU değeri
    private static final int REQUESTED_MTU = 256;

    // Kullanıcı kontrolü yeni değiştirdiyse, gelen ESP32 state'i kısa süre UI değerlerini ezmez
    private static final long LOCAL_CONTROL_PROTECT_MS = 700;

    // Gaz değerinin ekrana yazılma sıklığını sınırlar
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

    // Led ve gaz için son bilinen değerler
    private boolean ledOn = false;
    private int red = 255;
    private int green = 0;
    private int blue = 0;
    private int brightness = 255;
    private int lastGasValue = 0;

    // Handler'lar ana thread üzerinde gecikmeli işler çalıştırmak için kullanılır
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler controlHandler = new Handler(Looper.getMainLooper());

    // Bağlantı kopunca tekrar bağlanma denemesi burada yapılır
    private final Runnable yenidenBaglanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!baglimiKontrol && !baglaniyormuKontrol && tumIzinlerSaglandimiKontrol() && bluetoothHazirmiKontrol()) {
                esp32Baglan();
            }
        }
    };

    // Led kontrol komutu debounce sonrası gönderilir
    private final Runnable komutGonderRunnable = new Runnable() {
        @Override
        public void run() {
            komutGonder();
        }
    };

    // Telefonun Bluetooth açılıp kapanma durumu burada dinlenir
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

    // BLE bağlantı, servis keşfi ve notification olayları burada yakalanır
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

        // Android 13 ve üzeri callback yapısı
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic,
                                            byte[] value) {
            if (GAS_UUID.equals(characteristic.getUuid())) {
                String payload = new String(value, StandardCharsets.UTF_8);
                handler.post(() -> gelenPayloadiOkuVeGuncelle(payload));
            }
        }

        // Eski Android sürümleri için callback yapısı
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

    // XML tarafındaki arayüz elemanları Java değişkenlerine bağlanır
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

    // SeekBar ve switch kontrollerinin başlangıç ayarları yapılır
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

        // Hazır renk butonlarının renkleri ve tıklama olayları ayarlanır
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

    // Hazır renk seçildiğinde hem yerel değişkenler hem de arayüz güncellenir
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

    // Kullanıcının en son ne zaman kontrol değiştirdiği tutulur
    private void localSureyiGuncelle() {
        sonLocalKontrolDegisimSuresiMs = System.currentTimeMillis();
    }

    // 700 ms içinde kullanıcı bir kontrolü değiştirdiyse veya değiştirme işlemi devam ediyorsa,
    // gelen ESP32 verisiyle yerel kontrolleri güncelletmemek istiyoruz
    private boolean lokalAyarlariKorumalimiKontrol() {
        long now = System.currentTimeMillis();
        return kullaniciKaydiriyormuKontrol || (now - sonLocalKontrolDegisimSuresiMs < LOCAL_CONTROL_PROTECT_MS);
    }

    // ESP32'den gelen LED değerleri arayüzdeki switch ve seekbarlara yansıtılır
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

    // Gaz, LED, RGB ve parlaklık bilgileri ekrandaki TextView'e yazılır
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

    // Bağlantı durumuna göre kontrol elemanları aktif veya pasif yapılır
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

    // SeekBar hızlı değişirken her değişimi göndermek yerine kısa gecikmeyle son değer gönderilir
    private void kontroleGondermePlanla() {
        controlHandler.removeCallbacks(komutGonderRunnable);
        controlHandler.postDelayed(komutGonderRunnable, CONTROL_DEBOUNCE_MS);
    }

    // ESP32'ye BLE GATT bağlantısı başlatılır
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

    // Bağlantı kurulduktan sonra ESP32 üzerindeki BLE servisleri aranır
    @SuppressLint("MissingPermission")
    private void servisAramaBaslat(BluetoothGatt gatt) {
        if (gatt == null) return;
        if (!baglantiIziniVarmiKontrol()) return;
        if (servisAramaBasladimi) return;

        servisAramaBasladimi = true;
        gatt.discoverServices();
    }

    // Gaz characteristic'i için notification açılır
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

    // Eski Android sürümleri için notification descriptor yazımı
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

    // ESP32'den gelen JSON state payload'u okunur ve ekrana yansıtılır
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

    // Güncel LED kontrol değerleri ESP32'ye JSON olarak yazılır
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

            json.put("ledOn", ledOn);
            json.put("red", renkMenzilSaglama(red));
            json.put("green", renkMenzilSaglama(green));
            json.put("blue", renkMenzilSaglama(blue));
            json.put("brightness", renkMenzilSaglama(brightness));

            byte[] value = json.toString().getBytes(StandardCharsets.UTF_8);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt.writeCharacteristic(
                        controlCharacteristic,
                        value,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                );
            } else {
                writeCharacteristicLegacy(value);
            }

        } catch (Exception e) {
            durumTextAyarla("Komut gönderilemedi:\n" + e.getMessage());
        }
    }

    // Android 13 öncesi characteristic yazma yöntemi
    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private void writeCharacteristicLegacy(byte[] value) {
        if (!baglantiIziniVarmiKontrol()) {
            return;
        }

        controlCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        controlCharacteristic.setValue(value);
        bluetoothGatt.writeCharacteristic(controlCharacteristic);
    }

        // Gerekli Bluetooth izinleri yoksa kullanıcıdan izin ister
    private void ihtiyacVarsaYetkiIste() {
        if (tumIzinlerSaglandimiKontrol()) {
            esp32Baglan();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
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

    // Android sürümüne göre gerekli izinlerin verilip verilmediği kontrol edilir
    private boolean tumIzinlerSaglandimiKontrol() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // BLE bağlantısı için gerekli connect iznini kontrol eder
    private boolean baglantiIziniVarmiKontrol() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }

        return true;
    }

    // Bluetooth adaptörünün kullanılabilir ve açık olup olmadığını kontrol eder
    private boolean bluetoothHazirmiKontrol() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    // Bluetooth açma kapama durumlarını dinleyen receiver kayıt edilir
    private void bluetoothAlicisinaKaydol() {
        if (bluetoothAlicisiKayitlimi) {
            return;
        }

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    bluetoothDurumAlicisi,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(bluetoothDurumAlicisi, filter);
        }

        bluetoothAlicisiKayitlimi = true;
    }

    // Activity kapanırken Bluetooth receiver kaydı kaldırılır
    private void bluetoothAlicisindanCikisYap() {
        if (!bluetoothAlicisiKayitlimi) {
            return;
        }

        try {
            unregisterReceiver(bluetoothDurumAlicisi);
        } catch (Exception ignored) {
        }

        bluetoothAlicisiKayitlimi = false;
    }

    // Bağlantı koptuğunda veya hata olduğunda tekrar bağlanma işlemini gecikmeli başlatır
    private void yenidenBaglan() {
        handler.removeCallbacks(yenidenBaglanRunnable);
        handler.postDelayed(yenidenBaglanRunnable, 1500);
    }

    // Açık GATT bağlantısı varsa kapatır ve referansları temizler
    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (!baglantiIziniVarmiKontrol()) {
            return;
        }

        try {
            if (bluetoothGatt != null) {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            }
        } catch (Exception ignored) {
        }

        bluetoothGatt = null;
        controlCharacteristic = null;
    }

    // Durum yazıları ana thread üzerinden güncellenir
    private void durumTextAyarla(String text) {
        handler.post(() -> {
            if (gasTextView != null) {
                gasTextView.setText(text);
            }
        });
    }

    // Kısa bilgilendirme mesajı gösterir
    private void ekrandaAlttaBildirimGoster(String message) {
        handler.post(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
        );
    }

    // RGB, parlaklık ve renk önizleme alanı güncellenir
    private void uiRenkBirimleriniGuncelle() {
        redValueTextView.setText("Kırmızı: " + red);
        greenValueTextView.setText("Yeşil: " + green);
        blueValueTextView.setText("Mavi: " + blue);
        brightnessValueTextView.setText("Parlaklık: " + brightness);

        int previewRed = ledOn ? red : 0;
        int previewGreen = ledOn ? green : 0;
        int previewBlue = ledOn ? blue : 0;

        colorPreviewView.setBackgroundColor(Color.rgb(
                renkMenzilSaglama(previewRed),
                renkMenzilSaglama(previewGreen),
                renkMenzilSaglama(previewBlue)
        ));
    }

    // RGB ve parlaklık değerlerini 0-255 aralığında tutar
    private int renkMenzilSaglama(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    // SeekBar listener yazarken kullanılmayan metotları boş geçmek için yardımcı sınıf
    public abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}