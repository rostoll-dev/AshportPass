package com.rostoll.ashportpda;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class ShortcutManager {
    public interface ShortcutListener {
        void onNavigate(String url, boolean newTab);
    }

    private final Context context;
    private final SharedPreferences prefs;

    public ShortcutManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("AshportPDA_Shortcuts", Context.MODE_PRIVATE);
        initDefaultsIfNeeded();
    }

    private void initDefaultsIfNeeded() {
        if (!prefs.contains("shortcuts_json")) {
            try {
                JSONArray arr = new JSONArray();
                arr.put(new JSONObject().put("title", "Ashport Home").put("url", "https://ashport.net"));
                prefs.edit().putString("shortcuts_json", arr.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    public JSONArray getShortcuts() {
        try {
            return new JSONArray(prefs.getString("shortcuts_json", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public void saveShortcuts(JSONArray array) {
        prefs.edit().putString("shortcuts_json", array.toString()).apply();
    }

    public void showSaveShortcutDialog(String currentTitle, String currentUrl) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.parseColor("#25282b"));

        TextView labelName = new TextView(context);
        labelName.setText("Shortcut Name");
        labelName.setTextColor(Color.parseColor("#b0bec5"));
        layout.addView(labelName);

        EditText nameInput = new EditText(context);
        nameInput.setText(currentTitle != null && !currentTitle.isEmpty() ? currentTitle : "Ashport Page");
        nameInput.setTextColor(Color.WHITE);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(nameInput);

        TextView labelUrl = new TextView(context);
        labelUrl.setText("URL");
        labelUrl.setTextColor(Color.parseColor("#b0bec5"));
        labelUrl.setPadding(0, dp(12), 0, 0);
        layout.addView(labelUrl);

        EditText urlInput = new EditText(context);
        urlInput.setText(currentUrl);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        layout.addView(urlInput);

        new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Save as Shortcut")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    String title = nameInput.getText().toString().trim();
                    String url = urlInput.getText().toString().trim();
                    if (title.isEmpty() || url.isEmpty()) return;
                    try {
                        JSONArray arr = getShortcuts();
                        arr.put(new JSONObject().put("title", title).put("url", url));
                        saveShortcuts(arr);
                        Toast.makeText(context, "Shortcut saved", Toast.LENGTH_SHORT).show();
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void showBrowseShortcutsDialog(ShortcutListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        ScrollView scroll = new ScrollView(context);
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        container.setPadding(pad, pad, pad, pad);
        container.setBackgroundColor(Color.parseColor("#2b2e31"));
        scroll.addView(container);

        AlertDialog dialog = builder.setTitle("Saved Shortcuts")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        populateShortcutsList(container, dialog, listener);
        dialog.show();
    }

    private void populateShortcutsList(LinearLayout container, AlertDialog dialog, ShortcutListener listener) {
        container.removeAllViews();
        JSONArray arr = getShortcuts();
        if (arr.length() == 0) {
            TextView empty = new TextView(context);
            empty.setText("No shortcuts saved yet.");
            empty.setTextColor(Color.LTGRAY);
            container.addView(empty);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            final int index = i;
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            final String title = obj.optString("title", "Shortcut");
            final String url = obj.optString("url", "https://ashport.net");

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);
            row.setLayoutParams(rowLp);

            Button openBtn = new Button(context);
            openBtn.setText(title);
            openBtn.setTextColor(Color.WHITE);
            openBtn.setAllCaps(false);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#5c7c8e"));
            bg.setCornerRadius(dp(6));
            openBtn.setBackground(bg);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            btnLp.rightMargin = dp(6);
            openBtn.setLayoutParams(btnLp);
            openBtn.setOnClickListener(v -> {
                dialog.dismiss();
                listener.onNavigate(url, false);
            });
            openBtn.setOnLongClickListener(v -> {
                dialog.dismiss();
                listener.onNavigate(url, true);
                return true;
            });

            Button delBtn = new Button(context);
            delBtn.setText("✕");
            delBtn.setTextColor(Color.parseColor("#ff6b6b"));
            GradientDrawable delBg = new GradientDrawable();
            delBg.setColor(Color.parseColor("#3a3d40"));
            delBg.setCornerRadius(dp(6));
            delBtn.setBackground(delBg);
            delBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
            delBtn.setOnClickListener(v -> {
                JSONArray updated = new JSONArray();
                for (int j = 0; j < arr.length(); j++) {
                    if (j != index) updated.put(arr.optJSONObject(j));
                }
                saveShortcuts(updated);
                populateShortcutsList(container, dialog, listener);
            });

            row.addView(openBtn);
            row.addView(delBtn);
            container.addView(row);
        }

        TextView hint = new TextView(context);
        hint.setText("Tip: Tap to open in current tab, long-press to open in a new tab.");
        hint.setTextColor(Color.parseColor("#90a4ae"));
        hint.setTextSize(11f);
        hint.setPadding(0, dp(8), 0, 0);
        container.addView(hint);
    }

    private int dp(int val) {
        return Math.round(val * context.getResources().getDisplayMetrics().density);
    }
}
