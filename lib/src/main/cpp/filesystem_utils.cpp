#include <cstdlib>
#include <mutex>
#include <string>

#include "filesystem_utils.h"
#include "ghc/filesystem.hpp"
#include "log.h"

namespace fs = ghc::filesystem;

static std::string ROOT;
static std::mutex rootMutex;

bool SetTempDirectory(const std::string& path) {
	LOGD("SetTempDirectory: path=%s", path.c_str());
	try {
		if (!fs::exists(path)) {
			LOGE("SetTempDirectory: directory does not exist: %s",path.c_str());
			return false;
		}
		std::lock_guard<std::mutex> lock(rootMutex);
		ROOT = path;
		LOGD("SetTempDirectory: ROOT set to %s",ROOT.c_str());
		return true;
	} catch (const std::exception& e) {
		LOGE("SetTempDirectory: exception: %s", e.what());
		return false;
	} catch (...) {
		LOGE("SetTempDirectory: unknown exception");
		return false;
	}
}

std::string GetTempDirectory() {
	{
		std::lock_guard<std::mutex> lock(rootMutex);
		if (!ROOT.empty()) {
			LOGD("GetTempDirectory: using configured ROOT=%s",ROOT.c_str());
			return ROOT;
		}
	}
	const char* tmp = std::getenv("TMPDIR");
	if (tmp && *tmp) {
		LOGD("GetTempDirectory: using TMPDIR=%s", tmp);
		return tmp;
	}
#ifdef ANDROID
	std::string path = std::string("/data/data/") + WEBINIT_PACKAGE + "/cache";
	LOGD("GetTempDirectory: using Android cache=%s",path.c_str());
	return path;
#else
	LOGD("GetTempDirectory: using /tmp");
	return "/tmp";
#endif
}

bool CreateDirectory(const std::string& path) {
	LOGD("CreateDirectory: path=%s", path.c_str());
	try {
		if (fs::exists(path)) {
			LOGD("CreateDirectory: already exists: %s",path.c_str());
			return true;
		}
		bool result = fs::create_directories(path);
		if (result) {
			LOGD("CreateDirectory: created: %s",path.c_str());
		} else {
			LOGE("CreateDirectory: failed to create: %s",path.c_str());
		}
		return result;
	} catch (const std::exception& e) {
		LOGE("CreateDirectory: exception for %s: %s",path.c_str(),e.what());
		return false;
	} catch (...) {
		LOGE("CreateDirectory: unknown exception for %s",path.c_str());
		return false;
	}
}

bool RemoveDirectory(const std::string& path) {
	LOGD("RemoveDirectory: path=%s", path.c_str());
	try {
		if (!fs::exists(path)) {
			LOGD("RemoveDirectory: already absent: %s",path.c_str());
			return true;
		}
		auto count = fs::remove_all(path);
		LOGD("RemoveDirectory: removed %llu entries from %s",static_cast<unsigned long long>(count),path.c_str());
		bool removed = !fs::exists(path);
		if (!removed) {
			LOGE("RemoveDirectory: directory still exists: %s",path.c_str());
		} else {
			LOGD("RemoveDirectory: successfully removed: %s",path.c_str());
		}
		return removed;
	} catch (const std::exception& e) {
		LOGE("RemoveDirectory: exception for %s: %s",path.c_str(),e.what());
		return false;
	} catch (...) {
		LOGE("RemoveDirectory: unknown exception for %s",path.c_str());
		return false;
	}
}
