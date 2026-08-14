package com.mrr736.webinit;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.getcapacitor.Bridge;
import com.getcapacitor.CapConfig;
import com.getcapacitor.CapacitorWebView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * WebInit host Activity.
 *
 * WebInit provides the native HTTP server/WebView host while Capacitor owns
 * the JavaScript-to-native plugin bridge.
 *
 * Applications extend this class and register WebinitPlugin instances before
 * calling super.onCreate():
 *
 *   registerPlugin(new MyPlugin());
 *   Init("app.pak", 8080, "127.0.0.1");
 *   super.onCreate(savedInstanceState);
 */
public class WebInit extends AppCompatActivity {

	private final PluginManager pluginManager = new PluginManager(this);

	private Bridge bridge;
	private CapacitorWebView webView;

	private ProgressBar progressBar;
	private FrameLayout root;

	private String localAddress;
	private int localPort;
	private String localUrl;
	private String pak;

	private volatile boolean initialized;
	private volatile boolean nativeInitialized;

	/**
	 * Register a Capacitor/WebInit plugin.
	 *
	 * Must be called before super.onCreate().
	 */
	protected final void registerPlugin(WebinitPlugin plugin) {
		pluginManager.register(plugin);
	}

	/**
	 * Return the Capacitor Bridge.
	 */
	protected final Bridge getBridge() {
		return bridge;
	}

	/**
	 * Return the WebView after it has been created.
	 */
	public final WebView getWebView() {
		return webView;
	}

	/**
	 * Return the WebInit plugin registry.
	 */
	protected final PluginManager getPluginManager() {
		return pluginManager;
	}

	/**
	 * Configure WebInit before calling super.onCreate().
	 */
	public final void Init(String pak, int port, String address) {
		if (pak == null || pak.isEmpty()) {
			throw new IllegalArgumentException(
				"PAK file name cannot be null or empty"
			);
		}

		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException(
				"Invalid port: " + port
			);
		}

		this.pak = pak;
		this.localPort = port;
		this.localAddress = getAddress(address);
		this.localUrl =
			"http://" +
			this.localAddress +
			":" +
			this.localPort +
			"/index.html";
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		/*
		 * Plugins must be registered before native initialization.
		 */
		super.onCreate(savedInstanceState);

		if (pak == null || pak.isEmpty()) {
			throw new IllegalStateException(
				"WebInit.Init() must be called before super.onCreate()"
			);
		}

		if (!Native.SetPackageName(getPackageName())) {
			throw new IllegalStateException(
				"Failed to set WebInit package name: " +
				getPackageName()
			);
		}

		createRootLayout();

