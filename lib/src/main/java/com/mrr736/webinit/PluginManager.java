package com.mrr736.webinit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebInit plugin registry.
 *
 * Capacitor owns the actual JavaScript/native bridge. This class only keeps
 * the WebInit plugin instances and forwards WebInit-specific lifecycle hooks.
 */
final class PluginManager {

	private final WebInit webInit;
	private final Map<String, WebinitPlugin> plugins = new LinkedHashMap<>();

	private boolean created;
	private boolean destroyed;

	PluginManager(WebInit webInit) {
		this.webInit = webInit;
	}

	synchronized void register(WebinitPlugin plugin) {
		if (plugin == null) {
			throw new NullPointerException("plugin");
		}

		if (created) {
			throw new IllegalStateException(
				"Plugins must be registered before WebInit initialization"
			);
		}

		String name = plugin.getClass().getName();

		com.getcapacitor.annotation.CapacitorPlugin annotation =
			plugin.getClass().getAnnotation(
				com.getcapacitor.annotation.CapacitorPlugin.class
			);

		if (annotation == null || annotation.name().trim().isEmpty()) {
			throw new IllegalArgumentException(
				"Plugin " + name +
				" must have @CapacitorPlugin(name = \"...\")"
			);
		}

		String pluginName = annotation.name();

		if (plugins.containsKey(pluginName)) {
			throw new IllegalArgumentException(
				"A plugin named '" + pluginName + "' is already registered"
			);
		}

		plugin.attachWebInit(webInit);
		plugins.put(pluginName, plugin);
	}

	synchronized Collection<WebinitPlugin> all() {
		return Collections.unmodifiableCollection(
			new ArrayList<>(plugins.values())
		);
	}

	synchronized List<WebinitPlugin> snapshot() {
		return new ArrayList<>(plugins.values());
	}

	synchronized void onCreate() {
		if (created) {
			return;
		}

		created = true;

		for (WebinitPlugin plugin : plugins.values()) {
			plugin.onWebinitCreate();
		}
	}

	synchronized void onWebViewReady() {
		for (WebinitPlugin plugin : plugins.values()) {
			plugin.onWebinitWebViewReady();
		}
	}

	synchronized void onDestroy() {
		if (destroyed) {
			return;
		}

		destroyed = true;

		for (WebinitPlugin plugin : plugins.values()) {
			try {
				plugin.onWebinitDestroy();
			} catch (RuntimeException ignored) {
				// One plugin must not prevent other plugins from being destroyed.
			}
		}
	}
}
