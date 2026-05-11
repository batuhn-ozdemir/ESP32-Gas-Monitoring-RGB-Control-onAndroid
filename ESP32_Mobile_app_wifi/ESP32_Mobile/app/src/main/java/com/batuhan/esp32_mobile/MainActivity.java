package com.batuhan.esp32_mobile;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.batuhan.esp32_mobile.config.AppConfig;
import com.batuhan.esp32_mobile.model.ControlCommand;
import com.batuhan.esp32_mobile.model.DeviceState;
import com.batuhan.esp32_mobile.model.HistoryItem;
import com.batuhan.esp32_mobile.network.DeviceApiClient;
import com.batuhan.esp32_mobile.network.DeviceWebSocketClient;
import com.batuhan.esp32_mobile.util.HistoryFormatter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Wi-Fi sürümündeki ana ekran sınıfıdır
// Gaz değerini, LED durumunu, RGB kontrolünü ve geçmiş kayıtları yönetir
public class MainActivity extends Activity {

    private TextView connectionTextView;
    private TextView gasTextView;
    private TextView ledTextView;
    private TextView rgbTextView;
    private TextView updateTextView;
    private TextView historyTextView;

    private TextView redValueTextView;
    private TextView greenValueTextView;
    private TextView blueValueTextView;
    private TextView brightnessValueTextView;

    private SeekBar redSeekBar;
    private SeekBar greenSeekBar;
    private SeekBar blueSeekBar;
    private SeekBar brightnessSeekBar;

    private Switch ledSwitch;
    private View colorPreviewView;

    private Button redButton;
    private Button greenButton;
    private Button blueButton;
    private Button whiteButton;
    private Button yellowButton;
    private Button purpleButton;
    private Button offButton;

    // Kullanıcının ekranda ayarladığı son led değerleri burada tutulur
    private boolean currentLedOn = false;
    private int currentRed = 255;
    private int currentGreen = 0;
    private int currentBlue = 0;
    private int currentBrightness = 255;

    // UI güncellemesi sırasında listener'ların gereksiz tetiklenmesini önlemek için kullanılır
    private boolean ignoreControlEvents = false;
    private long lastLocalControlChangeMs = 0;

    private DeviceApiClient apiClient;
    private DeviceWebSocketClient webSocketClient;

    // Geçmiş veriler ayrı bir thread'de alınır, UI işlemleri ana threadde yapılır
    private final ExecutorService historyExecutor = Executors.newSingleThreadExecutor();
    private final Handler historyRefreshHandler = new Handler(Looper.getMainLooper());
    private final Handler controlHandler = new Handler(Looper.getMainLooper());

    // Kontrol komutunu kısa bir gecikmeyle göndermek için kullanılır
    private final Runnable sendControlRunnable = new Runnable() {
        @Override
        public void run() {
            sendControl();
        }
    };