		getOnBackPressedDispatcher().addCallback(
			this,
			new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					if (webView != null && webView.canGoBack()) {
						webView.goBack();
					} else {
						finish();
					}
				}
			}
		);

		/*
		 * Native initialization is intentionally kept off the UI thread.
		 */
		new Thread(
			this::initializeNative,
			"WebInit-NativeInit"
		).start();
	}

	private void createRootLayout() {
		root = new FrameLayout(this);
		setContentView(root);

		progressBar = new ProgressBar(this);

		FrameLayout.LayoutParams params =
			new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT
			);

		params.gravity = Gravity.CENTER;

		root.addView(progressBar, params);
	}

	private void initializeNative() {
		try {
			String pakPath = extractAssetToCache(pak);

			if (!Native.SetAddressHost(localAddress, localPort)) {
				throw new IllegalStateException(
					"Native.SetAddressHost() failed"
				);
			}

			if (!Native.SetCacheDir(
				getCacheDir().getAbsolutePath()
			)) {
				throw new IllegalStateException(
					"Native.SetCacheDir() failed"
				);
			}

			if (!Native.Init(pakPath)) {
				runOnUiThread(this::finish);
				return;
			}

			nativeInitialized = true;

			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}

				createCapacitorBridge();
			});

		} catch (Exception e) {
			e.printStackTrace();
			runOnUiThread(this::finish);
		}
	}

	private void createCapacitorBridge() {
		/*
		 * Bridge.Builder looks for com.getcapacitor.android.R.id.webview.
		 * Therefore the WebView is created with Capacitor's WebView class and
		 * the official Capacitor resource id.
		 */
		webView = new CapacitorWebView(this, null);
		webView.setId(com.getcapacitor.android.R.id.webview);

		WebSettings settings = webView.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setAllowFileAccess(true);
		settings.setAllowContentAccess(true);
		settings.setJavaScriptCanOpenWindowsAutomatically(true);
		settings.setSupportMultipleWindows(true);

		root.addView(
			webView,
			0,
			new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT
			)
		);

		/*
		 * The native WebInit server is the application's server URL.
		 *
		 * Capacitor injects its JavaScript bridge into the page served by
		 * this URL, while requests are still handled by the WebInit server.
		 */
		CapConfig config =
			new CapConfig.Builder(this)
				.setServerUrl(localUrl)
				.setHostname(localAddress)
				.setAndroidScheme("http")
				.setWebContentsDebuggingEnabled(
					(getApplicationInfo().flags &
					 android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
				)
				.create();

		Bridge.Builder builder =
			new Bridge.Builder(this)
				.setInstanceState(null)
				.setConfig(config);

		/*
		 * Register the exact plugin instances supplied by the application.
		 * Capacitor will create PluginHandle objects, connect the plugins to
		 * the Bridge, initialize ActivityResult launchers, and expose the
		 * @PluginMethod methods to JavaScript.
		 */
		for (WebinitPlugin plugin : pluginManager.snapshot()) {
			builder.addPluginInstance(plugin);
		}

		bridge = builder.create();

		/*
		 * The Capacitor Bridge has now loaded the plugin instances.
		 */
		pluginManager.onCreate();
		pluginManager.onWebViewReady();

		initialized = true;

		if (progressBar != null) {
			progressBar.setVisibility(ProgressBar.GONE);
		}
	}

	/**
	 * Extract a PAK from APK assets to application cache.
	 */
	private String extractAssetToCache(String assetName)
		throws IOException {

		File cacheDir =
			new File(getCacheDir(), "assets");

		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new IOException(
				"Failed creating cache directory: " +
				cacheDir
			);
		}

		File pakFile =
			new File(
				cacheDir,
				new File(assetName).getName()
			);

		if (pakFile.isFile() && pakFile.length() > 0) {
			return pakFile.getAbsolutePath();
		}

		File tempFile =
			new File(
				cacheDir,
				pakFile.getName() + ".tmp"
			);

		if (tempFile.exists() && !tempFile.delete()) {
			throw new IOException(
				"Failed removing temporary PAK: " +
				tempFile
			);
		}

		try (
			InputStream input =
				getAssets().open(assetName);
			FileOutputStream output =
				new FileOutputStream(tempFile)
		) {
			byte[] buffer = new byte[1024 * 1024];
			int read;

			while ((read = input.read(buffer)) != -1) {
				output.write(buffer, 0, read);
			}

			output.flush();

		} catch (IOException e) {
			tempFile.delete();
			throw e;
		}

		if (!tempFile.isFile() ||
			tempFile.length() <= 0) {

			tempFile.delete();

			throw new IOException(
				"PAK extraction failed or produced " +
				"an empty file: " +
				assetName
			);
		}

		if (pakFile.exists() && !pakFile.delete()) {
			tempFile.delete();

			throw new IOException(
				"Failed replacing existing PAK: " +
				pakFile
			);
		}

		if (!tempFile.renameTo(pakFile)) {
			tempFile.delete();

			throw new IOException(
				"Failed moving temporary PAK to: " +
				pakFile
			);
		}

		return pakFile.getAbsolutePath();
	}

	@Override
	protected void onStart() {
		super.onStart();

		if (bridge != null) {
			bridge.onStart();
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		if (bridge != null) {
			bridge.onResume();
		}
	}

	@Override
	protected void onPause() {
		if (bridge != null) {
			bridge.onPause();
		}

		super.onPause();
	}

	@Override
	protected void onStop() {
		if (bridge != null) {
			bridge.onStop();
		}

		super.onStop();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);

		if (bridge != null) {
			bridge.onNewIntent(intent);
		}
	}

	@Override
	public void onConfigurationChanged(
		android.content.res.Configuration newConfig
	) {
		super.onConfigurationChanged(newConfig);

		if (bridge != null) {
			bridge.onConfigurationChanged(newConfig);
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		if (bridge != null) {
			bridge.saveInstanceState(outState);
		}

		super.onSaveInstanceState(outState);
	}

	@Override
	protected void onDestroy() {
		initialized = false;

		/*
		 * Give WebInit plugins a chance to release their resources before
		 * Capacitor destroys their Bridge connection.
		 */
		pluginManager.onDestroy();

		if (bridge != null) {
			bridge.onDestroy();
			bridge = null;
		}

		if (webView != null) {
			webView.stopLoading();
			webView.loadUrl("about:blank");
			webView.clearHistory();
			webView.removeAllViews();
			webView.destroy();
			webView = null;
		}

		if (nativeInitialized) {
			nativeInitialized = false;
			Native.Close();
		}

		progressBar = null;
		root = null;

		super.onDestroy();
	}

	public final boolean isInitialized() {
		return initialized;
	}

	public static void deleteRecursive(File file) {
		if (file == null || !file.exists()) {
			return;
		}

		if (file.isDirectory()) {
			File[] files = file.listFiles();

			if (files != null) {
				for (File child : files) {
					deleteRecursive(child);
				}
			}
		}

		file.delete();
	}

	private static String getAddress(String address) {
		if (address == null ||
			"*".equals(address) ||
			"0.0.0.0".equals(address)) {

			return "127.0.0.1";
		}

		return address;
	}
}
