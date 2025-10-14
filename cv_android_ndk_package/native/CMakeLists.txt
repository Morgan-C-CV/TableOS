cmake_minimum_required(VERSION 3.10)
project(projectioncards)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Expect OpenCV_DIR to be provided (OpenCV Android SDK)
find_package(OpenCV REQUIRED)
include_directories(${OpenCV_INCLUDE_DIRS})

add_library(projectioncards SHARED
    # Decoder
    card_encoder_decoder.cpp
    card_encoder_decoder_c_api.cpp
    # Detection & preprocessing
    image_processing.cpp
    dot_card_detect.cpp
    # Detect+Decode C API
    detect_decode_api.cpp
)

target_include_directories(projectioncards PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(projectioncards
    ${OpenCV_LIBS}
)