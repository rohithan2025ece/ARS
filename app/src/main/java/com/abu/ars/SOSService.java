package com.abu.ars;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.telephony.SmsManager;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SOSService extends Service {
    private static final String CHANNEL_ID = "SOS_SERVICE_CHANNEL";
    private static final String ALERT_CHANNEL_ID = "SOS_ALERT_CHANNEL";
    private static final String TAG = "SOS_SERVICE";
    private static final String BACKEND_URL = "https://crisping-senate-flier.ngrok-free.dev/sos";
    private static final String PRIMARY_CONTACT = "9940541394";
    private static final String PREFS_NAME = "EmergencyContacts";
    private static final String CONTACTS_KEY = "phone_numbers";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private FusedLocationProviderClient fusedLocationClient;

    @Override
    public void onCreate() {
        super.onCreate();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification();
        startForeground(1, notification);

        if (intent != null && intent.getBooleanExtra("trigger_sos", false)) {
            String msg = intent.getStringExtra("sos_message");
            double lat = intent.getDoubleExtra("lat", 0.0);
            double lon = intent.getDoubleExtra("lon", 0.0);
            
            if (lat != 0.0 && lon != 0.0) {
                sendData(msg != null ? msg : "Manual Trigger", lat, lon);
            } else {
                triggerSOS(this, msg != null ? msg : "No voice message");
            }
        }

        return START_STICKY;
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ARS Protection Active")
                .setContentText("Monitoring for emergency triggers...")
                .setSmallIcon(R.mipmap.apple)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    public void triggerSOS(Context context, String message) {
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permission missing for SOS");
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(location -> {
                    double lat = (location != null) ? location.getLatitude() : 0.0;
                    double lon = (location != null) ? location.getLongitude() : 0.0;
                    sendData(message, lat, lon);
                });
    }

    private void sendData(String message, double lat, double lon) {
        showTriggerNotification(message);
        
        if (!isNetworkAvailable()) {
            Log.d(TAG, "No internet. Triggering SMS fallback.");
            sendSMSFallback(message, lat, lon);
            return;
        }

        executor.execute(() -> {
            boolean success = false;
            try {
                // Battery
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int battery = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;

                // ISO Timestamp
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                String timestamp = sdf.format(new Date());

                // JSON Construction
                JSONObject json = new JSONObject();
                json.put("latitude", lat);
                json.put("longitude", lon);
                json.put("message", message.replaceAll("[^\\p{ASCII}]", ""));
                json.put("battery", battery);
                json.put("timestamp", timestamp);

                URL url = new URL(BACKEND_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("ngrok-skip-browser-warning", "any");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.toString().getBytes());
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "SOS Sent. Server response: " + code);
                if (code == 200) success = true;

            } catch (Exception e) {
                Log.e(TAG, "Failed to send SOS to server", e);
            }

            if (!success) {
                Log.d(TAG, "Server delivery failed. Sending SMS fallback.");
                sendSMSFallback(message, lat, lon);
            }
        });
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || 
                                          capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        return false;
    }

    private void sendSMSFallback(String message, double lat, double lon) {
        try {
            // 1. Prepare Data (Match Backend JSON Format)
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int battery = (bm != null) ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(new Date());

            JSONObject json = new JSONObject();
            json.put("latitude", lat);
            json.put("longitude", lon);
            json.put("message", message.replaceAll("[^\\p{ASCII}]", ""));
            json.put("battery", battery);
            json.put("timestamp", timestamp);

            // Pretty print with 2-space indentation to match user request exactly
            String smsBody = json.toString(2);

            // 2. Verify Permission
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "SMS FAIL: Permission NOT granted");
                return;
            }

            // 3. Get SmsManager
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager == null) {
                Log.e(TAG, "SMS FAIL: SmsManager is null");
                return;
            }

            // 4. Collect numbers (Test + Primary + Saved Contacts)
            Set<String> recipients = new HashSet<>();
            recipients.add("8778064671"); // Test Number
            recipients.add(PRIMARY_CONTACT); // "9940541394"
            
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            Set<String> savedContacts = prefs.getStringSet(CONTACTS_KEY, new HashSet<>());
            recipients.addAll(savedContacts);

            // 5. Send to all
            for (String number : recipients) {
                if (number != null && !number.trim().isEmpty()) {
                    ArrayList<String> parts = smsManager.divideMessage(smsBody);
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null);
                    Log.d(TAG, "SMS SUCCESS: Sent JSON to " + number);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "SMS FAIL: " + e.getMessage());
        }
    }

    private void showTriggerNotification(String source) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification alertNotification = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("🚨 SOS ALERT TRIGGERED 🚨")
                .setContentText("Emergency Signal Sent: " + source)
                .setSmallIcon(R.mipmap.apple)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setVibrate(new long[]{1000, 500, 1000, 500})
                .setFullScreenIntent(pendingIntent, true)
                .build();

        manager.notify(2, alertNotification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            // Background Service Channel
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID, "ARS SOS Service Channel",
                    NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(serviceChannel);

            // SOS Trigger Alert Channel (High Importance for Heads-up)
            NotificationChannel alertChannel = new NotificationChannel(
                    ALERT_CHANNEL_ID, "SOS Trigger Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("High-priority alerts when SOS is triggered");
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{1000, 500, 1000, 500});
            manager.createNotificationChannel(alertChannel);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
