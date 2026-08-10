package com.mrr736.webinit;

public final class Native {
	static { System.loadLibrary("webinit"); }

	public static native boolean SetAddressHost(String address,int port);
	public static native boolean SetCacheDir(String path);
	public static native boolean SetPackageName(String pak);

	public static native boolean WebInit(String pak);
	public static native void WebInitClose();

	public static native boolean WebInitWC(String pak);
	public static native void WebInitCloseWC();

	public static boolean Init(String pak) {
		return WebInitWC(pak);
	}

	public static void Close() {
		WebInitCloseWC();
	}
}
