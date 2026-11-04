# SPDX-License-Identifier: AGPL-3.0-only
cmake_minimum_required(VERSION 3.22.1)
project(mbot_runtime_files LANGUAGES CXX)

add_library(mbot_runtime_files SHARED runtime_publication.cpp)
target_compile_features(mbot_runtime_files PRIVATE cxx_std_17)
target_compile_options(mbot_runtime_files PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(mbot_runtime_files PRIVATE
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
