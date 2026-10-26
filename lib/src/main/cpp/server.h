#pragma once

#include <string>
#include <cstdint>

extern uint16_t hostPort;
extern std::string hostAddress;

bool SetAddressHost(const std::string& host,uint16_t h);

bool StartServer();
void RunServer();
void EndServer();
bool IsServerRunning();
