package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.Collections;
import java.util.Map;

/**
 * Maps the remote's Input button to the system input switcher, from any app. Remotes such as the Chromecast voice
 * remote send KEYCODE_TV for it, others (KTC and Kalley Realtek Google TV remotes) KEYCODE_TV_INPUT; on Google TV
 * the stock launcher handles both, so with the stock launcher disabled the button does nothing. Buttons listed in
 * input_key_keycodes (config.xml or a theme) open the input switcher the same way, and app_keys start an app (a
 * YouTube button that only the stock launcher answered). Every other key passes through untouched.
 * Opt-in: INPUT_KEY_SERVICE=1 build-and-install.sh (or -InputKeyService on Windows) enables it.
 */
public class InputKeyService extends AccessibilityService {

    private static final String ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private int[] inputKeycodes = new int[0];
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();
    private Map<Integer, String> holdAppKeys = Collections.emptyMap();
    private Map<Integer, String> holdAppScancodes = Collections.emptyMap();
    /** app_keys "front:" entries, chosen the way HomeKeyService chooses them (RemoteKeys.tap). */
    private Map<Integer, String> frontAppKeys = Collections.emptyMap();
    private Map<Integer, String> frontAppScancodes = Collections.emptyMap();
    /** restore_key_services was on when the config was read: this helper is noted for ServiceRepair. */
    private boolean repairRecord;
    /** consume_volume_keys: Volume Up/Down go to VolumeKeys instead of on to the firmware. */
    private boolean consumeVolumeKeys;
    /** Stops key filtering while a game controller is connected or after a volume change; null unless gamepad_passthrough or volume_pauses_filter, and key_filter. */
    private GamepadPassthrough gamepads;
    /** hdmi_auto_switch; null when off. */
    private HdmiAutoSwitch hdmi;
    /** The bound helper, for global actions Home's own key path asks for (RemoteKeys). */
    static InputKeyService running;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = this;
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        Config config = Config.load(this);
        // restore_key_services: note that this helper runs, so Home can put it back after a force stop.
        repairRecord = config.restoreKeyServices;
        if (repairRecord) ServiceRepair.connected(this, getClass());
        inputKeycodes = config.inputKeycodes;
        consumeVolumeKeys = config.consumeVolumeKeys;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        RemoteKeys.applyKeyFilter(this, config.keyFilter);
        holdAppKeys = RemoteKeys.parseHoldAppKeys(config.appKeys);
        holdAppScancodes = RemoteKeys.parseHoldAppScancodes(config.appKeys);
        frontAppKeys = RemoteKeys.parseFrontAppKeys(config.appKeys);
        frontAppScancodes = RemoteKeys.parseFrontAppScancodes(config.appKeys);
        // gamepad_passthrough and volume_pauses_filter, off by default.
        if (gamepads == null) gamepads = GamepadPassthrough.start(this, config);
        if (hdmi == null) hdmi = HdmiAutoSwitch.start(this, config);
        // input_key_service.xml subscribes to no events; hdmi_auto_switch needs the app in front. setServiceInfo()
        // drops the XML's filter flag, so it is set again (as HomeKeyService does).
        AccessibilityServiceInfo info = hdmi == null ? null : getServiceInfo();
        if (info != null) {
            info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            if (config.keyFilter) info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
    }

    private boolean isInputKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_TV || keyCode == KeyEvent.KEYCODE_TV_INPUT) return true;
        for (int code : inputKeycodes) if (keyCode == code) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (consumeVolumeKeys && VolumeKeys.handle(this, event)) return true;
        // A scancode entry is more specific than any keycode, so it goes first.
        if (RemoteKeys.handle(this, event, RemoteKeys.tap(appScancodes.get(event.getScanCode()),
                frontAppScancodes.get(event.getScanCode())),
                holdAppScancodes.get(event.getScanCode()))) return true;
        if (RemoteKeys.handle(this, event, RemoteKeys.tap(appKeys.get(event.getKeyCode()),
                frontAppKeys.get(event.getKeyCode())),
                holdAppKeys.get(event.getKeyCode()))) return true;
        if (!isInputKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            try {
                startActivity(new Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        return true;
    }

    /** Unbound by Android: the user turned the helper off, so Home doesn't put it back (ServiceRepair). */
    @Override
    public boolean onUnbind(Intent intent) {
        RemoteKeys.release(this);
        if (repairRecord) ServiceRepair.disconnected(this, getClass());
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        RemoteKeys.release(this);
        if (gamepads != null) gamepads.stop();
        if (consumeVolumeKeys) VolumeKeys.stop();
        if (hdmi != null) hdmi.stop();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Only activities count, so a toast or the volume bar doesn't change the app in front (as in HomeKeyService).
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getPackageName() == null || event.getClassName() == null) return;
        try {
            ComponentName window = new ComponentName(event.getPackageName().toString(), event.getClassName().toString());
            getPackageManager().getActivityInfo(window, 0);
            RemoteKeys.frontPackage = window.getPackageName();
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            // a dialog, panel or overlay: the app under it is still in front. The input player's "No signal".
            if (hdmi != null) hdmi.onPlayerDialog(event.getPackageName().toString(), joined(event));
        }
        // Philips' playtv reports its window by a view class, not its activity: any window of the input player
        // means an input is on screen.
        if (hdmi != null && hdmi.isPlayer(event.getPackageName())) RemoteKeys.frontPackage = event.getPackageName().toString();
    }

    private static CharSequence joined(AccessibilityEvent event) {
        StringBuilder text = new StringBuilder();
        for (CharSequence part : event.getText()) text.append(part).append('\n');
        return text;
    }

    @Override
    public void onInterrupt() {
    }
}
