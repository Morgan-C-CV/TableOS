#include "shape_detector_c_api.h"
#include "shape_detector.h"
#include <opencv2/opencv.hpp>
#include <string>
#include <cstring>
#include <memory>

#ifdef ANDROID_NDK
#include <android/log.h>
#define LOG_TAG "ShapeDetectorNDK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) printf(__VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

// Global variables
static std::string g_last_error;
static const char* VERSION = "1.0.0";

// Helper functions
static ShapeType convert_shape_type(const std::string& type_str) {
    if (type_str == "Rectangle") return SHAPE_TYPE_RECTANGLE;
    if (type_str == "Long Rectangle") return SHAPE_TYPE_LONG_RECTANGLE;
    if (type_str == "Triangle") return SHAPE_TYPE_TRIANGLE;
    return SHAPE_TYPE_UNKNOWN;
}

static ColorType convert_color_type(const std::string& color_str) {
    if (color_str == "Red") return COLOR_RED;
    if (color_str == "Green") return COLOR_GREEN;
    if (color_str == "Blue") return COLOR_BLUE;
    if (color_str == "Yellow") return COLOR_YELLOW;
    if (color_str == "Cyan") return COLOR_CYAN;
    if (color_str == "Magenta") return COLOR_MAGENTA;
    if (color_str == "Black") return COLOR_BLACK;
    if (color_str == "White") return COLOR_WHITE;
    return COLOR_UNKNOWN;
}

static ShapeType convert_cpp_shape_type(const ShapeDetector::ShapeType& cpp_type) {
    switch (cpp_type) {
        case ShapeDetector::ShapeType::RECTANGLE: return SHAPE_TYPE_RECTANGLE;
        case ShapeDetector::ShapeType::LONG_RECTANGLE: return SHAPE_TYPE_LONG_RECTANGLE;
        case ShapeDetector::ShapeType::TRIANGLE: return SHAPE_TYPE_TRIANGLE;
        default: return SHAPE_TYPE_UNKNOWN;
    }
}

static cv::Mat image_data_to_mat(const ImageData* image_data) {
    if (!image_data || !image_data->data) {
        return cv::Mat();
    }
    
    return cv::Mat(image_data->height, image_data->width, CV_8UC3, image_data->data);
}

static bool mat_to_image_data(const cv::Mat& mat, ImageData* output_data) {
    if (mat.empty() || !output_data) {
        return false;
    }
    
    output_data->width = mat.cols;
    output_data->height = mat.rows;
    output_data->channels = mat.channels();
    
    size_t data_size = mat.total() * mat.elemSize();
    output_data->data = (uint8_t*)malloc(data_size);
    if (!output_data->data) {
        return false;
    }
    
    memcpy(output_data->data, mat.data, data_size);
    return true;
}

