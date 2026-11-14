package com.termant.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Thin client for a Termant server: asks once for the server address,
 * then shows the web terminal full screen.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "termant";
    private static final String KEY_URL = "url";

    private SharedPreferences prefs;
    private WebView web;
    private boolean showingWeb;
    private Uri server;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String url = prefs.getString(KEY_URL, null);
        if (url == null) {
            showSetup("", null);
        } else {
            showWeb(url);
        }
    }

    // ------------------------------------------------------------ setup

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void showSetup(String prefill, String error) {
        showingWeb = false;
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(72), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextSize(28);
        title.setTextColor(0xffffffff);
        box.addView(title);

        TextView text = new TextView(this);
        text.setText(R.string.setup_text);
        text.setTextSize(15);
        text.setTextColor(0xffc9b3c2);
        text.setPadding(0, dp(8), 0, dp(20));
        box.addView(text);

        final EditText input = new EditText(this);
        input.setHint(R.string.setup_hint);
        input.setText(prefill);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setTextColor(0xffffffff);
        input.setHintTextColor(0xff8f7888);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView message = new TextView(this);
        message.setTextSize(14);
        message.setTextColor(0xffff9d85);
        message.setPadding(0, dp(8), 0, dp(8));
        message.setMinHeight(dp(48));
        if (error != null) message.setText(error);
        box.addView(message);

        Button connect = new Button(this);
        connect.setText(R.string.connect);
        connect.setGravity(Gravity.CENTER);
        box.addView(connect, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        final Runnable submit = new Runnable() {
            @Override
            public void run() {
                String url = normalise(input.getText().toString());
                if (url == null) {
                    message.setText(R.string.bad_address);
                    return;
                }
                prefs.edit().putString(KEY_URL, url).apply();
                showWeb(url);
            }
        };
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submit.run();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                submit.run();
                return true;
            }
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(box);
        setContentView(scroll);
    }

    /** Returns a usable http(s) URL, or null. "192.168.1.5:3000" becomes "http://192.168.1.5:3000". */
    static String normalise(String raw) {
        String s = raw.trim();
        if (s.isEmpty() || s.contains(" ")) return null;
        if (!s.matches("(?i)^https?://.*")) {
            if (s.contains("://")) return null;
            s = "http://" + s;
        }
        Uri uri = Uri.parse(s);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return null;
        return s;
    }

    // ------------------------------------------------------------ terminal

    private void showWeb(final String url) {
        server = Uri.parse(url);
        if (web == null) web = createWebView();
        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        setContentView(web);
        showingWeb = true;
        web.clearHistory();
        web.loadUrl(url);
        web.requestFocus();
    }

    private boolean isServer(Uri uri) {
        return server != null
                && uri.getHost() != null
                && uri.getHost().equalsIgnoreCase(server.getHost())
                && uri.getPort() == server.getPort();
    }

    private WebView createWebView() {
        WebView view = new WebView(this);
        view.setBackgroundColor(0xff300a24);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);
        // The page only ever needs the server itself.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);

        CookieManager.getInstance().setAcceptCookie(true);

        // Needed for the page's confirm()/alert() dialogs to appear.
        view.setWebChromeClient(new WebChromeClient());

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                Uri target = request.getUrl();
                if (isServer(target)) return false;
                // Links printed in the terminal open in the real browser.
                String scheme = target.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, target));
                    } catch (Exception e) {
                        // no browser installed
                    }
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String pageUrl, Bitmap favicon) {
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame() || !showingWeb) return;
                String address = prefs.getString(KEY_URL, "");
                showSetup(address, getString(R.string.load_failed, address, error.getDescription()));
            }
        });
        return view;
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        if (!showingWeb) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.leave_title)
                .setMessage(R.string.leave_text)
                .setPositiveButton(R.string.exit, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setNeutralButton(R.string.change_server, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showSetup(prefs.getString(KEY_URL, ""), null);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
