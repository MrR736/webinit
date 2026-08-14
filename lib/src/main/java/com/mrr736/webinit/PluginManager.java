package com.mrr736.webinit;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal plugin registry and JavaScript bridge.
 */
final class PluginManager {
    private final WebInit webInit;
    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    private boolean created;
    private boolean destroyed;

    PluginManager(WebInit webInit) {
        this.webInit = webInit;
    }

    synchronized void register(Plugin plugin) {
        if (plugin == null) {
            throw new NullPointerException("plugin");
        }
        if (created) {
            throw new IllegalStateException(
                "Plugins must be registered before WebInit.onCreate()"
            );
        }
        String name = plugin.getName();
        if (plugins.containsKey(name)) {
            throw new IllegalArgumentException(
                "A plugin named '" + name + "' is already registered"
            );
        }
        plugin.attach(webInit);
        plugins.put(name, plugin);
    }

    synchronized Collection<Plugin> all() {
        return plugins.values();
    }

    synchronized void onCreate() {
        if (created) {
            return;
        }
        created = true;
        for (Plugin plugin : plugins.values()) {
            plugin.onCreate();
        }
    }

    synchronized void onWebViewReady(WebView webView) {
        for (Plugin plugin : plugins.values()) {
            plugin.onWebViewReady(webView);
        }
    }

    synchronized void onDestroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        for (Plugin plugin : plugins.values()) {
            try {
                plugin.onDestroy();
            } catch (RuntimeException ignored) {
                // One plugin must not prevent the remaining plugins from closing.
            }
        }
    }

    @JavascriptInterface
    public String invoke(String pluginName, String action, String data) {
        try {
            Plugin plugin;
            synchronized (this) {
                plugin = plugins.get(pluginName);
            }

            if (plugin == null) {
                return error("Unknown plugin: " + pluginName);
            }

            String result = plugin.dispatch(
                action == null ? "" : action,
                data == null ? "" : data
            );

            return result == null ? "null" : result;
        } catch (Throwable error) {
            return error(error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage());
        }
    }

    @JavascriptInterface
    public String list() {
        try {
            JSONObject result = new JSONObject();
            synchronized (this) {
                for (Plugin plugin : plugins.values()) {
                    result.put(plugin.getName(), true);
                }
            }
            return result.toString();
        } catch (Exception error) {
            return "{}";
        }
    }

    private static String error(String message) {
        try {
            JSONObject object = new JSONObject();
            object.put("ok", false);
            object.put("error", message == null ? "Unknown error" : message);
            return object.toString();
        } catch (Exception ignored) {
            return "{\"ok\":false,\"error\":\"Plugin error\"}";
        }
    }
}
