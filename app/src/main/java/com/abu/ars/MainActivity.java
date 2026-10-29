package com.abu.ars;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.WindowManager;
import android.media.MediaPlayer;
import android.os.BatteryManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import android.os.Looper;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // UI Elements
    private TextView voiceText, tvLat, tvLon, tvBattery, tvTimestamp, tvStatus;
    private Button sosButton;
    private android.widget.ImageView pulseRing;

    // Animations
    private android.view.animation.Animation pulseScaleAnim, scanlineAnim;

    // Location & Device Info
    private FusedLocationProviderClient fusedLocationClient;
    private com.google.android.gms.location.LocationCallback locationCallback;
    private int batteryLevel;
    private String currentTimestamp;
    private double lastLat = 0.0, lastLon = 0.0;

    // Periodic Updates
    private final android.os.Handler uiHandler = new android.os.Handler();
    private final Runnable uiUpdater = new Runnable() {
        @Override
        public void run() {
            updateDeviceInfo();
            uiHandler.postDelayed(this, 1000); // Update time/battery every second
        }
    };

    // Vosk Voice Recognition
    private Model voskModel;
    private SpeechService speechService;
    private String spokenText = "";

    // Sound
    private MediaPlayer startSound, stopSound;

    // Constants & Networking
    private static final String TAG = "SOS_APP";
    private static final String BACKEND_URL = "https://defile-blip-snowbird.ngrok-free.dev/sos";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Volume Trigger Logic
    private int volumeClickCount = 0;
    private long lastVolumeClickTime = 0;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        setContentView(R.layout.activity_main);

        // 1. Initialize UI (Ensure these IDs exist in your activity_main.xml)
        voiceText = findViewById(R.id.voiceText);
        tvLat = findViewById(R.id.tvLat);
        tvLon = findViewById(R.id.tvLon);
        tvBattery = findViewById(R.id.tvBattery);
        tvTimestamp = findViewById(R.id.tvTimestamp);
        tvStatus = findViewById(R.id.tvStatus);
        sosButton = findViewById(R.id.sosButton);
        pulseRing = findViewById(R.id.pulseRing);

        // Load Animations
        pulseScaleAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.pulse_scale);
        scanlineAnim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.scanline_anim);
        
        // Start animations
        pulseRing.startAnimation(pulseScaleAnim);
        findViewById(R.id.scanline).startAnimation(scanlineAnim);

        // 2. Initialize Location
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        setupLocationCallback();

        // 3. Request Permissions
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
        }, 1);

        // 4. Initialize Sounds
        try {
            startSound = MediaPlayer.create(this, R.raw.start_sound);
            stopSound = MediaPlayer.create(this, R.raw.stop_sound);
        } catch (Exception e) {
            Log.e(TAG, "Error loading sounds", e);
        }

        // 5. Initialize Vosk Model
        initVosk();

        // 6. SOS Button Touch Logic (Hold for manual alert)
        sosButton.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (voskModel != null) {
                    if (startSound != null) startSound.start();
                    spokenText = ""; 
                    voiceText.setText(R.string.manual_recording);
                    tvStatus.setText(R.string.status_manual_alert);
                } else {
                    Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show();
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (stopSound != null) stopSound.start();
                triggerSOS(getString(R.string.trigger_manual));
            }
            return true;
        });

        // Initial device info update
        updateDeviceInfo();

        // Check if triggered from VolumeService
        if (getIntent() != null && getIntent().getBooleanExtra("triggerSOS", false)) {
            triggerSOS("Accessibility Volume Trigger");
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Update activity intent
        if (intent.getBooleanExtra("triggerSOS", false)) {
            triggerSOS(getString(R.string.trigger_volume_accessibility));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && event.getRepeatCount() == 0) {
            long currentTime = System.currentTimeMillis();
            // Increased window to 3 seconds for higher sensitivity
            if (currentTime - lastVolumeClickTime < 3000) {
                volumeClickCount++;
            } else {
                volumeClickCount = 1;
            }
            lastVolumeClickTime = currentTime;

            if (volumeClickCount >= 3) {
                triggerSOS(getString(R.string.trigger_volume_triple));
                volumeClickCount = 0; // Reset
            }
            return true; // Consume event to prevent default volume change while app is open
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // 1. Force an immediate fresh location fix (more reliable than getLastLocation)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        updateLocationUI(location.getLatitude(), location.getLongitude());
                        tvStatus.setText(R.string.status_gps_synced);
                    }
                });

        // 2. High-Accuracy Continuous Updates
        LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(3000)
                .build();

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission error", e);
        }
    }

    private void updateLocationUI(double lat, double lon) {
        lastLat = lat;
        lastLon = lon;
        runOnUiThread(() -> {
            tvLat.setText(getString(R.string.lat_format, lat));
            tvLon.setText(getString(R.string.lon_format, lon));
        });
    }

    private void setupLocationCallback() {
        locationCallback = new com.google.android.gms.location.LocationCallback() {
            @Override
            public void onLocationResult(com.google.android.gms.location.LocationResult locationResult) {
                if (locationResult == null) return;
                for (android.location.Location location : locationResult.getLocations()) {
                    if (location != null) {
                        updateLocationUI(location.getLatitude(), location.getLongitude());
                    }
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiUpdater);
        
        // Reset UI to indicate searching if we don't have a location yet
        if (lastLat == 0.0) {
            tvLat.setText(R.string.lat_acquiring);
            tvLon.setText(R.string.lon_acquiring);
        }

        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiUpdater);
        fusedLocationClient.removeLocationUpdates(locationCallback);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates();
        }
    }

    // ================= 📍 SOS CORE LOGIC =================

    private void triggerSOS(String source) {
        stopSpeechService(); // Stop listening during processing
        runOnUiThread(() -> {
            tvStatus.setText(R.string.status_sos_triggered);
            voiceText.setText(getString(R.string.source_format, source.toUpperCase()));
            Toast.makeText(this, getString(R.string.status_sos_triggered) + " " + source, Toast.LENGTH_LONG).show();
        });
        updateDeviceInfo(); // Refresh battery and timestamp

        // Use the most recent location from the callback if possible
        double lat = lastLat;
        double lon = lastLon;
        
        String finalMessage = spokenText.isEmpty() ? getString(R.string.emergency_message_format, source) : spokenText;
        sendToServer(finalMessage, lat, lon);

        // Restart background listening after a short delay
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::startListening, 3000);
    }

    private void updateDeviceInfo() {
        // Battery Percentage
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        if (bm != null) {
            batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            runOnUiThread(() -> tvBattery.setText(getString(R.string.battery_format, batteryLevel)));
        }

        // Current Timestamp
        currentTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        runOnUiThread(() -> tvTimestamp.setText(getString(R.string.timestamp_format, currentTimestamp)));
    }

    // ================= 🎤 VOSK VOICE TRIGGER =================

    private void initVosk() {
        runOnUiThread(() -> tvStatus.setText(R.string.status_loading_model));
        new Thread(() -> {
            try {
                File modelDir = new File(getExternalFilesDir(null), "model");
                if (!modelDir.exists()) copyAssets("model", modelDir);
                voskModel = new Model(modelDir.getAbsolutePath());
                runOnUiThread(() -> {
                    tvStatus.setText(R.string.status_active);
                    voiceText.setText(R.string.listening_keywords);
                    startListening(); // Auto-start background detection
                });
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText(R.string.status_error_model));
                Log.e(TAG, "Vosk Model Init Error", e);
            }
        }).start();
    }

    private void startListening() {
        if (speechService != null) return; // Already listening
        try {
            Recognizer recognizer = new Recognizer(voskModel, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(new RecognitionListener() {
                @Override
                public void onPartialResult(String hypothesis) {
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        String partial = json.optString("partial", "").toLowerCase();
                        
                        if (!partial.isEmpty()) {
                            runOnUiThread(() -> voiceText.setText(getString(R.string.captured_format, partial.toUpperCase())));
                        }

                        // Expanded keywords for maximum sensitivity
                        String[] keywords = {"help", "sos", "emergency", "danger", "police", "save", "attack", "stop", "fire"};
                        for (String kw : keywords) {
                            if (partial.contains(kw)) {
                                runOnUiThread(() -> triggerSOS(getString(R.string.trigger_voice_format, kw)));
                                break;
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Partial Result Error", e);
                    }
                }

                @Override
                public void onResult(String hypothesis) {
                    try {
                        JSONObject json = new JSONObject(hypothesis);
                        spokenText = json.optString("text", "");
                        runOnUiThread(() -> voiceText.setText(spokenText));
                    } catch (Exception ignored) {}
                }

                @Override public void onFinalResult(String hypothesis) {}
                @Override public void onError(Exception e) { Log.e(TAG, "Vosk Error", e); }
                @Override public void onTimeout() {}
            });
        } catch (IOException e) {
            Log.e(TAG, "Listening Start Error", e);
        }
    }

    private void stopSpeechService() {
        if (speechService != null) {
            speechService.stop();
            speechService = null;
        }
    }

    // ================= 🌐 BACKEND (POST JSON) =================

    private void sendToServer(String message, double lat, double lon) {
        executor.execute(() -> {
            try {
                URL url = new URL(BACKEND_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                // Prepare JSON payload
                String json = String.format(Locale.US,
                        "{\"latitude\": %.6f, \"longitude\": %.6f, \"message\": \"%s\", \"battery\": %d, \"timestamp\": \"%s\"}",
                        lat, lon, message, batteryLevel, currentTimestamp);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes());
                }

                int code = conn.getResponseCode();
                runOnUiThread(() -> {
                    tvStatus.setText(getString(R.string.status_alert_transmitted, code));
                    Toast.makeText(MainActivity.this, R.string.sos_sent_success, Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText(R.string.status_network_error);
                    Log.e(TAG, "Server Error", e);
                });
            }
        });
    }

    // ================= 🛠 UTILITIES =================

    private void copyAssets(String assetPath, File outFile) throws IOException {
        String[] files = getAssets().list(assetPath);
        if (files == null || files.length == 0) {
            try (InputStream in = getAssets().open(assetPath);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            }
            return;
        }
        if (!outFile.exists()) outFile.mkdirs();
        for (String file : files) copyAssets(assetPath + "/" + file, new File(outFile, file));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSpeechService();
        if (voskModel != null) voskModel.close();
        if (startSound != null) startSound.release();
        if (stopSound != null) stopSound.release();
        executor.shutdown();
    }
}