    // Geçmiş kayıtlar belirli aralıklarla otomatik yenilenir
    private final Runnable historyRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            loadHistory();
            historyRefreshHandler.postDelayed(this, AppConfig.HISTORY_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apiClient = new DeviceApiClient();

        // WebSocket olayları burada dinlenir ve ekrana yansıtılır
        webSocketClient = new DeviceWebSocketClient(new DeviceWebSocketClient.Listener() {
            @Override
            public void onConnected() {
                showConnected();
                //Toast.makeText(MainActivity.this, "WebSocket bağlandı", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onDisconnected() {
                showDisconnected();
            }

            @Override
            public void onStateReceived(DeviceState state) {
                updateLatestUi(state, true);
            }

            @Override
            public void onError(String message) {
                //Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });

        bindViews();
        setupSeekBars();
        setupButtons();
        setupInitialUi();

        webSocketClient.connect();
        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Uygulama tekrar açıldığında WebSocket kopmuşsa yeniden bağlanılır
        if (!webSocketClient.isConnected()) {
            webSocketClient.connect();
        }

        historyRefreshHandler.post(historyRefreshRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Arka plana geçince bekleyen yenileme ve kontrol gönderimleri durdurulur
        historyRefreshHandler.removeCallbacks(historyRefreshRunnable);
        controlHandler.removeCallbacks(sendControlRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Activity kapanırken WebSocket ve thread kaynakları temizlenir
        webSocketClient.close();
        historyExecutor.shutdown();
    }

    // XML'deki arayüz elemanları Java tarafındaki değişkenlere bağlanır
    private void bindViews() {
        connectionTextView = findViewById(R.id.connectionTextView);
        gasTextView = findViewById(R.id.gasTextView);
        ledTextView = findViewById(R.id.ledTextView);
        rgbTextView = findViewById(R.id.rgbTextView);
        updateTextView = findViewById(R.id.updateTextView);
        historyTextView = findViewById(R.id.historyTextView);

        redValueTextView = findViewById(R.id.redValueTextView);
        greenValueTextView = findViewById(R.id.greenValueTextView);
        blueValueTextView = findViewById(R.id.blueValueTextView);
        brightnessValueTextView = findViewById(R.id.brightnessValueTextView);

        redSeekBar = findViewById(R.id.redSeekBar);
        greenSeekBar = findViewById(R.id.greenSeekBar);
        blueSeekBar = findViewById(R.id.blueSeekBar);
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar);

        ledSwitch = findViewById(R.id.ledSwitch);
        colorPreviewView = findViewById(R.id.colorPreviewView);

        redButton = findViewById(R.id.redButton);
        greenButton = findViewById(R.id.greenButton);
        blueButton = findViewById(R.id.blueButton);
        whiteButton = findViewById(R.id.whiteButton);
        yellowButton = findViewById(R.id.yellowButton);
        purpleButton = findViewById(R.id.purpleButton);
        offButton = findViewById(R.id.offButton);
    }

    // Uygulama ilk açıldığında ekranda gösterilecek varsayılan değerler hazırlanır
    private void setupInitialUi() {
        showDisconnected();

        gasTextView.setText("Gaz Değeri: -");
        ledTextView.setText("LED Durumu: -");
        rgbTextView.setText("RGB: - | Parlaklık: -");
        updateTextView.setText("Son Güncelleme: -");

        updateLocalControlUi();
    }

    // RGB ve parlaklık SeekBar kontrolleri ayarlanır
    private void setupSeekBars() {
        redSeekBar.setMax(255);
        greenSeekBar.setMax(255);
        blueSeekBar.setMax(255);
        brightnessSeekBar.setMax(255);

        redSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (ignoreControlEvents || !fromUser) return;
                currentRed = progress;
                markLocalControlChanged();
            }
        });

        greenSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (ignoreControlEvents || !fromUser) return;
                currentGreen = progress;
                markLocalControlChanged();
            }
        });

        blueSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (ignoreControlEvents || !fromUser) return;
                currentBlue = progress;
                markLocalControlChanged();
            }
        });

        brightnessSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (ignoreControlEvents || !fromUser) return;
                currentBrightness = progress;
                markLocalControlChanged();
            }
        });

        ledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (ignoreControlEvents) return;
                currentLedOn = isChecked;
                markLocalControlChanged();
            }
        });
    }

    // Hazır renk butonlarının görsel renkleri ve tıklama olayları ayarlanır
    private void setupButtons() {
        redButton.setBackgroundColor(Color.rgb(220, 40, 40));
        greenButton.setBackgroundColor(Color.rgb(40, 160, 70));
        blueButton.setBackgroundColor(Color.rgb(40, 90, 220));
        whiteButton.setBackgroundColor(Color.rgb(210, 210, 210));
        yellowButton.setBackgroundColor(Color.rgb(230, 190, 40));
        purpleButton.setBackgroundColor(Color.rgb(150, 60, 180));
        offButton.setBackgroundColor(Color.rgb(40, 40, 40));

        redButton.setTextColor(Color.WHITE);
        greenButton.setTextColor(Color.WHITE);
        blueButton.setTextColor(Color.WHITE);
        whiteButton.setTextColor(Color.BLACK);
        yellowButton.setTextColor(Color.BLACK);
        purpleButton.setTextColor(Color.WHITE);
        offButton.setTextColor(Color.WHITE);

        redButton.setOnClickListener(v -> applyPreset(true, 255, 0, 0, currentBrightness));
        greenButton.setOnClickListener(v -> applyPreset(true, 0, 255, 0, currentBrightness));
        blueButton.setOnClickListener(v -> applyPreset(true, 0, 0, 255, currentBrightness));
        whiteButton.setOnClickListener(v -> applyPreset(true, 255, 255, 255, currentBrightness));
        yellowButton.setOnClickListener(v -> applyPreset(true, 255, 255, 0, currentBrightness));
        purpleButton.setOnClickListener(v -> applyPreset(true, 255, 0, 255, currentBrightness));
        offButton.setOnClickListener(v -> applyPreset(false, currentRed, currentGreen, currentBlue, currentBrightness));
    }

    // Hazır renklerden biri seçildiğinde yerel kontrol değerleri güncellenir
    private void applyPreset(boolean ledOn, int red, int green, int blue, int brightness) {
        currentLedOn = ledOn;
        currentRed = clamp(red);
        currentGreen = clamp(green);
        currentBlue = clamp(blue);
        currentBrightness = clamp(brightness);

        markLocalControlChanged();
    }

    // Kullanıcının kontrol değiştirdiği zaman kaydedilir ve komut gönderimi planlanır
    private void markLocalControlChanged() {
        lastLocalControlChangeMs = System.currentTimeMillis();

        updateLocalControlUi();
        scheduleControlSend();
    }

    // Çok hızlı değişikliklerde her adımı göndermek yerine kısa gecikmeyle son değer gönderilir
    private void scheduleControlSend() {
        controlHandler.removeCallbacks(sendControlRunnable);
        controlHandler.postDelayed(sendControlRunnable, AppConfig.CONTROL_DEBOUNCE_MS);
    }

    // Güncel LED komutu WebSocket üzerinden backend'e gönderilir
    private void sendControl() {
        ControlCommand command = new ControlCommand(
                currentLedOn,
                currentRed,
                currentGreen,
                currentBlue,
                currentBrightness
        );

        boolean ok = webSocketClient.sendControl(command);

        if (!ok) {
            Toast.makeText(this, "WebSocket bağlı değil veya kontrol gönderilemedi", Toast.LENGTH_SHORT).show();
        }
    }

    // Geçmiş kayıtlar arka planda backend REST API'den alınır
    private void loadHistory() {
        historyExecutor.execute(() -> {
            try {
                List<HistoryItem> historyItems = apiClient.getHistory(20);

                runOnUiThread(() -> updateHistoryUi(historyItems));

            } catch (Exception e) {
//                runOnUiThread(() ->
//                        Toast.makeText(this, "Geçmiş alınamadı", Toast.LENGTH_SHORT).show()
//                );
            }
        });
    }

    // WebSocket'ten gelen son cihaz durumu ekrana yansıtılır
    private void updateLatestUi(DeviceState state, boolean updateControlsFromServer) {
        if (!state.isDeviceConnected()) {
            showDisconnected();

            gasTextView.setText("Gaz Değeri: -");
            ledTextView.setText("LED Durumu: -");
            rgbTextView.setText("RGB: - | Parlaklık: -");
            updateTextView.setText("Son Güncelleme: -");

            colorPreviewView.setBackgroundColor(Color.rgb(0, 0, 0));
            setControlViewsEnabled(false);
            return;
        }

        showConnected();
        setControlViewsEnabled(true);

        gasTextView.setText("Gaz Değeri: " + state.getGasValue());
        ledTextView.setText("LED Durumu: " + (state.isLedOn() ? "Açık" : "Kapalı"));

        rgbTextView.setText(
                "RGB: " + state.getRed() + ", " + state.getGreen() + ", " + state.getBlue()
                        + " | Parlaklık: " + state.getBrightness()
        );

        updateTextView.setText("Son Güncelleme: " + state.getUpdatedAt());

        // Kullanıcı yeni ayar yaptıysa, kısa süre boyunca backend'den gelen değerler kontrolleri ezmesin
        boolean recentlyChangedLocally =
                System.currentTimeMillis() - lastLocalControlChangeMs < 700;

        if (updateControlsFromServer && !recentlyChangedLocally) {
            currentLedOn = state.isLedOn();
            currentRed = clamp(state.getRed());
            currentGreen = clamp(state.getGreen());
            currentBlue = clamp(state.getBlue());
            currentBrightness = clamp(state.getBrightness());

            updateLocalControlUi();
        }
    }

    // Geçmiş kayıtlar okunabilir tablo görünümünde TextView'e yazılır.
    private void updateHistoryUi(List<HistoryItem> historyItems) {
        historyTextView.setTypeface(Typeface.MONOSPACE);
        historyTextView.setText(HistoryFormatter.format(historyItems));
    }

    // Yerel LED değerleri switch, seekbar ve renk önizleme alanına uygulanır
    private void updateLocalControlUi() {
        ignoreControlEvents = true;

        ledSwitch.setChecked(currentLedOn);
        redSeekBar.setProgress(currentRed);
        greenSeekBar.setProgress(currentGreen);
        blueSeekBar.setProgress(currentBlue);
        brightnessSeekBar.setProgress(currentBrightness);

        ignoreControlEvents = false;

        redValueTextView.setText("Kırmızı: " + currentRed);
        greenValueTextView.setText("Yeşil: " + currentGreen);
        blueValueTextView.setText("Mavi: " + currentBlue);
        brightnessValueTextView.setText("Parlaklık: " + currentBrightness);

        int previewRed = currentLedOn ? currentRed : 0;
        int previewGreen = currentLedOn ? currentGreen : 0;
        int previewBlue = currentLedOn ? currentBlue : 0;

        colorPreviewView.setBackgroundColor(Color.rgb(
                clamp(previewRed),
                clamp(previewGreen),
                clamp(previewBlue)
        ));
    }

    // Bağlantı durumu ekranda yeşil renkle gösterilir
    private void showConnected() {
        connectionTextView.setText("Cihaz Bağlantısı: Bağlı");
        connectionTextView.setTextColor(Color.rgb(30, 140, 60));
    }

    // Bağlantı yoksa durum kırmızı renkle gösterilir
    private void showDisconnected() {
        connectionTextView.setText("Cihaz Bağlantısı: Bağlı Değil");
        connectionTextView.setTextColor(Color.rgb(180, 30, 30));
    }

    // Cihaz bağlı değilken LED kontrol elemanları pasif hale getirilir
    private void setControlViewsEnabled(boolean enabled) {
        ledSwitch.setEnabled(enabled);

        redSeekBar.setEnabled(enabled);
        greenSeekBar.setEnabled(enabled);
        blueSeekBar.setEnabled(enabled);
        brightnessSeekBar.setEnabled(enabled);

        redButton.setEnabled(enabled);
        greenButton.setEnabled(enabled);
        blueButton.setEnabled(enabled);
        whiteButton.setEnabled(enabled);
        yellowButton.setEnabled(enabled);
        purpleButton.setEnabled(enabled);
        offButton.setEnabled(enabled);
    }

    // 0 - 255 arasında olduğunu sağlama alırız
    private static int clamp(int value) {
        if (value < 0) return 0;
        if (value > 255) return 255;
        return value;
    }

    // Kullanılmayan SeekBar metotlarını her seferinde yazmamak için basit yardımcı sınıf
    public abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}