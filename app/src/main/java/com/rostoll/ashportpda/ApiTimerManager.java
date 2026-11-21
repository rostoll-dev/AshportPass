package com.rostoll.ashportpda;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiTimerManager {
    private static final String TAG = "AshportLiveUpdate";
    public static final String PREFS_CACHE = "AshportPDA_TimersCache";
    private static final int HEARTBEAT_ALARM_ID = 9999;
    private static final int PROGRESS_TICK_ALARM_ID = 9998;
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long PROGRESS_TICK_INTERVAL_MS = 60L * 1000L;

    private static final String[][] TIMER_CONFIGS = {
            {"108", "notify_travel",  "live_travel",  "true",  "✈️ Flight / Travel"},
            {"109", "notify_status",  "live_status",  "true",  "🏥 Hospital / Jail"},
            {"105", "notify_drug",    "live_drug",    "true",  "💊 Drug Cooldown"},
            {"106", "notify_booster", "live_booster", "false", "🚀 Booster Cooldown"},
            {"107", "notify_medical", "live_medical", "false", "🩹 Medical Cooldown"},
            {"101", "notify_energy",  "live_energy",  "false", "⚡ Energy Bar"},
            {"102", "notify_nerve",   "live_nerve",   "false", "🔥 Grit Bar"},
            {"103", "notify_health",  "live_health",  "false", "❤️ Health Bar"},
            {"104", "notify_mood",    "live_mood",    "false", "😊 Mood Bar"},
            {"110", "notify_course",  "live_course",  "true",  "🎓 Education Course"},
            {"111", "notify_bank",    "live_bank",    "false", "🏦 Bank Investment"}
    };

    public static int getIconResForTimer(int notifId) {
        switch (notifId) {
            case 101: return R.drawable.ic_stat_energy;
            case 102: return R.drawable.ic_stat_nerve;
            case 103: return R.drawable.ic_stat_health;
            case 104: return R.drawable.ic_stat_mood;
            case 105: return R.drawable.ic_stat_drug;
            case 106: return R.drawable.ic_stat_booster;
            case 107: return R.drawable.ic_stat_medical;
            case 108: return R.drawable.ic_stat_travel;
            case 109: return R.drawable.ic_stat_status;
            case 110: return R.drawable.ic_stat_course;
            case 111: return R.drawable.ic_stat_bank;
            default:  return R.drawable.ic_stat_travel;
        }
    }

    private static class DynamicTargetView {
        final TextView rightTv;
        final long targetMs;
        final String activePrefix;
        final String readyText;
        final String activeColor;
        final String readyColor;

        DynamicTargetView(TextView rightTv, long targetMs, String activePrefix, String readyText, String activeColor, String readyColor) {
            this.rightTv = rightTv;
            this.targetMs = targetMs;
            this.activePrefix = activePrefix;
            this.readyText = readyText;
            this.activeColor = activeColor;
            this.readyColor = readyColor;
        }
    }

    private final Activity activity;
    private final SecureKeyStore keyStore;
    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler tickHandler = new Handler(Looper.getMainLooper());

    private JSONObject lastTimersJson = null;
    private JSONObject lastKeyInfoJson = null;
    private long serverTimeOffsetMs = 0L;
    private long lastFetchLocalMs = 0L;
    private boolean isFetching = false;

    private LinearLayout dashboardContainer;
    private TextView keyStatusBadge;
    private TextView syncStatusText;
    private LinearLayout liveContentBox;
    private final List<DynamicTargetView> dynamicTargets = new ArrayList<>();

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            if (dashboardContainer != null && dashboardContainer.isShown() && lastTimersJson != null) {
                if (dynamicTargets.isEmpty() && liveContentBox != null && liveContentBox.getChildCount() == 0) {
                    renderTimersContent();
                } else {
                    updateTimersValuesInPlace();
                }
            }
            tickHandler.postDelayed(this, 1000L);
        }
    };

    public ApiTimerManager(Activity activity) {
        this.activity = activity;
        this.keyStore = new SecureKeyStore(activity);
        this.prefs = activity.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        loadCachedData();
        if (keyStore.hasApiKey()) {
            scheduleBackgroundHeartbeat(activity);
        }
    }

    private void loadCachedData() {
        try {
            String cachedTimers = prefs.getString("cached_timers", null);
            String cachedKey = prefs.getString("cached_key_info", null);
            serverTimeOffsetMs = prefs.getLong("server_offset_ms", 0L);
            lastFetchLocalMs = prefs.getLong("last_fetch_local_ms", 0L);
            if (cachedTimers != null) lastTimersJson = new JSONObject(cachedTimers);
            if (cachedKey != null) lastKeyInfoJson = new JSONObject(cachedKey);
        } catch (Exception ignored) {}
    }

    public void startTicker() {
        loadCachedData();
        renderTimersContent();
        tickHandler.removeCallbacks(uiTicker);
        tickHandler.post(uiTicker);
        if (keyStore.hasApiKey() && (System.currentTimeMillis() - lastFetchLocalMs > 20000L)) {
            syncTimersNow(false);
        }
    }

    public void stopTicker() {
        tickHandler.removeCallbacks(uiTicker);
    }

    public void syncIfNeededOnActivity(long minIntervalMs) {
        if (!keyStore.hasApiKey() || isFetching) return;
        if (System.currentTimeMillis() - lastFetchLocalMs >= minIntervalMs) {
            syncTimersNow(false);
        }
    }

    public View buildDashboardView() {
        dashboardContainer = new LinearLayout(activity);
        dashboardContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout controlCard = new LinearLayout(activity);
        controlCard.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        controlCard.setPadding(pad, pad, pad, pad);
        controlCard.setBackground(makeCardDrawable("#132738", "#29465b"));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        controlCard.setLayoutParams(cardLp);

        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(activity);
        titleTv.setText("⏱  LIVE TIMERS & ALARMS");
        titleTv.setTextColor(Color.parseColor("#f2851d"));
        titleTv.setTextSize(13f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        keyStatusBadge = new TextView(activity);
        keyStatusBadge.setTextSize(11f);
        keyStatusBadge.setPadding(dp(8), dp(3), dp(8), dp(3));
        headerRow.addView(keyStatusBadge);
        controlCard.addView(headerRow);

        syncStatusText = new TextView(activity);
        syncStatusText.setTextColor(Color.parseColor("#90a4ae"));
        syncStatusText.setTextSize(12f);
        syncStatusText.setPadding(0, dp(4), dp(0), dp(10));
        controlCard.addView(syncStatusText);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button keyBtn = makeActionButton("🔑 API Key", "#2b4c65");
        LinearLayout.LayoutParams bLp1 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        bLp1.rightMargin = dp(6);
        keyBtn.setLayoutParams(bLp1);
        keyBtn.setOnClickListener(v -> showApiKeyModal());

        Button syncBtn = makeActionButton("🔄 Sync Now", "#5c7c8e");
        LinearLayout.LayoutParams bLp2 = new LinearLayout.LayoutParams(0, dp(38), 1f);
        bLp2.rightMargin = dp(6);
        syncBtn.setLayoutParams(bLp2);
        syncBtn.setOnClickListener(v -> syncTimersNow(true));

        Button notifBtn = makeActionButton("🔔 Alerts", "#2b4c65");
        notifBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(38), 1f));
        notifBtn.setOnClickListener(v -> showNotificationSettingsModal());

        btnRow.addView(keyBtn);
        btnRow.addView(syncBtn);
        btnRow.addView(notifBtn);
        controlCard.addView(btnRow);

        dashboardContainer.addView(controlCard);

        liveContentBox = new LinearLayout(activity);
        liveContentBox.setOrientation(LinearLayout.VERTICAL);
        dashboardContainer.addView(liveContentBox);

        updateHeaderStatus();
        renderTimersContent();
        return dashboardContainer;
    }

    private void updateHeaderStatus() {
        if (keyStatusBadge == null || syncStatusText == null) return;
        if (!keyStore.hasApiKey()) {
            keyStatusBadge.setText("NO KEY");
            keyStatusBadge.setTextColor(Color.parseColor("#ffb74d"));
            keyStatusBadge.setBackground(makePillDrawable("#3e2723"));
            syncStatusText.setText("Tap '🔑 API Key' to securely store your Ashport key (needs 'profile' scope).");
        } else {
            String playerInfo = "Key Stored • Exact Alarms Active";
            if (lastKeyInfoJson != null) {
                int pid = lastKeyInfoJson.optInt("playerId", 0);
                String kName = lastKeyInfoJson.optString("name", "Key");
                JSONObject rl = lastKeyInfoJson.optJSONObject("rateLimit");
                int rem = rl != null ? rl.optInt("remaining", 60) : 60;
                playerInfo = kName + (pid > 0 ? " [#" + pid + "]" : "") + " • " + rem + "/60 req • Promoted Live ON";
            }
            keyStatusBadge.setText("ENCRYPTED");
            keyStatusBadge.setTextColor(Color.parseColor("#69f0ae"));
            keyStatusBadge.setBackground(makePillDrawable("#1b3b2b"));
            syncStatusText.setText(isFetching ? "Syncing with ashport.net..." : playerInfo);
        }
    }

    public void showApiKeyModal() {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.parseColor("#162634"));

        TextView info = new TextView(activity);
        info.setText("In Ashport, open Me & Settings → Settings → API keys. Create a key with at least the 'profile' scope.\n\nYour key is encrypted with Android Keystore AES-256-GCM before saving.");
        info.setTextColor(Color.parseColor("#b0bec5"));
        info.setTextSize(13f);
        info.setPadding(0, 0, 0, dp(12));
        layout.addView(info);

        EditText keyInput = new EditText(activity);
        keyInput.setHint("ash_...");
        keyInput.setText(keyStore.getApiKey());
        keyInput.setTextColor(Color.WHITE);
        keyInput.setHintTextColor(Color.parseColor("#607d8b"));
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        keyInput.setBackground(makeCardDrawable("#0d1822", "#3a556a"));
        layout.addView(keyInput);

        Switch showToggle = new Switch(activity);
        showToggle.setText("Show plain key characters");
        showToggle.setTextColor(Color.parseColor("#90a4ae"));
        showToggle.setPadding(0, dp(8), 0, dp(8));
        showToggle.setOnCheckedChangeListener((btn, checked) -> {
            if (checked) {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            keyInput.setSelection(keyInput.getText().length());
        });
        layout.addView(showToggle);

        if (lastKeyInfoJson != null) {
            JSONArray scopes = lastKeyInfoJson.optJSONArray("scopes");
            StringBuilder sb = new StringBuilder("Active Scopes: ");
            if (scopes != null) {
                for (int i = 0; i < scopes.length(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(scopes.optString(i));
                }
            }
            TextView scopeTv = new TextView(activity);
            scopeTv.setText(sb.toString());
            scopeTv.setTextColor(Color.parseColor("#64b5f6"));
            scopeTv.setTextSize(12f);
            layout.addView(scopeTv);
        }

        builder.setTitle("🔑 Ashport API Key Vault")
                .setView(layout)
                .setPositiveButton("Save & Verify", (d, w) -> {
                    String entered = keyInput.getText().toString().trim();
                    if (entered.isEmpty()) {
                        keyStore.clearApiKey();
                        lastTimersJson = null;
                        lastKeyInfoJson = null;
                        prefs.edit().clear().apply();
                        cancelAllNotificationsAndAlarms(activity);
                        updateHeaderStatus();
                        renderTimersContent();
                        Toast.makeText(activity, "API Key cleared", Toast.LENGTH_SHORT).show();
                    } else {
                        keyStore.saveApiKey(entered);
                        updateHeaderStatus();
                        syncTimersNow(true);
                    }
                })
                .setNeutralButton("Clear Key", (d, w) -> {
                    keyStore.clearApiKey();
                    lastTimersJson = null;
                    lastKeyInfoJson = null;
                    prefs.edit().clear().apply();
                    cancelAllNotificationsAndAlarms(activity);
                    updateHeaderStatus();
                    renderTimersContent();
                    Toast.makeText(activity, "API Key removed from vault", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showNotificationSettingsModal() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.parseColor("#162634"));
        scroll.addView(layout);

        TextView legend = new TextView(activity);
        legend.setText("Choose per timer:\n• ⏱ Live: Pins a permanent full-size notification with its custom status icon.\n• 🔔 Alert: Vibrates & notifies you when the timer hits 00:00.");
        legend.setTextColor(Color.parseColor("#90a4ae"));
        legend.setTextSize(12f);
        legend.setPadding(0, 0, 0, dp(10));
        layout.addView(legend);

        if (Build.VERSION.SDK_INT >= 36 && !canPostPromotedNotifications(activity)) {
            Button permBtn = makeActionButton("⚡ Allow Android 16 Promoted Live Updates", "#f2851d");
            LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
            pLp.bottomMargin = dp(10);
            permBtn.setLayoutParams(pLp);
            permBtn.setOnClickListener(v -> openPromotedNotificationsSettings(activity));
            layout.addView(permBtn);
        }

        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setPadding(0, dp(4), 0, dp(6));

        TextView hTimer = new TextView(activity);
        hTimer.setText("TIMER");
        hTimer.setTextColor(Color.parseColor("#f2851d"));
        hTimer.setTextSize(11f);
        hTimer.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(hTimer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView hLive = new TextView(activity);
        hLive.setText("⏱ Live");
        hLive.setTextColor(Color.parseColor("#64b5f6"));
        hLive.setTextSize(11f);
        hLive.setTypeface(Typeface.DEFAULT_BOLD);
        hLive.setGravity(Gravity.CENTER);
        headerRow.addView(hLive, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hAlert = new TextView(activity);
        hAlert.setText("🔔 Alert");
        hAlert.setTextColor(Color.parseColor("#69f0ae"));
        hAlert.setTextSize(11f);
        hAlert.setTypeface(Typeface.DEFAULT_BOLD);
        hAlert.setGravity(Gravity.CENTER);
        headerRow.addView(hAlert, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

        layout.addView(headerRow);

        for (String[] cfg : TIMER_CONFIGS) {
            final String alertKey = cfg[1];
            final String liveKey = cfg[2];
            final boolean defLive = Boolean.parseBoolean(cfg[3]);
            final String labelText = cfg[4];

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView lbl = new TextView(activity);
            lbl.setText(labelText);
            lbl.setTextColor(Color.WHITE);
            lbl.setTextSize(13f);
            row.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            LinearLayout liveWrap = new LinearLayout(activity);
            liveWrap.setGravity(Gravity.CENTER);
            Switch liveSw = new Switch(activity);
            liveSw.setChecked(prefs.getBoolean(liveKey, defLive));
            liveSw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(liveKey, checked).apply());
            liveWrap.addView(liveSw);
            row.addView(liveWrap, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout alertWrap = new LinearLayout(activity);
            alertWrap.setGravity(Gravity.CENTER);
            Switch alertSw = new Switch(activity);
            alertSw.setChecked(prefs.getBoolean(alertKey, true));
            alertSw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(alertKey, checked).apply());
            alertWrap.addView(alertSw);
            row.addView(alertWrap, new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));

            layout.addView(row);
        }

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🔔 Promoted Live Updates & Alerts")
                .setView(scroll)
                .setPositiveButton("Apply Now", (d, w) -> {
                    if (lastTimersJson != null) {
                        scheduleAlarmsStatic(activity, prefs, lastTimersJson, serverTimeOffsetMs);
                        scheduleBackgroundHeartbeat(activity);
                        Toast.makeText(activity, "Notifications & alarms updated!", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    public void syncTimersNow(boolean showToast) {
        final String apiKey = keyStore.getApiKey();
        if (apiKey.isEmpty()) {
            if (showToast) showApiKeyModal();
            return;
        }
        if (isFetching) return;
        isFetching = true;
        updateHeaderStatus();

        executor.execute(() -> {
            try {
                JSONObject keyResp = fetchEndpointStatic("/v1/key", apiKey);
                JSONObject timersResp = fetchEndpointStatic("/v1/me/timers", apiKey);

                long serverNow = timersResp.optLong("now", System.currentTimeMillis());
                serverTimeOffsetMs = serverNow - System.currentTimeMillis();
                lastFetchLocalMs = System.currentTimeMillis();
                lastKeyInfoJson = keyResp;
                lastTimersJson = timersResp;

                prefs.edit()
                        .putString("cached_key_info", keyResp.toString())
                        .putString("cached_timers", timersResp.toString())
                        .putLong("server_offset_ms", serverTimeOffsetMs)
                        .putLong("last_fetch_local_ms", lastFetchLocalMs)
                        .apply();

                scheduleAlarmsStatic(activity, prefs, timersResp, serverTimeOffsetMs);
                scheduleBackgroundHeartbeat(activity);

                activity.runOnUiThread(() -> {
                    isFetching = false;
                    updateHeaderStatus();
                    renderTimersContent();
                    if (showToast) {
                        Toast.makeText(activity, "Ashport timers & live updates synced!", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "Sync error";
                activity.runOnUiThread(() -> {
                    isFetching = false;
                    updateHeaderStatus();
                    if (syncStatusText != null) syncStatusText.setText("Sync Error: " + msg);
                    if (showToast) {
                        Toast.makeText(activity, "API Error: " + msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    public static void refreshCachedLiveNotifications(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        try {
            String cached = prefs.getString("cached_timers", null);
            long offsetMs = prefs.getLong("server_offset_ms", 0L);
            if (cached != null) {
                scheduleAlarmsStatic(context, prefs, new JSONObject(cached), offsetMs);
            }
        } catch (Exception ignored) {}
    }

    public static void performHeadlessSync(Context context) {
        SecureKeyStore ks = new SecureKeyStore(context);
        String apiKey = ks.getApiKey();
        if (apiKey.isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
        try {
            JSONObject timersResp = fetchEndpointStatic("/v1/me/timers", apiKey);
            long serverNow = timersResp.optLong("now", System.currentTimeMillis());
            long offsetMs = serverNow - System.currentTimeMillis();

            prefs.edit()
                    .putString("cached_timers", timersResp.toString())
                    .putLong("server_offset_ms", offsetMs)
                    .putLong("last_fetch_local_ms", System.currentTimeMillis())
                    .apply();

            scheduleAlarmsStatic(context, prefs, timersResp, offsetMs);
        } catch (Exception ignored) {
            refreshCachedLiveNotifications(context);
        } finally {
            scheduleBackgroundHeartbeat(context);
        }
    }

    private static JSONObject fetchEndpointStatic(String path, String apiKey) throws Exception {
        String[] baseCandidates = {"https://ashport.net", "https://ashport.net/api"};
        Exception lastErr = null;

        for (String base : baseCandidates) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(base + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                }
                String raw = sb.toString().trim();
                if (code == 200 && raw.startsWith("{")) {
                    return new JSONObject(raw);
                } else if (raw.startsWith("{")) {
                    JSONObject errObj = new JSONObject(raw);
                    throw new Exception(errObj.optString("error", "HTTP " + code));
                } else {
                    lastErr = new Exception("HTTP " + code);
                }
            } catch (Exception e) {
                lastErr = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw lastErr != null ? lastErr : new Exception("Failed to reach Ashport API");
    }

    private void updateTimersValuesInPlace() {
        long nowMs = System.currentTimeMillis() + serverTimeOffsetMs;
        for (DynamicTargetView dtv : dynamicTargets) {
            if (dtv.targetMs > nowMs) {
                dtv.rightTv.setText(dtv.activePrefix + formatDuration(dtv.targetMs - nowMs));
                dtv.rightTv.setTextColor(Color.parseColor(dtv.activeColor));
            } else {
                dtv.rightTv.setText(dtv.readyText);
                dtv.rightTv.setTextColor(Color.parseColor(dtv.readyColor));
            }
        }
    }

    private void renderTimersContent() {
        if (liveContentBox == null) return;
        liveContentBox.removeAllViews();
        dynamicTargets.clear();

        if (lastTimersJson == null) return;

        long nowMs = System.currentTimeMillis() + serverTimeOffsetMs;

        JSONObject statusObj = lastTimersJson.optJSONObject("status");
        String statusKind = statusObj != null ? statusObj.optString("kind", "ok") : "ok";
        String statusReason = statusObj != null ? statusObj.optString("reason", "") : "";
        long hospUntil = lastTimersJson.optLong("hospitalUntil", 0L);
        long jailUntil = lastTimersJson.optLong("jailUntil", 0L);
        JSONObject travelObj = lastTimersJson.optJSONObject("travel");

        if (!"ok".equalsIgnoreCase(statusKind) || hospUntil > nowMs || jailUntil > nowMs || travelObj != null) {
            LinearLayout alertCard = new LinearLayout(activity);
            alertCard.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(12);
            alertCard.setPadding(pad, pad, pad, pad);
            alertCard.setBackground(makeCardDrawable("#2b1d28", "#f2851d"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            alertCard.setLayoutParams(lp);

            if (travelObj != null) {
                String destName = travelObj.optString("name", travelObj.optString("dest", "Abroad"));
                String dir = "home".equalsIgnoreCase(travelObj.optString("dir")) ? "Returning from " : "Flying to ";
                long landsAt = travelObj.optLong("landsAt", 0L);
                alertCard.addView(makeDynamicRowText("✈️  " + dir + destName, landsAt, nowMs,
                        "Lands in ", "Landed (" + destName + ")", "#64b5f6", "#69f0ae"));
            }
            if (hospUntil > nowMs) {
                alertCard.addView(makeDynamicRowText("🏥  Hospitalized", hospUntil, nowMs,
                        "", "OUT", "#ff8a80", "#69f0ae"));
            }
            if (jailUntil > nowMs) {
                alertCard.addView(makeDynamicRowText("⛓  In Jail", jailUntil, nowMs,
                        "", "OUT", "#ffd180", "#69f0ae"));
            }
            if (!statusReason.isEmpty() && hospUntil <= nowMs && jailUntil <= nowMs && travelObj == null) {
                alertCard.addView(makeRowText("Status: " + statusKind.toUpperCase(Locale.US), statusReason, "#ffb74d"));
            }
            liveContentBox.addView(alertCard);
        }

        JSONObject bars = lastTimersJson.optJSONObject("bars");
        if (bars != null) {
            LinearLayout barsCard = new LinearLayout(activity);
            barsCard.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(14);
            barsCard.setPadding(pad, pad, pad, pad);
            barsCard.setBackground(makeCardDrawable("#132738", "#234156"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            barsCard.setLayoutParams(lp);

            addBarRow(barsCard, "⚡ Energy", bars.optJSONObject("energy"), nowMs, "#69f0ae");
            addBarRow(barsCard, "🔥 Grit", (bars.optJSONObject("grit") != null ? bars.optJSONObject("grit") : bars.optJSONObject("nerve")), nowMs, "#ff8a65");
            addBarRow(barsCard, "❤️ Health", bars.optJSONObject("health"), nowMs, "#ef5350");
            addBarRow(barsCard, "😊 Mood", bars.optJSONObject("mood"), nowMs, "#64b5f6");

            liveContentBox.addView(barsCard);
        }

        LinearLayout timersCard = new LinearLayout(activity);
        timersCard.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        timersCard.setPadding(pad, pad, pad, pad);
        timersCard.setBackground(makeCardDrawable("#132738", "#234156"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        timersCard.setLayoutParams(lp);

        JSONObject cds = lastTimersJson.optJSONObject("cooldowns");
        long drugAt = cds != null ? cds.optLong("drug", 0L) : 0L;
        long boosterAt = cds != null ? cds.optLong("booster", 0L) : 0L;
        long medAt = cds != null ? cds.optLong("medical", 0L) : 0L;

        timersCard.addView(makeTimerLine("💊 Drug Cooldown", drugAt, nowMs));
        timersCard.addView(makeTimerLine("🚀 Booster Cooldown", boosterAt, nowMs));
        timersCard.addView(makeTimerLine("🩹 Medical Cooldown", medAt, nowMs));

        long courseEndsAt = lastTimersJson.optLong("courseEndsAt", 0L);
        long bankMaturesAt = lastTimersJson.optLong("bankMaturesAt", 0L);
        timersCard.addView(makeTimerLine("🎓 Education Course", courseEndsAt, nowMs));
        timersCard.addView(makeTimerLine("🏦 Bank Investment", bankMaturesAt, nowMs));

        JSONObject refills = lastTimersJson.optJSONObject("refills");
        if (refills != null) {
            boolean eRefill = refills.optBoolean("energy", false);
            boolean nRefill = refills.optBoolean("grit", refills.optBoolean("nerve", false));
            String refillText = (eRefill ? "⚡ Energy Available  " : "⚡ Used  ") +
                    (nRefill ? "🔥 Grit Available" : "🔥 Used");
            timersCard.addView(makeRowText("🔋 Daily Refills", refillText, (eRefill || nRefill) ? "#ffd54f" : "#78909c"));
        }

        long newDayAt = lastTimersJson.optLong("newDayAt", 0L);
        if (newDayAt > nowMs) {
            timersCard.addView(makeDynamicRowText("🌅 Next New Day", newDayAt, nowMs,
                    "", "NOW", "#90caf9", "#69f0ae"));
        }

        JSONArray buffs = lastTimersJson.optJSONArray("buffs");
        if (buffs != null && buffs.length() > 0) {
            for (int i = 0; i < buffs.length(); i++) {
                JSONObject b = buffs.optJSONObject(i);
                if (b == null) continue;
                long until = b.optLong("until", 0L);
                if (until > nowMs) {
                    String label = b.optString("label", b.optString("k", "Buff"));
                    timersCard.addView(makeDynamicRowText("✨ " + label, until, nowMs,
                            "", "EXPIRED", "#ce93d8", "#78909c"));
                }
            }
        }

        liveContentBox.addView(timersCard);
    }

    private void addBarRow(LinearLayout parent, String label, JSONObject barObj, long nowMs, String colorHex) {
        if (barObj == null) return;
        int val = barObj.optInt("value", 0);
        int max = Math.max(1, barObj.optInt("max", 100));
        long fullAt = barObj.optLong("fullAt", 0L);

        LinearLayout wrap = new LinearLayout(activity);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.bottomMargin = dp(8);
        wrap.setLayoutParams(wLp);

        wrap.addView(makeDynamicRowText(label + "  (" + val + "/" + max + ")", fullAt, nowMs,
                "Full in ", "FULL", "#b0bec5", colorHex));

        ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(max);
        pb.setProgress(Math.min(val, max));
        pb.setProgressTintList(ColorStateList.valueOf(Color.parseColor(colorHex)));
        pb.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#263c4f")));
        wrap.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        parent.addView(wrap);
    }

    private View makeTimerLine(String label, long targetAtMs, long nowMs) {
        return makeDynamicRowText(label, targetAtMs, nowMs, "", "READY", "#ffb74d", "#69f0ae");
    }

    private View makeDynamicRowText(String left, long targetMs, long nowMs,
                                    String activePrefix, String readyText,
                                    String activeColorHex, String readyColorHex) {
        boolean isActive = targetMs > nowMs;
        String initialRight = isActive ? (activePrefix + formatDuration(targetMs - nowMs)) : readyText;
        String initialColor = isActive ? activeColorHex : readyColorHex;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView lTv = new TextView(activity);
        lTv.setText(left);
        lTv.setTextColor(Color.WHITE);
        lTv.setTextSize(13f);
        row.addView(lTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rTv = new TextView(activity);
        rTv.setText(initialRight);
        rTv.setTextColor(Color.parseColor(initialColor));
        rTv.setTextSize(13f);
        rTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(rTv);

        dynamicTargets.add(new DynamicTargetView(rTv, targetMs, activePrefix, readyText, activeColorHex, readyColorHex));
        return row;
    }

    private View makeRowText(String left, String right, String rightColorHex) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView lTv = new TextView(activity);
        lTv.setText(left);
        lTv.setTextColor(Color.WHITE);
        lTv.setTextSize(13f);
        row.addView(lTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView rTv = new TextView(activity);
        rTv.setText(right);
        rTv.setTextColor(Color.parseColor(rightColorHex));
        rTv.setTextSize(13f);
        rTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        row.addView(rTv);

        return row;
    }

    public static void scheduleAlarmsStatic(Context context, SharedPreferences prefs, JSONObject t, long serverOffsetMs) {
        long nowServer = System.currentTimeMillis() + serverOffsetMs;
        boolean anyLiveActive = false;

        JSONObject bars = t.optJSONObject("bars");
        if (bars != null) {
            JSONObject eBar = bars.optJSONObject("energy");
            int eVal = eBar != null ? eBar.optInt("value", 0) : 0;
            int eMax = eBar != null ? eBar.optInt("max", 100) : 100;
            anyLiveActive |= handleTimerEntryStatic(context, prefs, 101, "notify_energy", "live_energy", false,
                    eBar != null ? eBar.optLong("fullAt", 0L) : 0L, 0L, nowServer,
                    "⚡ Energy Full", "Your Ashport Energy bar has reached maximum capacity!",
                    "Regenerating Energy", eVal + " / " + eMax + " Energy", "#69f0ae");

            JSONObject nBar = (bars.optJSONObject("grit") != null ? bars.optJSONObject("grit") : bars.optJSONObject("nerve"));
            int nVal = nBar != null ? nBar.optInt("value", 0) : 0;
            int nMax = nBar != null ? nBar.optInt("max", 100) : 100;
            anyLiveActive |= handleTimerEntryStatic(context, prefs, 102, "notify_nerve", "live_nerve", false,
                    nBar != null ? nBar.optLong("fullAt", 0L) : 0L, 0L, nowServer,
                    "🔥 Grit Full", "Your Ashport Grit bar is full and ready for crimes!",
                    "Regenerating Grit", nVal + " / " + nMax + " Grit", "#ff8a65");

            JSONObject hBar = bars.optJSONObject("health");
            int hVal = hBar != null ? hBar.optInt("value", 0) : 0;
            int hMax = hBar != null ? hBar.optInt("max", 100) : 100;
            anyLiveActive |= handleTimerEntryStatic(context, prefs, 103, "notify_health", "live_health", false,
                    hBar != null ? hBar.optLong("fullAt", 0L) : 0L, 0L, nowServer,
                    "❤️ Health Full", "Your Health bar is back to 100%.",
                    "Healing Health", hVal + " / " + hMax + " HP", "#ef5350");

            JSONObject mBar = bars.optJSONObject("mood");
            int mVal = mBar != null ? mBar.optInt("value", 0) : 0;
            int mMax = mBar != null ? mBar.optInt("max", 100) : 100;
            anyLiveActive |= handleTimerEntryStatic(context, prefs, 104, "notify_mood", "live_mood", false,
                    mBar != null ? mBar.optLong("fullAt", 0L) : 0L, 0L, nowServer,
                    "😊 Mood Full", "Your Mood bar has fully regenerated.",
                    "Recovering Mood", mVal + " / " + mMax + " Mood", "#64b5f6");
        }

        JSONObject cds = t.optJSONObject("cooldowns");
        if (cds != null) {
            anyLiveActive |= handleTimerEntryStatic(context, prefs, 105, "notify_drug", "live_drug", true,
                    cds.optLong("drug", 0L), 0L, nowServer,
                    "💊 Drug Cooldown Ready", "Your drug cooldown has ended.",
                    "Drug Cooldown Active", "Waiting for substance effects to clear.", "#f48fb1");

            anyLiveActive |= handleTimerEntryStatic(context, prefs, 106, "notify_booster", "live_booster", false,
                    cds.optLong("booster", 0L), 0L, nowServer,
                    "🚀 Booster Cooldown Ready", "Your booster cooldown has ended.",
                    "Booster Cooldown Active", "Waiting for booster timer to reset.", "#ce93d8");

            anyLiveActive |= handleTimerEntryStatic(context, prefs, 107, "notify_medical", "live_medical", false,
                    cds.optLong("medical", 0L), 0L, nowServer,
                    "🩹 Medical Cooldown Ready", "Your medical cooldown has cleared.",
                    "Medical Cooldown Active", "Medical supplies on cooldown.", "#ef9a9a");
        }

        JSONObject travel = t.optJSONObject("travel");
        long landsAt = travel != null ? travel.optLong("landsAt", 0L) : 0L;
        long departedAt = travel != null ? travel.optLong("departedAt", travel.optLong("startedAt", 0L)) : 0L;
        String dest = travel != null ? travel.optString("name", travel.optString("dest", "Destination")) : "Destination";
        boolean returning = travel != null && "home".equalsIgnoreCase(travel.optString("dir"));
        String flightTitle = returning ? "Returning to Ashport" : "Traveling to " + dest;
        String flightSub = returning
                ? "Inbound flight from " + dest + ". Smooth skies ahead."
                : "Wheels up, en route to " + dest + ". Nothing to report.";

        anyLiveActive |= handleTimerEntryStatic(context, prefs, 108, "notify_travel", "live_travel", true,
                landsAt, departedAt, nowServer,
                "✈️ Flight Landed", "You have landed in " + (returning ? "Ashport" : dest) + "!",
                flightTitle, flightSub, "#64b5f6");

        long hospUntil = t.optLong("hospitalUntil", 0L);
        long jailUntil = t.optLong("jailUntil", 0L);
        long statusUntil = Math.max(hospUntil, jailUntil);
        boolean isJail = jailUntil > hospUntil;
        JSONObject statusObj = t.optJSONObject("status");
        String statusReason = statusObj != null ? statusObj.optString("reason", "") : "";

        anyLiveActive |= handleTimerEntryStatic(context, prefs, 109, "notify_status", "live_status", true,
                statusUntil, 0L, nowServer,
                isJail ? "⛓ Released from Jail!" : "🏥 Released from Hospital!",
                "You are out and ready for action in Ashport.",
                isJail ? "Currently in Jail" : "Currently Hospitalized",
                !statusReason.isEmpty() ? statusReason : "Resting until discharge.", "#ffb74d");

        anyLiveActive |= handleTimerEntryStatic(context, prefs, 110, "notify_course", "live_course", true,
                t.optLong("courseEndsAt", 0L), 0L, nowServer,
                "🎓 Course Completed", "Your Ashport education course has finished!",
                "Education Course in Progress", "Studying current curriculum module.", "#90caf9");

        anyLiveActive |= handleTimerEntryStatic(context, prefs, 111, "notify_bank", "live_bank", false,
                t.optLong("bankMaturesAt", 0L), 0L, nowServer,
                "🏦 Bank Deposit Matured", "Your Ashport bank investment is ready to collect!",
                "Bank Investment Active", "Accruing compound interest until maturity.", "#ffd54f");

        scheduleProgressTickAlarm(context, anyLiveActive);
    }

    private static boolean handleTimerEntryStatic(Context context, SharedPreferences prefs, int notifId,
                                                  String alertPrefKey, String livePrefKey, boolean defaultLive,
                                                  long targetServerMs, long explicitStartServerMs, long nowServerMs,
                                                  String finishTitle, String finishMessage,
                                                  String liveTitleText, String liveBodyText, String accentHex) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (am == null || nm == null) return false;

        boolean alertEnabled = prefs.getBoolean(alertPrefKey, true);
        boolean liveEnabled = prefs.getBoolean(livePrefKey, defaultLive);

        Intent intent = new Intent(context, TimerAlarmReceiver.class);
        intent.setAction(TimerAlarmReceiver.ACTION_TIMER_ALARM);
        intent.putExtra("notif_id", notifId);
        intent.putExtra("title", finishTitle);
        intent.putExtra("message", finishMessage);
        intent.putExtra("alert_enabled", alertEnabled);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                notifId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (targetServerMs <= nowServerMs) {
            am.cancel(pi);
            nm.cancel(notifId + 1000);
            prefs.edit()
                    .remove("scheduled_target_" + notifId)
                    .remove("start_server_ms_" + notifId)
                    .apply();
            return false;
        }

        long delayMs = targetServerMs - nowServerMs;
        long triggerLocalMs = System.currentTimeMillis() + delayMs;

        long prevTarget = prefs.getLong("scheduled_target_" + notifId, 0L);
        long startServerMs = prefs.getLong("start_server_ms_" + notifId, 0L);
        if (explicitStartServerMs > 0L && explicitStartServerMs < targetServerMs) {
            startServerMs = explicitStartServerMs;
        }
        if (Math.abs(targetServerMs - prevTarget) > 5000L || startServerMs <= 0L || startServerMs >= targetServerMs) {
            if (explicitStartServerMs <= 0L) {
                startServerMs = nowServerMs;
            }
            prefs.edit()
                    .putLong("scheduled_target_" + notifId, targetServerMs)
                    .putLong("start_server_ms_" + notifId, startServerMs)
                    .apply();
        }

        if (alertEnabled || liveEnabled) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerLocalMs, pi);
            } catch (SecurityException se) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerLocalMs, pi);
            }
        } else {
            am.cancel(pi);
        }

        if (liveEnabled) {
            long totalSec = Math.max(1L, (targetServerMs - startServerMs) / 1000L);
            long elapsedSec = Math.min(totalSec, Math.max(0L, (nowServerMs - startServerMs) / 1000L));
            postPromotedProgressNotification(context, nm, notifId, notifId + 1000, triggerLocalMs,
                    liveTitleText, liveBodyText, (int) totalSec, (int) elapsedSec, accentHex);
            return true;
        } else {
            nm.cancel(notifId + 1000);
            return false;
        }
    }

    private static void postPromotedProgressNotification(Context context, NotificationManager nm,
                                                         int notifId, int liveNotifId, long triggerLocalMs,
                                                         String title, String bodyText,
                                                         int totalSec, int elapsedSec,
                                                         String accentHex) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel existing = nm.getNotificationChannel(TimerAlarmReceiver.LIVE_CHANNEL_ID);
            if (existing != null && existing.getImportance() == NotificationManager.IMPORTANCE_HIGH) {
                nm.deleteNotificationChannel(TimerAlarmReceiver.LIVE_CHANNEL_ID);
            }
            NotificationChannel liveChannel = new NotificationChannel(
                    TimerAlarmReceiver.LIVE_CHANNEL_ID,
                    "Travel Live Updates",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            liveChannel.setDescription("Promoted ongoing live updates");
            liveChannel.setSound(null, null);
            liveChannel.enableVibration(false);
            liveChannel.setShowBadge(false);
            liveChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(liveChannel);
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                liveNotifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String etaClock = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(triggerLocalMs));
        String etaText = "ETA " + etaClock;
        int iconRes = getIconResForTimer(notifId);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, TimerAlarmReceiver.LIVE_CHANNEL_ID)
                : new Notification.Builder(context);

        builder.setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(bodyText)
                .setSubText(etaText)
                .setWhen(triggerLocalMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_STATUS)
                   .setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        boolean styleApplied = applyProgressStyleIfAvailable(context, builder, iconRes, totalSec, elapsedSec, accentHex);
        if (!styleApplied) {
            builder.setProgress(totalSec, elapsedSec, false);
        }

        invokeFrameworkPromotedOngoing(builder);
        invokeFrameworkShortCriticalText(builder, etaText);

        Notification notification = builder.build();
        nm.notify(liveNotifId, notification);
    }

    private static boolean applyProgressStyleIfAvailable(Context context, Notification.Builder builder,
                                                         int iconRes, int totalSec, int elapsedSec,
                                                         String accentHex) {
        if (Build.VERSION.SDK_INT < 35) return false;
        ProgressStyleRefs refs = ProgressStyleRefs.resolve();
        if (refs == null) return false;

        int total = Math.max(1, totalSec);
        int elapsed = Math.max(0, Math.min(total, elapsedSec));

        try {
            Object style = refs.styleCtor.newInstance();
            refs.setProgress.invoke(style, elapsed);
            refs.setTrackerIcon.invoke(style, createTintedVectorTrackerIcon(context, iconRes, accentHex));
            refs.setStartIcon.invoke(style, createDotBadgeIcon("#1b3b52", "#64b5f6"));
            refs.setEndIcon.invoke(style, createDotBadgeIcon("#2b1d28", accentHex));
            Object segment = refs.segmentCtor.newInstance(total);
            refs.addSegment.invoke(style, segment);
            builder.setStyle((Notification.Style) style);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "ProgressStyle unavailable, using single-line progress", t);
            return false;
        }
    }

    private static Icon createTintedVectorTrackerIcon(Context context, int drawableRes, String accentHex) {
        try {
            Drawable d = context.getDrawable(drawableRes);
            if (d != null) {
                Drawable mutated = d.mutate();
                mutated.setColorFilter(Color.parseColor(accentHex), PorterDuff.Mode.SRC_IN);
                int size = 72;
                Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                mutated.setBounds(6, 6, size - 6, size - 6);
                mutated.draw(c);
                return Icon.createWithBitmap(bmp);
            }
        } catch (Throwable ignored) {}
        return Icon.createWithResource(context, drawableRes);
    }

    private static void invokeFrameworkPromotedOngoing(Notification.Builder builder) {
        try {
            Bundle extras = new Bundle();
            extras.putBoolean("android.requestPromotedOngoing", true);
            builder.addExtras(extras);
        } catch (Exception ignored) {}

        try {
            Method m = builder.getClass().getMethod("setRequestPromotedOngoing", boolean.class);
            m.invoke(builder, true);
        } catch (Exception ignored) {}
    }

    private static void invokeFrameworkShortCriticalText(Notification.Builder builder, String text) {
        try {
            Bundle extras = new Bundle();
            extras.putCharSequence("android.shortCriticalText", text);
            builder.addExtras(extras);
        } catch (Exception ignored) {}

        try {
            Method m = builder.getClass().getMethod("setShortCriticalText", String.class);
            m.invoke(builder, text);
        } catch (Exception ignored) {}
    }

    public static boolean canPostPromotedNotifications(Context context) {
        if (Build.VERSION.SDK_INT < 36) return true;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return true;
        try {
            Method m = nm.getClass().getMethod("canPostPromotedNotifications");
            Object res = m.invoke(nm);
            return res instanceof Boolean ? (Boolean) res : true;
        } catch (Throwable t) {
            return true;
        }
    }

    public static void openPromotedNotificationsSettings(Context context) {
        String action = Build.VERSION.SDK_INT >= 36
                ? "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
                : Settings.ACTION_APP_NOTIFICATION_SETTINGS;
        Intent intent = new Intent(action)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {}
    }

    private static Icon createDotBadgeIcon(String fillHex, String ringHex) {
        int size = 64;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor(fillHex));
        c.drawCircle(32f, 32f, 24f, p);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        p.setColor(Color.parseColor(ringHex));
        c.drawCircle(32f, 32f, 24f, p);

        p.setStyle(Paint.Style.FILL);
        c.drawCircle(32f, 32f, 9f, p);
        return Icon.createWithBitmap(bmp);
    }

    private static class ProgressStyleRefs {
        final Constructor<?> styleCtor;
        final Constructor<?> segmentCtor;
        final Method setProgress;
        final Method setTrackerIcon;
        final Method setStartIcon;
        final Method setEndIcon;
        final Method addSegment;

        private static volatile ProgressStyleRefs cached = null;
        private static volatile boolean unavailable = false;

        ProgressStyleRefs(Constructor<?> styleCtor, Constructor<?> segmentCtor,
                          Method setProgress, Method setTrackerIcon,
                          Method setStartIcon, Method setEndIcon, Method addSegment) {
            this.styleCtor = styleCtor;
            this.segmentCtor = segmentCtor;
            this.setProgress = setProgress;
            this.setTrackerIcon = setTrackerIcon;
            this.setStartIcon = setStartIcon;
            this.setEndIcon = setEndIcon;
            this.addSegment = addSegment;
        }

        static ProgressStyleRefs resolve() {
            if (cached != null) return cached;
            if (unavailable) return null;
            try {
                Class<?> styleClass = Class.forName("android.app.Notification$ProgressStyle");
                Class<?> segmentClass = Class.forName("android.app.Notification$ProgressStyle$Segment");
                cached = new ProgressStyleRefs(
                        styleClass.getDeclaredConstructor(),
                        segmentClass.getDeclaredConstructor(int.class),
                        styleClass.getMethod("setProgress", int.class),
                        styleClass.getMethod("setProgressTrackerIcon", Icon.class),
                        styleClass.getMethod("setProgressStartIcon", Icon.class),
                        styleClass.getMethod("setProgressEndIcon", Icon.class),
                        styleClass.getMethod("addProgressSegment", segmentClass)
                );
                return cached;
            } catch (Throwable t) {
                unavailable = true;
                return null;
            }
        }
    }

    public static void scheduleProgressTickAlarm(Context context, boolean active) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerAlarmReceiver.class);
        intent.setAction(TimerAlarmReceiver.ACTION_LIVE_PROGRESS_TICK);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                PROGRESS_TICK_ALARM_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (!active) {
            am.cancel(pi);
            return;
        }

        long triggerAt = System.currentTimeMillis() + PROGRESS_TICK_INTERVAL_MS;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void scheduleBackgroundHeartbeat(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerAlarmReceiver.class);
        intent.setAction(TimerAlarmReceiver.ACTION_BACKGROUND_HEARTBEAT);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                HEARTBEAT_ALARM_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long triggerAt = System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
    }

    public static void cancelAllNotificationsAndAlarms(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (am != null) {
            Intent hb = new Intent(context, TimerAlarmReceiver.class);
            hb.setAction(TimerAlarmReceiver.ACTION_BACKGROUND_HEARTBEAT);
            PendingIntent hbPi = PendingIntent.getBroadcast(context, HEARTBEAT_ALARM_ID, hb,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(hbPi);
            scheduleProgressTickAlarm(context, false);

            for (int id = 101; id <= 111; id++) {
                Intent tIntent = new Intent(context, TimerAlarmReceiver.class);
                tIntent.setAction(TimerAlarmReceiver.ACTION_TIMER_ALARM);
                PendingIntent tPi = PendingIntent.getBroadcast(context, id, tIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.cancel(tPi);
            }
        }
        if (nm != null) {
            nm.cancelAll();
        }
    }

    private String formatDuration(long diffMs) {
        long totalSec = Math.max(0L, diffMs / 1000L);
        long days = totalSec / 86400L;
        long hours = (totalSec % 86400L) / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (days > 0) {
            return String.format(Locale.US, "%dd %02d:%02d:%02d", days, hours, mins, secs);
        }
        return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
    }

    private GradientDrawable makeCardDrawable(String bgHex, String borderHex) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setStroke(dp(1), Color.parseColor(borderHex));
        gd.setCornerRadius(dp(10));
        return gd;
    }

    private GradientDrawable makePillDrawable(String bgHex) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(12));
        return gd;
    }

    private Button makeActionButton(String text, String colorHex) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(12f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(colorHex));
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        return b;
    }

    private int dp(int val) {
        return Math.round(val * activity.getResources().getDisplayMetrics().density);
    }
}
