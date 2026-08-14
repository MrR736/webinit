package com.mrr736.webinit;

import com.getcapacitor.Plugin;

/**
 * Base class for Capacitor plugins hosted by WebInit.
 *
 * Example:
 *
 * @CapacitorPlugin(name = "MyPlugin")
 * public class MyPlugin extends WebinitPlugin {
 *
 *	@PluginMethod
 *	public void hello(PluginCall call) {
 *		call.resolve();
 *	}
 * }
 */
public abstract class WebinitPlugin extends Plugin {

	private WebInit webInit;

	/**
	 * Called by WebInit when this plugin is registered.
	 *
	 * This is intentionally separate from Capacitor's Plugin.load().
	 */
	final void attachWebInit(WebInit webInit) {
		if (this.webInit != null && this.webInit != webInit) {
			throw new IllegalStateException(
				"Plugin is already attached to another WebInit instance"
			);
		}

		this.webInit = webInit;
		onWebinitRegister();
	}

	/**
	 * Return the WebInit host.
	 */
	protected final WebInit getWebInit() {
		if (webInit == null) {
			throw new IllegalStateException(
				"Plugin is not registered with WebInit"
			);
		}
		return webInit;
	}

	/**
	 * Called when the plugin is registered with WebInit.
	 */
	protected void onWebinitRegister() {
	}

	/**
	 * Called after the Capacitor Bridge has loaded the plugin
	 * and WebInit has finished native initialization.
	 */
	protected void onWebinitCreate() {
	}

	/**
	 * Called after the WebView and Capacitor Bridge are ready.
	 */
	protected void onWebinitWebViewReady() {
	}

	/**
	 * Called before WebInit destroys the Capacitor Bridge.
	 */
	protected void onWebinitDestroy() {
	}
}