// API implementations
extern "C" {

bool shape_detector_init() {
    try {
        LOGI("Shape detector initialized successfully");
        return true;
    } catch (const std::exception& e) {
        g_last_error = std::string("Initialization failed: ") + e.what();
        LOGE("Initialization failed: %s", e.what());
        return false;
    }
}

void shape_detector_cleanup() {
    LOGI("Shape detector cleanup completed");
}

DetectionResult* shape_detector_detect(const ImageData* image_data, bool debug) {
    if (!image_data) {
        g_last_error = "Invalid image data";
        LOGE("Invalid image data");
        return nullptr;
    }
    
    try {
        // Convert ImageData to cv::Mat
        cv::Mat image = image_data_to_mat(image_data);
        if (image.empty()) {
            g_last_error = "Failed to convert image data";
            LOGE("Failed to convert image data");
            return nullptr;
        }
        
        // Detect shapes
        ShapeDetector::DetectionResult cpp_result = ShapeDetector::detectShapes(image, debug);
        
        // Allocate C result structure
        DetectionResult* result = (DetectionResult*)malloc(sizeof(DetectionResult));
        if (!result) {
            g_last_error = "Memory allocation failed";
            LOGE("Memory allocation failed");
            return nullptr;
        }
        
        result->shape_count = cpp_result.shapes.size();
        result->total_count = cpp_result.shapes.size();
        
        if (result->shape_count > 0) {
            result->shapes = (DetectedShape*)malloc(sizeof(DetectedShape) * result->shape_count);
            if (!result->shapes) {
                free(result);
                g_last_error = "Memory allocation failed for shapes";
                LOGE("Memory allocation failed for shapes");
                return nullptr;
            }
            
            // Convert shapes
            for (int i = 0; i < result->shape_count; i++) {
                const auto& cpp_shape = cpp_result.shapes[i];
                DetectedShape& c_shape = result->shapes[i];
                
                c_shape.id = cpp_shape.shapeId;
                c_shape.type = convert_cpp_shape_type(cpp_shape.type);
                c_shape.color = convert_color_type(cpp_shape.color);
                c_shape.center.x = cpp_shape.center.x;
                c_shape.center.y = cpp_shape.center.y;
                c_shape.area = cpp_shape.area;
                c_shape.aspect_ratio = cpp_shape.aspectRatio;
                c_shape.orientation_angle = cpp_shape.orientationAngle;
                c_shape.direction_line_start.x = cpp_shape.directionLineStart.x;
                c_shape.direction_line_start.y = cpp_shape.directionLineStart.y;
                c_shape.direction_line_end.x = cpp_shape.directionLineEnd.x;
                c_shape.direction_line_end.y = cpp_shape.directionLineEnd.y;
                
                // Generate shape code
                std::string shape_code = cpp_shape.color.substr(0, 1);
                if (cpp_shape.type == ShapeDetector::ShapeType::LONG_RECTANGLE) {
                    shape_code += "LR";
                } else if (cpp_shape.type == ShapeDetector::ShapeType::RECTANGLE) {
                    shape_code += "RE";
                } else if (cpp_shape.type == ShapeDetector::ShapeType::TRIANGLE) {
                    shape_code += "TR";
                } else {
                    shape_code += "UN";
                }
                
                strncpy(c_shape.shape_code, shape_code.c_str(), sizeof(c_shape.shape_code) - 1);
                c_shape.shape_code[sizeof(c_shape.shape_code) - 1] = '\0';
            }
        } else {
            result->shapes = nullptr;
        }
        
        LOGI("Detected %d shapes", result->shape_count);
        return result;
        
    } catch (const std::exception& e) {
        g_last_error = std::string("Detection failed: ") + e.what();
        LOGE("Detection failed: %s", e.what());
        return nullptr;
    }
}

char* shape_detector_generate_json(const DetectionResult* result) {
    if (!result) {
        g_last_error = "Invalid detection result";
        LOGE("Invalid detection result");
        return nullptr;
    }
    
    try {
        // Convert C result back to C++ result for JSON generation
        ShapeDetector::DetectionResult cpp_result;
        
        for (int i = 0; i < result->shape_count; i++) {
            const DetectedShape& c_shape = result->shapes[i];
            ShapeDetector::DetectedShape cpp_shape;
            
            cpp_shape.shapeId = c_shape.id;
            cpp_shape.center = cv::Point2f(c_shape.center.x, c_shape.center.y);
            cpp_shape.area = c_shape.area;
            cpp_shape.aspectRatio = c_shape.aspect_ratio;
            cpp_shape.orientationAngle = c_shape.orientation_angle;
            cpp_shape.directionLineStart = cv::Point2f(c_shape.direction_line_start.x, c_shape.direction_line_start.y);
            cpp_shape.directionLineEnd = cv::Point2f(c_shape.direction_line_end.x, c_shape.direction_line_end.y);
            
            // Convert C enums to C++ enums
            switch (c_shape.type) {
                case SHAPE_TYPE_RECTANGLE: cpp_shape.type = ShapeDetector::ShapeType::RECTANGLE; break;
                case SHAPE_TYPE_LONG_RECTANGLE: cpp_shape.type = ShapeDetector::ShapeType::LONG_RECTANGLE; break;
                case SHAPE_TYPE_TRIANGLE: cpp_shape.type = ShapeDetector::ShapeType::TRIANGLE; break;
                default: cpp_shape.type = ShapeDetector::ShapeType::RECTANGLE; break;
            }
            
            switch (c_shape.color) {
                case COLOR_RED: cpp_shape.color = "Red"; break;
                case COLOR_GREEN: cpp_shape.color = "Green"; break;
                case COLOR_BLUE: cpp_shape.color = "Blue"; break;
                case COLOR_YELLOW: cpp_shape.color = "Yellow"; break;
                case COLOR_CYAN: cpp_shape.color = "Cyan"; break;
                case COLOR_MAGENTA: cpp_shape.color = "Magenta"; break;
                case COLOR_BLACK: cpp_shape.color = "Black"; break;
                case COLOR_WHITE: cpp_shape.color = "White"; break;
                default: cpp_shape.color = "Unknown"; break;
            }
            
            cpp_result.shapes.push_back(cpp_shape);
        }
        
        std::string json_str = ShapeDetector::generateJsonOutput(cpp_result);
        
        // Allocate C string
        char* c_json = (char*)malloc(json_str.length() + 1);
        if (!c_json) {
            g_last_error = "Memory allocation failed for JSON";
            LOGE("Memory allocation failed for JSON");
            return nullptr;
        }
        
        strcpy(c_json, json_str.c_str());
        return c_json;
        
    } catch (const std::exception& e) {
        g_last_error = std::string("JSON generation failed: ") + e.what();
        LOGE("JSON generation failed: %s", e.what());
        return nullptr;
    }
}

bool shape_detector_annotate_image(const ImageData* image_data, 
                                   const DetectionResult* result, 
                                   ImageData* output_data) {
    if (!image_data || !result || !output_data) {
        g_last_error = "Invalid parameters for image annotation";
        LOGE("Invalid parameters for image annotation");
        return false;
    }
    
    try {
        cv::Mat image = image_data_to_mat(image_data);
        if (image.empty()) {
            g_last_error = "Failed to convert input image";
            LOGE("Failed to convert input image");
            return false;
        }
        
        // Convert C result to C++ result
        std::vector<ShapeDetector::DetectedShape> cpp_shapes;
        for (int i = 0; i < result->shape_count; i++) {
            const DetectedShape& c_shape = result->shapes[i];
            ShapeDetector::DetectedShape cpp_shape;
            
            cpp_shape.shapeId = c_shape.id;
            cpp_shape.center = cv::Point2f(c_shape.center.x, c_shape.center.y);
            cpp_shape.area = c_shape.area;
            cpp_shape.aspectRatio = c_shape.aspect_ratio;
            cpp_shape.orientationAngle = c_shape.orientation_angle;
            cpp_shape.directionLineStart = cv::Point2f(c_shape.direction_line_start.x, c_shape.direction_line_start.y);
            cpp_shape.directionLineEnd = cv::Point2f(c_shape.direction_line_end.x, c_shape.direction_line_end.y);
            
            // Convert enums to C++ enum class
            switch (c_shape.type) {
                case SHAPE_TYPE_RECTANGLE: cpp_shape.type = ShapeDetector::ShapeType::RECTANGLE; break;
                case SHAPE_TYPE_LONG_RECTANGLE: cpp_shape.type = ShapeDetector::ShapeType::LONG_RECTANGLE; break;
                case SHAPE_TYPE_TRIANGLE: cpp_shape.type = ShapeDetector::ShapeType::TRIANGLE; break;
                default: cpp_shape.type = ShapeDetector::ShapeType::RECTANGLE; break;
            }
            
            switch (c_shape.color) {
                case COLOR_RED: cpp_shape.color = "Red"; break;
                case COLOR_GREEN: cpp_shape.color = "Green"; break;
                case COLOR_BLUE: cpp_shape.color = "Blue"; break;
                case COLOR_YELLOW: cpp_shape.color = "Yellow"; break;
                case COLOR_CYAN: cpp_shape.color = "Cyan"; break;
                case COLOR_MAGENTA: cpp_shape.color = "Magenta"; break;
                case COLOR_BLACK: cpp_shape.color = "Black"; break;
                case COLOR_WHITE: cpp_shape.color = "White"; break;
                default: cpp_shape.color = "Unknown"; break;
            }
            
            cpp_shapes.push_back(cpp_shape);
        }
        
        cv::Mat annotated = ShapeDetector::annotateShapes(image, cpp_shapes);
        
        return mat_to_image_data(annotated, output_data);
        
    } catch (const std::exception& e) {
        g_last_error = std::string("Image annotation failed: ") + e.what();
        LOGE("Image annotation failed: %s", e.what());
        return false;
    }
}

void shape_detector_free_result(DetectionResult* result) {
    if (result) {
        if (result->shapes) {
            free(result->shapes);
        }
        free(result);
    }
}

void shape_detector_free_json(char* json_str) {
    if (json_str) {
        free(json_str);
    }
}

void shape_detector_free_image(ImageData* image_data) {
    if (image_data && image_data->data) {
        free(image_data->data);
        image_data->data = nullptr;
    }
}

const char* shape_detector_get_version() {
    return VERSION;
}

const char* shape_detector_get_last_error() {
    return g_last_error.c_str();
}

} // extern "C"