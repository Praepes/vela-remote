cmake_minimum_required(VERSION 3.22)
project(vela_input LANGUAGES C)
add_library(vela_input SHARED uinput_mouse.c)
target_compile_options(vela_input PRIVATE -Wall -Wextra -Werror)
