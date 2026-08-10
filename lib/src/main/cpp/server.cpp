#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/sendfile.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <cstring>
#include <sstream>
#include <string>
#include <thread>

#include "server.h"
#include "filesystem_utils.h"
#include "log.h"

uint16_t hostPort = 8080;
std::string hostAddress = "127.0.0.1";

static std::atomic<bool> serverRunning(false);
static int serverSocket = -1;
static std::thread serverThread;
static std::string serverRoot;

static bool EndsWith(const std::string& str,const std::string& suffix) {
	if(suffix.size() > str.size()) return false;
	return str.compare(str.size() - suffix.size(),suffix.size(),suffix) == 0;
}

static std::string MimeType(const std::string& file) {
	if(EndsWith(file,".html")) return "text/html";
	if(EndsWith(file,".js")) return "application/javascript";
	if(EndsWith(file,".css")) return "text/css";
	if(EndsWith(file,".json")) return "application/json";
	if(EndsWith(file,".png")) return "image/png";
	if(EndsWith(file,".jpg") || EndsWith(file,".jpeg")) return "image/jpeg";
	if(EndsWith(file,".svg")) return "image/svg+xml";
	if(EndsWith(file,".woff")) return "font/woff";
	if(EndsWith(file,".woff2")) return "font/woff2";
	if(EndsWith(file,".ttf")) return "font/ttf";
	return "application/octet-stream";
}

static off_t GetFileSize(const std::string& path) {
	struct stat st{};
	if(stat(path.c_str(), &st) < 0) {
		LOGD("GetFileSize: stat failed: %s (%s)",path.c_str(), strerror(errno));
		return -1;
	}
	return st.st_size;
}

static bool SendFile(int client,const std::string& path) {
	LOGD("SendFile: client=%d path=%s", client, path.c_str());
	int fd = open(path.c_str(),O_RDONLY);
	if(fd < 0) return false;
	off_t size = GetFileSize(path);
	if(size < 0) {
		LOGD("SendFile: open failed: %s (%s)",path.c_str(), strerror(errno));
		close(fd);
		return false;
	}

	const std::string mime = MimeType(path);
	LOGD("SendFile: size=%lld mime=%s",static_cast<long long>(size),mime.c_str());

	std::ostringstream out;
	out
	<< "HTTP/1.1 200 OK\r\n"
	<< "Content-Type: "
	<< mime
	<< "\r\n"
	<< "Content-Length: "
	<< size
	<< "\r\n"
	<< "Access-Control-Allow-Origin: *\r\n"
	<< "Cache-Control: no-cache\r\n"
	<< "Connection: close\r\n\r\n";

	std::string header = out.str();

	ssize_t headerSent = send(client,header.data(),header.size(),0);
	if (headerSent < 0) {
		LOGD("SendFile: failed to send HTTP header: %s",strerror(errno));
		close(fd);
		return false;
	}
	off_t offset = 0;
	while(offset < size) {
		ssize_t sent = sendfile(client,fd,&offset,size - offset);
		if(sent <= 0) {
			LOGD("SendFile: sendfile failed: %s",strerror(errno));
			close(fd);
			return false;
		}
	}
	close(fd);
	LOGD("SendFile: completed %s", path.c_str());
	return true;
}

static void Send404(int client) {
	LOGD("Send404: client=%d", client);
	const char* msg =
	"HTTP/1.1 404 Not Found\r\n"
	"Content-Length:0\r\n"
	"Connection: close\r\n"
	"\r\n";
	send(client,msg,strlen(msg),0);
}

static void ClientThread(int client) {
	LOGD("ClientThread: client=%d connected", client);
	char buffer[4096];
	int len = recv(client,buffer,sizeof(buffer)-1,0);
	if(len <= 0) {
		LOGD("ClientThread: recv failed/connection closed: %s",strerror(errno));
		close(client);
		return;
	}
	buffer[len] = 0;
	std::string request(buffer);
	LOGD("ClientThread: request:\n%s", request.c_str());
	size_t start = request.find("GET ");
	size_t end = request.find(" HTTP/");
	if(start == std::string::npos || end == std::string::npos) {
		LOGD("ClientThread: invalid HTTP request");
		close(client);
		return;
	}
	std::string file = request.substr(start + 4,end - start - 4);
	LOGD("ClientThread: requested file=%s", file.c_str());
	if(file == "/") file = "/index.html";
	// prevent ../ attacks
	if(file.find("..") != std::string::npos) {
		LOGD("ClientThread: blocked path traversal: %s",file.c_str());
		Send404(client);
		close(client);
		return;
	}
	std::string path = serverRoot + file;
	LOGD("ClientThread: resolved path=%s", path.c_str());
	if(!SendFile(client,path)) {
		LOGD("ClientThread: file not found or failed: %s",path.c_str());
		Send404(client);
	}
	close(client);
	LOGD("ClientThread: client=%d disconnected", client);
}

