package com.rostoll.ashportpda;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.os.PowerManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://ashport.net";
    private static final int REQ_POST_NOTIFICATIONS = 2001;

    private static class BrowserTab {
        WebView webView;
        String title = "Ashport";
        String url = DEFAULT_URL;
        int zoom = 100;
        List<ScriptHandler> scriptHandlers = new ArrayList<>();
    }

    private final List<BrowserTab> tabs = new ArrayList<>();
    private int activeTabIndex = -1;

    private FrameLayout rootContainer;
    private LinearLayout hubView;
    private LinearLayout browserView;

    private TextView topTitleBox;
    private LinearLayout findBar;
    private EditText findInput;
    private FrameLayout webViewStack;
    private LinearLayout tabStrip;
    private TextView hubStatusSubtitle;
    private TextView linkCardTitle;
    private TextView linkCardSub;
    private LinearLayout linkCardContainer;

    private ScriptManager scriptManager;
    private ShortcutManager shortcutManager;
    private ApiTimerManager apiTimerManager;
    private AutoUpdater autoUpdater;
    private String cachedPolyfill = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(true);
        cachedPolyfill = readAssetFile("gm_polyfill.js");

        shortcutManager = new ShortcutManager(this);
        scriptManager = new ScriptManager(this, this::rebindScriptsAcrossAllTabs);
        apiTimerManager = new ApiTimerManager(this);
        autoUpdater = new AutoUpdater(this);

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(Color.parseColor("#0b1926"));

        hubView = buildHubScreen();
        browserView = buildBrowserScreen();

        rootContainer.addView(hubView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootContainer.addView(browserView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootContainer);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::handleBackNavigation
            );
        }

        String initialUrl = extractUrlFromIntent(getIntent());
        createNewTab(initialUrl != null ? initialUrl : DEFAULT_URL, true);
        showBrowser();

        // 1. Run sequential first-launch onboarding: Notifications first -> then Link Catching
        startOnboardingFlow();

        // 2. Check GitHub Releases for updates in the background
        autoUpdater.checkForUpdates(false);
    }

    // =========================================================================
    // SEQUENTIAL ONBOARDING (1. Notifications -> 2. Link Catching)
    // =========================================================================
    private void startOnboardingFlow() {
        updateLinkStatusCardUI(isAshportLinkHandlingEnabled());

        if (Build.VERSION.SDK_INT >= 33) {
            SharedPreferences prefs = getSharedPreferences("AshportPDA_AppPrefs", Context.MODE_PRIVATE);
            boolean askedNotif = prefs.getBoolean("prompted_notifications_v1", false);
            if (!askedNotif && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("prompted_notifications_v1", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS);
                return; // Wait for onRequestPermissionsResult before prompting Link Catching
            }
        }

        checkAndPromptLinkHandling(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIFICATIONS) {
            // Once the user answers the Notifications prompt, trigger the Link Catching check
            checkAndPromptLinkHandling(false);
        }
    }

    // =========================================================================
    // LINK CATCHING VERIFICATION & AUTO-HIDING HUB CARD
    // =========================================================================
    private boolean isAshportLinkHandlingEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                DomainVerificationManager dvm = getSystemService(DomainVerificationManager.class);
                if (dvm != null) {
                    DomainVerificationUserState state = dvm.getDomainVerificationUserState(getPackageName());
                    if (state != null && state.isLinkHandlingAllowed()) {
                        Map<String, Integer> hostMap = state.getHostToStateMap();
                        Integer hostState = hostMap.get("ashport.net");
                        return hostState != null &&
                                (hostState == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                                 hostState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED);
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void checkAndPromptLinkHandling(boolean forceDialog) {
        boolean enabled = isAshportLinkHandlingEnabled();
        updateLinkStatusCardUI(enabled);

        if (enabled) {
            promptBatteryOptimizationExemption();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("AshportPDA_AppPrefs", Context.MODE_PRIVATE);
        boolean alreadyPrompted = prefs.getBoolean("prompted_link_handling", false);
        if (!forceDialog && alreadyPrompted) return;

        prefs.edit().putBoolean("prompted_link_handling", true).apply();

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🔗 Catch Ashport Links")
                .setMessage("To make Ashport Pass automatically open ashport.net links from Discord and other apps:\n\n1. Tap 'Open Link Settings' below.\n2. Tap '+ Add link' (or Supported web addresses).\n3. Check both ashport.net and www.ashport.net.")
                .setPositiveButton("Open Link Settings", (d, w) -> openSystemLinkSettings())
                .setNegativeButton("Later", null)
                .show();
    }

    @SuppressLint("BatteryLife")
    private void promptBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                SharedPreferences prefs = getSharedPreferences("AshportPDA_AppPrefs", Context.MODE_PRIVATE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName()) && !prefs.getBoolean("prompted_battery_opt", false)) {
                    prefs.edit().putBoolean("prompted_battery_opt", true).apply();
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
    }

    private void openSystemLinkSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent intent = new Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
        } catch (Exception ignored) {}

        try {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(fallback);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open app link settings", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateLinkStatusCardUI(boolean enabled) {
        if (linkCardContainer == null || linkCardTitle == null || linkCardSub == null) return;

        // Hide the card completely once link catching is active
        if (enabled) {
            linkCardContainer.setVisibility(View.GONE);
            return;
        }

        linkCardContainer.setVisibility(View.VISIBLE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#132738"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(2), Color.parseColor("#ffb74d"));
        linkCardTitle.setText("⚠️  Enable Ashport Link Catching");
        linkCardSub.setText("Tap here to open Android's 'Open by default' screen and check ashport.net");
        linkCardContainer.setBackground(bg);
    }

    // =========================================================================
    // WELCOME / UTILITIES HUB SCREEN
    // =========================================================================
    private LinearLayout buildHubScreen() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0b1926"));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        content.setPadding(pad, dp(24), pad, pad);

        TextView badge = new TextView(this);
        badge.setText("⚓ ASHPORT COMPANION");
        badge.setTextColor(Color.parseColor("#f2851d"));
        badge.setTextSize(12f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(badge);

        TextView title = new TextView(this);
        title.setText("ASHPORT PASS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(2), 0, dp(2));
        content.addView(title);

        hubStatusSubtitle = new TextView(this);
        hubStatusSubtitle.setText("Syndicate Browser, Userscript Engine & Live Timers");
        hubStatusSubtitle.setTextColor(Color.parseColor("#90a4ae"));
        hubStatusSubtitle.setTextSize(13f);
        hubStatusSubtitle.setPadding(0, 0, 0, dp(16));
        content.addView(hubStatusSubtitle);

        // 1. Primary Action: Resume Browser
        content.addView(makeHubCard(
                "🌐  Open Ashport Browser",
                "Return to your active game tabs and live session",
                "#f2851d",
                v -> showBrowser()
        ));

        // 2. Dynamic Link Catching Warning Card (Auto-hidden once enabled)
        linkCardContainer = new LinearLayout(this);
        linkCardContainer.setOrientation(LinearLayout.VERTICAL);
        int cPad = dp(14);
        linkCardContainer.setPadding(cPad, cPad, cPad, cPad);
        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLp.bottomMargin = dp(12);
        linkCardContainer.setLayoutParams(linkLp);

        linkCardTitle = new TextView(this);
        linkCardTitle.setTextColor(Color.WHITE);
        linkCardTitle.setTextSize(15f);
        linkCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        linkCardContainer.addView(linkCardTitle);

        linkCardSub = new TextView(this);
        linkCardSub.setTextColor(Color.parseColor("#90a4ae"));
        linkCardSub.setTextSize(12f);
        linkCardSub.setPadding(0, dp(3), 0, 0);
        linkCardContainer.addView(linkCardSub);

        linkCardContainer.setOnClickListener(v -> openSystemLinkSettings());
        updateLinkStatusCardUI(isAshportLinkHandlingEnabled());
        content.addView(linkCardContainer);

        // 3. Native Built-in Timers Dashboard & Encrypted API Key Vault
        content.addView(apiTimerManager.buildDashboardView());

        // 4. Userscripts & Shortcuts Cards
        content.addView(makeHubCard(
                "📜  User Scripts Manager",
                "Toggle, edit, paste, or install .user.js scripts from URL",
                "#234156",
                v -> scriptManager.showScriptManagerDialog()
        ));

        content.addView(makeHubCard(
                "♡  Saved Shortcuts",
                "Quick-jump to your favorite Ashport city pages",
                "#234156",
                v -> shortcutManager.showBrowseShortcutsDialog((url, newTab) -> {
                    showBrowser();
                    if (newTab || getActiveTab() == null) {
                        createNewTab(url, true);
                    } else {
                        getActiveTab().webView.loadUrl(url);
                    }
                })
        ));

        // 5. App Updates Card
        content.addView(makeHubCard(
                "⬇  App Updates (v" + (autoUpdater != null ? autoUpdater.getCurrentVersionName() : "1.0.0") + ")",
                "Tap to check GitHub Releases for a newer Ashport Pass APK",
                "#234156",
                v -> autoUpdater.checkForUpdates(true)
        ));

        scroll.addView(content);
        layout.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layout;
    }

    private View makeHubCard(String heading, String subtext, String borderHex, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#132738"));
        bg.setStroke(dp(2), Color.parseColor(borderHex));
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextColor(Color.WHITE);
        h.setTextSize(15f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(h);

        TextView s = new TextView(this);
        s.setText(subtext);
        s.setTextColor(Color.parseColor("#90a4ae"));
        s.setTextSize(12f);
        s.setPadding(0, dp(3), 0, 0);
        card.addView(s);

        card.setOnClickListener(onClick);
        return card;
    }

    private LinearLayout buildBrowserScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#1a1c1e"));
        topBar.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView closeToHubBtn = new TextView(this);
        closeToHubBtn.setText("✕");
        closeToHubBtn.setTextColor(Color.parseColor("#d0d0d0"));
        closeToHubBtn.setTextSize(20f);
        closeToHubBtn.setGravity(Gravity.CENTER);
        closeToHubBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(38)));
        closeToHubBtn.setOnClickListener(v -> showHub());
        topBar.addView(closeToHubBtn);

        topTitleBox = new TextView(this);
        topTitleBox.setText("Ashport");
        topTitleBox.setTextColor(Color.parseColor("#e0e0e0"));
        topTitleBox.setTextSize(16f);
        topTitleBox.setSingleLine(true);
        topTitleBox.setEllipsize(TextUtils.TruncateAt.END);
        topTitleBox.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable dashedBox = new GradientDrawable();
        dashedBox.setColor(Color.parseColor("#141618"));
        dashedBox.setCornerRadius(dp(4));
        dashedBox.setStroke(dp(1), Color.parseColor("#555555"), dp(4), dp(3));
        topTitleBox.setBackground(dashedBox);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(6);
        titleLp.rightMargin = dp(6);
        topTitleBox.setLayoutParams(titleLp);
        topTitleBox.setOnClickListener(v -> showOptionsModal());
        topBar.addView(topTitleBox);

        TextView reloadBtn = new TextView(this);
        reloadBtn.setText("↻");
        reloadBtn.setTextColor(Color.parseColor("#d0d0d0"));
        reloadBtn.setTextSize(22f);
        reloadBtn.setGravity(Gravity.CENTER);
        reloadBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(38)));
        reloadBtn.setOnClickListener(v -> {
            BrowserTab active = getActiveTab();
            if (active != null) active.webView.reload();
        });
        topBar.addView(reloadBtn);
        root.addView(topBar);

        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setBackgroundColor(Color.parseColor("#25282b"));
        findBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        findBar.setVisibility(View.GONE);

        findInput = new EditText(this);
        findInput.setHint("Find in page...");
        findInput.setTextColor(Color.WHITE);
        findInput.setHintTextColor(Color.GRAY);
        findInput.setSingleLine(true);
        findInput.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        findInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                BrowserTab active = getActiveTab();
                if (active != null) active.webView.findAllAsync(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        findBar.addView(findInput);

        Button findPrev = makeSmallBarButton("▲");
        findPrev.setOnClickListener(v -> {
            BrowserTab active = getActiveTab();
            if (active != null) active.webView.findNext(false);
        });
        findBar.addView(findPrev);

        Button findNext = makeSmallBarButton("▼");
        findNext.setOnClickListener(v -> {
            BrowserTab active = getActiveTab();
            if (active != null) active.webView.findNext(true);
        });
        findBar.addView(findNext);

        Button findClose = makeSmallBarButton("✕");
        findClose.setOnClickListener(v -> {
            findBar.setVisibility(View.GONE);
            findInput.setText("");
            BrowserTab active = getActiveTab();
            if (active != null) active.webView.clearMatches();
        });
        findBar.addView(findClose);
        root.addView(findBar);

        webViewStack = new FrameLayout(this);
        root.addView(webViewStack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(Color.parseColor("#1e2022"));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabStrip = new LinearLayout(this);
        tabStrip.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabStrip);
        bottomBar.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView tabMenuBtn = new TextView(this);
        tabMenuBtn.setText("•••");
        tabMenuBtn.setTextColor(Color.parseColor("#90a4ae"));
        tabMenuBtn.setTextSize(16f);
        tabMenuBtn.setGravity(Gravity.CENTER);
        tabMenuBtn.setBackground(makeBorderedCell("#26292c", "#3e4347"));
        tabMenuBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        tabMenuBtn.setOnClickListener(v -> showTabActionsMenu());
        bottomBar.addView(tabMenuBtn);

        TextView newTabBtn = new TextView(this);
        newTabBtn.setText("⊕");
        newTabBtn.setTextColor(Color.parseColor("#b0bec5"));
        newTabBtn.setTextSize(22f);
        newTabBtn.setGravity(Gravity.CENTER);
        newTabBtn.setBackground(makeBorderedCell("#26292c", "#3e4347"));
        newTabBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        newTabBtn.setOnClickListener(v -> createNewTab(DEFAULT_URL, true));
        bottomBar.addView(newTabBtn);

        root.addView(bottomBar);
        return root;
    }

    private void showOptionsModal() {
        BrowserTab active = getActiveTab();
        final String currentUrl = active != null ? active.webView.getUrl() : DEFAULT_URL;
        final String currentTitle = active != null ? active.title : "Ashport";

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog);
        ScrollView scroll = new ScrollView(this);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(18);
        panel.setPadding(pad, dp(16), pad, pad);

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.parseColor("#3a3c3f"));
        panelBg.setCornerRadius(dp(14));
        panel.setBackground(panelBg);

        TextView phoneIcon = new TextView(this);
        phoneIcon.setText("📱");
        phoneIcon.setTextSize(26f);
        phoneIcon.setGravity(Gravity.CENTER);
        panel.addView(phoneIcon);

        TextView optionsHeader = new TextView(this);
        optionsHeader.setText("OPTIONS");
        optionsHeader.setTextColor(Color.parseColor("#e0e0e0"));
        optionsHeader.setTextSize(13f);
        optionsHeader.setPadding(0, dp(4), 0, dp(14));
        panel.addView(optionsHeader);

        AlertDialog dialog = builder.setView(scroll).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams urlRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        urlRowLp.bottomMargin = dp(14);
        urlRow.setLayoutParams(urlRowLp);

        EditText urlInput = new EditText(this);
        urlInput.setHint("Browse URL");
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.parseColor("#aaaaaa"));
        urlInput.setSingleLine(true);
        urlInput.setTextSize(14f);
        urlInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#323437"));
        inputBg.setStroke(dp(1), Color.parseColor("#888888"));
        inputBg.setCornerRadius(dp(6));
        urlInput.setBackground(inputBg);
        urlRow.addView(urlInput, new LinearLayout.LayoutParams(0, dp(44), 1f));

        TextView goBtn = new TextView(this);
        goBtn.setText("»");
        goBtn.setTextColor(Color.WHITE);
        goBtn.setTextSize(26f);
        goBtn.setTypeface(Typeface.DEFAULT_BOLD);
        goBtn.setGravity(Gravity.CENTER);
        goBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
        goBtn.setOnClickListener(v -> {
            String raw = urlInput.getText().toString().trim();
            if (!raw.isEmpty() && active != null) {
                if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                    raw = "https://" + raw;
                }
                dialog.dismiss();
                active.webView.loadUrl(raw);
            }
        });
        urlRow.addView(goBtn);
        panel.addView(urlRow);

        panel.addView(makeModalActionButton("🏠   Ashport Home", v -> {
            dialog.dismiss();
            if (active != null) active.webView.loadUrl(DEFAULT_URL);
        }));

        panel.addView(makeModalActionButton("📋   Copy URL", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Ashport URL", currentUrl));
                Toast.makeText(this, "URL copied to clipboard", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        }));

        panel.addView(makeModalActionButton("♡   Browse shortcuts", v -> {
            dialog.dismiss();
            shortcutManager.showBrowseShortcutsDialog((targetUrl, newTab) -> {
                if (newTab || active == null) {
                    createNewTab(targetUrl, true);
                } else {
                    active.webView.loadUrl(targetUrl);
                }
            });
        }));

        panel.addView(makeModalActionButton("♡+  Save as shortcut", v -> {
            dialog.dismiss();
            shortcutManager.showSaveShortcutDialog(currentTitle, currentUrl);
        }));

        panel.addView(makeModalActionButton("🔍   Find in page", v -> {
            dialog.dismiss();
            findBar.setVisibility(View.VISIBLE);
            findInput.requestFocus();
        }));

        panel.addView(makeModalActionButton("↗   External browser", v -> {
            dialog.dismiss();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)));
            } catch (Exception ignored) {}
        }));

        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor("#525558"));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(6);
        divLp.bottomMargin = dp(8);
        panel.addView(div1, divLp);

        TextView zoomLabel = new TextView(this);
        zoomLabel.setText("ZOOM");
        zoomLabel.setTextColor(Color.parseColor("#cfcfcf"));
        zoomLabel.setTextSize(11f);
        zoomLabel.setPadding(0, 0, 0, dp(8));
        panel.addView(zoomLabel);

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams zoomRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        zoomRowLp.bottomMargin = dp(12);
        zoomRow.setLayoutParams(zoomRowLp);

        Button zoomOutBtn = makeZoomButton("—");
        zoomOutBtn.setOnClickListener(v -> adjustZoom(active, -10));

        Button zoomResetBtn = makeZoomButton("↻");
        zoomResetBtn.setOnClickListener(v -> adjustZoom(active, 0));

        Button zoomInBtn = makeZoomButton("＋");
        zoomInBtn.setOnClickListener(v -> adjustZoom(active, +10));

        LinearLayout.LayoutParams zLp1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        zLp1.rightMargin = dp(8);
        LinearLayout.LayoutParams zLp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        zLp2.rightMargin = dp(8);
        LinearLayout.LayoutParams zLp3 = new LinearLayout.LayoutParams(0, dp(42), 1f);

        zoomRow.addView(zoomOutBtn, zLp1);
        zoomRow.addView(zoomResetBtn, zLp2);
        zoomRow.addView(zoomInBtn, zLp3);
        panel.addView(zoomRow);

        panel.addView(makeModalActionButton("📜   User Scripts", v -> {
            dialog.dismiss();
            scriptManager.showScriptManagerDialog();
        }));

        scroll.addView(panel);
        dialog.show();
    }

    private Button makeModalActionButton(String label, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTextSize(14f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#5c7c8e"));
        bg.setCornerRadius(dp(6));
        btn.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.bottomMargin = dp(10);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(onClick);
        return btn;
    }

    private Button makeZoomButton(String symbol) {
        Button btn = new Button(this);
        btn.setText(symbol);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(18f);
        btn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#5c7c8e"));
        bg.setCornerRadius(dp(6));
        btn.setBackground(bg);
        return btn;
    }

    private void adjustZoom(BrowserTab tab, int delta) {
        if (tab == null) return;
        if (delta == 0) {
            tab.zoom = 100;
        } else {
            tab.zoom = Math.max(50, Math.min(200, tab.zoom + delta));
        }
        tab.webView.getSettings().setTextZoom(tab.zoom);
        Toast.makeText(this, "Zoom: " + tab.zoom + "%", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createNewTab(String url, boolean switchToIt) {
        final BrowserTab tab = new BrowserTab();
        tab.url = url;
        WebView wv = new WebView(this);
        tab.webView = wv;

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        wv.addJavascriptInterface(new GmBridge(this, wv), "AndroidGMBridge");
        attachScriptsToTab(tab);

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                if (title != null && !title.trim().isEmpty()) {
                    String clean = title.replace(" - Ashport", "").replace(" | Ashport", "").trim();
                    tab.title = clean.isEmpty() ? title : clean;
                    updateBarsUI();
                }
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
                if (host.equals("ashport.net") || host.endsWith(".ashport.net")) {
                    return false;
                } else {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {}
                    return true;
                }
            }

            @Override
            public void onPageFinished(WebView view, String loadedUrl) {
                super.onPageFinished(view, loadedUrl);
                tab.url = loadedUrl;
                if (apiTimerManager != null) apiTimerManager.syncIfNeededOnActivity(45000L);
                updateBarsUI();
            }
        });

        wv.setVisibility(View.GONE);
        webViewStack.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabs.add(tab);

        wv.loadUrl(url);

        if (switchToIt || activeTabIndex == -1) {
            switchToTab(tabs.size() - 1);
        } else {
            updateBarsUI();
        }
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        activeTabIndex = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).webView.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        updateBarsUI();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (tabs.size() == 1) {
            tabs.get(0).webView.loadUrl(DEFAULT_URL);
            return;
        }
        BrowserTab removed = tabs.remove(index);
        webViewStack.removeView(removed.webView);
        removed.webView.destroy();

        if (activeTabIndex >= tabs.size()) {
            activeTabIndex = tabs.size() - 1;
        }
        switchToTab(activeTabIndex);
    }

    private void showTabActionsMenu() {
        BrowserTab active = getActiveTab();
        if (active == null) return;
        String[] options = {
                "➕ Duplicate Current Tab",
                "✕ Close Current Tab",
                "🧹 Close All Other Tabs"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Tab Management (" + tabs.size() + " open)")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        createNewTab(active.webView.getUrl() != null ? active.webView.getUrl() : DEFAULT_URL, true);
                    } else if (which == 1) {
                        closeTab(activeTabIndex);
                    } else if (which == 2) {
                        BrowserTab keep = getActiveTab();
                        for (int i = tabs.size() - 1; i >= 0; i--) {
                            if (tabs.get(i) != keep) {
                                BrowserTab rem = tabs.remove(i);
                                webViewStack.removeView(rem.webView);
                                rem.webView.destroy();
                            }
                        }
                        activeTabIndex = 0;
                        switchToTab(0);
                    }
                })
                .show();
    }

    private void updateBarsUI() {
        BrowserTab active = getActiveTab();
        if (active != null && topTitleBox != null) {
            topTitleBox.setText(active.title);
        }

        if (hubStatusSubtitle != null) {
            int enabledCount = scriptManager.getEnabledScripts().size();
            hubStatusSubtitle.setText(tabs.size() + " Active Tab(s)  •  " + enabledCount + " Userscript(s) Enabled");
        }

        if (tabStrip == null) return;
        tabStrip.removeAllViews();

        for (int i = 0; i < tabs.size(); i++) {
            final int tabIdx = i;
            BrowserTab t = tabs.get(i);
            boolean isCurrent = (i == activeTabIndex);

            TextView tabBtn = new TextView(this);
            tabBtn.setText(t.title);
            tabBtn.setSingleLine(true);
            tabBtn.setEllipsize(TextUtils.TruncateAt.END);
            tabBtn.setMaxWidth(dp(140));
            tabBtn.setMinWidth(dp(72));
            tabBtn.setGravity(Gravity.CENTER);
            tabBtn.setPadding(dp(14), 0, dp(14), 0);
            tabBtn.setTextSize(13f);
            tabBtn.setTextColor(isCurrent ? Color.parseColor("#90caf9") : Color.parseColor("#b0bec5"));
            tabBtn.setTypeface(isCurrent ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabBtn.setBackground(makeBorderedCell(
                    isCurrent ? "#2b3640" : "#1e2022",
                    isCurrent ? "#64b5f6" : "#373b3e"
            ));

            tabBtn.setOnClickListener(v -> switchToTab(tabIdx));
            tabBtn.setOnLongClickListener(v -> {
                closeTab(tabIdx);
                return true;
            });

            tabStrip.addView(tabBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
    }

    private void attachScriptsToTab(BrowserTab tab) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;

        for (ScriptHandler h : tab.scriptHandlers) {
            try { h.remove(); } catch (Exception ignored) {}
        }
        tab.scriptHandlers.clear();

        Set<String> allowedOrigins = new HashSet<>(Arrays.asList(
                "https://ashport.net",
                "https://*.ashport.net",
                "http://ashport.net",
                "http://*.ashport.net"
        ));

        if (!cachedPolyfill.isEmpty()) {
            tab.scriptHandlers.add(WebViewCompat.addDocumentStartJavaScript(tab.webView, cachedPolyfill, allowedOrigins));
        }

        for (ScriptManager.UserScript script : scriptManager.getEnabledScripts()) {
            boolean isDocStart = "document-start".equalsIgnoreCase(script.runAt) ||
                    (script.code.contains("@run-at") && script.code.contains("document-start"));

            String wrapped;
            if (isDocStart) {
                wrapped = "(function() {\n" + script.code + "\n})();";
            } else {
                wrapped = "(function() {\n" +
                        "  function __run() {\n" + script.code + "\n  }\n" +
                        "  if (document.readyState === 'loading') {\n" +
                        "    document.addEventListener('DOMContentLoaded', __run, { once: true });\n" +
                        "  } else {\n" +
                        "    __run();\n" +
                        "  }\n" +
                        "})();";
            }
            tab.scriptHandlers.add(WebViewCompat.addDocumentStartJavaScript(tab.webView, wrapped, allowedOrigins));
        }
    }

    private void rebindScriptsAcrossAllTabs() {
        for (BrowserTab tab : tabs) {
            attachScriptsToTab(tab);
        }
        updateBarsUI();
    }

    private void showHub() {
        updateBarsUI();
        updateLinkStatusCardUI(isAshportLinkHandlingEnabled());
        browserView.setVisibility(View.GONE);
        hubView.setVisibility(View.VISIBLE);
        apiTimerManager.startTicker();
    }

    private void showBrowser() {
        apiTimerManager.stopTicker();
        apiTimerManager.syncIfNeededOnActivity(10000L);
        hubView.setVisibility(View.GONE);
        browserView.setVisibility(View.VISIBLE);
        updateBarsUI();
    }

    private BrowserTab getActiveTab() {
        if (activeTabIndex >= 0 && activeTabIndex < tabs.size()) {
            return tabs.get(activeTabIndex);
        }
        return null;
    }

    private void handleBackNavigation() {
        if (browserView.getVisibility() == View.VISIBLE) {
            BrowserTab active = getActiveTab();
            if (active != null && active.webView.canGoBack()) {
                active.webView.goBack();
            } else {
                showHub();
            }
        } else {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String targetUrl = extractUrlFromIntent(intent);
        if (targetUrl != null) {
            showBrowser();
            BrowserTab active = getActiveTab();
            if (active != null) {
                active.webView.loadUrl(targetUrl);
            } else {
                createNewTab(targetUrl, true);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLinkStatusCardUI(isAshportLinkHandlingEnabled());
        if (hubView != null && hubView.getVisibility() == View.VISIBLE) {
            apiTimerManager.startTicker();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        apiTimerManager.stopTicker();
        apiTimerManager.syncIfNeededOnActivity(10000L);
        CookieManager.getInstance().flush();
    }

    private String extractUrlFromIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null) return data.toString();
        }
        return null;
    }

    private GradientDrawable makeBorderedCell(String bgHex, String strokeHex) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setStroke(dp(1), Color.parseColor(strokeHex));
        return gd;
    }

    private Button makeSmallBarButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(36)));
        return b;
    }

    private String readAssetFile(String path) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
