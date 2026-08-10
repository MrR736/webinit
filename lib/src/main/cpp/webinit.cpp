#include <atomic>
#include <string>

#include "server.h"
#include "log.h"
#include "filesystem_utils.h"
#include "webinit.h"
#include "ghc/filesystem.hpp"

namespace fs = ghc::filesystem;

std::string temp;
static std::atomic<bool> webinitRunning(false);


std::string WEBINIT_PACKAGE = "com.mrr736.webinit";

bool SetPackageName(const std::string& pak) {
	LOGD("SetPackageName: requested pak=%s",pak.c_str());
	if (webinitRunning.load()) {
		LOGD("SetPackageName: server is already running");
		return false;
	}
	WEBINIT_PACKAGE = pak;
	LOGD("SetPackageName: File:%s",pak.c_str());
	return true;
}

bool WebInit_Start() {
	LOGD("WebInit_Start: entering");
	if (webinitRunning.load()) {
		LOGI("WebInit already running");
		LOGD("WebInit_Start: already running, nothing to do");
		return true;
	}
	temp = GetTempDirectory() + "/webinit";
	LOGD("WebInit_Start: temp directory=%s",temp.c_str());
	LOGI("WebInit directory: %s", temp.c_str());
	LOGD("WebInit_Start: removing old directory");
	if (!RemoveDirectory(temp)) {
		LOGE("Failed to remove old WebInit directory");
		LOGD("WebInit_Start: RemoveDirectory failed");
		return false;
	}
	LOGD("WebInit_Start: old directory removed");
	LOGD("WebInit_Start: creating directory");
	if (!CreateDirectory(temp)) {
		LOGE("Failed to create WebInit directory");
		LOGD("WebInit_Start: CreateDirectory failed");
		return false;
	}
	LOGD("WebInit_Start: directory created");
	LOGD("WebInit_Start: extracting WebInit.pak");
	if (!Pak_ExtractAll(temp)) {
		LOGE("Failed to extract WebInit.pak");
		LOGD("WebInit_Start: Pak_ExtractAll failed");
		LOGD("WebInit_Start: cleaning up %s",temp.c_str());
		RemoveDirectory(temp);
		return false;
	}
	LOGI("WebInit extracted");
	LOGD("WebInit_Start: extraction completed");
	LOGD("WebInit_Start: starting HTTP server");
	if (!StartServer()) {
		LOGE("Failed to start HTTP server");
		LOGD("WebInit_Start: StartServer failed");
		LOGD("WebInit_Start: cleaning up %s",temp.c_str());
		RemoveDirectory(temp);
		return false;
	}
	LOGD("WebInit_Start: HTTP server started");
	LOGD("WebInit_Start: starting server thread");
	RunServer();
	webinitRunning.store(true);
	LOGD("WebInit_Start: webinitRunning=true");
	LOGI("WebInit ready: http://%s:%u/index.html",hostAddress.c_str(),static_cast<unsigned>(hostPort));
	LOGD("WebInit_Start: completed successfully");
	return true;
}

void WebInit_Stop() {
	LOGD("WebInit_Stop: entering");
	if (!webinitRunning.load()) {
		LOGD("WebInit_Stop: WebInit is not running");
		return;
	}
	LOGD("WebInit_Stop: setting running=false");
	webinitRunning.store(false);
	LOGD("WebInit_Stop: stopping HTTP server");
	EndServer();
	LOGD("WebInit_Stop: HTTP server stopped");
	LOGD("WebInit_Stop: removing WebInit directory=%s",temp.c_str());
	if (!RemoveDirectory(temp)) {
		LOGE("Failed to remove WebInit files");
		LOGD("WebInit_Stop: cleanup failed");
	} else {
		LOGD("WebInit_Stop: WebInit files removed");
	}
	LOGI("WebInit stopped");
	LOGD("WebInit_Stop: completed");
}

