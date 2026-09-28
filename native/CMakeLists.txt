# Builds webrtc_aec3.dll from webrtc_aec3_stub.cpp + a WebRTC checkout.
#
# Usage (x64 Native Tools Command Prompt for VS):
#   cmake -S . -B build -DWEBRTC_ROOT=C:\webrtc\src
#   cmake --build build --config Release
#   -> build\Release\webrtc_aec3.dll
#
# WEBRTC_ROOT must point at the WebRTC checkout created per BUILD_WINDOWS.md.
# WEBRTC_BUILD_DIR optionally points at the GN/Ninja output dir containing
# the built WebRTC static libs (defaults to <WEBRTC_ROOT>\out\aec3).

cmake_minimum_required(VERSION 3.20)
project(webrtc_aec3 CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED WEBRTC_ROOT)
  message(FATAL_ERROR "Pass -DWEBRTC_ROOT=<path to webrtc src checkout>")
endif()
if(NOT DEFINED WEBRTC_BUILD_DIR)
  set(WEBRTC_BUILD_DIR "${WEBRTC_ROOT}/out/aec3")
endif()

find_package(JNI REQUIRED)

add_library(webrtc_aec3 SHARED webrtc_aec3_stub.cpp)

target_include_directories(webrtc_aec3 PRIVATE
  ${JNI_INCLUDE_DIRS}
  ${WEBRTC_ROOT}
  ${WEBRTC_ROOT}/third_party/abseil-cpp
)

# Link the WebRTC static libraries produced by the GN build.
# NOTE: exact .lib file names depend on the pinned WebRTC revision;
# list them explicitly if the glob below misses any (link errors will
# name the missing symbols).
file(GLOB WEBRTC_LIBS "${WEBRTC_BUILD_DIR}/obj/modules/audio_processing/*.lib")
target_link_libraries(webrtc_aec3 PRIVATE ${WEBRTC_LIBS})

# Keep the DLL self-contained next to the app: copy it to the packaging dir.
add_custom_command(TARGET webrtc_aec3 POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_if_different
    $<TARGET_FILE:webrtc_aec3>
    ${CMAKE_SOURCE_DIR}/../packaging/webrtc_aec3.dll
  COMMENT "Copying webrtc_aec3.dll to packaging/"
)
