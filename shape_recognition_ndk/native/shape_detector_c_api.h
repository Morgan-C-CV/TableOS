#ifndef SHAPE_DETECTOR_C_API_H
#define SHAPE_DETECTOR_C_API_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stdbool.h>

// Shape types enumeration
typedef enum {
    SHAPE_TYPE_UNKNOWN = 0,
    SHAPE_TYPE_RECTANGLE = 1,
    SHAPE_TYPE_LONG_RECTANGLE = 2,
    SHAPE_TYPE_TRIANGLE = 3
} ShapeType;

// Color enumeration
typedef enum {
    COLOR_UNKNOWN = 0,
    COLOR_RED = 1,
    COLOR_GREEN = 2,
    COLOR_BLUE = 3,
    COLOR_YELLOW = 4,
    COLOR_CYAN = 5,
    COLOR_MAGENTA = 6,
    COLOR_BLACK = 7,
    COLOR_WHITE = 8
} ColorType;

// Point structure
typedef struct {
    float x;
    float y;
} Point2f;

// Detected shape structure
typedef struct {
    int id;                          // Shape ID
    ShapeType type;                  // Shape type
    ColorType color;                 // Shape color
    Point2f center;                  // Center position
    float area;                      // Area
    float aspect_ratio;              // Aspect ratio
    float orientation_angle;         // Orientation angle in degrees
    Point2f direction_line_start;    // Direction line start point
    Point2f direction_line_end;      // Direction line end point
    char shape_code[8];              // Shape code (e.g., "RRE", "GLR")
} DetectedShape;

// Detection result structure
typedef struct {
    DetectedShape* shapes;           // Array of detected shapes
    int shape_count;                 // Number of detected shapes
    int total_count;                 // Total count (same as shape_count)
} DetectionResult;

// Image data structure for input
typedef struct {
    uint8_t* data;                   // Image data (BGR format)
    int width;                       // Image width
    int height;                      // Image height
    int channels;                    // Number of channels (should be 3 for BGR)
} ImageData;

/**
 * Initialize the shape detector
 * @return true if initialization successful, false otherwise
 */
bool shape_detector_init();

/**
 * Cleanup the shape detector
 */
void shape_detector_cleanup();

/**
 * Detect shapes in an image
 * @param image_data Input image data
 * @param debug Enable debug mode
 * @return Detection result (caller must free using shape_detector_free_result)
 */
DetectionResult* shape_detector_detect(const ImageData* image_data, bool debug);

/**
 * Generate JSON output from detection result
 * @param result Detection result
 * @return JSON string (caller must free using shape_detector_free_json)
 */
char* shape_detector_generate_json(const DetectionResult* result);

/**
 * Annotate image with detection results
 * @param image_data Input image data
 * @param result Detection result
 * @param output_data Output annotated image data (caller must free using shape_detector_free_image)
 * @return true if successful, false otherwise
 */
bool shape_detector_annotate_image(const ImageData* image_data, 
                                   const DetectionResult* result, 
                                   ImageData* output_data);

/**
 * Free detection result memory
 * @param result Detection result to free
 */
void shape_detector_free_result(DetectionResult* result);

/**
 * Free JSON string memory
 * @param json_str JSON string to free
 */
void shape_detector_free_json(char* json_str);

/**
 * Free image data memory
 * @param image_data Image data to free
 */
void shape_detector_free_image(ImageData* image_data);

/**
 * Get version information
 * @return Version string
 */
const char* shape_detector_get_version();

/**
 * Get last error message
 * @return Error message string
 */
const char* shape_detector_get_last_error();

#ifdef __cplusplus
}
#endif

#endif // SHAPE_DETECTOR_C_API_H