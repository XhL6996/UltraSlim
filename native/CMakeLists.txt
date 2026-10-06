# UltraSlim native library: libultrahdr (JPEG/HEIF/AVIF gain map transcode) + JNI bridge.
#
# Requires the dependency stack (libjpeg-turbo, x265, aom, libde265, libheif) to be
# pre-built into native/prefix/<flavor> via native/deps/build_deps.sh.
cmake_minimum_required(VERSION 3.22.1)
project(ultraslim CXX C)

option(ULTRASLIM_HOST_TEST "Build host-side transcode test executable" OFF)

if(ULTRASLIM_HOST_TEST)
  set(DEPS_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/prefix/host)
else()
  set(DEPS_PREFIX ${CMAKE_CURRENT_SOURCE_DIR}/prefix/android-${ANDROID_ABI})
endif()

if(NOT EXISTS ${DEPS_PREFIX}/lib/libheif.a)
  message(FATAL_ERROR "Native deps not found at ${DEPS_PREFIX}. Run native/deps/build_deps.sh first.")
endif()

list(APPEND CMAKE_PREFIX_PATH ${DEPS_PREFIX})
# The NDK toolchain restricts find_* to the sysroot; our deps live outside it.
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(JPEG_INCLUDE_DIR ${DEPS_PREFIX}/include)
set(JPEG_LIBRARY ${DEPS_PREFIX}/lib/libjpeg.a)

# libultrahdr (static "core" target), without its bundled-deps machinery
set(UHDR_BUILD_DEPS OFF)
set(UHDR_BUILD_EXAMPLES OFF)
set(UHDR_BUILD_TESTS OFF)
set(UHDR_BUILD_BENCHMARK OFF)
set(UHDR_BUILD_FUZZERS OFF)
set(UHDR_ENABLE_INSTALL OFF)
set(UHDR_ENABLE_HEIF ON)
set(UHDR_ENABLE_GLES OFF)
set(UHDR_ENABLE_SMPTE2094_50 OFF)
set(UHDR_ENABLE_LOGS OFF)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr
                 ${CMAKE_CURRENT_BINARY_DIR}/libultrahdr)

# codec/container static libraries from the deps prefix
find_library(AOM_LIB NAMES aom PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(X265_LIB NAMES x265 PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(DE265_LIB NAMES de265 PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(JPEG_LIB NAMES jpeg PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(HEIF_LIB NAMES heif PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(SVT_LIB NAMES SvtAv1Enc svtav1 PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)
find_library(DAV1D_LIB NAMES dav1d PATHS ${DEPS_PREFIX}/lib NO_DEFAULT_PATH REQUIRED)

set(TRANSCODE_SRCS ${CMAKE_CURRENT_SOURCE_DIR}/jni/transcode.cpp)

if(ULTRASLIM_HOST_TEST)
  add_executable(transcode_test ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/host_test/main.cpp)
  target_include_directories(transcode_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(transcode_test PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB})

  add_executable(rendition_dump ${CMAKE_CURRENT_SOURCE_DIR}/host_test/rendition_dump.cpp)
  target_include_directories(rendition_dump PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(rendition_dump PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB})

  add_executable(gm_dump ${CMAKE_CURRENT_SOURCE_DIR}/host_test/gm_dump.cpp)
  target_include_directories(gm_dump PRIVATE ${DEPS_PREFIX}/include ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr)
  target_link_libraries(gm_dump PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB})
else()
  add_library(ultraslim SHARED ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/jni/ultraslim_jni.cpp)
  # NDK r27 defaults to 4 KB. Align both LOAD segments and the RELRO boundary.
  target_link_options(ultraslim PRIVATE
      "-Wl,-z,max-page-size=16384"
      "-Wl,-z,common-page-size=16384")
  target_include_directories(ultraslim PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  # Keep the Maven 1.0.1 Kotlin API, rebuilding its JNI component with aligned RELRO.
  add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/androidx-graphics-path
                   ${CMAKE_CURRENT_BINARY_DIR}/graphics-path)
  target_compile_features(androidx.graphics.path PRIVATE cxx_std_17)
  target_compile_options(androidx.graphics.path PRIVATE -fno-exceptions -fno-rtti
      -ffast-math -ffp-contract=fast -fvisibility=hidden -fvisibility-inlines-hidden
      -fomit-frame-pointer -ffunction-sections -fdata-sections)
  target_link_options(androidx.graphics.path PRIVATE
      "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384"
      "-Wl,--gc-sections" "-Wl,-Bsymbolic-functions")
  find_library(log-lib log REQUIRED)
  # link order matters for static archives: consumers before providers
  target_link_libraries(ultraslim PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB} ${log-lib})
endif()

# AGP Debug compiles in-tree code at -O0. Keep the prebuilt codecs as shipped.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
  set(_ultraslim_opt)
  if(TARGET ultraslim)
    list(APPEND _ultraslim_opt ultraslim)
  endif()
  if(TARGET transcode_test)
    list(APPEND _ultraslim_opt transcode_test)
  endif()
  if(TARGET core)
    list(APPEND _ultraslim_opt core)
  endif()
  if(TARGET image_io)
    list(APPEND _ultraslim_opt image_io)
  endif()
  if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    foreach(_t IN LISTS _ultraslim_opt)
      target_compile_options(${_t} PRIVATE -O2)
    endforeach()
  else()
    foreach(_t IN LISTS _ultraslim_opt)
      target_compile_options(${_t} PRIVATE -O3 -flto=thin)
    endforeach()
    if(TARGET ultraslim)
      target_link_options(ultraslim PRIVATE -flto=thin)
    endif()
    if(TARGET transcode_test)
      target_link_options(transcode_test PRIVATE -flto=thin)
    endif()
  endif()
endif()

add_compile_definitions(WITH_EXPERIMENTAL_GAIN_MAP=1)

if(ULTRASLIM_HOST_TEST)
  enable_testing()
  add_executable(transcode_regression ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/host_test/regression.cpp)
  target_include_directories(transcode_regression PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(transcode_regression PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB})
  file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/regression-output)
  add_test(NAME transcode_regression COMMAND transcode_regression ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/tests/data ${CMAKE_CURRENT_BINARY_DIR}/regression-output)
endif()

if(ULTRASLIM_HOST_TEST)
  add_executable(gainmap_preservation ${TRANSCODE_SRCS} ${CMAKE_CURRENT_SOURCE_DIR}/host_test/gainmap_preservation.cpp)
  target_include_directories(gainmap_preservation PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/lib/include ${DEPS_PREFIX}/include)
  target_link_libraries(gainmap_preservation PRIVATE core ${HEIF_LIB} ${SVT_LIB} ${AOM_LIB} ${X265_LIB} ${DE265_LIB} ${DAV1D_LIB} ${JPEG_LIB})
  add_test(NAME gainmap_preservation COMMAND gainmap_preservation ${CMAKE_CURRENT_BINARY_DIR}/regression-output ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/libultrahdr/tests/data/apple_gainmap_new.jpg)
endif()
