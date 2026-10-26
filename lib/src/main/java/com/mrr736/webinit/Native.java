package com.mrr736.webinit;

import java.util.Map;

public final class Native {
	static { System.loadLibrary("webinit"); }

	public static native Map<String, String> getMimeTypes();

	public static native String MimeTypetoExtension(String extension);
	public static native String MimeTypeFromExtension(String extension);
	public static native String MimeTypeFromFileName(String file);

	public static native boolean SetAddressHost(String address,int port);
	public static native boolean SetCacheDir(String path);
	public static native boolean SetPackageName(String pak);

	public static native boolean WebInit(String pak);
	public static native void WebInitClose();

	public static native boolean WebInitWC(String pak); // With Cache
	public static native void WebInitCloseWC(); // With Cache

	public static boolean Init(String pak) {
		return WebInitWC(pak);
	}

	public static void Close() {
		WebInitCloseWC();
	}
}
