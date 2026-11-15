package com.rostoll.ashportpda;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiTimerManager {
    public static final String PREFS_CACHE = "AshportPDA_TimersCache";
    private static final int HEARTBEAT_ALARM_ID = 9999;
    private static final long HEARTBEAT_INTERVAL_MS = 5L * 60L * 1000L; // 15 minutes

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

    /**
     * Called automatically when WebView pages finish loading or when the app is minimized.
     * Debounced to avoid hitting rate limits while clicking rapidly.
     */
    public void syncIfNeededOnActivity(long minIntervalMs) {
        if (!keyStore.hasApiKey() || isFetching) return;
        if (System.currentTimeMillis() - lastFetchLocalMs >= minIntervalMs) {
            syncTimersNow(false);
        }
    }

    // =========================================================================
    // 1. BUILD THE HOMEPAGE TIMERS DASHBOARD VIEW
    // =========================================================================
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
        syncStatusText.setPadding(0, dp(4), 0, dp(10));
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
                playerInfo = kName + (pid > 0 ? " [#" + pid + "]" : "") + " • " + rem + "/60 req • Bg Sync ON";
            }
            keyStatusBadge.setText("ENCRYPTED");
            keyStatusBadge.setTextColor(Color.parseColor("#69f0ae"));
            keyStatusBadge.setBackground(makePillDrawable("#1b3b2b"));
            syncStatusText.setText(isFetching ? "Syncing with ashport.net..." : playerInfo);
        }
    }

    // =========================================================================
    // 2. SECURE API KEY INPUT MODAL
    // =========================================================================
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
                        cancelBackgroundHeartbeat(activity);
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
                    cancelBackgroundHeartbeat(activity);
                    updateHeaderStatus();
                    renderTimersContent();
                    Toast.makeText(activity, "API Key removed from vault", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================================================
    // 3. NOTIFICATION TOGGLES MODAL
    // =========================================================================
    public void showNotificationSettingsModal() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.parseColor("#162634"));
        scroll.addView(layout);

        String[][] items = {
                {"notify_energy", "⚡ Energy Bar Full"},
                {"notify_nerve", "🔥 Nerve Bar Full"},
                {"notify_health", "❤️ Health Bar Full"},
                {"notify_mood", "😊 Mood Bar Full"},
                {"notify_drug", "💊 Drug Cooldown Ended"},
                {"notify_booster", "🚀 Booster Cooldown Ended"},
                {"notify_medical", "🩹 Medical Cooldown Ended"},
                {"notify_travel", "✈️ Flight Landed"},
                {"notify_status", "🏥 Hospital / Jail Release"},
                {"notify_course", "🎓 Education Course Finished"},
                {"notify_bank", "🏦 Bank Investment Matured"}
        };

        for (String[] item : items) {
            final String prefKey = item[0];
            Switch sw = new Switch(activity);
            sw.setText(item[1]);
            sw.setTextColor(Color.WHITE);
            sw.setTextSize(14f);
            sw.setPadding(0, dp(10), 0, dp(10));
            sw.setChecked(prefs.getBoolean(prefKey, true));
            sw.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(prefKey, checked).apply());
            layout.addView(sw);
        }

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🔔 Timer Notification Alerts")
                .setView(scroll)
                .setPositiveButton("Apply & Reschedule", (d, w) -> {
                    if (lastTimersJson != null) {
                        scheduleAlarmsStatic(activity, prefs, lastTimersJson, serverTimeOffsetMs);
                        scheduleBackgroundHeartbeat(activity);
                        Toast.makeText(activity, "Exact alarms updated!", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // =========================================================================
    // 4. FOREGROUND & HEADLESS BACKGROUND NETWORK SYNC
    // =========================================================================
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
                        Toast.makeText(activity, "Ashport timers & exact alarms synced!", Toast.LENGTH_SHORT).show();
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

    /**
     * Runs completely headless when the app is closed (triggered by 15-min heartbeat or phone boot).
     */
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
            // If offline during heartbeat, re-ensure existing cached timers are scheduled
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

    // =========================================================================
    // 5. RENDER LIVE COUNTDOWNS ON THE HUB SCREEN
    // =========================================================================
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
    // 6. EXACT OS ALARMS & 15-MINUTE SELF-SUSTAINING HEARTBEAT
    // =========================================================================
    public static void scheduleAlarmsStatic(Context context, SharedPreferences prefs, JSONObject t, long serverOffsetMs) {
        long nowServer = System.currentTimeMillis() + serverOffsetMs;

        JSONObject bars = t.optJSONObject("bars");
        if (bars != null) {
            scheduleSingleAlarmStatic(context, prefs, 101, "notify_energy",
                    bars.optJSONObject("energy") != null ? bars.optJSONObject("energy").optLong("fullAt", 0L) : 0L,
                    nowServer, "⚡ Energy Full", "Your Ashport Energy bar has reached maximum capacity!");
            scheduleSingleAlarmStatic(context, prefs, 102, "notify_nerve",
                    bars.optJSONObject("nerve") != null ? bars.optJSONObject("nerve").optLong("fullAt", 0L) : 0L,
                    nowServer, "🔥 Nerve Full", "Your Ashport Nerve bar is full and ready for crimes!");
            scheduleSingleAlarmStatic(context, prefs, 103, "notify_health",
                    bars.optJSONObject("health") != null ? bars.optJSONObject("health").optLong("fullAt", 0L) : 0L,
                    nowServer, "❤️ Health Full", "Your Health bar is back to 100%.");
            scheduleSingleAlarmStatic(context, prefs, 104, "notify_mood",
                    bars.optJSONObject("mood") != null ? bars.optJSONObject("mood").optLong("fullAt", 0L) : 0L,
                    nowServer, "😊 Mood Full", "Your Mood bar has fully regenerated.");
        }

        JSONObject cds = t.optJSONObject("cooldowns");
        if (cds != null) {
            scheduleSingleAlarmStatic(context, prefs, 105, "notify_drug", cds.optLong("drug", 0L),
                    nowServer, "💊 Drug Cooldown Ready", "Your drug cooldown has ended.");
            scheduleSingleAlarmStatic(context, prefs, 106, "notify_booster", cds.optLong("booster", 0L),
                    nowServer, "🚀 Booster Cooldown Ready", "Your booster cooldown has ended.");
            scheduleSingleAlarmStatic(context, prefs, 107, "notify_medical", cds.optLong("medical", 0L),
                    nowServer, "🩹 Medical Cooldown Ready", "Your medical cooldown has cleared.");
        }

        JSONObject travel = t.optJSONObject("travel");
        long landsAt = travel != null ? travel.optLong("landsAt", 0L) : 0L;
        String dest = travel != null ? travel.optString("name", "your destination") : "your destination";
        scheduleSingleAlarmStatic(context, prefs, 108, "notify_travel", landsAt,
                nowServer, "✈️ Flight Landed", "You have landed in " + dest + "!");

        long hospRelease = Math.max(t.optLong("hospitalUntil", 0L), t.optLong("jailUntil", 0L));
        scheduleSingleAlarmStatic(context, prefs, 109, "notify_status", hospRelease,
                nowServer, "🏥 Released!", "You are out of hospital/jail in Ashport.");

        scheduleSingleAlarmStatic(context, prefs, 110, "notify_course", t.optLong("courseEndsAt", 0L),
                nowServer, "🎓 Course Completed", "Your Ashport education course has finished!");

        scheduleSingleAlarmStatic(context, prefs, 111, "notify_bank", t.optLong("bankMaturesAt", 0L),
                nowServer, "🏦 Bank Deposit Matured", "Your Ashport bank investment is ready to collect!");
    }

    private static void scheduleSingleAlarmStatic(Context context, SharedPreferences prefs, int notifId, String prefKey,
                                                  long targetServerMs, long nowServerMs, String title, String message) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, TimerAlarmReceiver.class);
        intent.setAction(TimerAlarmReceiver.ACTION_TIMER_ALARM);
        intent.putExtra("notif_id", notifId);
        intent.putExtra("title", title);
        intent.putExtra("message", message);

        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                notifId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        boolean enabled = prefs.getBoolean(prefKey, true);
        // Only schedule if target is in the future (and wasn't already fired for this exact timestamp)
        long lastScheduledTarget = prefs.getLong("scheduled_target_" + notifId, 0L);
        if (!enabled || targetServerMs <= nowServerMs) {
            am.cancel(pi);
            if (targetServerMs <= nowServerMs) {
                prefs.edit().remove("scheduled_target_" + notifId).apply();
            }
            return;
        }

        long delayMs = targetServerMs - nowServerMs;
        long triggerLocalMs = System.currentTimeMillis() + delayMs;
        prefs.edit().putLong("scheduled_target_" + notifId, targetServerMs).apply();

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerLocalMs, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerLocalMs, pi);
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

    public static void cancelBackgroundHeartbeat(Context context) {
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
        am.cancel(pi);
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
