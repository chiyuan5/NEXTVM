cmake_minimum_required(VERSION 3.22)
project(nextvm-native-hook LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Visibility: only export JNI functions
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# Main hook library — outputs libnextvm-native.so to match System.loadLibrary("nextvm-native")
add_library(nextvm-native SHARED
    native-hook.cpp
)

# Link against Android log + dynamic linker
target_link_libraries(nextvm-native
    log     # __android_log_print
    dl      # dlsym, dlopen
)

# Compiler flags
target_compile_options(nextvm-native PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -fno-exceptions
    -fno-rtti
    -O2
)

# 16KB page alignment for Android 15+ (CRITICAL)
target_link_options(nextvm-native PRIVATE
    -Wl,-z,max-page-size=16384
)
