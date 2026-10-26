package com.mrr736.webinit;

import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketException;

public class WebInit extends AppCompatActivity {

	private static final String TAG = "WebInit";

	private static final int REQUEST_SAVE_FILE = 0x736;
	private static final int REQUEST_OPEN_FILE = 0x737;

	/*
	 * ==========================================================
	 * INIT DATA
	 * ==========================================================
	 */

	private WebView browser;
	private FrameLayout root;
	private ProgressBar progressBar;

	private volatile boolean canGoBack;
	private volatile boolean initialized;
	private volatile boolean nativeInitialized;

	private String localAddress;
	private int localPort;
	private String localUrl;
	private String pak;

	private String baseFileName = "download";

	private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

	private ValueCallback<Uri[]> pendingFileCallback;

	private volatile PendingDownload pendingDownload;

	/*
	 * Only one Blob download is transferred at a time.
	 */
	private volatile BlobTransfer blobTransfer;

	private final Map<String, String> blobFileNames = new ConcurrentHashMap<>();

	public void setBaseFileName(String filename) {
		if (filename.isEmpty()) {
			baseFileName = "download";
		}
		baseFileName = filename;
	}

	/*
	 * ==========================================================
	 * MIME NORMALIZATION
	 * ==========================================================
	 */

	private static String normalizeMimeType(String mimeType) {

		if (mimeType == null) {
			return "";
		}

		String mime = mimeType.trim().toLowerCase(Locale.US);

		if (mime.isEmpty()) {
			return "";
		}

		int semicolon = mime.indexOf(';');
		if (semicolon >= 0) {
			mime = mime.substring(0,semicolon).trim();
		}

		/*
		 * STL.
		 */
		if (mime.equals("application/sla") ||
			mime.equals("application/vnd.ms-pki.stl") || mime.equals("model/x.stl-binary") ||
			mime.equals("model/x.stl-ascii") || mime.equals("model/stl")) {
			return "model/stl";
		}

		/*
		 * GLTF JSON.
		 */
		if (mime.equals("application/gltf+json") || mime.equals("model/gltf+json")) {
			return "model/gltf+json";
		}

		/*
		 * GLTF binary.
		 */
		if (mime.equals("application/gltf-binary") ||mime.equals("model/gltf-binary")) {
			return "model/gltf-binary";
		}

		/*
		 * ZIP.
		 */
		if (mime.equals("application/x-zip-compressed") || mime.equals("multipart/x-zip") || mime.equals("application/zip")) {

			return "application/zip";
		}

		/*
		 * JavaScript.
		 */
		if (mime.equals("application/javascript") || mime.equals("application/x-javascript")) {
			return "text/javascript";
		}

		/*
		 * JSON.
		 */
		if (mime.equals("text/json")) {
			return "application/json";
		}

		return mime;
	}


	/*
	 * BLOB TRANSFER
	 */
	private static final class BlobTransfer {
		File file;
		OutputStream output;
		String fileName;
		String mimeType;
	}


	/*
	 * PENDING DOWNLOAD
	 */

	private static final class PendingDownload {
		File stagedFile;
		String url;
		String fileName;
		String mimeType;
	}


	/*
	 * ==========================================================
	 * JAVASCRIPT BLOB BRIDGE
	 * ==========================================================
	 */

	private final class BlobDownloadBridge {
		@JavascriptInterface
		public void start(String name,String mime) {
			/*
			 * All Blob operations go through the same executor.
			 *
			 * This guarantees:
			 *
			 * start
			 * chunk
			 * chunk
			 * chunk
			 * finish
			 *
			 * are processed in order.
			 */
			downloadExecutor.execute(
				() -> startBlobTransfer(name,mime)
			);
		}


		@JavascriptInterface
		public void chunk(String data) {
			downloadExecutor.execute(
				() -> writeBlobChunk(data)
			);
		}


		@JavascriptInterface
		public void finish() {
			downloadExecutor.execute(
				() -> finishBlobTransferInternal(true)
			);
		}


		@JavascriptInterface
		public void fail(String message) {
			Log.e(TAG,"Blob download failed: " + message);
			downloadExecutor.execute(
				() -> finishBlobTransferInternal(false)
			);
		}

		@JavascriptInterface
		public void rememberFileName(String url, String name) {
			if (url == null || name == null) {
				return;
			}

			String key = url.trim();
			String safeName = sanitizeFileName(name);

			if (!key.startsWith("blob:") || safeName == null || safeName.isEmpty()) {
				return;
			}

			blobFileNames.put(key, safeName);
			Log.d(TAG, "Captured Blob filename: " + safeName + " for " + key);
		}

	}


	/*
	 * ==========================================================
	 * INIT
	 * ==========================================================
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

	// random port and address
	public void Init(String pak) {
		if (pak == null || pak.isEmpty()) {
			throw new IllegalArgumentException("PAK file name cannot be null or empty");
		}

		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to find an available port", e);
		}

		String address;
		try {
			address = InetAddress.getLocalHost().getHostAddress();
		} catch (IOException e) {
			throw new IllegalStateException("Unable to determine local address", e);
		}

		Init(pak, port, address);
	}


	/*
	 * ==========================================================
	 * CREATE
	 * ==========================================================
	 */

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (pak == null || pak.isEmpty()) {
			throw new IllegalStateException("WebInit.Init() must be called before " + "Activity creation");
		}

		if (!Native.SetPackageName(getPackageName())) {
			throw new IllegalStateException("Failed to set WebInit package name: " + getPackageName());
		}

		createRoot();
		configureBackButton();

