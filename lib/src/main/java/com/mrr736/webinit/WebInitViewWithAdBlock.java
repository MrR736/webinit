package com.mrr736.webinit;

import android.annotation.SuppressLint;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.mlm.adblock.AdblockEngine;
import org.mlm.adblock.RequestTypeMapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public class WebInitViewWithAdBlock extends AppCompatActivity {

	private WebView webView;
	private FrameLayout fullscreenContainer;
	private AdblockEngine adblockEngine;

	private String PAGE_HOST = "example.com";
	private String PAGE_HTTPS = "https://";
	private String PAGE_URL = PAGE_HTTPS + PAGE_HOST;

	private volatile String currentPageUrl = PAGE_URL;
	private volatile String[] PageUrls = new String[0];

	private View customView;
	private WebChromeClient.CustomViewCallback customViewCallback;

	private int previousRequestedOrientation =
			ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

	private boolean previousDecorFitsSystemWindows = true;
	private boolean fullscreenActive = false;

	/**
	 * Sets the initial page.
	 *
	 * Accepts either ("https", "example.com")
	 * or ("https://", "example.com").
	 *
	 * The second argument may also be a complete URL.
	 */
	public void SetPageUrl(String scheme, String hostOrUrl) {
		if (scheme == null || hostOrUrl == null) {
			throw new IllegalArgumentException(
					"Scheme and host/URL must not be null"
			);
		}

		String input = hostOrUrl.trim();
		String normalizedScheme = scheme.trim();

		if (input.isEmpty() || normalizedScheme.isEmpty()) {
			throw new IllegalArgumentException(
					"Scheme and host/URL must not be empty"
			);
		}

		String url;

		if (input.contains("://")) {
			url = input;
		} else {
			if (normalizedScheme.endsWith("://")) {
				normalizedScheme = normalizedScheme.substring(
						0, normalizedScheme.length() - 3
				);
			}

			if (normalizedScheme.contains(":")) {
				throw new IllegalArgumentException(
						"Invalid URL scheme"
				);
			}

			url = normalizedScheme + "://" + input;
		}

		Uri uri = Uri.parse(url);
		String host = normalizeHost(uri.getHost());

		if (host == null || !isHttpOrHttps(uri.getScheme())) {
			throw new IllegalArgumentException(
					"A valid HTTP or HTTPS URL is required"
			);
		}

		PAGE_HOST = host;
		PAGE_HTTPS = uri.getScheme().toLowerCase(Locale.ROOT) + "://";
		PAGE_URL = url;
		currentPageUrl = url;
	}

	/**
	 * Sets additional allowed hosts.
	 *
	 * Entries may be hostnames or complete HTTP/HTTPS URLs.
	 * Subdomains of each allowed host are also permitted.
	 */
	public void SetOpenPageUrls(String... urls) {
		if (urls == null) {
			throw new IllegalArgumentException(
					"URLs must not be null"
			);
		}

		Set<String> hosts = new LinkedHashSet<>();

		for (String value : urls) {
			if (value == null || value.trim().isEmpty()) {
				continue;
			}

			String host = normalizeHost(value);

			if (host != null) {
				hosts.add(host);
			}
		}

		PageUrls = hosts.toArray(new String[0]);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.activity_web_init_view);

		webView = findViewById(R.id.webView);
		fullscreenContainer = findViewById(R.id.fullscreenContainer);

		if (webView == null || fullscreenContainer == null) {
			throw new IllegalStateException(
					"WebView or fullscreen container is missing from the layout"
			);
		}

		fullscreenContainer.setVisibility(View.GONE);

		setupAdblock();
		setupWebView();
		setupBackButton();

		if (savedInstanceState == null) {
			webView.loadUrl(PAGE_URL);
		} else {
			WebView restoredWebView = webView;

			android.webkit.WebBackForwardList restored =
					restoredWebView.restoreState(savedInstanceState);

			if (restored == null) {
				restoredWebView.loadUrl(PAGE_URL);
			} else if (restored.getCurrentItem() != null) {
				currentPageUrl =
						restored.getCurrentItem().getUrl();
			}
		}
	}

	private void setupAdblock() {
		adblockEngine = AdblockEngine.Companion.create();

		try {
			String filters = readAsset("adblock/easylist.txt");

			if (!filters.isEmpty()) {
				adblockEngine.loadFilterList(filters);
			}
		} catch (Exception ignored) {
			// Continue without the filter list if it cannot be loaded.
		}

		try {
			String resources = readAsset("adblock/resources.json");

			if (!resources.isEmpty()) {
				adblockEngine.loadResources(resources);
			}
		} catch (Exception ignored) {
			// Continue without optional adblock resources.
		}
	}

	private String readAsset(String assetPath) throws Exception {
		StringBuilder content = new StringBuilder();

		try (InputStream input = getAssets().open(assetPath);
			 BufferedReader reader = new BufferedReader(
					 new InputStreamReader(
							 input, StandardCharsets.UTF_8
					 )
			 )) {

			String line;

			while ((line = reader.readLine()) != null) {
				content.append(line).append('\n');
			}
		}

		return content.toString();
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void setupWebView() {
		WebSettings settings = webView.getSettings();

		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setLoadsImagesAutomatically(true);
		settings.setMediaPlaybackRequiresUserGesture(false);
		settings.setLoadWithOverviewMode(true);
		settings.setUseWideViewPort(true);
		settings.setCacheMode(WebSettings.LOAD_DEFAULT);
		settings.setBuiltInZoomControls(false);
		settings.setDisplayZoomControls(false);
		settings.setSupportZoom(false);

		settings.setAllowFileAccess(false);
		settings.setAllowContentAccess(false);

		settings.setJavaScriptCanOpenWindowsAutomatically(false);
		settings.setSupportMultipleWindows(false);

		webView.setWebViewClient(new WebViewClient() {

			@Override
			public void onPageStarted(
					WebView view,
					String url,
					Bitmap favicon
			) {
				currentPageUrl = url;
				super.onPageStarted(view, url, favicon);
			}

			@Override
			public void onPageFinished(WebView view, String url) {
				currentPageUrl = url;
				super.onPageFinished(view, url);
			}

			@Override
			public boolean shouldOverrideUrlLoading(
					WebView view,
					WebResourceRequest request
			) {
				return !isAllowedNavigation(request.getUrl());
			}

			@SuppressWarnings("deprecation")
			@Override
			public boolean shouldOverrideUrlLoading(
					WebView view,
					String url
			) {
				return !isAllowedNavigation(Uri.parse(url));
			}

			@Nullable
			@Override
			public WebResourceResponse shouldInterceptRequest(
					WebView view,
					WebResourceRequest request
			) {
				AdblockEngine engine = adblockEngine;

				if (engine == null) {
					return super.shouldInterceptRequest(view, request);
				}

				String url = request.getUrl().toString();
				String sourceUrl = currentPageUrl;

				String accept = request.getRequestHeaders() == null
						? null
						: request.getRequestHeaders().get("Accept");

				String requestType =
						RequestTypeMapper.INSTANCE.from(
								Uri.parse(url),
								accept
						);

				try {
					org.mlm.adblock.BlockDecision decision =
							engine.checkNetworkRequest(
									url,
									sourceUrl,
									requestType
							);

					if (decision != null && decision.getMatched()) {
						return new WebResourceResponse(
								"text/plain",
								"UTF-8",
								new ByteArrayInputStream(new byte[0])
						);
					}
				} catch (Exception ignored) {
					// Fail open if the adblock check fails.
				}

				return super.shouldInterceptRequest(view, request);
			}
		});

		webView.setWebChromeClient(new WebChromeClient() {

			@Override
			public void onShowCustomView(
					View view,
					CustomViewCallback callback
			) {
				if (view == null) {
					if (callback != null) {
						callback.onCustomViewHidden();
					}
					return;
				}

				if (customView != null) {
					if (callback != null) {
						callback.onCustomViewHidden();
					}
					return;
				}

				customView = view;
				customViewCallback = callback;

				previousRequestedOrientation =
						getRequestedOrientation();

				ViewParentHelper.detachFromParent(view);

				fullscreenContainer.addView(
						view,
						new FrameLayout.LayoutParams(
								FrameLayout.LayoutParams.MATCH_PARENT,
								FrameLayout.LayoutParams.MATCH_PARENT
						)
				);

				webView.setVisibility(View.GONE);
				fullscreenContainer.setVisibility(View.VISIBLE);

				setRequestedOrientation(
						ActivityInfo.SCREEN_ORIENTATION_SENSOR
				);

				enterFullscreen();
			}

			@Override
			public void onHideCustomView() {
				exitFullscreen();
			}
		});
	}

	private boolean isAllowedNavigation(Uri uri) {
		if (uri == null || !isHttpOrHttps(uri.getScheme())) {
			return false;
		}

		String host = normalizeHost(uri.getHost());

		if (host == null) {
			return false;
		}

		if (matchesHost(host, PAGE_HOST)) {
			return true;
		}

		String[] allowedHosts = PageUrls;

		for (String allowedHost : allowedHosts) {
			if (matchesHost(host, allowedHost)) {
				return true;
			}
		}

		// Return true from shouldOverrideUrlLoading to block navigation.
		return false;
	}

	private static boolean matchesHost(
			String actualHost,
			String allowedHost
	) {
		return actualHost != null
				&& allowedHost != null
				&& (
					actualHost.equals(allowedHost)
					|| actualHost.endsWith("." + allowedHost)
				);
	}

	@Nullable
	private static String normalizeHost(@Nullable String value) {
		if (value == null) {
			return null;
		}

		String input = value.trim();

		if (input.isEmpty()) {
			return null;
		}

		Uri uri = Uri.parse(
				input.contains("://") ? input : "https://" + input
		);

		String host = uri.getHost();

		if (host == null || host.isEmpty()) {
			return null;
		}

		host = host.toLowerCase(Locale.ROOT);

		// A trailing dot is equivalent in ordinary DNS hostname notation.
		while (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}

		return host.isEmpty() ? null : host;
	}

	private static boolean isHttpOrHttps(@Nullable String scheme) {
		return scheme != null
				&& (
					scheme.equalsIgnoreCase("http")
					|| scheme.equalsIgnoreCase("https")
				);
	}

	private void setupBackButton() {
		getOnBackPressedDispatcher().addCallback(
				this,
				new OnBackPressedCallback(true) {

					@Override
					public void handleOnBackPressed() {
						if (customView != null) {
							exitFullscreen();
						} else if (webView != null
								&& webView.canGoBack()) {
							webView.goBack();
						} else {
							finish();
						}
					}
				}
		);
	}

	private void enterFullscreen() {
		if (fullscreenActive) {
			return;
		}

		previousDecorFitsSystemWindows = true;
		fullscreenActive = true;

		WindowCompat.setDecorFitsSystemWindows(
				getWindow(), false
		);

		WindowInsetsControllerCompat controller =
				new WindowInsetsControllerCompat(
						getWindow(),
						getWindow().getDecorView()
				);

		controller.hide(WindowInsetsCompat.Type.systemBars());

		controller.setSystemBarsBehavior(
				WindowInsetsControllerCompat
						.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		);
	}

	private void exitFullscreen() {
		if (customView == null && !fullscreenActive) {
			return;
		}

		View view = customView;
		WebChromeClient.CustomViewCallback callback =
				customViewCallback;

		// Clear the references first to prevent re-entrant cleanup.
		customView = null;
		customViewCallback = null;

		if (view != null && fullscreenContainer != null) {
			fullscreenContainer.removeView(view);
		}

		if (fullscreenContainer != null) {
			fullscreenContainer.setVisibility(View.GONE);
		}

		if (webView != null) {
			webView.setVisibility(View.VISIBLE);
		}

		setRequestedOrientation(previousRequestedOrientation);

		if (fullscreenActive) {
			WindowCompat.setDecorFitsSystemWindows(
					getWindow(),
					previousDecorFitsSystemWindows
			);

			WindowInsetsControllerCompat controller =
					new WindowInsetsControllerCompat(
							getWindow(),
							getWindow().getDecorView()
					);

			controller.show(WindowInsetsCompat.Type.systemBars());

			fullscreenActive = false;
		}

		if (callback != null) {
			callback.onCustomViewHidden();
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
		if (customView != null) {
			exitFullscreen();
		}

		if (webView != null) {
			webView.stopLoading();
			webView.setWebChromeClient(null);
			webView.setWebViewClient(null);

			if (webView.getParent() instanceof ViewGroup) {
				((ViewGroup) webView.getParent()).removeView(webView);
			}

			webView.destroy();
			webView = null;
		}

		if (adblockEngine != null) {
			adblockEngine.close();
			adblockEngine = null;
		}

		super.onDestroy();
	}

	/**
	 * Detaches a fullscreen video view from its previous parent,
	 * if it already has one.
	 */
	private static final class ViewParentHelper {
		private ViewParentHelper() {
		}

		static void detachFromParent(View view) {
			android.view.ViewParent parent = view.getParent();

			if (parent instanceof ViewGroup) {
				((ViewGroup) parent).removeView(view);
			}
		}
	}
}
