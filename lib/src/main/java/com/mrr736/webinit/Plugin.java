package com.mrr736.webinit;

import android.content.Context;
import android.webkit.WebView;

/**
 * Base class for WebInit plugins.
 *
 * A plugin is registered with WebInit before Activity.onCreate()
 * and receives lifecycle callbacks as the WebView is initialized.
 */
public abstract class Plugin {
    private final String name;
    private WebInit webInit;

    protected Plugin(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Plugin name cannot be null or empty");
        }
        this.name = name;
    }

    public final String getName() {
        return name;
    }

    final void attach(WebInit webInit) {
        if (this.webInit != null && this.webInit != webInit) {
            throw new IllegalStateException("Plugin is already attached to another WebInit instance");
        }
        this.webInit = webInit;
        onRegister();
    }

    protected final WebInit getWebInit() {
        if (webInit == null) {
            throw new IllegalStateException("Plugin is not attached to WebInit");
        }
        return webInit;
    }

    protected final Context getContext() {
        return getWebInit();
    }

    protected final WebView getWebView() {
        return getWebInit().getWebView();
    }

    /**
     * Called when the plugin is registered.
     * Registration happens before native/WebView initialization.
     */
    protected void onRegister() {
    }

    /**
     * Called after WebInit native initialization succeeds.
     */
    protected void onCreate() {
    }

    /**
     * Called after the WebView has been created and configured.
     */
    protected void onWebViewReady(WebView webView) {
    }

    /**
     * Called when WebInit is being destroyed.
     */
    protected void onDestroy() {
    }

    /**
     * Handle a JavaScript plugin call.
     *
     * Return a JSON string or any String suitable for JavaScript.
     * Throw an exception when the requested action is unsupported.
     */
    protected String invoke(String action, String data) {
        throw new UnsupportedOperationException(
            "Plugin '" + name + "' does not implement action '" + action + "'"
        );
    }

    final String dispatch(String action, String data) {
        return invoke(action, data);
    }
}
