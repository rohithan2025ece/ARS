package com.abu.ars;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.telephony.SmsManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.os.Build;
import android.content.Context;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    FusedLocationProviderClient fusedLocationClient;
    TextView locationText, voiceText;
    SpeechRecognizer speechRecognizer;
    Intent speechIntent;
    String spokenText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button sosButton = findViewById(R.id.sosButton);
        locationText = findViewById(R.id.locationText);
        voiceText = findViewById(R.id.voiceText);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECORD_AUDIO
        }, 1);

        // Voice setup
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        speechRecognizer.setRecognitionListener(new SimpleRecognitionListener() {

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> data =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (data != null && !data.isEmpty()) {
                    voiceText.setText("🎤 " + data.get(0));
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> data =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (data != null && !data.isEmpty()) {
                    spokenText = data.get(0);
                    voiceText.setText("🚨 " + spokenText);
                }
            }
        });

        // HOLD to record
        sosButton.setOnTouchListener((v, event) -> {

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                speechRecognizer.startListening(speechIntent);
                voiceText.setText("🎤 Listening...");
            }

            else if (event.getAction() == MotionEvent.ACTION_UP) {
                speechRecognizer.stopListening();
                sendSOS();
            }

            v.performClick();
            return true;
        });

        // 🔥 Trigger from VolumeService
        if (getIntent().getBooleanExtra("triggerSOS", false)) {
            sendSOS();
        }
    }

    private void vibrate() {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(500);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            triggerVolumeSOS();
        }
        return super.onKeyDown(keyCode, event);
    }

    private int volumeCount = 0;
    private long lastVolumeTime = 0;

    private void triggerVolumeSOS() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastVolumeTime < 1000) {
            volumeCount++;
        } else {
            volumeCount = 1;
        }
        lastVolumeTime = currentTime;

        if (volumeCount >= 3) {
            volumeCount = 0;
            sendSOS();
            Toast.makeText(this, "SOS Activated via Volume Buttons", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendSOS() {
        vibrate();
        
        // Check for both Location and SMS permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permissions not granted!", Toast.LENGTH_SHORT).show();
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        double lat = location.getLatitude();
                        double lon = location.getLongitude();

                        String time = new SimpleDateFormat(
                                "dd MMM yyyy, hh:mm a", Locale.getDefault())
                                .format(new Date());

                        // Creating the SOS Message with all details
                        String msg = "🚨 SOS EMERGENCY!\n"
                                + "📍 Latitude: " + lat + "\n"
                                + "📍 Longitude: " + lon + "\n"
                                + "⏰ Time: " + time + "\n"
                                + "🎤 Voice: " + (spokenText.isEmpty() ? "No audio captured" : spokenText);

                        try {
                            SmsManager smsManager;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                smsManager = getSystemService(SmsManager.class);
                            } else {
                                smsManager = SmsManager.getDefault();
                            }

                            if (smsManager == null) {
                                smsManager = SmsManager.getDefault();
                            }
                            
                            if (smsManager != null) {
                                smsManager.sendTextMessage("+919940541394", null, msg, null, null);
                                Toast.makeText(this, "SOS SMS Sent!", Toast.LENGTH_LONG).show();
                                locationText.setText(msg);
                            } else {
                                Toast.makeText(this, "SMS Error: Could not get SMS Manager", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(this, "SMS Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        Toast.makeText(this, "Could not get location. Try turning on GPS.", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}