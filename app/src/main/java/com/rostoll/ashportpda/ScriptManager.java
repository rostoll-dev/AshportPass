package com.rostoll.ashportpda;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScriptManager {
    public static class UserScript {
        public String id;
        public String name;
        public String description;
        public String runAt;
        public String matchPattern;
        public String code;
        public boolean enabled;
    }

    public interface OnScriptsChangedListener {
        void onScriptsChanged();
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private static final String KEY_LAST_UPDATE_CHECK = "last_script_update_check_ms";
    private static final long UPDATE_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private final OnScriptsChangedListener listener;

    public ScriptManager(Activity activity, OnScriptsChangedListener listener) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("AshportPDA_UserScripts", Context.MODE_PRIVATE);
        this.listener = listener;
        seedBundledAssetsIfNeeded();
    }

    private void seedBundledAssetsIfNeeded() {
        if (prefs.getBoolean("bundled_seeded_v1", false)) return;
        try {
            JSONArray arr = getScriptsJson();
            String[] files = activity.getAssets().list("scripts");
            if (files != null) {
                Arrays.sort(files);
                for (String filename : files) {
                    if (!filename.endsWith(".js")) continue;
                    String code = readAsset("scripts/" + filename);
                    if (!code.isEmpty()) {
                        arr.put(createScriptJson(UUID.randomUUID().toString(), filename, code, true));
                    }
                }
            }
            prefs.edit()
                    .putString("scripts_list", arr.toString())
                    .putBoolean("bundled_seeded_v1", true)
                    .apply();
        } catch (Exception ignored) {}
    }

    private String readAsset(String path) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = activity.getAssets().open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private JSONObject createScriptJson(String id, String fallbackName, String code, boolean enabled) throws Exception {
        String name = extractMeta(code, "name", fallbackName);
        String desc = extractMeta(code, "description", "Custom Ashport userscript");
        String runAt = extractMeta(code, "run-at", "document-end");
        String match = extractMeta(code, "match", extractMeta(code, "include", "*://*.ashport.net/*"));
        return new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("description", desc)
                .put("runAt", runAt)
                .put("match", match)
                .put("code", code)
                .put("enabled", enabled);
    }

    private String extractMeta(String code, String key, String fallback) {
        Pattern p = Pattern.compile("@" + key + "\\s+(.+)");
        Matcher m = p.matcher(code);
        if (m.find() && m.group(1) != null) {
            return m.group(1).trim();
        }
        return fallback;
    }

    private JSONArray getScriptsJson() {
        try {
            return new JSONArray(prefs.getString("scripts_list", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveScriptsJson(JSONArray arr) {
        prefs.edit().putString("scripts_list", arr.toString()).apply();
        if (listener != null) listener.onScriptsChanged();
    }

    public List<UserScript> getAllScripts() {
        List<UserScript> list = new ArrayList<>();
        JSONArray arr = getScriptsJson();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            UserScript s = new UserScript();
            s.id = o.optString("id");
            s.name = o.optString("name", "Unnamed Script");
            s.description = o.optString("description", "");
            s.runAt = o.optString("runAt", "document-end");
            s.code = o.optString("code", "");
            s.matchPattern = o.optString("match", extractMeta(s.code, "match", "*"));
            s.enabled = o.optBoolean("enabled", true);
            list.add(s);
        }
        return list;
    }

    public List<UserScript> getEnabledScripts() {
        List<UserScript> enabled = new ArrayList<>();
        for (UserScript s : getAllScripts()) {
            if (s.enabled && !s.code.trim().isEmpty()) {
                enabled.add(s);
            }
        }
        return enabled;
    }

    public void showScriptManagerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b1926"));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.parseColor("#132738"));
        int pad = dp(14);
        header.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText("📜 User Scripts Manager");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        AlertDialog dialog = builder.setView(root).create();

        Button closeBtn = makeButton("✕ Close", "#3a4d5c");
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn);
        root.addView(header);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(pad, dp(10), pad, dp(10));

        LinearLayout listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(pad, dp(4), pad, pad);

        Button addCodeBtn = makeButton("＋ Paste .user.js", "#f2851d");
        LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lpLeft.rightMargin = dp(8);
        addCodeBtn.setLayoutParams(lpLeft);
        addCodeBtn.setOnClickListener(v -> showEditorDialog(null, () -> refreshListUI(listContainer)));

        Button addUrlBtn = makeButton("⬇ Install via URL", "#5c7c8e");
        addUrlBtn.setLayoutParams(new LinearLayout.LayoutParams(0, dp(42), 1f));
        addUrlBtn.setOnClickListener(v -> showInstallUrlDialog(() -> refreshListUI(listContainer)));

        actions.addView(addCodeBtn);
        actions.addView(addUrlBtn);
        root.addView(actions);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshListUI(listContainer);
        dialog.show();
        checkForScriptUpdates(false, () -> refreshListUI(listContainer));
    }

    private void refreshListUI(LinearLayout container) {
        container.removeAllViews();
        List<UserScript> scripts = getAllScripts();
        if (scripts.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("No userscripts installed. Tap '＋ Paste .user.js' or '⬇ Install via URL' above.");
            empty.setTextColor(Color.parseColor("#90a4ae"));
            empty.setPadding(dp(8), dp(20), dp(8), dp(20));
            container.addView(empty);
            return;
        }

        for (UserScript script : scripts) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(Color.parseColor("#152a3a"));
            cardBg.setStroke(dp(1), Color.parseColor("#29465b"));
            cardBg.setCornerRadius(dp(8));
            card.setBackground(cardBg);
            int pad = dp(12);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.bottomMargin = dp(10);
            card.setLayoutParams(cardLp);

            LinearLayout topRow = new LinearLayout(activity);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameTv = new TextView(activity);
            nameTv.setText(script.name);
            nameTv.setTextColor(Color.WHITE);
            nameTv.setTextSize(16f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            topRow.addView(nameTv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Switch toggle = new Switch(activity);
            toggle.setChecked(script.enabled);
            toggle.setOnCheckedChangeListener((btn, isChecked) -> setScriptEnabled(script.id, isChecked));
            topRow.addView(toggle);
            card.addView(topRow);

            TextView metaTv = new TextView(activity);
            String ver = extractMeta(script.code, "version", "");
            String verTag = ver.isEmpty() ? "" : "v" + ver + " • ";
            metaTv.setText(verTag + "@run-at " + script.runAt + (script.description.isEmpty() ? "" : " • " + script.description));
            metaTv.setTextColor(Color.parseColor("#90a4ae"));
            metaTv.setTextSize(12f);
            metaTv.setPadding(0, dp(4), 0, dp(10));
            card.addView(metaTv);

            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);

            Button editBtn = makeButton("✎ Edit Code", "#3a556a");
            LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            editLp.rightMargin = dp(8);
            editBtn.setLayoutParams(editLp);
            editBtn.setOnClickListener(v -> showEditorDialog(script, () -> refreshListUI(container)));

            Button delBtn = makeButton("🗑 Delete", "#6a2c2c");
            delBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(100), dp(36)));
            delBtn.setOnClickListener(v -> {
                deleteScript(script.id);
                refreshListUI(container);
            });

            btnRow.addView(editBtn);
            btnRow.addView(delBtn);
            card.addView(btnRow);

            container.addView(card);
        }
    }

    private void showEditorDialog(UserScript existing, Runnable onDone) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.parseColor("#1a2630"));

        EditText codeInput = new EditText(activity);
        codeInput.setTextColor(Color.parseColor("#e0f2f1"));
        codeInput.setHintTextColor(Color.parseColor("#607d8b"));
        codeInput.setTypeface(Typeface.MONOSPACE);
        codeInput.setTextSize(12f);
        codeInput.setGravity(Gravity.TOP | Gravity.START);
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        codeInput.setMinLines(14);
        codeInput.setMaxLines(22);

        if (existing != null) {
            codeInput.setText(existing.code);
        } else {
            codeInput.setText("// ==UserScript==\n// @name         New Ashport Script\n// @description  Custom helper\n// @match        https://ashport.net/*\n// @run-at       document-end\n// ==/UserScript==\n\nconsole.log('Ashport script running!');\n");
        }

        layout.addView(codeInput);

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(existing != null ? "Edit: " + existing.name : "New Userscript")
                .setView(layout)
                .setPositiveButton("Save & Apply", (d, w) -> {
                    String code = codeInput.getText().toString();
                    if (code.trim().isEmpty()) return;
                    saveOrUpdateScript(existing != null ? existing.id : null, code);
                    if (onDone != null) onDone.run();
                    Toast.makeText(activity, "Script saved (reload page to apply)", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showInstallUrlDialog(Runnable onDone) {
        EditText urlInput = new EditText(activity);
        urlInput.setHint("https://example.com/script.user.js");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.GRAY);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        LinearLayout box = new LinearLayout(activity);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, pad);
        box.addView(urlInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Install Userscript from URL")
                .setView(box)
                .setPositiveButton("Fetch & Install", (d, w) -> {
                    String urlStr = urlInput.getText().toString().trim();
                    if (urlStr.isEmpty()) return;
                    Toast.makeText(activity, "Fetching script...", Toast.LENGTH_SHORT).show();
                    Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                            conn.setConnectTimeout(12000);
                            conn.setReadTimeout(12000);
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                            }
                            final String code = sb.toString();
                            activity.runOnUiThread(() -> {
                                saveOrUpdateScript(null, code);
                                if (onDone != null) onDone.run();
                                Toast.makeText(activity, "Userscript installed!", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveOrUpdateScript(String existingId, String code) {
        try {
            JSONArray arr = getScriptsJson();
            if (existingId == null) {
                arr.put(createScriptJson(UUID.randomUUID().toString(), "Custom Script", code, true));
            } else {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null && existingId.equals(o.optString("id"))) {
                        boolean enabled = o.optBoolean("enabled", true);
                        arr.put(i, createScriptJson(existingId, o.optString("name", "Script"), code, enabled));
                        break;
                    }
                }
            }
            saveScriptsJson(arr);
        } catch (Exception ignored) {}
    }

    private void setScriptEnabled(String id, boolean enabled) {
        try {
            JSONArray arr = getScriptsJson();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    o.put("enabled", enabled);
                    break;
                }
            }
            saveScriptsJson(arr);
        } catch (Exception ignored) {}
    }

    private void deleteScript(String id) {
        JSONArray arr = getScriptsJson();
        JSONArray updated = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) {
                updated.put(o);
            }
        }
        saveScriptsJson(updated);
    }

    private Button makeButton(String text, String hexColor) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(13f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(hexColor));
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    public void checkForScriptUpdatesIfDue() {
        long lastCheck = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L);
        if (System.currentTimeMillis() - lastCheck >= UPDATE_CHECK_INTERVAL_MS) {
            checkForScriptUpdates(false, null);
        }
    }

    public void checkForScriptUpdates(boolean showNoUpdateToast, Runnable onUpdated) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONArray arr = getScriptsJson();
                int updatedCount = 0;

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;

                    String localCode = o.optString("code", "");
                    String localVer = extractMeta(localCode, "version", "");
                    String updateUrl = extractMeta(localCode, "updateURL", "");
                    String downloadUrl = extractMeta(localCode, "downloadURL", "");

                    if (updateUrl.isEmpty() && downloadUrl.isEmpty()) continue;
                    String checkUrl = !updateUrl.isEmpty() ? updateUrl : downloadUrl;
                    String fetchUrl = !downloadUrl.isEmpty() ? downloadUrl : updateUrl;

                    String metaText = httpGetText(checkUrl);
                    if (metaText == null || !metaText.contains("==UserScript==")) continue;

                    String remoteVer = extractMeta(metaText, "version", "");
                    if (remoteVer.isEmpty() || !isNewerVersion(remoteVer, localVer)) continue;

                    String newCode = checkUrl.equals(fetchUrl) ? metaText : httpGetText(fetchUrl);
                    if (newCode == null || !newCode.contains("==UserScript==")) continue;

                    String id = o.optString("id", UUID.randomUUID().toString());
                    String name = o.optString("name", "Script");
                    boolean enabled = o.optBoolean("enabled", true);
                    arr.put(i, createScriptJson(id, name, newCode, enabled));
                    updatedCount++;
                }

                prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();

                final int finalUpdated = updatedCount;
                final JSONArray finalArr = arr;
                activity.runOnUiThread(() -> {
                    if (finalUpdated > 0) {
                        saveScriptsJson(finalArr);
                        if (onUpdated != null) onUpdated.run();
                        String msg = finalUpdated == 1
                                ? "1 userscript updated (reload page to apply)"
                                : finalUpdated + " userscripts updated (reload page to apply)";
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
                    } else if (showNoUpdateToast) {
                        Toast.makeText(activity, "All userscripts are up to date", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    private String httpGetText(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isNewerVersion(String remote, String local) {
        if (local == null || local.isEmpty()) return true;
        String[] rParts = remote.replaceAll("[^0-9.]", "").split("\\.");
        String[] lParts = local.replaceAll("[^0-9.]", "").split("\\.");
        int len = Math.max(rParts.length, lParts.length);
        for (int i = 0; i < len; i++) {
            int r = (i < rParts.length && !rParts[i].isEmpty()) ? Integer.parseInt(rParts[i]) : 0;
            int l = (i < lParts.length && !lParts[i].isEmpty()) ? Integer.parseInt(lParts[i]) : 0;
            if (r > l) return true;
            if (r < l) return false;
        }
        return false;
    }

    private int dp(int val) {
        return Math.round(val * activity.getResources().getDisplayMetrics().density);
    }
}
