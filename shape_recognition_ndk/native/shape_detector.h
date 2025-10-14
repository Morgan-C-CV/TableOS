#ifndef SHAPE_DETECTOR_H
#define SHAPE_DETECTOR_H

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/highgui.hpp>
#include <vector>
#include <string>
#include <map>

namespace ShapeDetector {

// 颜色范围结构体
struct ColorRange {
    cv::Scalar lower;
    cv::Scalar upper;
    
    ColorRange() = default;
    ColorRange(const cv::Scalar& l, const cv::Scalar& u) : lower(l), upper(u) {}
};

// 形状类型枚举
enum class ShapeType {
    LONG_RECTANGLE,    // 长矩形（长边是短边的2倍）
    TRIANGLE,          // 三角形
    RECTANGLE          // 普通矩形
};

// 检测到的形状结构体
struct DetectedShape {
    ShapeType type;                    // 形状类型
    std::string color;                 // 颜色名称
    std::vector<cv::Point> contour;    // 轮廓点
    cv::Rect boundingRect;             // 边界矩形
    cv::Point2f center;                // 中心点
    double area;                       // 面积
    double aspectRatio;                // 长宽比
    double orientationAngle;           // 方向角（与垂直方向的夹角，度数）
    cv::Point2f directionLineStart;    // 方向线起点
    cv::Point2f directionLineEnd;      // 方向线终点
    int shapeId;                       // 形状编码ID
    
    DetectedShape() = default;
};

// 检测结果结构体
struct DetectionResult {
    std::vector<DetectedShape> shapes;  // 检测到的所有形状
    cv::Mat annotatedImage;             // 标注后的图像
    bool success;                       // 检测是否成功
    
    DetectionResult() : success(false) {}
};

// 主要函数声明

/**
 * 加载图像
 */
cv::Mat loadImage(const std::string& path);

/**
 * 获取默认颜色范围
 */
std::map<std::string, ColorRange> getDefaultColorRanges();

/**
 * 预处理图像
 */
cv::Mat preprocessImage(const cv::Mat& image);

/**
 * 检测指定颜色的区域
 */
cv::Mat detectColorRegions(const cv::Mat& hsv, const ColorRange& colorRange);

/**
 * 分析轮廓形状
 */
ShapeType analyzeContourShape(const std::vector<cv::Point>& contour);

/**
 * 检测长矩形（长边是短边的2倍）
 */
bool isLongRectangle(const std::vector<cv::Point>& contour, double& aspectRatio);

/**
 * 检测三角形
 */
bool isTriangle(const std::vector<cv::Point>& contour);

/**
 * 检测普通矩形
 */
bool isRectangle(const std::vector<cv::Point>& contour, double& aspectRatio);

/**
 * 主检测函数
 */
DetectionResult detectShapes(const cv::Mat& image, bool debug = false);

/**
 * 在图像上标注检测结果
 */
cv::Mat annotateShapes(const cv::Mat& image, const std::vector<DetectedShape>& shapes);

/**
 * 打印检测结果
 */
void printDetectionResults(const DetectionResult& result);

// 输出JSON格式结果
std::string generateJsonOutput(const DetectionResult& result);

} // namespace ShapeDetector

#endif // SHAPE_DETECTOR_H