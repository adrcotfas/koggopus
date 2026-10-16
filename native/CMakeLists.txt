cmake_minimum_required(VERSION 3.22.1)
project(koggopus C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
# MSVC: link the C runtime statically, so the Windows library needs no Visual C++
# redistributable. The policy default reaches libogg, whose minimum CMake version predates it.
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
if(MSVC)
    set(KOGG_WARNINGS /W3)
else()
    set(KOGG_WARNINGS -Wall -Wextra)
endif()

option(KOGG_BUILD_JNI "Build the JNI shared library" ON)
option(KOGG_BUILD_TESTS "Build the native hostile-input test" OFF)
option(KOGG_BUILD_FUZZER "Build the libFuzzer target (needs a clang that ships libFuzzer)" OFF)

# Third-party libraries are built as static archives and linked into one shared library.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "" FORCE)

add_subdirectory(third_party/opus opus EXCLUDE_FROM_ALL)
add_subdirectory(third_party/ogg ogg EXCLUDE_FROM_ALL)

# libopusenc ships no CMake build; these are the sources and defines from its Makefile.am.
set(OPE_DIR third_party/libopusenc)
# The version ends up in the vendor string of every encoded stream, so take it from the
# checked-out tag rather than repeating it here.
execute_process(COMMAND git describe --tags --match "v*"
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/${OPE_DIR}
        OUTPUT_VARIABLE OPE_VERSION OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
string(REGEX REPLACE "^v" "" OPE_VERSION "${OPE_VERSION}")
if(NOT OPE_VERSION)
    set(OPE_VERSION "unknown")
endif()
add_library(opusenc STATIC
        ${OPE_DIR}/src/ogg_packer.c
        ${OPE_DIR}/src/opus_header.c
        ${OPE_DIR}/src/opusenc.c
        ${OPE_DIR}/src/picture.c
        ${OPE_DIR}/src/resample.c
        ${OPE_DIR}/src/unicode_support.c)
target_include_directories(opusenc PUBLIC ${OPE_DIR}/include PRIVATE ${OPE_DIR}/src)
target_compile_definitions(opusenc PRIVATE
        RANDOM_PREFIX=libopusenc
        OUTSIDE_SPEEX
        FLOATING_POINT
        PACKAGE_NAME="libopusenc"
        PACKAGE_VERSION="${OPE_VERSION}")
target_link_libraries(opusenc PUBLIC opus)

add_library(koggcore STATIC core/kogg_enc.c core/kogg_dec.c)
target_include_directories(koggcore PUBLIC core)
target_compile_options(koggcore PRIVATE ${KOGG_WARNINGS})
target_link_libraries(koggcore PUBLIC opusenc opus ogg)
if(NOT ANDROID AND NOT WIN32)
    target_link_libraries(koggcore PUBLIC m)
endif()

if(KOGG_BUILD_JNI)
    add_library(koggopus SHARED jni/koggopus_jni.c)
    target_compile_options(koggopus PRIVATE ${KOGG_WARNINGS})
    target_link_libraries(koggopus PRIVATE koggcore)
    if(ANDROID)
        # Keep the bundled libraries' symbols private, drop the symbol table, and align
        # for 16 KB page sizes.
        target_link_options(koggopus PRIVATE
                -Wl,--exclude-libs,ALL
                -Wl,--gc-sections
                -Wl,--strip-all
                -Wl,-z,max-page-size=16384)
    else()
        # Desktop JVM. The same treatment as Android, in each linker's terms.
        if(APPLE)
            target_link_options(koggopus PRIVATE -Wl,-exported_symbol,_Java_* -Wl,-dead_strip -Wl,-x)
        elseif(NOT WIN32)
            target_link_options(koggopus PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
        endif()
        target_include_directories(koggopus PRIVATE
                ${JAVA_HOME}/include
                ${JAVA_HOME}/include/darwin
                ${JAVA_HOME}/include/linux
                ${JAVA_HOME}/include/win32)
    endif()
endif()

if(KOGG_BUILD_TESTS)
    add_executable(kogg_hostile test/hostile.c)
    target_link_libraries(kogg_hostile PRIVATE koggcore)
endif()

if(KOGG_BUILD_FUZZER)
    # The same harness as kogg_hostile, driven by libFuzzer. Configure with
    # -DCMAKE_C_FLAGS="-fsanitize=fuzzer-no-link,address,undefined" so the libraries are
    # instrumented too.
    add_executable(kogg_fuzz test/hostile.c)
    target_compile_definitions(kogg_fuzz PRIVATE KOGG_FUZZ)
    target_link_options(kogg_fuzz PRIVATE -fsanitize=fuzzer)
    target_link_libraries(kogg_fuzz PRIVATE koggcore)
endif()