bool SetAddressHost(const std::string& host,uint16_t h) {
	LOGD("SetAddressHost: requested host=%s port=%u",host.c_str(),static_cast<unsigned>(h));
	if (serverRunning) {
		LOGD("SetAddressHost: server is already running");
		return false;
	}
	hostAddress = host;
	hostPort = h;
	LOGD("SetAddressHost: configured %s:%u",hostAddress.c_str(),static_cast<unsigned>(hostPort));
	return true;
}

bool StartServer() {
	LOGD("StartServer: entering");
	if (serverRunning) {
		LOGD("StartServer: server already running");
		return true;
	}
	serverRoot = GetTempDirectory() + "/webinit";
	LOGD("StartServer: serverRoot=%s", serverRoot.c_str());
	LOGD("StartServer: binding to %s:%u",hostAddress.c_str(),static_cast<unsigned>(hostPort));
	serverSocket = socket(AF_INET,SOCK_STREAM,0);
	if(serverSocket < 0) {
		LOGE("socket failed: %s",strerror(errno));
		return false;
	}
	LOGD("StartServer: socket=%d", serverSocket);
	int yes = 1;
	if (setsockopt(serverSocket,SOL_SOCKET,SO_REUSEADDR,&yes,sizeof(yes)) < 0) {
		LOGD("StartServer: SO_REUSEADDR failed: %s",strerror(errno));
	}
	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(hostPort);
	if (hostAddress == "*" || hostAddress == "0.0.0.0") {
		addr.sin_addr.s_addr = htonl(INADDR_ANY);
		LOGD("StartServer: binding to INADDR_ANY");
	} else {
		if (inet_pton(AF_INET, hostAddress.c_str(), &addr.sin_addr) != 1) {
			LOGE("Invalid host address: %s", hostAddress.c_str());
			close(serverSocket);
			serverSocket = -1;
			return false;
		}
		LOGD("StartServer: parsed address %s",hostAddress.c_str());
	}
	if(bind(serverSocket,reinterpret_cast<sockaddr*>(&addr),sizeof(addr)) < 0) {
		LOGE("bind failed on %s:%u: %s",hostAddress.c_str(),static_cast<unsigned>(hostPort),strerror(errno));
		close(serverSocket);
		serverSocket = -1;
		return false;
	}
	LOGD("StartServer: bind successful");
	if(listen(serverSocket,32) < 0) {
		LOGE("listen failed: %s", strerror(errno));
		close(serverSocket);
		serverSocket = -1;
		return false;
	}
	LOGD("StartServer: listen successful");
	serverRunning = true;
	LOGI("HTTP server ready: http://%s:%u",hostAddress.c_str(),static_cast<unsigned>(hostPort));
	return true;
}

void RunServer() {
	LOGD("RunServer: entering");
	if (!serverRunning) {
		LOGD("RunServer: server is not running");
		return;
	}
	serverThread = std::thread([] {
		LOGD("Server thread started");
		while (serverRunning) {
			sockaddr_in clientAddr{};
			socklen_t len = sizeof(clientAddr);
			int client = accept(serverSocket,reinterpret_cast<sockaddr*>(&clientAddr),&len);
			if (client < 0) {
				if (!serverRunning) break;
				LOGD("accept failed: %s",strerror(errno));
				continue;
			}
			char clientIP[INET_ADDRSTRLEN]{};
			inet_ntop(AF_INET,&clientAddr.sin_addr,clientIP,sizeof(clientIP));
			LOGD("Accepted client=%d from %s:%u",client,clientIP,static_cast<unsigned>(ntohs(clientAddr.sin_port)));
			std::thread(ClientThread,client).detach();
		}
		LOGD("Server thread exiting");
	});
}

void EndServer() {
	LOGD("EndServer: entering");
	if (!serverRunning) {
		LOGD("EndServer: server is not running");
		return;
	}
	serverRunning = false;
	if (serverSocket >= 0) {
		LOGD("EndServer: closing socket=%d",serverSocket);
		shutdown(serverSocket,SHUT_RDWR);
		close(serverSocket);
		serverSocket = -1;
	}
	if (serverThread.joinable()) {
		LOGD("EndServer: waiting for server thread");
		serverThread.join();
	}
	LOGI("HTTP server stopped");
}

bool IsServerRunning() {
	bool running = serverRunning.load();
	LOGD("IsServerRunning: %s",running ? "true" : "false");
	return running;
}
