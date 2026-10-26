#pragma once

#include <string>
#include <vector>

bool Pak_Open(std::string& path);
void Pak_Close();
bool Pak_IsOpen();
bool Pak_FileExists(const char* filename);
std::vector<unsigned char> Pak_ReadFile(const char* filename);
std::string Pak_ReadText(const char* filename);
bool Pak_ExtractAll(const std::string& outDir);
