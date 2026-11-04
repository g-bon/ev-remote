package it.gbon.evremote;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Login nella WebView pulita dell'app del marchio; intercetta il redirect <scheme>://oauth2redirect con il codice OAuth. */
public class LoginActivity extends Activity {
    private boolean done;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();

        WebView web = new WebView(this);
        web.setFitsSystemWindows(true);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.clearCache(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handle(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handle(Uri.parse(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                handle(request.getUrl());
            }
        });
        setContentView(web);
        web.loadUrl(Api.authorizeUrl());
    }

    private boolean handle(Uri uri) {
        if (uri == null || !BuildConfig.SCHEME.equals(uri.getScheme())) return false;
        String code = uri.getQueryParameter("code");
        if (!done && code != null) {
            done = true;
            setResult(RESULT_OK, new Intent().putExtra("code", code));
            finish();
        }
        return true;
    }
}
