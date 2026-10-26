#ifndef FILESYSTEM_UTILS_H
#define FILESYSTEM_UTILS_H

#include <string>

#ifdef USE_GHC_FILESYSTEM
#include "ghc/filesystem.hpp"
namespace fs = ghc::filesystem;
using cifstream = ghc::filesystem::ifstream;
#else
#include <fstream>
#include <filesystem>
namespace fs = std::filesystem;
using cifstream = std::ifstream;
#endif

bool SetTempDirectory(const std::string& path);
std::string GetTempDirectory();
bool CreateDirectory(const std::string& path);
bool RemoveDirectory(const std::string& path);

#endif