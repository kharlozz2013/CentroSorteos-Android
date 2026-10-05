package com.viralenunclick.cambio;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {

    private static final String BASE_URL = "https://cambio.viralenunclick.com";
    private static final int FILE_CHOOSER_REQUEST = 501;
    private static final int STORAGE_PERMISSION_REQUEST = 502;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMimeType;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setBackgroundColor(Color.rgb(248, 247, 252));
        setContentView(webView);

        configureWebView();

        if (savedInstanceState == null) {
            webView.loadUrl(BASE_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " CasaCambioAndroid/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                Toast.makeText(MainActivity.this,
                        "No se pudo validar la conexión segura.", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                if (request.isForMainFrame()) {
                    showConnectionError();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallbackParam,
                    FileChooserParams fileChooserParams
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = filePathCallbackParam;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"image/jpeg", "image/png", "image/webp", "application/pdf"});
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams != null &&
                                fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException ex) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "No se encontró un selector de archivos.", Toast.LENGTH_LONG).show();
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimetype,
                    long contentLength
            ) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                != PackageManager.PERMISSION_GRANTED) {
                    pendingDownloadUrl = url;
                    pendingDownloadUserAgent = userAgent;
                    pendingDownloadDisposition = contentDisposition;
                    pendingDownloadMimeType = mimetype;
                    requestPermissions(
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            STORAGE_PERMISSION_REQUEST
                    );
                    return;
                }
                enqueueDownload(url, userAgent, contentDisposition, mimetype);
            }
        });
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        if ("http".equals(scheme) || "https".equals(scheme)) {
            if ("cambio.viralenunclick.com".equals(host)) {
                return false;
            }
            openExternal(uri);
            return true;
        }

        if ("intent".equals(scheme)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (Exception ignored) {
                Toast.makeText(this, "No se pudo abrir el enlace.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "No hay una aplicación disponible para abrir este enlace.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void enqueueDownload(
            String url,
            String userAgent,
            String contentDisposition,
            String mimetype
    ) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Descargando comprobante");
            request.setMimeType(mimetype);
            request.addRequestHeader("User-Agent", userAgent);

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }

            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(this, "Descarga iniciada.", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, "No se pudo iniciar la descarga.", Toast.LENGTH_LONG).show();
        }
    }

    private void showConnectionError() {
        String html =
                "<!doctype html><html><head><meta name='viewport' " +
                "content='width=device-width,initial-scale=1'><style>" +
                "body{font-family:sans-serif;background:#f8f7fc;color:#16162a;" +
                "display:flex;align-items:center;justify-content:center;height:100vh;" +
                "margin:0;padding:24px;box-sizing:border-box}" +
                ".c{max-width:420px;text-align:center;background:white;padding:28px;" +
                "border-radius:24px;box-shadow:0 12px 35px rgba(40,30,90,.08)}" +
                "button{border:0;border-radius:14px;background:#7567e8;color:white;" +
                "font-weight:700;padding:14px 22px;font-size:16px}</style></head>" +
                "<body><div class='c'><h2>Sin conexión</h2>" +
                "<p>No pudimos conectar con Casa de Cambio.</p>" +
                "<button onclick=\"location.href='" + BASE_URL + "'\">Reintentar</button>" +
                "</div></body></html>";

        webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) {
            return;
        }

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_REQUEST &&
                grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                pendingDownloadUrl != null) {

            enqueueDownload(
                    pendingDownloadUrl,
                    pendingDownloadUserAgent,
                    pendingDownloadDisposition,
                    pendingDownloadMimeType
            );
        }

        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadDisposition = null;
        pendingDownloadMimeType = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
