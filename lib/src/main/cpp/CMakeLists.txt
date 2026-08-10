cmake_minimum_required(VERSION 3.22)

project(webinit)

include(${CMAKE_CURRENT_SOURCE_DIR}/libdeflate/FindLibDeflate.cmake)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(${PROJECT_NAME} SHARED
	jni.cpp
	webinit.cpp
	pak.cpp
	filesystem_utils.cpp
	server.cpp
	miniz/miniz.c
	${PAK_OBJ}
)

target_include_directories(${PROJECT_NAME} PRIVATE . miniz libdeflate/include ghc)
find_library(log-lib log)
target_link_libraries(${PROJECT_NAME} PRIVATE ${log-lib} libdeflate)

# Enable DEBUG only for Debug builds.
target_compile_definitions(${PROJECT_NAME} PRIVATE $<$<CONFIG:Debug>:DEBUG>)
