package io.github.maxhayim.brightness;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * The optional "system screens" mode. An ordinary layer over other apps is hidden while the TV's Settings are open
 * (they ask Android to hide such windows) and is drawn under the volume bar. An accessibility overlay is neither,
 * so while the owner has this service on, {@link DimService} hands it the layer to draw. It draws and nothing else:
 * no events, no window content, no keys.
 */
public class SystemScreensService extends AccessibilityService {
    /** The connected service, or null; main thread only. */
    private static SystemScreensService connected;

    private WindowManager wm;
    private View layer;

    /** True while the owner has the service on and Android has bound it. */
    static boolean on() {
        return connected != null;
    }

    /** Draws the dimming layer at this colour; false when the service isn't on. Main thread. */
    static boolean show(int color) {
        return connected != null && connected.draw(color);
    }

    /** Takes the layer away, if it is up. Main thread. */
    static void hide() {
        if (connected != null) connected.remove();
    }

    @Override
    protected void onServiceConnected() {
        wm = getSystemService(WindowManager.class);
        connected = this;
        // Take over from the ordinary layer if one is up, or start dimming if the settings call for it.
        DimService.start(this);
        DimService.redraw();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        leave();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        leave();
        super.onDestroy();
    }

    private void leave() {
        if (connected != this) return;
        remove();
        connected = null;
        // Back to the ordinary layer, so switching this off never leaves the screen undimmed.
        DimService.redraw();
    }

    private boolean draw(int color) {
        if (layer != null) {
            layer.setBackgroundColor(color);
            return true;
        }
        View v = new View(this);
        v.setBackgroundColor(color);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never focus or touches: the remote keeps working what is on screen. An accessibility overlay is
                // trusted, so unlike the ordinary layer it may be darker than 80 % and still let touches through.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        p.setTitle("Brightness (system screens)");
        try {
            wm.addView(v, p);
            layer = v;
            return true;
        } catch (RuntimeException e) {
            Log.w(Prefs.TAG, "system screens layer not shown: " + e.getMessage());
            return false;
        }
    }

    private void remove() {
        if (layer == null) return;
        try {
            wm.removeView(layer);
        } catch (RuntimeException ignored) {
            // Already gone with the window manager.
        }
        layer = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Subscribed to none.
    }

    @Override
    public void onInterrupt() {
    }
}
