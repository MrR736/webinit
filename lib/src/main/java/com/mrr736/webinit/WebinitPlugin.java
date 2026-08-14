package com.mrr736.webinit;

import com.getcapacitor.Plugin;

/**
 * Convenience wrapper for user-defined WebInit plugins.
 *
 * Applications should normally extend this class instead of Plugin:
 *
 * <pre>
 * public final class MyPlugin extends WebinitPlugin {
 *     public MyPlugin() {
 *         super("myPlugin");
 *     }
 *
 *     @Override
 *     protected void onWebViewReady(WebView webView) {
 *         // initialize JavaScript bridge
 *     }
 *
 *     @Override
 *     protected String invoke(String action, String data) {
 *         // handle JavaScript calls
 *         return "{\"ok\":true}";
 *     }
 * }
 * </pre>
 */
public abstract class WebinitPlugin extends Plugin {
    protected WebinitPlugin() {
        super();
    }

    protected void onWebinitCreate() {
    }

    protected void onWebinitWebViewReady() {
    }

    protected void onWebinitDestroy() {
    }
}
