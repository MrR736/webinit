#include <cstdint>
#include <jni.h>
#include <string>

#include "filesystem_utils.h"
#include "server.h"
#include "webinit.h"
#include "pak.h"
#include "log.h"

std::string GetString(JNIEnv* env,jstring js) {
	if (js == nullptr) {
		LOGE("GetString: js is null");
		return nullptr;
	}
	const char* cs = env->GetStringUTFChars(js, nullptr);
	if (cs == nullptr) {
		LOGE("GetString: GetStringUTFChars failed");
		return nullptr;
	}
	std::string s(cs);
	env->ReleaseStringUTFChars(js,cs);
	return s;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrr736_webinit_Native_SetCacheDir(JNIEnv* env,jclass,jstring path) {
	LOGD("JNI SetCacheDir: entering");
	std::string cacheDir = GetString(env,path);
	if (cacheDir.empty()) {
		LOGE("JNI SetCacheDir: GetString failed");
		return JNI_FALSE;
	}
	LOGD("JNI SetCacheDir: cacheDir=%s",cacheDir.c_str());
	bool ok = SetTempDirectory(cacheDir);
	LOGD("JNI SetCacheDir: result=%s",ok ? "success" : "failure");
	return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrr736_webinit_Native_WebInit(JNIEnv* env,jclass,jstring jpak) {
	LOGD("JNI WebInit: entering");
	LOGD("JNI WebInit: opening PAK");
	std::string pak = GetString(env,jpak);
	if (pak.empty()) {
		LOGE("JNI WebInit: GetString failed");
		return JNI_FALSE;
	}
	if (!Pak_Open(pak)) {
		LOGE("Cannot open WebInit.pak");
		LOGD("JNI WebInit: Pak_Open failed");
		return JNI_FALSE;
	}
	LOGD("JNI WebInit: PAK opened");
	LOGD("JNI WebInit: starting WebInit");
	if (!WebInit_Start()) {
		LOGE("Cannot start WebInit");
		LOGD("JNI WebInit: WebInit_Start failed");
		return JNI_FALSE;
	}
	LOGD("JNI WebInit: WebInit started");
	LOGD("JNI WebInit: success");
	return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mrr736_webinit_Native_WebInitClose(JNIEnv* env,jclass) {
	LOGD("JNI WebInitClose: entering");
	LOGD("JNI WebInitClose: stopping WebInit");
	WebInit_Stop();
	LOGD("JNI WebInitClose: WebInit stopped");
	LOGD("JNI WebInitClose: closing PAK");
	Pak_Close();
	LOGD("JNI WebInitClose: PAK closed");
	LOGD("JNI WebInitClose: completed");
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrr736_webinit_Native_WebInitWC(JNIEnv* env,jclass,jstring jpak) {
	LOGD("JNI WebInitWC: entering");
	LOGD("JNI WebInitWC: opening PAK");
	std::string pak = GetString(env,jpak);
	if (pak.empty()) {
		LOGE("JNI WebInit: GetString failed");
		return JNI_FALSE;
	}
	if (!Pak_Open(pak)) {
		LOGE("Cannot open WebInit.pak");
		LOGD("JNI WebInitWC: Pak_Open failed");
		return JNI_FALSE;
	}
	LOGD("JNI WebInitWC: PAK opened");
	LOGD("JNI WebInitWC: starting WebInit with cache");
	if (!WebInit_WC_Start()) {
		LOGE("Cannot start WebInit");
		LOGD("JNI WebInitWC: WebInit_WC_Start failed");
		return JNI_FALSE;
	}
	LOGD("JNI WebInitWC: WebInit started");
	LOGD("JNI WebInitWC: success");
	return JNI_TRUE;
}


extern "C" JNIEXPORT void JNICALL
Java_com_mrr736_webinit_Native_WebInitCloseWC(JNIEnv* env,jclass) {
	LOGD("JNI WebInitCloseWC: entering");
	LOGD("JNI WebInitCloseWC: stopping WebInit");
	WebInit_WC_Stop();
	LOGD("JNI WebInitCloseWC: WebInit stopped");
	LOGD("JNI WebInitCloseWC: closing PAK");
	Pak_Close();
	LOGD("JNI WebInitCloseWC: PAK closed");
	LOGD("JNI WebInitCloseWC: completed");
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrr736_webinit_Native_SetAddressHost(JNIEnv* env,jclass,jstring jaddress,jint jh) {
	LOGD("JNI SetAddressHost: entering address=%p port=%d",static_cast<void*>(jaddress),static_cast<int>(jh));
	std::string address = GetString(env,jaddress);
	if (address.empty()) {
		LOGE("JNI SetAddressHost: GetString failed");
		return JNI_FALSE;
	}
	if (jh < 0 || jh > 65535) {
		LOGE("JNI SetAddressHost: invalid port=%d",static_cast<int>(jh));
		return JNI_FALSE;
	}
	LOGD("JNI SetAddressHost: address=%s port=%d",address.c_str(),static_cast<int>(jh));
	bool ok = SetAddressHost(address,static_cast<uint16_t>(jh));
	LOGD("JNI SetAddressHost: result=%s",ok ? "success" : "failure");
	return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mrr736_webinit_Native_SetPackageName(JNIEnv* env,jclass,jstring jpak) {
	LOGD("JNI SetPackageName: entering file=%p",static_cast<void*>(jpak));
	std::string pak = GetString(env,jpak);
	if (pak.empty()) {
		LOGE("JNI SetPackageName: GetString failed");
		return JNI_FALSE;
	}
	LOGD("JNI SetPackageName: file=%s",pak.c_str());
	bool ok = SetPackageName(pak);
	LOGD("JNI SetPackageName: result=%s",ok ? "success" : "failure");
	return ok ? JNI_TRUE : JNI_FALSE;
}
