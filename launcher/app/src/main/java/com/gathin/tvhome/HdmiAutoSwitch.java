package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * hdmi_auto_switch (config.xml), off by default. While InputKeyService is bound:
 * - an HDMI port that gets a signal is switched to;
 * - at boot and each time the screen turns on, the lowest-numbered HDMI port with a signal is switched to;
 * - when the port on screen loses its signal, the lowest-numbered port that still has one is switched to, or Home
 *   when none has. A PC that keeps its HDMI port powered when off never drops the hotplug state, so the input
 *   player's "No signal" dialog (hdmi_no_signal_text, matched in its text) counts as lost too, after a grace
 *   period that lets a booting PC start sending a picture.
 * A port whose CEC device is an audio system (an ARC soundbar, always "connected") is never switched to. The port
 * state is the framework's hotplug state, which a PC changes when it powers on or off; a TV in standby stops
 * tracking it (Philips Google TV TA1), so waking the TV is left to CEC or Wake-on-LAN.
 */
final class HdmiAutoSwitch extends TvInputManager.TvInputCallback {
    private static final String TAG = "HdmiAutoSwitch";
    /** HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM. */
    private static final int AUDIO_SYSTEM = 5;
    /** After boot or screen-on, the time the ports take to report their state (a PC took about 2 s on a Philips). */
    private static final long SETTLE_MS = 4000;
    /** A rebind later than this after boot (a reinstall) is not a start-up, and switches nothing by itself. */
    private static final long BOOT_WINDOW_MS = 180_000;
    /** After a switch, a "No signal" dialog is the source still starting up, not one that went away. */
    private static final long NO_SIGNAL_GRACE_MS = 60_000;

    /** The port this launcher put on screen, by this class or the Inputs menu (MainActivity.openInput). */
    static volatile String current;

    private final Context ctx;
    private final TvInputManager tim;
    private final String player;
    private final String noSignalText;
    private long switchedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> states = new HashMap<>();
    private final Runnable startUp = this::onStartUp;
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            handler.removeCallbacks(startUp);
            handler.postDelayed(startUp, SETTLE_MS);
        }
    };

    private HdmiAutoSwitch(Context ctx, TvInputManager tim, String player) {
        this.ctx = ctx;
        this.tim = tim;
        this.player = player;
        this.noSignalText = ctx.getString(R.string.hdmi_no_signal_text);
    }

    static HdmiAutoSwitch start(Context ctx, Config config) {
        if (!ctx.getResources().getBoolean(R.bool.hdmi_auto_switch)) return null;
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        HdmiAutoSwitch s = new HdmiAutoSwitch(ctx, tim, config.liveTvPackage);
        for (String port : s.ports()) s.states.put(port, tim.getInputState(port));
        Log.i(TAG, "watching " + s.states + ", player " + s.player);
        tim.registerCallback(s, s.handler);
        ctx.registerReceiver(s.screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) s.handler.postDelayed(s.startUp, SETTLE_MS);
        return s;
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        tim.unregisterCallback(this);
        try {
            ctx.unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void onInputStateChanged(String inputId, int state) {
        Integer before = states.get(inputId);
        if (before == null) return; // not a watched port: a CEC device, the tuner, the soundbar's port
        states.put(inputId, state);
        boolean was = before == TvInputManager.INPUT_STATE_CONNECTED;
        boolean is = state == TvInputManager.INPUT_STATE_CONNECTED;
        Log.i(TAG, inputId + " " + before + " -> " + state + ", current " + current + ", front " + RemoteKeys.frontPackage);
        if (is && !was) {
            switchTo(inputId);
        } else if (was && !is && inputId.equals(current) && onScreen()) {
            leave(inputId);
        }
    }

    boolean isPlayer(CharSequence pkg) {
        return pkg != null && !player.isEmpty() && player.contentEquals(pkg);
    }

    /** A window of the input player that is not an activity (a dialog), with its text (InputKeyService). */
    void onPlayerDialog(String pkg, CharSequence text) {
        String port = current;
        if (port == null || noSignalText.isEmpty() || !pkg.equals(player) || text == null
                || !text.toString().contains(noSignalText)) return;
        long since = SystemClock.elapsedRealtime() - switchedAt;
        Log.i(TAG, "no signal on " + port + ", " + since + " ms after the switch");
        if (since > NO_SIGNAL_GRACE_MS) leave(port);
    }

    /** The port on screen has no signal: the lowest other port with one, else Home. */
    private void leave(String port) {
        states.put(port, TvInputManager.INPUT_STATE_CONNECTED_STANDBY);
        String next = lowestConnected();
        if (next != null) switchTo(next);
        else {
            current = null;
            RemoteKeys.launch(ctx, "home");
        }
    }

    private void onStartUp() {
        for (String port : states.keySet()) states.put(port, tim.getInputState(port));
        String port = lowestConnected();
        Log.i(TAG, "start-up " + states + " -> " + port);
        if (port != null && !(port.equals(current) && onScreen())) switchTo(port);
    }

    private void switchTo(String port) {
        current = port;
        switchedAt = SystemClock.elapsedRealtime();
        RemoteKeys.launch(ctx, "input:" + port);
    }

    /** HDMI is what the TV shows: the input player is the app in front (InputKeyService tracks it). */
    private boolean onScreen() {
        return player.isEmpty() || player.equals(RemoteKeys.frontPackage);
    }

    private String lowestConnected() {
        for (String port : ports()) {
            Integer state = states.get(port);
            if (state != null && state == TvInputManager.INPUT_STATE_CONNECTED) return port;
        }
        return null;
    }

    /** HDMI ports in port order ("HDMI 1" first), without CEC device rows and without a port holding an audio system. */
    private List<String> ports() {
        List<TvInputInfo> all = tim.getTvInputList();
        List<TvInputInfo> ports = new ArrayList<>();
        for (TvInputInfo info : all) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null) continue;
            boolean audio = false;
            for (TvInputInfo child : all) {
                if (info.getId().equals(child.getParentId()) && isAudioSystem(child)) audio = true;
            }
            if (!audio) ports.add(info);
        }
        ports.sort((a, b) -> String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx))));
        List<String> ids = new ArrayList<>();
        for (TvInputInfo info : ports) ids.add(info.getId());
        return ids;
    }

    /** TvInputInfo.getHdmiDeviceInfo() is a system API; where the firmware blocks it, no port counts as audio. */
    private static boolean isAudioSystem(TvInputInfo info) {
        try {
            Object device = TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(info);
            return device != null && (int) device.getClass().getMethod("getDeviceType").invoke(device) == AUDIO_SYSTEM;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "cannot read the CEC device type of " + info.getId() + ": " + e);
            return false;
        }
    }
}
