add_library(libdeflate STATIC IMPORTED GLOBAL)

set_target_properties(libdeflate PROPERTIES
	IMPORTED_LOCATION
		"${CMAKE_CURRENT_LIST_DIR}/lib/${ANDROID_ABI}/libdeflate.a"
	INTERFACE_INCLUDE_DIRECTORIES
		"${CMAKE_CURRENT_LIST_DIR}/include"
)
