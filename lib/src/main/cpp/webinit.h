#pragma once

#include "pak.h"

bool SetPackageName(const std::string& pak);

bool WebInit_Start();
void WebInit_Stop();

bool WebInit_WC_Start();
void WebInit_WC_Stop();
