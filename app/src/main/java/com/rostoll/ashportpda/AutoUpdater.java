package com.rostoll.ashportpda;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AutoUpdater {
    public static final String GITHUB_REPO = "rostoll-dev/AshportPass";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AutoUpdater(Activity activity) {
        this.activity = activity;
    }

    public String getCurrentVersionName() {
        try {
            PackageInfo pInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0.0";
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    public void checkForUpdates(boolean manualCheck) {
        if (manualCheck) {
            Toast.makeText(activity, "Checking GitHub for Ashport Pass updates...", Toast.LENGTH_SHORT).show();
        }

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(LATEST_RELEASE_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "AshportPass-Android-Updater");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int code = conn.getResponseCode();
                if (code != 200) {
                    if (manualCheck) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "No newer release found on GitHub (HTTP " + code + ")", Toast.LENGTH_SHORT).show());
                    }
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.optString("tag_name", "").replaceFirst("^[vV]", "").trim();
                String changelog = release.optString("body", "Bug fixes and improvements.");
                String apkDownloadUrl = null;

                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset != null && asset.optString("name", "").toLowerCase().endsWith(".apk")) {
                            apkDownloadUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }

                String currentVer = getCurrentVersionName().replaceFirst("^[vV]", "").trim();
                if (apkDownloadUrl != null && isNewerVersion(tagName, currentVer)) {
                    final String finalUrl = apkDownloadUrl;
                    activity.runOnUiThread(() -> showUpdateDialog(tagName, currentVer, changelog, finalUrl));
                } else if (manualCheck) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Ashport Pass v" + currentVer + " is up to date!", Toast.LENGTH_SHORT).show());
                }

            } catch (Exception e) {
                if (manualCheck) {
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Update check failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private boolean isNewerVersion(String remoteVer, String localVer) {
        if (remoteVer.isEmpty() || remoteVer.equals(localVer)) return false;
        String cleanRemote = remoteVer.split("-")[0];
        String cleanLocal = localVer.split("-")[0];
        String[] rParts = cleanRemote.split("\\.");
        String[] lParts = cleanLocal.split("\\.");
        int len = Math.max(rParts.length, lParts.length);
        for (int i = 0; i < len; i++) {
            int r = i < rParts.length ? parseSafeInt(rParts[i]) : 0;
            int l = i < lParts.length ? parseSafeInt(lParts[i]) : 0;
            if (r > l) return true;
            if (r < l) return false;
        }
        return false;
    }

    private int parseSafeInt(String s) {
        try {
            String base = s.split("-")[0].replaceAll("[^0-9]", "");
            return base.isEmpty() ? 0 : Integer.parseInt(base);
        } catch (Exception e) {
            return 0;
        }
    }

    private void showUpdateDialog(String newVer, String currentVer, String notes, String apkUrl) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🚀 Ashport Pass v" + newVer + " Available!")
                .setMessage("Installed: v" + currentVer + "\nNew Version: v" + newVer + "\n\nRelease Notes:\n" + notes)
                .setPositiveButton("Download & Update", (d, w) -> downloadAndInstallApk(apkUrl, newVer))
                .setNegativeButton("Later", null)
                .show();
    }

    private void downloadAndInstallApk(String apkUrl, String version) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, "Allow 'Install unknown apps' for Ashport Pass, then tap Update again", Toast.LENGTH_LONG).show();
                Intent permIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(permIntent);
                return;
            }
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView statusTv = new TextView(activity);
        statusTv.setText("Downloading AshportPass v" + version + "... 0%");
        statusTv.setTextColor(Color.WHITE);
        statusTv.setPadding(0, pad / 2, 0, 0);
        box.addView(statusTv);

        AlertDialog progressDialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Updating Ashport Pass")
                .setView(box)
                .setCancelable(false)
                .create();
        progressDialog.show();

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String currentUrl = apkUrl;
                for (int redirects = 0; redirects < 5; redirects++) {
                    conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", "AshportPass-Android-Updater");
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);

                    int code = conn.getResponseCode();
                    if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null || loc.isEmpty()) break;
                        currentUrl = loc;
                    } else {
                        break;
                    }
                }

                int finalCode = conn.getResponseCode();
                if (finalCode < 200 || finalCode >= 300) {
                    throw new Exception("Server returned HTTP " + finalCode);
                }

                int totalBytes = conn.getContentLength();
                File dir = activity.getExternalCacheDir() != null ? activity.getExternalCacheDir() : activity.getCacheDir();
                File outFile = new File(dir, "AshportPass-update.apk");
                if (outFile.exists()) outFile.delete();

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int lastPct = -1;
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                        downloaded += read;
                        if (totalBytes > 0) {
                            final int pct = (int) ((downloaded * 100L) / totalBytes);
                            if (pct != lastPct) {
                                lastPct = pct;
                                activity.runOnUiThread(() -> {
                                    bar.setProgress(pct);
                                    statusTv.setText("Downloading AshportPass v" + version + "... " + pct + "%");
                                });
                            }
                        }
                    }
                    fos.flush();
                }

                if (!outFile.exists() || outFile.length() < 10240) {
                    throw new Exception("Downloaded APK is incomplete (" + outFile.length() + " bytes)");
                }

                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    triggerPackageInstaller(outFile);
                });

            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(activity, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void triggerPackageInstaller(File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile
            );
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(installIntent);
        } catch (Exception e) {
            Toast.makeText(activity, "Installer error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
