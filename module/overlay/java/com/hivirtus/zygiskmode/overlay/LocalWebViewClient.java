package com.hivirtus.zygiskmode.overlay;

import android.webkit.WebView;
import android.webkit.WebViewClient;

final class LocalWebViewClient extends WebViewClient {
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return url == null || !url.startsWith("file://");
    }
}
