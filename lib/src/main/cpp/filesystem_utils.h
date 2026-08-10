#pragma once

#include <string>

bool SetTempDirectory(const std::string& path);
std::string GetTempDirectory();
bool CreateDirectory(const std::string& path);
bool RemoveDirectory(const std::string& path);