bool WebInit_WC_Start() {
	LOGD("WebInit_WC_Start: entering");

	if (webinitRunning.load()) {
		LOGI("WebInit already running");
		LOGD("WebInit_WC_Start: already running");
		return true;
	}

	temp = GetTempDirectory() + "/webinit";

	LOGD("WebInit_WC_Start: cache directory=%s", temp.c_str());
	LOGI("WebInit directory: %s", temp.c_str());

	const fs::path rootDir(temp);
	const fs::path indexFile = rootDir / "index.html";

	bool cacheValid = false;

	try {
		cacheValid =
		fs::exists(indexFile) &&
		fs::is_regular_file(indexFile) &&
		fs::file_size(indexFile) > 0;
	} catch (const std::exception& e) {
		LOGD(
			"WebInit_WC_Start: cache validation failed: %s",
	   e.what()
		);
		cacheValid = false;
	}

	if (cacheValid) {
		LOGD(
			"WebInit_WC_Start: valid cached index.html found: %s",
	   indexFile.string().c_str()
		);
	} else {
		LOGD(
			"WebInit_WC_Start: index.html missing or invalid, extracting PAK"
		);

		/*
		 * Remove incomplete/old cache.
		 */
		if (fs::exists(rootDir)) {
			LOGD(
				"WebInit_WC_Start: removing incomplete cache: %s",
		temp.c_str()
			);

			if (!RemoveDirectory(temp)) {
				LOGE(
					"WebInit_WC_Start: failed removing old cache: %s",
		 temp.c_str()
				);
				return false;
			}
		}

		/*
		 * Recreate cache directory.
		 */
		if (!CreateDirectory(temp)) {
			LOGE("Failed to create WebInit directory");
			LOGD("WebInit_WC_Start: CreateDirectory failed");
			return false;
		}

		LOGD(
			"WebInit_WC_Start: extracting WebInit.pak into %s",
	   temp.c_str()
		);

		if (!Pak_ExtractAll(temp)) {
			LOGE("Failed to extract WebInit.pak");
			LOGD(
				"WebInit_WC_Start: extraction failed, removing cache"
			);

			RemoveDirectory(temp);
			return false;
		}

		/*
		 * Verify extraction actually produced index.html.
		 */
		try {
			if (!fs::exists(indexFile) ||
				!fs::is_regular_file(indexFile) ||
				fs::file_size(indexFile) == 0) {

				LOGE(
					"WebInit_WC_Start: extraction completed but index.html "
					"is missing or empty"
				);

			RemoveDirectory(temp);
			return false;
				}
		} catch (const std::exception& e) {
			LOGE(
				"WebInit_WC_Start: failed verifying index.html: %s",
		e.what()
			);

			RemoveDirectory(temp);
			return false;
		}

		LOGI("WebInit extracted successfully");
		LOGD(
			"WebInit_WC_Start: index.html extracted: %s",
	   indexFile.string().c_str()
		);
	}

	LOGD("WebInit_WC_Start: starting HTTP server");

	if (!StartServer()) {
		LOGE("Failed to start HTTP server");
		LOGD(
			"WebInit_WC_Start: StartServer failed, keeping cached files"
		);
		return false;
	}

	LOGD("WebInit_WC_Start: HTTP server started");

	LOGD("WebInit_WC_Start: starting server thread");

	RunServer();

	webinitRunning.store(true);

	LOGD("WebInit_WC_Start: webinitRunning=true");

	LOGI(
		"WebInit ready: http://%s:%u/index.html",
	  hostAddress.c_str(),
		 static_cast<unsigned>(hostPort)
	);

	LOGD("WebInit_WC_Start: completed successfully");

	return true;
}

void WebInit_WC_Stop() {
	LOGD("WebInit_WC_Stop: entering");
	if (!webinitRunning.load()) {
		LOGD("WebInit_WC_Stop: WebInit is not running");
		return;
	}
	LOGD("WebInit_WC_Stop: setting running=false");
	webinitRunning.store(false);
	LOGD("WebInit_WC_Stop: stopping HTTP server");
	EndServer();
	LOGD("WebInit_WC_Stop: HTTP server stopped");
	LOGD("WebInit_WC_Stop: preserving cached files at %s",temp.c_str());
	LOGI("WebInit stopped");
	LOGD("WebInit_WC_Stop: completed");
}
