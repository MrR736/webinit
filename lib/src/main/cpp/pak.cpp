#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "pak.h"
#include "miniz/miniz.h"
#include "libdeflate.h"
#include "ghc/filesystem.hpp"
#include "log.h"

namespace fs = ghc::filesystem;

static mz_zip_archive g_zip{};
static libdeflate_decompressor* d_zip = nullptr;
static bool pakOpened = false;
static std::mutex pakMutex;

static unsigned char* _binary_webinit_pak_start = nullptr;
static unsigned char* _binary_webinit_pak_end   = nullptr;

static bool _Pak_Open() {
	const size_t size = _binary_webinit_pak_end - _binary_webinit_pak_start;

	LOGD("Pak_Open: embedded PAK address=%p size=%zu bytes (%.2f MB)",
		static_cast<const void*>(_binary_webinit_pak_start),size, static_cast<double>(size) / (1024.0 * 1024.0)	);

	if (_binary_webinit_pak_start == nullptr || size == 0) {
		LOGE("Pak_Open: invalid PAK memory");
		return false;
	}

	if (!mz_zip_reader_init_mem(&g_zip,_binary_webinit_pak_start,size,0)) {
		LOGE("Failed opening webinit.pak");
		LOGD("Pak_Open: mz_zip_reader_init_mem failed");
		return false;
	}

	LOGD("Pak_Open: miniz initialized successfully");

	d_zip = libdeflate_alloc_decompressor();

	if (!d_zip) {
		LOGE("Failed allocating libdeflate decompressor");
		LOGD("Pak_Open: cleaning up miniz");
		mz_zip_reader_end(&g_zip);
		return false;
	}

	LOGD("Pak_Open: libdeflate decompressor allocated");
	pakOpened = true;

	const mz_uint count = mz_zip_reader_get_num_files(&g_zip);

	LOGI("Pak opened");
	LOGD("Pak_Open: PAK contains %u files",static_cast<unsigned>(count));
	return true;
}

bool Pak_Open(std::string& path) {
	LOGD("Pak_Open: entering");

	std::lock_guard<std::mutex> lock(pakMutex);
	if (pakOpened) {
		LOGD("Pak_Open: PAK already open");
		return true;
	}

	std::ifstream file(path,std::ios::binary | std::ios::ate);
	if (!file.is_open()) {
		LOGE("Pak_Open: failed opening file: %s", path.c_str());
		return false;
	}

	const std::streamsize fileSize = file.tellg();
	if (fileSize <= 0) {
		LOGE("Pak_Open: invalid file size");
		return false;
	}
	file.seekg(0, std::ios::beg);
	const size_t size = static_cast<size_t>(fileSize);
	unsigned char* data = new (std::nothrow) unsigned char[size];
	if (!data) {
		LOGE("Pak_Open: failed allocating %zu bytes", size);
		return false;
	}
	if (!file.read(reinterpret_cast<char*>(data),fileSize)) {
		LOGE("Pak_Open: failed reading PAK");
		delete[] data;
		return false;
	}
	file.close();
	_binary_webinit_pak_start = data;
	_binary_webinit_pak_end   = data + size;
	LOGD("Pak_Open: loaded %zu bytes from %s",size,path.c_str());
	if (!_Pak_Open()) {
		delete[] _binary_webinit_pak_start;
		_binary_webinit_pak_start = nullptr;
		_binary_webinit_pak_end   = nullptr;
		return false;
	}
	return true;
}

void Pak_Close() {
	LOGD("Pak_Close: entering");
	std::lock_guard<std::mutex> lock(pakMutex);
	if (!pakOpened) {
		LOGD("Pak_Close: PAK is not open");
		return;
	}
	LOGD("Pak_Close: closing miniz archive");
	mz_zip_reader_end(&g_zip);
	if (d_zip) {
		LOGD("Pak_Close: freeing libdeflate decompressor");
		libdeflate_free_decompressor(d_zip);
		d_zip = nullptr;
	}
	pakOpened = false;
	LOGD("Pak_Close: completed");
}

bool Pak_IsOpen() {
	std::lock_guard<std::mutex> lock(pakMutex);
	bool opened = pakOpened;
	LOGD("Pak_IsOpen: %s",opened ? "true" : "false");
	return opened;
}

