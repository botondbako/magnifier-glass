cmake_minimum_required(VERSION 3.4.1)

add_library(yuv-decoder SHARED
            yuv-decoder.c)

# Link required libraries
target_link_libraries(yuv-decoder
                      android
                      log)