		new Thread(this::initializeNative,"WebInit-NativeInit").start();
	}


	/*
	 * ==========================================================
	 * ROOT
	 * ==========================================================
	 */

	private void createRoot() {
		root = new FrameLayout(this);
		setContentView(root);
		progressBar = new ProgressBar(this);
		FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT);
		params.gravity = Gravity.CENTER;
		root.addView(progressBar,params);
	}


	/*
	 * ==========================================================
	 * BACK BUTTON
	 * ==========================================================
	 */

	private void configureBackButton() {
		getOnBackPressedDispatcher().addCallback(
			this,
			new androidx.activity.OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					if (browser != null && browser.canGoBack()) {
						browser.goBack();
						return;
					}
					finish();
				}
			}
		);
	}


	/*
	 * ==========================================================
	 * NATIVE INITIALIZATION
	 * ==========================================================
	 */

	private void initializeNative() {
		try {
			String pakPath = extractAssetToCache(pak);
			if (!Native.SetAddressHost(localAddress,localPort)) {
				throw new IllegalStateException("Native.SetAddressHost() failed");
			}

			if (!Native.SetCacheDir(getCacheDir().getAbsolutePath())) {
				throw new IllegalStateException("Native.SetCacheDir() failed");
			}

			boolean ok = Native.Init(pakPath);
			if (!ok) {
				runOnUiThread(this::finish);
				return;
			}
			nativeInitialized = true;
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) { return; }
				initialized = true;
				createBrowser();
			});
		} catch (Exception e) {
			Log.e(TAG,"Native initialization failed",e);
			runOnUiThread(this::finish);
		}
	}


	/*
	 * PAK EXTRACTION
	 */

	private String extractAssetToCache(String assetName) throws IOException {
		File cacheDir = new File(getCacheDir(),"assets");
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new IOException("Failed creating cache directory: " +cacheDir);
		}

		String fileName = new File(assetName).getName();
		File pakFile = new File(cacheDir,fileName);

		if (pakFile.isFile() && pakFile.length() > 0) {
			return pakFile.getAbsolutePath();
		}

		File tempFile = new File(cacheDir,fileName + ".tmp");

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
				output.write(buffer,0,read);
			}
			output.flush();
		} catch (IOException e) {
			tempFile.delete();
			throw e;
		}

		if (!tempFile.isFile() || tempFile.length() <= 0) {
			tempFile.delete();
			throw new IOException("PAK extraction failed: " + assetName);
		}

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


	/*
	 * ==========================================================
	 * WEBVIEW
	 * ==========================================================
	 */

	private void createBrowser() {

		browser =
				new WebView(this);

		FrameLayout.LayoutParams params =
				new FrameLayout.LayoutParams(
						FrameLayout.LayoutParams.MATCH_PARENT,
						FrameLayout.LayoutParams.MATCH_PARENT
				);

		root.addView(
				browser,
				0,
				params
		);

		WebSettings settings =
				browser.getSettings();

		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setDatabaseEnabled(true);
		settings.setAllowFileAccess(true);
		settings.setAllowContentAccess(true);
		settings.setBuiltInZoomControls(false);
		settings.setDisplayZoomControls(false);
		settings.setSupportMultipleWindows(true);
		settings.setJavaScriptCanOpenWindowsAutomatically(true);
		settings.setMediaPlaybackRequiresUserGesture(false);

		CookieManager.getInstance()
				.setAcceptCookie(true);

		CookieManager.getInstance()
				.setAcceptThirdPartyCookies(
						browser,
						true
				);

		browser.addJavascriptInterface(
				new BlobDownloadBridge(),
				"WebInitBlob"
		);

		browser.setWebViewClient(
				new BrowserWebViewClient()
		);

		browser.setWebChromeClient(
				new BrowserWebChromeClient()
		);

		browser.setDownloadListener(
				new BrowserDownloadListener()
		);

		browser.loadUrl(localUrl);
	}


	/*
	 * ==========================================================
	 * WEBVIEW CLIENT
	 * ==========================================================
	 */

	private final class BrowserWebViewClient
			extends WebViewClient {

		@Override
		public boolean shouldOverrideUrlLoading(
				WebView view,
				WebResourceRequest request
		) {

			if (
					request == null ||
					request.getUrl() == null
			) {

				return true;
			}

			return handleWebNavigation(
					request.getUrl(),
					request.isForMainFrame()
			);
		}


		@Override
		public boolean shouldOverrideUrlLoading(
				WebView view,
				String url
		) {

			if (url == null) {
				return true;
			}

			return handleWebNavigation(
					Uri.parse(url),
					true
			);
		}


		@Override
		public void onPageStarted(
				WebView view,
				String url,
				android.graphics.Bitmap favicon
		) {

			canGoBack =
					view.canGoBack();

			installBlobFilenameCapture();

			runOnUiThread(() -> {

				if (
						progressBar != null &&
						progressBar.getParent() == null &&
						root != null
				) {

					root.addView(
							progressBar
					);
				}
			});
		}


		@Override
		public void onPageFinished(WebView view, String url) {
			canGoBack = view.canGoBack();
			installBlobFilenameCapture();
			runOnUiThread(() -> {
				if (progressBar != null && progressBar.getParent() != null && root != null) {
					root.removeView(progressBar);
				}
			});
		}


		@Override
		public void onReceivedError(
				WebView view,
				WebResourceRequest request,
				WebResourceError error
		) {

			super.onReceivedError(
					view,
					request,
					error
			);

			Log.e(
					TAG,
					"WebView error: " +
							(
									error == null
											? "unknown"
											: error.getDescription()
							)
			);
		}
	}


	private boolean handleWebNavigation(
			Uri uri,
			boolean mainFrame
	) {

		if (uri == null) {
			return true;
		}

		String value =
				uri.toString();

		if (
				isLocalUri(uri) ||
				value.startsWith("about:") ||
				value.startsWith("blob:") ||
				value.startsWith("data:") ||
				value.startsWith("javascript:")
		) {

			return false;
		}

		openExternal(uri);

		return true;
	}


	/*
	 * ==========================================================
	 * WEB CHROME CLIENT
	 * ==========================================================
	 */

	private final class BrowserWebChromeClient
			extends WebChromeClient {

		@Override
		public boolean onShowFileChooser(
				WebView webView,
				ValueCallback<Uri[]> filePathCallback,
				FileChooserParams fileChooserParams
		) {

			if (pendingFileCallback != null) {

				pendingFileCallback
						.onReceiveValue(null);
			}

			pendingFileCallback =
					filePathCallback;

			Intent intent =
					new Intent(
							Intent.ACTION_OPEN_DOCUMENT
					);

			intent.addCategory(
					Intent.CATEGORY_OPENABLE
			);

			intent.addFlags(
					Intent.FLAG_GRANT_READ_URI_PERMISSION
			);

			intent.setType("*/*");

			boolean multiple =
					fileChooserParams != null &&
							fileChooserParams.getMode() ==
									FileChooserParams.MODE_OPEN_MULTIPLE;

			intent.putExtra(
					Intent.EXTRA_ALLOW_MULTIPLE,
					multiple
			);

			try {

				startActivityForResult(
						intent,
						REQUEST_OPEN_FILE
				);

				return true;

			} catch (Exception e) {

				Log.e(
						TAG,
						"Unable to open Android file picker",
						e
				);

				pendingFileCallback = null;

				filePathCallback
						.onReceiveValue(null);

				return false;
			}
		}


		@Override
		public boolean onCreateWindow(
				WebView view,
				boolean isDialog,
				boolean isUserGesture,
				android.os.Message resultMsg
		) {

			final WebView.HitTestResult hit =
					view.getHitTestResult();

			String extra =
					hit == null
							? null
							: hit.getExtra();

			if (extra != null) {

				Uri uri =
						Uri.parse(extra);

				if (isLocalUri(uri)) {

					view.loadUrl(extra);

				} else {

					openExternal(uri);
				}
			}

			return false;
		}


		@Override
		public void onCloseWindow(
				WebView window
		) {

			if (window != null) {
				window.stopLoading();
			}
		}
	}


	/*
	 * ==========================================================
	 * BLOB FILENAME CAPTURE
	 * ==========================================================
	 *
	 * A blob: URL carries no filename (no Content-Disposition, no path),
	 * so DownloadListener.onDownloadStart() cannot know it. The only place
	 * the name exists is the <a download="name"> element that the page
	 * clicks. This script records "blob URL -> download attribute" through
	 * WebInitBlob.rememberFileName() BEFORE the WebView starts the native
	 * download, then onDownloadStart() looks the name up in blobFileNames.
	 *
	 * Covered cases:
	 *  - a.click() on a detached anchor (most common; no DOM event bubbles
	 *    to document, so a document click listener alone misses it)
	 *  - a.click() on an attached anchor
	 *  - a.dispatchEvent(new MouseEvent('click'))
	 *  - real user taps on <a download href="blob:...">
	 */
	private void installBlobFilenameCapture() {
		if (browser == null) {
			return;
		}

		String js =
			"(function() {" +
			"  if (window.__webinitBlobNameHook) return;" +
			"  window.__webinitBlobNameHook = true;" +

			"  function remember(a) {" +
			"    try {" +
			"      if (!a || !a.hasAttribute || !a.hasAttribute('download')) return;" +
			"      const url = a.href || '';" +
			"      if (url.indexOf('blob:') !== 0) return;" +
			"      const name = (a.getAttribute('download') || '').trim();" +
			"      if (!name || !window.WebInitBlob) return;" +
			// Synchronous bridge call: finishes before the download starts.
			"      window.WebInitBlob.rememberFileName(url, name);" +
			"    } catch (e) {}" +
			"  }" +

			// Programmatic a.click(), attached or detached.
			"  const nativeClick = HTMLAnchorElement.prototype.click;" +
			"  HTMLAnchorElement.prototype.click = function() {" +
			"    remember(this);" +
			"    return nativeClick.apply(this, arguments);" +
			"  };" +

			// dispatchEvent(new MouseEvent('click')) on an anchor.
			"  const nativeDispatch = EventTarget.prototype.dispatchEvent;" +
			"  EventTarget.prototype.dispatchEvent = function(ev) {" +
			"    if (ev && ev.type === 'click' && this instanceof HTMLAnchorElement) remember(this);" +
			"    return nativeDispatch.apply(this, arguments);" +
			"  };" +

			// Real taps (capture phase, before the default action).
			"  document.addEventListener('click', function(event) {" +
			"    let el = event.target;" +
			"    if (el && el.nodeType !== 1) el = el.parentElement;" +
			"    if (!el || !el.closest) return;" +
			"    remember(el.closest('a'));" +
			"  }, true);" +

			"})();";

		browser.evaluateJavascript(js, null);
	}


	/*
	 * ==========================================================
	 * DOWNLOAD LISTENER
	 * ==========================================================
	 */

	private final class BrowserDownloadListener
			implements DownloadListener {

		@Override
		public void onDownloadStart(
				String url,
				String userAgent,
				String contentDisposition,
				String mimeType,
				long contentLength
		) {

			if (
					url == null ||
					url.isEmpty()
			) {

				return;
			}

			String suggested =
					parseContentDispositionFileName(
							contentDisposition
					);

			Log.d(
					TAG,
					"URL : " + url
			);

			Log.d(
					TAG,
					"Content Disposition : " +
							contentDisposition
			);

			Log.d(
					TAG,
					"FileName : " +
							suggested
			);

			Log.d(
					TAG,
					"MimeType : " +
							mimeType
			);

			String fileName = null;

			// Blob URL: use the filename captured from JavaScript.
			if (url.startsWith("blob:")) {
				fileName = blobFileNames.remove(url);
				if (fileName == null || fileName.isEmpty()) {
					Log.w(TAG, "Blob filename not captured: " + url);
				}
			}

			// Fall back to the existing filename detection.
			if (fileName == null || fileName.isEmpty()) {
				fileName = getDownloadFileName(
					url,
					suggested,
					mimeType
				);
			}

			fileName = sanitizeFileName(fileName);

			if (fileName == null || fileName.isEmpty()) {
				fileName = baseFileName;
			}

			String mime =
					normalizeMimeType(
							mimeType
					);

			/*
			 * If WebView did not provide a useful MIME type,
			 * use the filename.
			 */
			String extensionMime =
					Native.MimeTypeFromFileName(
							fileName
					);

			if (
					mime.isEmpty() ||
					mime.equals("*/*") ||
					mime.equals("application/octet-stream")
			) {

				if (
						extensionMime != null &&
						!extensionMime.isEmpty()
				) {

					mime =
							normalizeMimeType(
									extensionMime
							);
				}
			}

			if (
					mime.isEmpty() ||
					mime.equals("*/*")
			) {

				mime =
						"application/octet-stream";
			}

			/*
			 * Blob URL.
			 */
			if (url.startsWith("blob:")) {

				startBlobDownloadFromWebView(
						url,
						fileName,
						mime
				);

				return;
			}

			/*
			 * Normal HTTP/HTTPS download.
			 */
			PendingDownload download =
					new PendingDownload();

			download.url =
					url;

			download.fileName =
					fileName;

			download.mimeType =
					mime;

			pendingDownload =
					download;

			final String finalUserAgent =
					userAgent;

			final String finalDisposition =
					contentDisposition;

			downloadExecutor.execute(
					() -> downloadHttpUrl(
							download,
							finalUserAgent,
							finalDisposition
					)
			);
		}
	}


	/*
	 * ==========================================================
	 * BLOB DOWNLOAD
	 * ==========================================================
	 */

	private void startBlobDownloadFromWebView(String url,String fileName,String mimeType) {
		if (browser == null) {
			return;
		}

		final String jsUrl = escapeJs(url);
		final String jsName = escapeJs(fileName);
		final String jsMime = escapeJs(mimeType);

		/*
		 * The Blob itself remains inside the WebView.
		 *
		 * JavaScript reads it in 256 KB chunks and sends
		 * Base64 chunks through the JavascriptInterface.
		 *
		 * This avoids creating one huge Base64 string.
		 */
		String script =
				"(async()=>{" +
					"try{" +
						"const r=await fetch('" + jsUrl + "');" +
						"if(!r.ok)" + "throw new Error('HTTP '+r.status);" +
						"const b=await r.blob();" +
						"window.WebInitBlob.start('" + jsName + "','" + jsMime + "');" +
						"const size=262144;" +
						"for(let p=0;p<b.size;p+=size){" +
							"const a=await b.slice(" + "p," + "Math.min(p+size,b.size)" + ").arrayBuffer();" +
							"let s='';" +
							"const u=new Uint8Array(a);" +
							"for(let i=0;i<u.length;i+=8192){" +
								"s+=String.fromCharCode(" + "...u.subarray(" + "i," + "Math.min(i+8192,u.length)" + ")" + ");" +
							"}" +
							"window.WebInitBlob.chunk(btoa(s));" +
						"}" +
						"window.WebInitBlob.finish();" +
					"}catch(e){" +
						"window.WebInitBlob.fail(" + "String(e)" + ");" +
					"}" +
				"})();";

		browser.evaluateJavascript(script,null);
	}


	/*
	 * ==========================================================
	 * JAVASCRIPT ESCAPE
	 * ==========================================================
	 */

	private String escapeJs(String value) {
		if (value == null) {
			return "";
		}

		return value
				.replace("\\","\\\\")
				.replace("'","\\'")
				.replace("\n","\\n")
				.replace("\r","\\r")
				.replace("\u2028","\\u2028")
				.replace("\u2029","\\u2029");
	}


	/*
	 * ==========================================================
	 * START BLOB TRANSFER
	 * ==========================================================
	 */

	private void startBlobTransfer(String name,String mime) {
		synchronized (this) {
			/*
			 * Cancel previous Blob transfer.
			 */
			if (blobTransfer != null) {
				BlobTransfer old = blobTransfer;
				blobTransfer = null;
				try {
					if (old.output != null) {
						old.output.close();
					}
				} catch (Exception ignored) { }
				if (old.file != null) {
					old.file.delete();
				}
			}

			try {
				File directory = new File(getCacheDir(),"pending-downloads");
				if (!directory.exists() && !directory.mkdirs()) {
					throw new IOException("Unable to create cache");
				}

				String safeName = sanitizeFileName(name);
				if (safeName == null || safeName.isEmpty()) {
					safeName = baseFileName;
				}

				File file = File.createTempFile("blob_","_" + safeName,directory);
				BlobTransfer transfer = new BlobTransfer();
				transfer.file = file;
				transfer.output = new FileOutputStream(file);
				transfer.fileName = safeName;
				transfer.mimeType = normalizeMimeType(mime);
				if (transfer.mimeType.isEmpty() || transfer.mimeType.equals("*/*")) {
					String nativeMime = Native.MimeTypeFromFileName(safeName);
					if (nativeMime != null && !nativeMime.isEmpty()) {
						transfer.mimeType = normalizeMimeType(nativeMime);
					}
				}

				if (transfer.mimeType.isEmpty() || transfer.mimeType.equals("*/*")) {
					transfer.mimeType = "application/octet-stream";
				}

				blobTransfer = transfer;
				Log.d(TAG,"Blob staging started: " + safeName);
			} catch (Exception e) {
				Log.e(TAG,"Unable to create Blob staging file",e);
				blobTransfer = null;
			}
		}
	}


	/*
	 * ==========================================================
	 * WRITE BLOB CHUNK
	 * ==========================================================
	 */

	private void writeBlobChunk(
			String data
	) {

		BlobTransfer transfer =
				blobTransfer;

		if (
				transfer == null ||
				transfer.output == null ||
				data == null
		) {

			return;
		}

		try {

			byte[] bytes =
					Base64.decode(
							data,
							Base64.DEFAULT
					);

			transfer.output.write(
					bytes
			);

		} catch (Exception e) {

			Log.e(
					TAG,
					"Blob chunk failed",
					e
			);

			finishBlobTransferInternal(
					false
			);
		}
	}


	/*
	 * ==========================================================
	 * FINISH BLOB TRANSFER
	 * ==========================================================
	 */

	private void finishBlobTransferInternal(
			boolean success
	) {

		BlobTransfer transfer;

		synchronized (this) {

			transfer =
					blobTransfer;

			blobTransfer =
					null;
		}

		if (transfer == null) {
			return;
		}

		try {

			if (transfer.output != null) {
				transfer.output.flush();
				transfer.output.close();
			}

		} catch (Exception e) {

			Log.e(
					TAG,
					"Unable to close Blob output",
					e
			);

			success = false;
		}

		if (
				!success ||
				transfer.file == null ||
				!transfer.file.isFile()
		) {

			if (transfer.file != null) {
				transfer.file.delete();
			}

			return;
		}

		PendingDownload download =
				new PendingDownload();

		download.stagedFile =
				transfer.file;

		download.fileName =
				transfer.fileName;

		download.mimeType =
				transfer.mimeType;

		pendingDownload =
				download;

		Log.d(
				TAG,
				"Blob staging finished: " +
						download.fileName +
						" (" +
						download.stagedFile.length() +
						" bytes)"
		);

		runOnUiThread(() -> {

			if (
					pendingDownload != download
			) {

				deleteStagedFile(
						download
				);

				return;
			}

			showSaveDialog(
					download.fileName,
					download.mimeType
			);
		});
	}


	/*
	 * ==========================================================
	 * CANCEL BLOB TRANSFER
	 * ==========================================================
	 */

	private void cancelBlobTransfer() {

		BlobTransfer transfer;

		synchronized (this) {

			transfer =
					blobTransfer;

			blobTransfer =
					null;
		}

		if (transfer == null) {
			return;
		}

		try {

			if (transfer.output != null) {
				transfer.output.close();
			}

		} catch (Exception ignored) {
		}

		if (transfer.file != null) {
			transfer.file.delete();
		}
	}


	/*
	 * ==========================================================
	 * HTTP DOWNLOAD
	 * ==========================================================
	 */

	private void downloadHttpUrl(
			PendingDownload download,
			String userAgent,
			String contentDisposition
	) {

		HttpURLConnection connection =
				null;

		File staged =
				null;

		try {

			URL url =
					new URL(
							download.url
					);

			connection =
					(HttpURLConnection)
							url.openConnection();

			connection.setInstanceFollowRedirects(
					true
			);

			connection.setConnectTimeout(
					30000
			);

			connection.setReadTimeout(
					120000
			);

			if (
					userAgent != null &&
					!userAgent.isEmpty()
			) {

				connection.setRequestProperty(
						"User-Agent",
						userAgent
				);
			}

			String cookies =
					CookieManager
							.getInstance()
							.getCookie(
									download.url
							);

			if (
					cookies != null &&
					!cookies.isEmpty()
			) {

				connection.setRequestProperty(
						"Cookie",
						cookies
				);
			}

			int code =
					connection.getResponseCode();

			if (
					code < 200 ||
					code >= 400
			) {

				throw new IOException(
						"HTTP " + code
				);
			}

			File directory =
					new File(
							getCacheDir(),
							"pending-downloads"
					);

			if (
					!directory.exists() &&
					!directory.mkdirs()
			) {

				throw new IOException(
						"Unable to create cache"
				);
			}

			String safeName =
					sanitizeFileName(
							download.fileName
					);

			if (
					safeName == null ||
					safeName.isEmpty()
			) {

				safeName = baseFileName;
			}

			staged =
					File.createTempFile(
							"download_",
							"_" + safeName,
							directory
					);

			try (
					InputStream in =
							connection.getInputStream();

					OutputStream out =
							new FileOutputStream(
									staged
							)
			) {

				byte[] buffer =
						new byte[1024 * 1024];

				int read;

				while (
						(read = in.read(buffer)) != -1
				) {

					out.write(
							buffer,
							0,
							read
					);
				}

				out.flush();
			}

			download.stagedFile =
					staged;

			if (
					pendingDownload != download
			) {

				staged.delete();

				return;
			}

			runOnUiThread(
					() -> showSaveDialog(
							download.fileName,
							download.mimeType
					)
			);

		} catch (Exception e) {

			Log.e(
					TAG,
					"WebView download failed",
					e
			);

			if (staged != null) {
				staged.delete();
			}

			if (
					pendingDownload == download
			) {

				pendingDownload =
						null;
			}

		} finally {

			if (connection != null) {
				connection.disconnect();
			}
		}
	}


	/*
	 * ==========================================================
	 * HEADERS
	 * ==========================================================
	 */

	private static String getHeader(
			Map<String, String> headers,
			String name
	) {

		if (
				headers == null ||
				name == null
		) {

			return "";
		}

		for (
				Map.Entry<String, String> entry :
				headers.entrySet()
		) {

			if (
					entry.getKey() != null &&
					entry.getKey().equalsIgnoreCase(name)
			) {

				return entry.getValue() == null
						? ""
						: entry.getValue();
			}
		}

		return "";
	}


	/*
	 * ==========================================================
	 * FILENAME
	 * ==========================================================
	 */

	protected String getDownloadFileName(
			String url,
			String suggestedFileName,
			String mimeType
	) {

		String suggested =
				sanitizeFileName(
						suggestedFileName
				);

		String mime =
				normalizeMimeType(
						mimeType
				);

		if (
				mime.isEmpty() ||
				mime.equals("*/*")
		) {

			String nativeMime =
					Native.MimeTypeFromFileName(
							suggested
					);

			if (
					nativeMime != null &&
					!nativeMime.isEmpty()
			) {

				mime =
						normalizeMimeType(
								nativeMime
						);
			}
		}

		/*
		 * 1. Content-Disposition.
		 */
		if (
				suggested != null &&
				!suggested.isEmpty() &&
				!looksLikeBlobUuid(suggested)
		) {

			return suggested;
		}

		/*
		 * 2. URL filename.
		 */
		if (
				url != null &&
				!url.isEmpty() &&
				!url.startsWith("blob:")
		) {

			String urlName =
					getUrlFileName(
							url
					);

			if (
					urlName != null &&
					!urlName.isEmpty() &&
					!looksLikeBlobUuid(urlName)
			) {

				return urlName;
			}
		}

		/*
		 * 3. MIME extension.
		 */
		String extension =
				Native.MimeTypetoExtension(
						mime
				);

		if (
				extension != null &&
				!extension.isEmpty()
		) {

			return "download." + extension;
		}

		Log.d(
				TAG,
				"Suggested FileName : " +
						suggestedFileName
		);

		Log.d(
				TAG,
				"Sanitize FileName : " +
						suggested
		);

		Log.d(
				TAG,
				"Type Extension : " +
						extension
		);

		return baseFileName;
	}


	/*
	 * ==========================================================
	 * URL FILENAME
	 * ==========================================================
	 */

	private String getUrlFileName(
			String url
	) {

		if (
				url == null ||
				url.isEmpty()
		) {

			return null;
		}

		try {

			Uri uri =
					Uri.parse(url);

			String path =
					uri.getPath();

			if (
					path == null ||
					path.isEmpty()
			) {

				return null;
			}

			int slash =
					path.lastIndexOf('/');

			String name =
					slash >= 0
							? path.substring(
							slash + 1
					)
							: path;

			if (name.isEmpty()) {
				return null;
			}

			try {

				name =
						URLDecoder.decode(
								name,
								"UTF-8"
						);

			} catch (Exception ignored) {
			}

			return sanitizeFileName(
					name
			);

		} catch (Exception e) {

			return null;
		}
	}


	/*
	 * ==========================================================
	 * CONTENT DISPOSITION
	 * ==========================================================
	 */

	private String parseContentDispositionFileName(
			String contentDisposition
	) {

		if (
				contentDisposition == null ||
				contentDisposition.isEmpty()
		) {

			return null;
		}

		try {

			String lower =
					contentDisposition
							.toLowerCase(
									Locale.US
							);

			/*
			 * filename*=UTF-8''file.ext
			 */
			int index =
					lower.indexOf(
							"filename*="
					);

			if (index >= 0) {

				String value =
						contentDisposition.substring(
								index + 10
						);

				int semicolon =
						value.indexOf(';');

				if (semicolon >= 0) {

					value =
							value.substring(
									0,
									semicolon
							);
				}

				value =
						value.trim();

				int separator =
						value.indexOf("''");

				if (separator >= 0) {

					value =
							value.substring(
									separator + 2
							);
				}

				if (
						value.length() >= 2 &&
						value.startsWith("\"") &&
						value.endsWith("\"")
				) {

					value =
							value.substring(
									1,
									value.length() - 1
							);
				}

				try {

					return URLDecoder.decode(
							value,
							"UTF-8"
					);

				} catch (Exception ignored) {

					return value;
				}
			}

			/*
			 * filename="file.ext"
			 */
			index =
					lower.indexOf(
							"filename="
					);

			if (index >= 0) {

				String value =
						contentDisposition.substring(
								index + 9
						);

				int semicolon =
						value.indexOf(';');

				if (semicolon >= 0) {

					value =
							value.substring(
									0,
									semicolon
							);
				}

				value =
						value.trim();

				if (
						value.length() >= 2 &&
						value.startsWith("\"") &&
						value.endsWith("\"")
				) {

					value =
							value.substring(
									1,
									value.length() - 1
							);
				}

				return value;
			}

		} catch (Exception e) {

			Log.e(
					TAG,
					"Failed parsing Content-Disposition",
					e
			);
		}

		return null;
	}


	/*
	 * ==========================================================
	 * BLOB UUID FILENAME
	 * ==========================================================
	 */

	private boolean looksLikeBlobUuid(
			String name
	) {

		if (name == null) {
			return false;
		}

		String value =
				name.trim();

		return value.matches(
				"(?i)" +
						"[0-9a-f]{8}-" +
						"[0-9a-f]{4}-" +
						"[0-9a-f]{4}-" +
						"[0-9a-f]{4}-" +
						"[0-9a-f]{12}" +
						"(\\.bin)?"
		);
	}


	/*
	 * ==========================================================
	 * LOCAL URI
	 * ==========================================================
	 */

	private boolean isLocalUri(
			Uri uri
	) {

		if (uri == null) {
			return false;
		}

		String scheme =
				uri.getScheme();

		String host =
				uri.getHost();

		int port =
				uri.getPort();

		if (
				scheme == null ||
				host == null
		) {

			return false;
		}

		boolean correctHost =
				localAddress != null &&
						(
								localAddress.equalsIgnoreCase(host) ||
								"localhost".equalsIgnoreCase(host) ||
								"127.0.0.1".equals(host)
						);

		boolean correctPort =
				port == -1 ||
						port == localPort;

		return
				"http".equalsIgnoreCase(scheme) &&
						correctHost &&
						correctPort;
	}


	/*
	 * ==========================================================
	 * EXTERNAL URI
	 * ==========================================================
	 */

	private void openExternal(
			Uri uri
	) {

		if (uri == null) {
			return;
		}

		Intent intent =
				new Intent(
						Intent.ACTION_VIEW,
						uri
				);

		intent.addCategory(
				Intent.CATEGORY_BROWSABLE
		);

		try {

			startActivity(intent);

		} catch (Exception e) {

			Log.e(
					TAG,
					"Unable to open external URI: " +
							uri,
					e
			);
		}
	}


	/*
	 * ==========================================================
	 * ADDRESS
	 * ==========================================================
	 */

	private static String getAddress(
			String address
	) {

		if (
				address == null ||
				address.isEmpty() ||
				"*".equals(address) ||
				"0.0.0.0".equals(address)
		) {

			return "127.0.0.1";
		}

		return address;
	}


	/*
	 * ==========================================================
	 * INITIALIZED
	 * ==========================================================
	 */

	public boolean isInitialized() {
		return initialized;
	}


	/*
	 * ==========================================================
	 * ACTIVITY RESULT
	 * ==========================================================
	 */

	@Override
	protected void onActivityResult(
			int requestCode,
			int resultCode,
			Intent data
	) {

		super.onActivityResult(
				requestCode,
				resultCode,
				data
		);

		if (
				requestCode ==
						REQUEST_OPEN_FILE
		) {

			handleOpenFileResult(
					resultCode,
					data
			);

			return;
		}

		if (
				requestCode ==
						REQUEST_SAVE_FILE
		) {

			PendingDownload download =
					pendingDownload;

			pendingDownload =
					null;

			if (download == null) {
				return;
			}

			if (
					resultCode != RESULT_OK ||
					data == null ||
					data.getData() == null
			) {

				deleteStagedFile(
						download
				);

				return;
			}

			File stagedFile =
					download.stagedFile;

			Uri destination =
					data.getData();

			if (
					stagedFile == null ||
					!stagedFile.isFile()
			) {

				return;
			}

			downloadExecutor.execute(
					() -> saveStagedFileToUri(
							stagedFile,
							destination
					)
			);
		}
	}


	/*
	 * ==========================================================
	 * OPEN FILE RESULT
	 * ==========================================================
	 */

	private void handleOpenFileResult(
			int resultCode,
			Intent data
	) {

		ValueCallback<Uri[]> callback =
				pendingFileCallback;

		pendingFileCallback =
				null;

		if (callback == null) {
			return;
		}

		if (
				resultCode != RESULT_OK ||
				data == null
		) {

			callback.onReceiveValue(null);

			return;
		}

		ArrayList<Uri> selected =
				new ArrayList<>();

		ClipData clipData =
				data.getClipData();

		if (clipData != null) {

			for (
					int i = 0;
					i < clipData.getItemCount();
					i++
			) {

				Uri uri =
						clipData
								.getItemAt(i)
								.getUri();

				if (uri != null) {
					selected.add(uri);
				}
			}

		} else if (data.getData() != null) {

			selected.add(
					data.getData()
			);
		}

		if (selected.isEmpty()) {

			callback.onReceiveValue(null);

			return;
		}

		/*
		 * Preserve persistable access when possible.
		 */
		for (Uri uri : selected) {

			try {

				getContentResolver()
						.takePersistableUriPermission(
								uri,
								Intent.FLAG_GRANT_READ_URI_PERMISSION
						);

			} catch (Exception ignored) {
			}
		}

		Uri[] result =
				selected.toArray(
						new Uri[0]
				);

		callback.onReceiveValue(
				result
		);
	}


	/*
	 * ==========================================================
	 * URI FILENAME
	 * ==========================================================
	 */

	private String getFileName(
			Uri uri
	) {

		if (uri == null) {
			return null;
		}

		Cursor cursor =
				null;

		try {

			cursor =
					getContentResolver().query(
							uri,
							new String[]{
									OpenableColumns.DISPLAY_NAME
							},
							null,
							null,
							null
					);

			if (
					cursor != null &&
					cursor.moveToFirst()
			) {

				int index =
						cursor.getColumnIndex(
								OpenableColumns.DISPLAY_NAME
						);

				if (index >= 0) {

					String name =
							cursor.getString(
									index
							);

					if (
							name != null &&
							!name.isEmpty()
					) {

						return name;
					}
				}
			}

		} catch (Exception e) {

			Log.w(
					TAG,
					"Unable to query filename: " +
							uri,
					e
			);

		} finally {

			if (cursor != null) {
				cursor.close();
			}
		}

		String path =
				uri.getPath();

		if (
				path != null &&
				!path.isEmpty()
		) {

			int slash =
					path.lastIndexOf('/');

			if (
					slash >= 0 &&
					slash + 1 < path.length()
			) {

				return path.substring(
						slash + 1
				);
			}

			return path;
		}

		return null;
	}


	/*
	 * ==========================================================
	 * SAVE DIALOG
	 * ==========================================================
	 */

	private void showSaveDialog(
			String fileName,
			String mimeType
	) {

		if (
				isFinishing() ||
				isDestroyed()
		) {

			closePendingDownload();

			return;
		}

		fileName =
				sanitizeFileName(
						fileName
				);

		if (
				fileName == null ||
				fileName.isEmpty()
		) {

			fileName = baseFileName;
		}

		mimeType =
				normalizeMimeType(
						mimeType
				);

		if (
				mimeType.isEmpty() ||
				mimeType.equals("*/*")
		) {

			String nativeMime =
					Native.MimeTypeFromFileName(
							fileName
					);

			if (
					nativeMime != null &&
					!nativeMime.isEmpty()
			) {

				mimeType =
						normalizeMimeType(
								nativeMime
						);
			}
		}

		if (
				mimeType.isEmpty() ||
				mimeType.equals("*/*")
		) {

			mimeType =
					"application/octet-stream";
		}

		Intent intent =
				new Intent(
						Intent.ACTION_CREATE_DOCUMENT
				);

		intent.addCategory(
				Intent.CATEGORY_OPENABLE
		);

		intent.setType(
				mimeType
		);

		intent.putExtra(
				Intent.EXTRA_TITLE,
				fileName
		);

		try {

			startActivityForResult(
					intent,
					REQUEST_SAVE_FILE
			);

		} catch (Exception e) {

			Log.e(
					TAG,
					"Unable to open save dialog",
					e
			);

			closePendingDownload();
		}
	}


	/*
	 * ==========================================================
	 * CLOSE PENDING DOWNLOAD
	 * ==========================================================
	 */

	private void closePendingDownload() {

		PendingDownload download =
				pendingDownload;

		pendingDownload =
				null;

		deleteStagedFile(
				download
		);
	}


	/*
	 * ==========================================================
	 * DELETE STAGED FILE
	 * ==========================================================
	 */

	private void deleteStagedFile(
			PendingDownload download
	) {

		if (
				download == null ||
				download.stagedFile == null
		) {

			return;
		}

		try {

			if (download.stagedFile.exists()) {
				download.stagedFile.delete();
			}

		} catch (Exception ignored) {
		}

		download.stagedFile =
				null;
	}


	/*
	 * ==========================================================
	 * SAVE STAGED FILE
	 * ==========================================================
	 */

	private void saveStagedFileToUri(
			File source,
			Uri destination
	) {

		if (
				source == null ||
				destination == null
		) {

			return;
		}

		try (
				InputStream input =
						new FileInputStream(source);

				OutputStream output =
						getContentResolver()
								.openOutputStream(
										destination,
										"w"
								)
		) {

			if (output == null) {

				throw new IOException(
						"Unable to open destination: " +
								destination
				);
			}

			byte[] buffer =
					new byte[1024 * 1024];

			int read;

			while (
					(read = input.read(buffer)) != -1
			) {

				output.write(
						buffer,
						0,
						read
				);
			}

			output.flush();

		} catch (Exception e) {

			Log.e(
					TAG,
					"Download failed",
					e
			);

		} finally {

			try {

				if (source.exists()) {
					source.delete();
				}

			} catch (Exception ignored) {
			}
		}
	}


	/*
	 * ==========================================================
	 * OPEN FILE
	 * ==========================================================
	 */

	private void openFile(
			Uri uri,
			String mimeType
	) {

		if (uri == null) {
			return;
		}

		String mime =
				normalizeMimeType(
						mimeType
				);

		if (
				mime.isEmpty() ||
				mime.equals("*/*")
		) {

			mime =
					"application/octet-stream";
		}

		Intent intent =
				new Intent(
						Intent.ACTION_VIEW
				);

		intent.setDataAndType(
				uri,
				mime
		);

		intent.addFlags(
				Intent.FLAG_GRANT_READ_URI_PERMISSION
		);

		try {

			startActivity(intent);

		} catch (Exception e) {

			Log.e(
					TAG,
					"No application can open: " +
							uri,
					e
			);
		}
	}


	/*
	 * ==========================================================
	 * OPEN RESPONSE
	 * ==========================================================
	 */

	protected void openResponse(
			PendingDownload download
	) {

		if (
				download == null ||
				download.stagedFile == null ||
				!download.stagedFile.isFile()
		) {

			return;
		}

		final File file =
				download.stagedFile;

		String mime =
				normalizeMimeType(
						download.mimeType
				);

		if (
				mime.isEmpty() ||
				mime.equals("*/*")
		) {

			String nativeMime =
					Native.MimeTypeFromFileName(
							download.fileName
					);

			if (
					nativeMime != null &&
					!nativeMime.isEmpty()
			) {

				mime =
						normalizeMimeType(
								nativeMime
						);
			}
		}

		if (mime.isEmpty()) {

			mime =
					"application/octet-stream";
		}

		final String finalMime =
				mime;

		runOnUiThread(() -> {

			if (
					isFinishing() ||
					isDestroyed()
			) {

				file.delete();

				return;
			}

			try {

				Uri contentUri =
						FileProvider.getUriForFile(
								WebInit.this,
								getPackageName() +
										".fileprovider",
								file
						);

				openFile(
						contentUri,
						finalMime
				);

			} catch (Exception e) {

				Log.e(
						TAG,
						"Unable to create FileProvider URI",
						e
				);

				file.delete();
			}
		});
	}


	/*
	 * ==========================================================
	 * RECURSIVE DELETE
	 * ==========================================================
	 */

	public static void deleteRecursive(
			File file
	) {

		if (
				file == null ||
				!file.exists()
		) {

			return;
		}

		if (file.isDirectory()) {

			File[] files =
					file.listFiles();

			if (files != null) {

				for (File child : files) {

					deleteRecursive(
							child
					);
				}
			}
		}

		file.delete();
	}


	/*
	 * ==========================================================
	 * SANITIZE FILENAME
	 * ==========================================================
	 */

	private String sanitizeFileName(
			String fileName
	) {

		if (
				fileName == null ||
				fileName.isEmpty()
		) {

			return null;
		}

		String value =
				fileName.trim();

		if (value.isEmpty()) {
			return null;
		}

		/*
		 * Remove path separators and characters that are unsafe
		 * for Android document providers/filesystem staging.
		 */
		value =
				value.replace(
						'\\',
						'_'
				);

		value =
				value.replace(
						'/',
						'_'
				);

		value =
				value.replace(
						':',
						'_'
				);

		value =
				value.replace(
						'*',
						'_'
				);

		value =
				value.replace(
						'?',
						'_'
				);

		value =
				value.replace(
						'"',
						'_'
				);

		value =
				value.replace(
						'<',
						'_'
				);

		value =
				value.replace(
						'>',
						'_'
				);

		value =
				value.replace(
						'|',
						'_'
				);

		/*
		 * Remove ASCII control characters.
		 */
		StringBuilder builder =
				new StringBuilder(
						value.length()
				);

		for (
				int i = 0;
				i < value.length();
				i++
		) {

			char c =
					value.charAt(i);

			if (
					c >= 32 &&
					c != 127
			) {

				builder.append(c);
			}
		}

		value =
				builder.toString().trim();

		/*
		 * Avoid "." and "..".
		 */
		if (
				value.equals(".") ||
				value.equals("..")
		) {

			return null;
		}

		/*
		 * Keep filenames at a reasonable size.
		 */
		if (value.length() > 240) {

			int extension =
					value.lastIndexOf('.');

			if (
					extension > 0 &&
					extension < value.length() - 1
			) {

				String ext =
						value.substring(
								extension
						);

				int maxBase =
						240 -
								ext.length();

				if (maxBase > 0) {

					value =
							value.substring(
									0,
									Math.min(
											maxBase,
											extension
									)
							) +
									ext;

				} else {

					value =
							value.substring(
									0,
									240
							);
				}

			} else {

				value =
						value.substring(
								0,
								240
						);
			}
		}

		return value;
	}


	/*
	 * ==========================================================
	 * DESTROY
	 * ==========================================================
	 */

	@Override
	protected void onDestroy() {

		initialized = false;

		canGoBack = false;

		if (pendingFileCallback != null) {
			pendingFileCallback.onReceiveValue(null);
			pendingFileCallback = null;
		}

		/*
		 * Cancel active Blob transfer.
		 */
		cancelBlobTransfer();

		/*
		 * Delete staged HTTP/Blob download.
		 */
		closePendingDownload();

		/*
		 * Stop background downloads.
		 */
		downloadExecutor.shutdownNow();

		if (browser != null) {
			try {
				browser.stopLoading();
				browser.removeJavascriptInterface("WebInitBlob");
				browser.setWebChromeClient(null);
				browser.setWebViewClient(null);
				browser.setDownloadListener(null);
				browser.destroy();
			} catch (Exception ignored) { }
			browser = null;
		}

		if (nativeInitialized) {
			nativeInitialized = false;
			try {
				Native.Close();
			} catch (Exception e) {
				Log.e(TAG,"Native.Close() failed",e);
			}
		}

		progressBar = null;
		root = null;
		super.onDestroy();
	}
}