bool Pak_FileExists(const char* filename) {
	LOGD("Pak_FileExists: filename=%s",filename ? filename : "(null)");
	std::lock_guard<std::mutex> lock(pakMutex);
	if (!pakOpened) {
		LOGD("Pak_FileExists: PAK is not open");
		return false;
	}
	int index = mz_zip_reader_locate_file(&g_zip,filename,nullptr,0);
	bool exists = index >= 0;
	LOGD("Pak_FileExists: %s -> %s index=%d",filename,exists ? "FOUND" : "NOT FOUND",index);
	return exists;
}

std::vector<unsigned char> Pak_ReadFile(const char* filename) {
	LOGD("Pak_ReadFile: filename=%s",filename ? filename : "(null)");
	std::lock_guard<std::mutex> lock(pakMutex);
	std::vector<unsigned char> result;
	if (!pakOpened) {
		LOGD("Pak_ReadFile: PAK is not open");
		return result;
	}
	int index = mz_zip_reader_locate_file(&g_zip,filename,nullptr,0);
	if (index < 0) {
		LOGD("Pak_ReadFile: file not found: %s",filename);
		return result;
	}
	LOGD("Pak_ReadFile: located %s at index=%d",filename,index);
	mz_zip_archive_file_stat stat{};
	if (!mz_zip_reader_file_stat(&g_zip,index,&stat)) {
		LOGE("Pak_ReadFile: failed getting file stat: %s",filename);
		return result;
	}
	LOGD("Pak_ReadFile: compressed=%llu uncompressed=%llu method=%u",static_cast<unsigned long long>(stat.m_comp_size),
		static_cast<unsigned long long>(stat.m_uncomp_size),static_cast<unsigned>(stat.m_method));
	result.resize(static_cast<size_t>(stat.m_uncomp_size));
	if (!mz_zip_reader_extract_to_mem(&g_zip,index,result.data(),result.size(),0)) {
		LOGE("Pak_ReadFile: extraction failed: %s",filename);
		result.clear();
		return result;
	}
	LOGD("Pak_ReadFile: successfully read %s (%zu bytes)",filename,result.size());
	return result;
}

std::string Pak_ReadText(const char* filename) {
	LOGD("Pak_ReadText: filename=%s",filename ? filename : "(null)");
	auto data = Pak_ReadFile(filename);
	if (data.empty()) {
		LOGD("Pak_ReadText: empty or missing file: %s",filename);
		return {};
	}
	LOGD("Pak_ReadText: read %zu bytes",data.size());
	return std::string(reinterpret_cast<char*>(data.data()),data.size());
}

static const unsigned char* Pak_GetCompressedData(mz_zip_archive_file_stat& stat,size_t& size) {
	size_t offset = static_cast<size_t>(stat.m_local_header_ofs);
	LOGD("Pak_GetCompressedData: file=%s local_header=%zu",stat.m_filename,offset);
	const unsigned char* header = _binary_webinit_pak_start + offset;
	uint16_t filename_len = static_cast<uint16_t>(header[26] | (header[27] << 8));
	uint16_t extra_len = static_cast<uint16_t>(header[28] | (header[29] << 8));
	size_t data_offset = offset + 30 + filename_len + extra_len;
	size = static_cast<size_t>(stat.m_comp_size);
	LOGD("Pak_GetCompressedData: file=%s filename_len=%u extra_len=%u data_offset=%zu compressed_size=%zu",
		stat.m_filename,static_cast<unsigned>(filename_len),static_cast<unsigned>(extra_len),data_offset,size);
	return _binary_webinit_pak_start + data_offset;
}

