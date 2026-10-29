package com.abu.ars;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.content.Intent;

public class VolumeService extends AccessibilityService {

    int count = 0;
    long lastTime = 0;

    @Override
    public boolean onKeyEvent(KeyEvent event) {

        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ||
                        event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)) {

            long current = System.currentTimeMillis();

            if (current - lastTime < 1000) count++;
            else count = 1;

            lastTime = current;

            if (count == 3) {
                count = 0;

                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.putExtra("triggerSOS", true);
                startActivity(i);
            }
        }

        return super.onKeyEvent(event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}