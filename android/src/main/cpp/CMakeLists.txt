cmake_minimum_required(VERSION 3.22.1)
project(scope_iso LANGUAGES CXX)
add_library(scope_iso SHARED scope_iso.cpp)
target_compile_features(scope_iso PRIVATE cxx_std_17)
target_compile_options(scope_iso PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(scope_iso PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
# Link the C++ runtime into this one small library; no additional runtime .so is required.
set_property(TARGET scope_iso PROPERTY ANDROID_STL c++_static)