bool Pak_ExtractAll(const std::string& outDir) {
	LOGD("Pak_ExtractAll: entering outDir=%s",outDir.c_str());
	std::lock_guard<std::mutex> lock(pakMutex);
	if (!pakOpened) {
		LOGE("Pak_ExtractAll: PAK is not open");
		return false;
	}
	if (!d_zip) {
		LOGE("Pak_ExtractAll: libdeflate decompressor is null");
		return false;
	}
	LOGD("Pak_ExtractAll: creating output directory");
	try {
		fs::create_directories(outDir);
	} catch (const std::exception& e) {
		LOGE("Pak_ExtractAll: failed creating output directory: %s",e.what());
		return false;
	}
	mz_uint count = mz_zip_reader_get_num_files(&g_zip);
	LOGI("Extracting %u files to %s",static_cast<unsigned>(count),outDir.c_str());
	LOGD("Pak_ExtractAll: starting extraction of %u entries",static_cast<unsigned>(count));
	size_t extractedFiles = 0;
	size_t extractedDirectories = 0;
	size_t totalBytes = 0;
	for (mz_uint i = 0; i < count; i++) {
		mz_zip_archive_file_stat stat{};
		if (!mz_zip_reader_file_stat(&g_zip,i,&stat)) {
			LOGD("Pak_ExtractAll: failed stat for entry %u",static_cast<unsigned>(i));
			continue;
		}
		fs::path output = fs::path(outDir) / stat.m_filename;

		LOGD("Pak_ExtractAll: [%u/%u] %s",static_cast<unsigned>(i + 1),static_cast<unsigned>(count),stat.m_filename);
		if (mz_zip_reader_is_file_a_directory(&g_zip,i)) {
			LOGD("Pak_ExtractAll: directory=%s",stat.m_filename);
			try {
				fs::create_directories(output);
			} catch (const std::exception& e) {
				LOGE("Pak_ExtractAll: failed creating directory %s: %s",output.string().c_str(),e.what());
				return false;
			}
			++extractedDirectories;
			continue;
		}

		try {
			fs::create_directories(output.parent_path());
		} catch (const std::exception& e) {
			LOGE("Pak_ExtractAll: failed creating parent directory for %s: %s",stat.m_filename,e.what());
			return false;
		}
		std::vector<unsigned char> data(static_cast<size_t>(stat.m_uncomp_size));
		size_t compressed_size = 0;
		const unsigned char* compressed = Pak_GetCompressedData(stat,compressed_size);
		bool ok = false;

		if (stat.m_method == 8) {
			// DEFLATE
			LOGD("Pak_ExtractAll: DEFLATE %s (%zu -> %zu bytes)",stat.m_filename,compressed_size,data.size());
			size_t actual = 0;
			libdeflate_result result =
				libdeflate_deflate_decompress(d_zip,compressed,compressed_size,data.data(),data.size(),&actual);
			ok = result == LIBDEFLATE_SUCCESS && actual == data.size();
			if (!ok) {
				LOGE("Pak_ExtractAll: libdeflate failed for %s result=%d actual=%zu expected=%zu",
					stat.m_filename,static_cast<int>(result),actual,data.size());
			}
		} else if (stat.m_method == 0) {
			// STORE
			LOGD("Pak_ExtractAll: STORE %s (%zu bytes)",stat.m_filename,data.size());
			if (!data.empty()) memcpy(data.data(),compressed,data.size());
			ok = true;
		} else {
			LOGE("Pak_ExtractAll: unsupported compression method %u for %s",
				static_cast<unsigned>(stat.m_method),stat.m_filename);
		}
		if (!ok) {
			LOGE("Failed extracting %s",stat.m_filename);
			return false;
		}
		FILE* fp = fopen(output.string().c_str(),"wb");
		if (!fp) {
			LOGE("Pak_ExtractAll: fopen failed for %s: %s",output.string().c_str(),strerror(errno));
			return false;
		}
		size_t written = 0;
		if (!data.empty()) written =fwrite(data.data(),1,data.size(),fp);
		int closeResult = fclose(fp);
		if (written != data.size()) {
			LOGE("Pak_ExtractAll: fwrite failed for %s (written=%zu expected=%zu)",
				output.string().c_str(),written,data.size());
			return false;
		}
		if (closeResult != 0) {
			LOGE("Pak_ExtractAll: fclose failed for %s",output.string().c_str());
			return false;
		}
		++extractedFiles;
		totalBytes += data.size();
		LOGD("Pak_ExtractAll: extracted %s -> %s (%zu bytes)",
		stat.m_filename,
		output.string().c_str(),data.size());
	}
	LOGI("PAK extraction complete: %zu files, %zu directories, %zu bytes",
		extractedFiles,extractedDirectories,totalBytes);
	LOGD("Pak_ExtractAll: completed successfully files=%zu directories=%zu bytes=%zu",
		 extractedFiles,extractedDirectories,totalBytes);
	return true;
}
