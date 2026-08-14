package com.mrr736.webinit;

/**
 * Legacy WebInit plugin base.
 *
 * This class is retained for source compatibility with old WebInit plugins.
 *
 * New plugins must extend {@link WebinitPlugin} and use Capacitor's
 * @CapacitorPlugin and @PluginMethod APIs.
 *
 * @deprecated Use WebinitPlugin.
 */
@Deprecated
public abstract class Plugin extends WebinitPlugin {

	private final String name;

	protected Plugin(String name) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException(
				"Plugin name cannot be null or empty"
			);
		}

		this.name = name;
	}

	public final String getName() {
		return name;
	}

	/**
	 * Legacy JavaScript action API.
	 *
	 * Old plugins can override this, but it is not the Capacitor API.
	 */
	@Deprecated
	protected String invoke(String action, String data) {
		throw new UnsupportedOperationException(
			"Plugin '" + name +
			"' does not implement action '" + action + "'"
		);
	}
}
