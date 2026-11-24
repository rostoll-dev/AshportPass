package com.rostoll.ashportpda;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GmBridge {
    private static final String TAG = "AshportPDA_JS";
    private final SharedPreferences prefs;
    private final WebView webView;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final SecureKeyStore secureKeyStore;

    public GmBridge(Context context, WebView webView) {
        this.prefs = context.getSharedPreferences("AshportPDA_GMStorage", Context.MODE_PRIVATE);
        this.webView = webView;
        this.secureKeyStore = new SecureKeyStore(context);
    }

    @JavascriptInterface
    public String getApiKey() {
        return secureKeyStore.getApiKey();
    }

    @JavascriptInterface
    public void setValue(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public String getValue(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    @JavascriptInterface
    public void deleteValue(String key) {
        prefs.edit().remove(key).apply();
    }

    @JavascriptInterface
    public String listValues() {
        Map<String, ?> all = prefs.getAll();
        return new JSONArray(all.keySet()).toString();
    }

    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, message);
    }

    @JavascriptInterface
    public void showToast(final String message) {
        webView.post(() -> android.widget.Toast.makeText(
                webView.getContext(),
                message,
                android.widget.Toast.LENGTH_LONG
        ).show());
    }

    @JavascriptInterface
    public void xmlHttpRequest(final String requestId, final String method, final String urlStr,
                               final String headersJson, final String bodyData) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method != null ? method.toUpperCase() : "GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject headers = new JSONObject(headersJson);
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        conn.setRequestProperty(k, headers.optString(k));
                    }
                }

                if (bodyData != null && !bodyData.isEmpty() &&
                        ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(bodyData.getBytes(StandardCharsets.UTF_8));
                    }
                }

                int status = conn.getResponseCode();
                InputStream is = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder responseText = new StringBuilder();
                if (is != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            responseText.append(line).append("\n");
                        }
                    }
                }

                JSONObject result = new JSONObject();
                result.put("status", status);
                result.put("responseText", responseText.toString());
                result.put("finalUrl", conn.getURL().toString());

                final String payload = result.toString();
                webView.post(() -> webView.evaluateJavascript(
                        "window.__gmResolveXhr(" + JSONObject.quote(requestId) + ", true, " + payload + ");", null));

            } catch (Exception e) {
                try {
                    JSONObject err = new JSONObject();
                    err.put("error", e.getMessage());
                    final String payload = err.toString();
                    webView.post(() -> webView.evaluateJavascript(
                            "window.__gmResolveXhr(" + JSONObject.quote(requestId) + ", false, " + payload + ");", null));
                } catch (Exception ignored) {}
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }
}
