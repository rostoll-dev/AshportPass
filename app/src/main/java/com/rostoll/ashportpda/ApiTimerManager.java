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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiTimerManager {
    public static final String PREFS_CACHE = "AshportPDA_TimersCache";
    private static final int HEARTBEAT_ALARM_ID = 9999;
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L; // 5 minutes

    private static final String[][] TIMER_CONFIGS = {
            {"108", "notify_travel",  "live_travel",  "true",  "✈️ Flight / Travel"},
            {"109", "notify_status",  "live_status",  "true",  "🏥 Hospital / Jail"},
            {"105", "notify_drug",    "live_drug",    "true",  "💊 Drug Cooldown"},
            {"106", "notify_booster", "live_booster", "false", "🚀 Booster Cooldown"},
            {"107", "notify_medical", "live_medical", "false", "🩹 Medical Cooldown"},
            {"101", "notify_energy",  "live_energy",  "false", "⚡ Energy Bar"},
            {"102", "notify_nerve",   "live_nerve",   "false", "🔥 Nerve Bar"},
            {"103", "notify_health",  "live_health",  "false", "❤️ Health Bar"},
            {"104", "notify_mood",    "live_mood",    "false", "😊 Mood Bar"},
            {"110", "notify_course",  "live_course",  "true",  "🎓 Education Course"},
            {"111", "notify_bank",    "live_bank",    "false", "🏦 Bank Investment"}
    };

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

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            if (dashboardContainer != null && dashboardContainer.isShown() && lastTimersJson != null) {
                renderTimersContent();
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
                playerInfo = kName + (pid > 0 ? " [#" + pid + "]" : "") + " • " + rem + "/60 req • 5m Sync ON";
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
        legend.setText("Choose per timer:\n• ⏱ Live: Pins an expanded live countdown card with visual progress tracker in your status bar.\n• 🔔 Alert: Vibrates & notifies you when the timer hits 00:00.");
        legend.setTextColor(Color.parseColor("#90a4ae"));
        legend.setTextSize(12f);
        legend.setPadding(0, 0, 0, dp(10));
        layout.addView(legend);

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
                .setTitle("🔔 Live Bars & Timer Alerts")
                .setView(scroll)
                .setPositiveButton("Apply Now", (d, w) -> {
                    if (lastTimersJson != null) {
                        scheduleAlarmsStatic(activity, prefs, lastTimersJson, serverTimeOffsetMs);
                        scheduleBackgroundHeartbeat(activity);
                        Toast.makeText(activity, "Live cards & alarms updated!", Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(activity, "Ashport timers & live cards synced!", Toast.LENGTH_SHORT).show();
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
            try {
                String cached = prefs.getString("cached_timers", null);
                long offsetMs = prefs.getLong("server_offset_ms", 0L);
                if (cached != null) {
                    scheduleAlarmsStatic(context, prefs, new JSONObject(cached), offsetMs);
                }
            } catch (Exception ignored2) {}
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

    private void renderTimersContent() {
        if (liveContentBox == null) return;
        liveContentBox.removeAllViews();

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
                String landStr = landsAt > nowMs ? "Lands in " + formatDuration(landsAt - nowMs) : "Landed (" + destName + ")";
                alertCard.addView(makeRowText("✈️  " + dir + destName, landStr, "#64b5f6"));
            }
            if (hospUntil > nowMs) {
                alertCard.addView(makeRowText("🏥  Hospitalized", formatDuration(hospUntil - nowMs), "#ff8a80"));
            }
            if (jailUntil > nowMs) {
                alertCard.addView(makeRowText("⛓  In Jail", formatDuration(jailUntil - nowMs), "#ffd180"));
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
            addBarRow(barsCard, "🔥 Nerve", bars.optJSONObject("nerve"), nowMs, "#ff8a65");
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
            boolean nRefill = refills.optBoolean("nerve", false);
            String refillText = (eRefill ? "⚡ Energy Available  " : "⚡ Used  ") +
                    (nRefill ? "🔥 Nerve Available" : "🔥 Used");
            timersCard.addView(makeRowText("🔋 Daily Refills", refillText, (eRefill || nRefill) ? "#ffd54f" : "#78909c"));
        }

        long newDayAt = lastTimersJson.optLong("newDayAt", 0L);
        if (newDayAt > nowMs) {
            timersCard.addView(makeRowText("🌅 Next New Day", formatDuration(newDayAt - nowMs), "#90caf9"));
        }

        JSONArray buffs = lastTimersJson.optJSONArray("buffs");
        if (buffs != null && buffs.length() > 0) {
            for (int i = 0; i < buffs.length(); i++) {
                JSONObject b = buffs.optJSONObject(i);
                if (b == null) continue;
                long until = b.optLong("until", 0L);
                if (until > nowMs) {
                    String label = b.optString("label", b.optString("k", "Buff"));
                    timersCard.addView(makeRowText("✨ " + label, formatDuration(until - nowMs), "#ce93d8"));
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

        String rightStatus = (fullAt > nowMs) ? "Full in " + formatDuration(fullAt - nowMs) : "FULL";
        wrap.addView(makeRowText(label + "  (" + val + "/" + max + ")", rightStatus,
                (fullAt > nowMs) ? "#b0bec5" : colorHex));

        ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(max);
        pb.setProgress(Math.min(val, max));
        pb.getProgressDrawable().setColorFilter(Color.parseColor(colorHex), android.graphics.PorterDuff.Mode.SRC_IN);
        wrap.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));

        parent.addView(wrap);
    }

    private View makeTimerLine(String label, long targetAtMs, long nowMs) {
        if (targetAtMs > nowMs) {
            return makeRowText(label, formatDuration(targetAtMs - nowMs), "#ffb74d");
        } else {
            return makeRowText(label, "READY", "#69f0ae");
        }
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

    // =========================================================================
    // 6. SCHEDULE EXACT OS ALARMS & TORN-PDA-STYLE LIVE CARDS
    // =========================================================================
    public static void scheduleAlarmsStatic(Context context, SharedPreferences prefs, JSONObject t, long serverOffsetMs) {
        long nowServer = System.currentTimeMillis() + serverOffsetMs;

        // 1. Bars (Energy, Nerve, Health, Mood)
        JSONObject bars = t.optJSONObject("bars");
        if (bars != null) {
            JSONObject eBar = bars.optJSONObject("energy");
            int eVal = eBar != null ? eBar.optInt("value", 0) : 0;
            int eMax = eBar != null ? eBar.optInt("max", 100) : 100;
            handleTimerEntryStatic(context, prefs, 101, "notify_energy", "live_energy", false,
                    eBar != null ? eBar.optLong("fullAt", 0L) : 0L, nowServer, eVal, eMax,
                    "Regenerating Energy", "Currently at " + eVal + " / " + eMax + " Energy",
                    "⚡", "MAX",
                    "⚡ Energy Full", "Your Ashport Energy bar has reached maximum capacity!");

            JSONObject nBar = bars.optJSONObject("nerve");
            int nVal = nBar != null ? nBar.optInt("value", 0) : 0;
            int nMax = nBar != null ? nBar.optInt("max", 100) : 100;
            handleTimerEntryStatic(context, prefs, 102, "notify_nerve", "live_nerve", false,
                    nBar != null ? nBar.optLong("fullAt", 0L) : 0L, nowServer, nVal, nMax,
                    "Regenerating Nerve", "Currently at " + nVal + " / " + nMax + " Nerve",
                    "🔥", "MAX",
                    "🔥 Nerve Full", "Your Ashport Nerve bar is full and ready for crimes!");

            JSONObject hBar = bars.optJSONObject("health");
            int hVal = hBar != null ? hBar.optInt("value", 0) : 0;
            int hMax = hBar != null ? hBar.optInt("max", 100) : 100;
            handleTimerEntryStatic(context, prefs, 103, "notify_health", "live_health", false,
                    hBar != null ? hBar.optLong("fullAt", 0L) : 0L, nowServer, hVal, hMax,
                    "Healing Health", "Currently at " + hVal + " / " + hMax + " HP",
                    "❤️", "100%",
                    "❤️ Health Full", "Your Health bar is back to 100%.");

            JSONObject mBar = bars.optJSONObject("mood");
            int mVal = mBar != null ? mBar.optInt("value", 0) : 0;
            int mMax = mBar != null ? mBar.optInt("max", 100) : 100;
            handleTimerEntryStatic(context, prefs, 104, "notify_mood", "live_mood", false,
                    mBar != null ? mBar.optLong("fullAt", 0L) : 0L, nowServer, mVal, mMax,
                    "Recovering Mood", "Currently at " + mVal + " / " + mMax + " Mood",
                    "😊", "100%",
                    "😊 Mood Full", "Your Mood bar has fully regenerated.");
        }

        // 2. Cooldowns (Drug, Booster, Medical)
        JSONObject cds = t.optJSONObject("cooldowns");
        if (cds != null) {
            handleTimerEntryStatic(context, prefs, 105, "notify_drug", "live_drug", true,
                    cds.optLong("drug", 0L), nowServer, -1, -1,
                    "Drug Cooldown Active", "Waiting for substance effects to clear.",
                    "💊", "OK",
                    "💊 Drug Cooldown Ready", "Your drug cooldown has ended.");

            handleTimerEntryStatic(context, prefs, 106, "notify_booster", "live_booster", false,
                    cds.optLong("booster", 0L), nowServer, -1, -1,
                    "Booster Cooldown Active", "Waiting for booster timer to reset.",
                    "🚀", "OK",
                    "🚀 Booster Cooldown Ready", "Your booster cooldown has ended.");

            handleTimerEntryStatic(context, prefs, 107, "notify_medical", "live_medical", false,
                    cds.optLong("medical", 0L), nowServer, -1, -1,
                    "Medical Cooldown Active", "Medical supplies on cooldown.",
                    "🩹", "OK",
                    "🩹 Medical Cooldown Ready", "Your medical cooldown has cleared.");
        }

        // 3. Travel / Flight (Clean title without emoji + atmospheric flight flavor text + destination code badge)
        JSONObject travel = t.optJSONObject("travel");
        long landsAt = travel != null ? travel.optLong("landsAt", 0L) : 0L;
        String dest = travel != null ? travel.optString("name", travel.optString("dest", "Destination")) : "Destination";
        boolean returning = travel != null && "home".equalsIgnoreCase(travel.optString("dir"));
        String flightLiveTitle = returning ? "Returning to Ashport" : "Traveling to " + dest;
        String flightLiveSub = returning
                ? "Inbound flight from " + dest + ". Smooth skies ahead."
                : "Wheels up, en route to " + dest + ". Nothing to report.";
        String endBadge = returning ? "ASH" : makeDestCode(dest);

        handleTimerEntryStatic(context, prefs, 108, "notify_travel", "live_travel", true,
                landsAt, nowServer, -1, -1,
                flightLiveTitle, flightLiveSub,
                "✈️", endBadge,
                "✈️ Flight Landed", "You have landed in " + (returning ? "Ashport" : dest) + "!");

        // 4. Hospital / Jail Release
        long hospUntil = t.optLong("hospitalUntil", 0L);
        long jailUntil = t.optLong("jailUntil", 0L);
        long statusUntil = Math.max(hospUntil, jailUntil);
        boolean isJail = jailUntil > hospUntil;
        JSONObject statusObj = t.optJSONObject("status");
        String statusReason = statusObj != null ? statusObj.optString("reason", "") : "";

        handleTimerEntryStatic(context, prefs, 109, "notify_status", "live_status", true,
                statusUntil, nowServer, -1, -1,
                isJail ? "Currently in Jail" : "Currently Hospitalized",
                !statusReason.isEmpty() ? statusReason : (isJail ? "Serving sentence. Counting down release." : "Resting in medical ward until discharge."),
                isJail ? "⛓" : "🏥", "OUT",
                isJail ? "⛓ Released from Jail!" : "🏥 Released from Hospital!",
                "You are out and ready for action in Ashport.");

        // 5. Education Course
        handleTimerEntryStatic(context, prefs, 110, "notify_course", "live_course", true,
                t.optLong("courseEndsAt", 0L), nowServer, -1, -1,
                "Education Course in Progress", "Studying current curriculum module.",
                "🎓", "GRAD",
                "🎓 Course Completed", "Your Ashport education course has finished!");

        // 6. Bank Investment
        handleTimerEntryStatic(context, prefs, 111, "notify_bank", "live_bank", false,
                t.optLong("bankMaturesAt", 0L), nowServer, -1, -1,
                "Bank Investment Active", "Accruing compound interest until maturity.",
                "🏦", "$$$",
                "🏦 Bank Deposit Matured", "Your Ashport bank investment is ready to collect!");
    }

    private static String makeDestCode(String dest) {
        if (dest == null || dest.trim().isEmpty()) return "DST";
        String clean = dest.trim().toUpperCase(Locale.US);
        String[] words = clean.split("\\s+");
        if (words.length >= 2) {
            return "" + words[0].charAt(0) + words[1].charAt(0);
        }
        return clean.length() >= 3 ? clean.substring(0, 3) : clean;
    }

    private static void handleTimerEntryStatic(Context context, SharedPreferences prefs, int notifId,
                                               String alertPrefKey, String livePrefKey, boolean defaultLive,
                                               long targetServerMs, long nowServerMs,
                                               int barVal, int barMax,
                                               String liveTitle, String liveSubtitle,
                                               String trackerSymbol, String endBadgeText,
                                               String finishTitle, String finishMessage) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (am == null || nm == null) return;

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
            return;
        }

        long delayMs = targetServerMs - nowServerMs;
        long triggerLocalMs = System.currentTimeMillis() + delayMs;

        long prevTarget = prefs.getLong("scheduled_target_" + notifId, 0L);
        long startServerMs = prefs.getLong("start_server_ms_" + notifId, nowServerMs);
        if (Math.abs(targetServerMs - prevTarget) > 5000L || startServerMs >= targetServerMs) {
            startServerMs = nowServerMs;
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
            int progressPct;
            if (barMax > 0 && barVal >= 0) {
                progressPct = Math.min(100, Math.max(0, (barVal * 100) / barMax));
            } else {
                long totalDur = Math.max(1L, targetServerMs - startServerMs);
                long elapsed = Math.max(0L, nowServerMs - startServerMs);
                progressPct = (int) Math.min(100L, (elapsed * 100L) / totalDur);
            }
            postOngoingChronometerNotification(context, nm, notifId, notifId + 1000,
                    triggerLocalMs, progressPct, liveTitle, liveSubtitle, trackerSymbol, endBadgeText);
        } else {
            nm.cancel(notifId + 1000);
        }
    }

    private static void postOngoingChronometerNotification(Context context, NotificationManager nm,
                                                           int baseTimerId, int liveNotifId, long triggerLocalMs,
                                                           int progressPct, String title, String subtitle,
                                                           String trackerSymbol, String endBadgeText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Clean up old low-importance channel so Android promotes the card
            nm.deleteNotificationChannel("ashport_live_chronometer_channel");

            NotificationChannel liveChannel = new NotificationChannel(
                    TimerAlarmReceiver.LIVE_CHANNEL_ID,
                    "Ashport Live Activity Cards",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            liveChannel.setDescription("Promoted live countdown cards with visual progress tracker");
            liveChannel.setSound(null, null);
            liveChannel.enableVibration(false);
            liveChannel.setShowBadge(false);
            liveChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(liveChannel);
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                liveNotifId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String etaStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(triggerLocalMs));

        // Render the custom Torn-PDA-style visual progress bitmap
        boolean isFlight = (baseTimerId == 108);
        Bitmap trackBitmap = renderTornStyleTrackBitmap(progressPct, isFlight, trackerSymbol, endBadgeText);

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.notif_live_timer);
        rv.setTextViewText(R.id.notif_title, title);
        rv.setTextViewText(R.id.notif_subtitle, subtitle);
        rv.setImageViewBitmap(R.id.notif_progress_img, trackBitmap);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, TimerAlarmReceiver.LIVE_CHANNEL_ID)
                : new Notification.Builder(context);

        int smallIconRes = isFlight
                ? android.R.drawable.ic_menu_send
                : (baseTimerId == 109 ? android.R.drawable.ic_menu_myplaces : android.R.drawable.ic_popup_sync);

        builder.setSmallIcon(smallIconRes)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSubText("ETA " + etaStr)
                .setWhen(triggerLocalMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setChronometerCountDown(true);
            builder.setStyle(new Notification.DecoratedCustomViewStyle());
            builder.setCustomContentView(rv);
            builder.setCustomBigContentView(rv);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(Notification.CATEGORY_PROGRESS)
                   .setVisibility(Notification.VISIBILITY_PUBLIC)
                   .setColor(Color.parseColor("#4a6583")); // Torn PDA slate-blue accent circle
        }

        nm.notify(liveNotifId, builder.build());
    }

    // Draws the origin circle ('A'), thick filled blue bar, thin remaining line, moving airplane/tracker, and destination badge
    private static Bitmap renderTornStyleTrackBitmap(int progressPct, boolean isFlight, String trackerSymbol, String endBadgeText) {
        int width = 900;
        int height = 96;
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cy = height / 2f;
        float startX = 44f;
        float endX = width - 44f;
        float trackLeft = startX + 38f;
        float trackRight = endX - 38f;
        float clampedPct = Math.max(0, Math.min(100, progressPct)) / 100f;
        float curX = trackLeft + (trackRight - trackLeft) * clampedPct;

        // 1. Origin Badge Circle ('A' for Ashport, styled like Torn's 'T' badge)
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#cfd8dc"));
        c.drawCircle(startX, cy, 28f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(Color.parseColor("#90a4ae"));
        c.drawCircle(startX, cy, 28f, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor("#102027"));
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(34f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float textOffsetY = -(fm.ascent + fm.descent) / 2f;
        c.drawText("A", startX, cy + textOffsetY, p);

        // 2. Remaining Track Line (Thin slate-grey line to destination)
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(5f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Color.parseColor("#8ea1b2"));
        c.drawLine(curX, cy, trackRight, cy, p);

        // 3. Completed Progress Segment (Thick deep-blue capsule bar from origin to current position)
        if (curX > trackLeft + 2f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(15f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.parseColor("#39557a"));
            c.drawLine(trackLeft, cy, curX, cy, p);
        }

        // 4. Destination Badge Circle on the Right
        p.setStyle(Paint.Style.FILL);
        p.setColor(isFlight ? Color.parseColor("#c62828") : Color.parseColor("#37474f"));
        c.drawCircle(endX, cy, 28f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(Color.parseColor("#eceff1"));
        c.drawCircle(endX, cy, 28f, p);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setTextSize(19f);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        Paint.FontMetrics fmEnd = p.getFontMetrics();
        c.drawText(endBadgeText, endX, cy - (fmEnd.ascent + fmEnd.descent) / 2f, p);

        // 5. Moving Tracker Icon at Current Progress Point (Blue Airplane silhouette for flights)
        if (isFlight) {
            drawBlueAirplane(c, curX, cy);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.parseColor("#039be5"));
            c.drawCircle(curX, cy, 22f, p);
            p.setTextSize(26f);
            Paint.FontMetrics fmTr = p.getFontMetrics();
            c.drawText(trackerSymbol, curX, cy - (fmTr.ascent + fmTr.descent) / 2f, p);
        }

        return bmp;
    }

    private static void drawBlueAirplane(Canvas c, float cx, float cy) {
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setStyle(Paint.Style.FILL);
        ap.setColor(Color.parseColor("#0095ff"));

        // Fuselage
        RectF body = new RectF(cx - 28f, cy - 6f, cx + 28f, cy + 6f);
        c.drawRoundRect(body, 6f, 6f, ap);

        // Swept Main Wings
        Path wings = new Path();
        wings.moveTo(cx + 6f, cy);
        wings.lineTo(cx - 10f, cy - 34f);
        wings.lineTo(cx - 18f, cy - 34f);
        wings.lineTo(cx - 6f, cy);
        wings.lineTo(cx - 18f, cy + 34f);
        wings.lineTo(cx - 10f, cy + 34f);
        wings.close();
        c.drawPath(wings, ap);

        // Tail Fins
        Path tail = new Path();
        tail.moveTo(cx - 18f, cy);
        tail.lineTo(cx - 28f, cy - 18f);
        tail.lineTo(cx - 33f, cy - 18f);
        tail.lineTo(cx - 25f, cy);
        tail.lineTo(cx - 33f, cy + 18f);
        tail.lineTo(cx - 28f, cy + 18f);
        tail.close();
        c.drawPath(tail, ap);
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
