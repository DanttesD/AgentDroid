cmake_minimum_required(VERSION 3.22)
project("agentadb")
set(CMAKE_CXX_STANDARD 17)
add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti)

# BoringSSL comes as a prefab AAR with schema_version 2, which the prefab tool of our (old) Android
# Gradle Plugin cannot read. Gradle unpacks the AAR into BORINGSSL_DIR and we use the files directly.
set(BORINGSSL_DIR "" CACHE PATH "Unpacked boringssl AAR")
file(GLOB_RECURSE BSSL_LIBS "${BORINGSSL_DIR}/prefab/modules/crypto*/libs/android.${ANDROID_ABI}/*.a")
file(GLOB_RECURSE BSSL_HEADERS "${BORINGSSL_DIR}/prefab/modules/*/include/openssl/base.h")
if(NOT BSSL_LIBS OR NOT BSSL_HEADERS)
    file(GLOB_RECURSE BSSL_ALL "${BORINGSSL_DIR}/*")
    message(FATAL_ERROR "boringssl not found in '${BORINGSSL_DIR}' (abi ${ANDROID_ABI}). Files: ${BSSL_ALL}")
endif()
list(GET BSSL_LIBS 0 BSSL_LIB)
list(GET BSSL_HEADERS 0 BSSL_HEADER)
get_filename_component(BSSL_OPENSSL_DIR "${BSSL_HEADER}" DIRECTORY)
get_filename_component(BSSL_INCLUDE_DIR "${BSSL_OPENSSL_DIR}" DIRECTORY)
add_library(bssl_crypto STATIC IMPORTED)
set_target_properties(bssl_crypto PROPERTIES
    IMPORTED_LOCATION "${BSSL_LIB}"
    INTERFACE_INCLUDE_DIRECTORIES "${BSSL_INCLUDE_DIR}")

add_library(agentadb SHARED adb_pairing.cpp misc.cpp)
target_link_libraries(agentadb log bssl_crypto)
