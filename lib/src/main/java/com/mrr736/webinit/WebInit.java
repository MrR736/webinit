package com.mrr736.webinit;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class WebInit extends AppCompatActivity {

	private final PluginManager pluginManager = new PluginManager(this);

	private WebView webView;
	private ProgressBar progressBar;
	private FrameLayout root;

	private String localAddress;
	private int localPort;
	private String localUrl;
	private String pak;

	private volatile boolean initialized = false;
	private volatile boolean nativeInitialized = false;

	/**
	 * Register a custom WebInit plugin.
	 *
	 * Must be called before super.onCreate().
	 */
	protected final void registerPlugin(Plugin plugin) {
		pluginManager.register(plugin);
	}

	/** Return the WebView after it has been created. */
	public final WebView getWebView() {
		return webView;
	}

	/** Return the registered plugin manager for advanced integrations. */
	protected final PluginManager getPluginManager() {
		return pluginManager;
	}

	/*
	 * Configure WebInit before calling super.onCreate().
	 * Example:
	 * Init("webinit.pak",8080,"127.0.0.1");
	 */
	public void Init(String pak,int port,String address) {
		if (pak == null || pak.isEmpty()) {
			throw new IllegalArgumentException("PAK file name cannot be null or empty");
		}
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("Invalid port: " + port);
		}
		this.pak = pak;
		this.localPort = port;
		this.localAddress = getAddress(address);
		this.localUrl = "http://" + this.localAddress + ":" + this.localPort + "/index.html";
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		/*
		 * Init() must have been called by the subclass
		 * before super.onCreate().
		 */
		if (pak == null || pak.isEmpty()) {
			throw new IllegalStateException("WebInit.Init() must be called before super.onCreate()");
		}
		/*
		 * Native package name must be configured before
		 * native initialization.
		 */
		if (!Native.SetPackageName(getPackageName())) {
			throw new IllegalStateException("Failed to set WebInit package name: " + getPackageName());
		}
		/* Root layout. */
		root = new FrameLayout(this);
		setContentView(root);
		/* Loading indicator. */
		progressBar = new ProgressBar(this);
		FrameLayout.LayoutParams progressParams =
			new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT
			);
		progressParams.gravity = Gravity.CENTER;
		root.addView(progressBar,progressParams);
		/*
		 * Android back button.
		 *
		 * If the WebView has browser history,
		 * navigate backward.
		 *
		 * Otherwise close the Activity.
		 */
		getOnBackPressedDispatcher().addCallback(this,
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
		 * Native initialization must not run
		 * on the Android UI thread.
		 */
		new Thread(this::initializeNative,"WebInit-NativeInit").start();
	}

	private void initializeNative() {
		try {
			/*
			 * Copy the PAK from APK assets into
			 * an actual filesystem path.
			 */
			String pakPath = extractAssetToCache(pak);
			/* Configure native server address/port. */
			if (!Native.SetAddressHost(localAddress,localPort)) {
				throw new IllegalStateException("Native.SetAddressHost() failed");
			}
			/* Configure native cache directory. */
			if (!Native.SetCacheDir(getCacheDir().getAbsolutePath())) {
				throw new IllegalStateException("Native.SetCacheDir() failed");
			}
			/* Initialize native WebInit. */
			boolean ok = Native.Init(pakPath);
			if (!ok) {
				runOnUiThread(this::finish);
				return;
			}
			nativeInitialized = true;
			pluginManager.onCreate();
			/* WebView must be created on the UI thread. */
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				initialized = true;
				createWebView();
			});
		} catch (Exception e) {
			e.printStackTrace();
			runOnUiThread(this::finish);
		}
	}

	/* Extract a PAK from APK assets to the application cache. */
	private String extractAssetToCache(String assetName) throws IOException {
		File cacheDir = new File(getCacheDir(), "assets");
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new IOException("Failed creating cache directory: " + cacheDir);
		}

		/*
		 * Only use the filename portion.
		 *
		 * Example:
		 * assets/webinit.pak
		 *
		 * becomes:
		 * cache/assets/webinit.pak
		 */
		File pakFile = new File(cacheDir,new File(assetName).getName());

		/* Reuse an existing valid PAK. */
		if (pakFile.isFile() && pakFile.length() > 0) {
			return pakFile.getAbsolutePath();
		}

		/*
		 * Copy to a temporary file first.
		 *
		 * This prevents a failed copy from leaving a corrupt PAK
		 * at the final path.
		 */
		File tempFile = new File(cacheDir,pakFile.getName() + ".tmp");
		if (tempFile.exists() && !tempFile.delete()) {
			throw new IOException("Failed removing temporary PAK: " + tempFile);
		}

		try (
			InputStream input = getAssets().open(assetName);
			FileOutputStream output = new FileOutputStream(tempFile)
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
		if (!tempFile.isFile() || tempFile.length() <= 0) {
			tempFile.delete();
			throw new IOException("PAK extraction failed or produced an empty file: " + assetName);
		}

		/* Replace the old file with the completed PAK. */
		if (pakFile.exists() && !pakFile.delete()) {
			tempFile.delete();
			throw new IOException("Failed replacing existing PAK: " + pakFile);
		}
		if (!tempFile.renameTo(pakFile)) {
			tempFile.delete();
			throw new IOException("Failed moving temporary PAK to: " + pakFile);
		}
		return pakFile.getAbsolutePath();
	}

	/* Create and configure the WebView. */
	private void createWebView() {
		webView = new WebView(this);
		WebSettings settings = webView.getSettings();

		/* JavaScript. */
		settings.setJavaScriptEnabled(true);

		/*
		 * DOM storage is required by many modern
		 * web applications.
		 */
		settings.setDomStorageEnabled(true);

		/* File/content access. */
		settings.setAllowFileAccess(true);
		settings.setAllowContentAccess(true);


		/* Support window.open() / target="_blank". */
		settings.setSupportMultipleWindows(true);
		settings.setJavaScriptCanOpenWindowsAutomatically(true);

		/* WebInit custom plugin bridge. */
		webView.addJavascriptInterface(pluginManager, "WebInitPlugins");

		/* WebView navigation handling. */
		webView.setWebViewClient(
			new WebViewClient() {
				private boolean isLocal(Uri uri) {
					if (uri == null) {
						return false;
					}
					String scheme = uri.getScheme();
					String host = uri.getHost();
					int port = uri.getPort();
					boolean correctHost = localAddress.equalsIgnoreCase(host)
						|| "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);

					/*
					 * Uri.getPort() returns -1 when
					 * no explicit port is present.
					 */
					boolean correctPort = port == -1 || port == localPort;
					return "http".equalsIgnoreCase(scheme) && correctHost && correctPort;
				}

				@Override
				public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
					Uri uri = request.getUrl();
					if (isLocal(uri)) {
						return false;
					}
					openExternal(uri);
					return true;
				}

				@SuppressWarnings("deprecation")
				@Override
				public boolean shouldOverrideUrlLoading(WebView view,String url) {
					Uri uri = Uri.parse(url);
					if (isLocal(uri)) {
						return false;
					}
					openExternal(uri);
					return true;
				}

				@Override
				public void onPageFinished(WebView view,String url) {
					if (progressBar != null && progressBar.getParent() != null) {
						root.removeView(progressBar);
					}
				}
			}
		);


		/*
		 * Handle JavaScript-created windows.
		 *
		 * We do not display the new WebView.
		 * Instead, its navigation is redirected to
		 * the external Android browser.
		 */
		webView.setWebChromeClient(
			new WebChromeClient() {
				@Override
				public boolean onCreateWindow(
					WebView view,
					boolean isDialog,
					boolean isUserGesture,
					android.os.Message resultMsg
				) {
					WebView tempWebView = new WebView(WebInit.this);
					tempWebView.setWebViewClient(
						new WebViewClient() {
							@Override
							public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request) {
								openExternal(request.getUrl());
								return true;
							}

							@SuppressWarnings("deprecation")
							@Override
							public boolean shouldOverrideUrlLoading(WebView v,String url) {
								openExternal(Uri.parse(url));
								return true;
							}
						}
					);
					WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
					transport.setWebView(tempWebView);
					resultMsg.sendToTarget();
					return true;
				}
			}
		);
		/* Put WebView below the loading indicator. */
		root.addView(webView,0);
		pluginManager.onWebViewReady(webView);
		/* Start loading the local WebInit server. */
		webView.loadUrl(localUrl);
	}

	/* Open non-local URLs in the Android browser. */
	private void openExternal(Uri uri) {
		if (uri == null) {
			return;
		}
		Intent intent = new Intent(Intent.ACTION_VIEW,uri);
		intent.addCategory(Intent.CATEGORY_BROWSABLE);
		try {
			startActivity(intent);
		} catch (Exception ignored) {
			/*
			 * No browser/application can handle
			 * this URI.
			 */
		}
	}

	@Override
	protected void onDestroy() {
		initialized = false;
		pluginManager.onDestroy();
		/* Destroy WebView. */
		if (webView != null) {
			webView.stopLoading();
			webView.loadUrl("about:blank");
			webView.clearHistory();
			webView.removeAllViews();
			webView.destroy();
			webView = null;
		}
		/*
		 * Close native WebInit only if it
		 * successfully initialized.
		 */
		if (nativeInitialized) {
			nativeInitialized = false;
			Native.Close();
		}
		progressBar = null;
		root = null;
		super.onDestroy();
	}

	public boolean isInitialized() {
		return initialized;
	}

	/* Recursively delete a directory/file. */
	public static void deleteRecursive(File file) {
		if (file == null || !file.exists()) {
			return;
		}
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files != null) {
				for (File f : files) {
					deleteRecursive(f);
				}
			}
		}
		file.delete();
	}

	/*
	 * Convert wildcard/all-interface addresses
	 * to the local loopback address used by WebView.
	 */
	private static String getAddress(String address) {
		if (address == null || "*".equals(address) || "0.0.0.0".equals(address)) {
			return "127.0.0.1";
		}
		return address;
	}
}
