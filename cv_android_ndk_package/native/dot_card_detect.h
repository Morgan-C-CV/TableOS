#ifndef DOT_CARD_DETECT_H
#define DOT_CARD_DETECT_H

// 统一的 OpenCV 可用性检测与条件包含
#ifndef HAVE_OPENCV
#  if defined(__has_include)
#    if __has_include(<opencv2/core.hpp>)
#      define HAVE_OPENCV 1
#    else
#      define HAVE_OPENCV 0
#    endif
#  else
     // 某些静态分析器不支持 __has_include；在未明确声明时默认关闭
#    define HAVE_OPENCV 0
#  endif
#endif

#if HAVE_OPENCV
#  include <opencv2/core.hpp>
#  include <opencv2/imgproc.hpp>
#  include <opencv2/imgcodecs.hpp>
#endif

// 高级GUI（highgui）仅在 OpenCV 可用且头存在时启用
#if HAVE_OPENCV
#  if defined(__has_include)
#    if __has_include(<opencv2/highgui.hpp>)
#      include <opencv2/highgui.hpp>
#      define HAVE_OPENCV_HIGHGUI 1
#    else
#      define HAVE_OPENCV_HIGHGUI 0
#    endif
#  else
#    define HAVE_OPENCV_HIGHGUI 0
#  endif
#else
#  define HAVE_OPENCV_HIGHGUI 0
#endif

#if !HAVE_OPENCV
// 轻量级占位声明，便于 IDE 在缺少 OpenCV 头文件时避免诊断错误
namespace cv {
  struct Point { int x; int y; Point(int xx=0,int yy=0):x(xx),y(yy){} };
  struct Point2f { float x; float y; Point2f(float xx=0.f,float yy=0.f):x(xx),y(yy){} };
  struct Rect { int x; int y; int width; int height; Rect(int xx=0,int yy=0,int w=0,int h=0):x(xx),y(yy),width(w),height(h){} };
  struct Mat { int cols=0; int rows=0; bool empty() const { return cols==0||rows==0; } };
  struct Scalar { double v0,v1,v2; Scalar(double a=0.0,double b=0.0,double c=0.0):v0(a),v1(b),v2(c){} };
}
#endif
#include <vector>
#include <map>
#include <string>
#include <utility>
#include <iostream>
#include <tuple>

namespace DotCardDetect {

// 颜色范围结构体
struct ColorRange {
    cv::Scalar lower;
    cv::Scalar upper;
    
    ColorRange() = default;
    ColorRange(const cv::Scalar& l, const cv::Scalar& u) : lower(l), upper(u) {}
};

// 卡片结构体
struct Card {
    std::vector<cv::Point> corners;  // 四个角点（按顺序：左上、右上、右下、左下）
    cv::Rect boundingRect;           // 卡片边界矩形
    std::vector<int> cornerIndices;  // 对应的角点mark在rectangles中的索引
    
    Card() = default;
};

// 检测结果结构体
struct DetectionResult {
    cv::Mat rectMask;        // 检测到的矩形区域掩码
    cv::Mat dotMask;         // 检测到的点区域掩码
    std::vector<std::vector<cv::Point>> rectangles;  // 检测到的矩形轮廓
    double angle;            // 旋转角度
    bool success;            // 检测是否成功
    
    // 区域颜色信息：键为区域代码(U/D/L/R)，值为(近距离颜色ID, 远距离颜色ID)
    // 颜色ID: 0=Red, 1=Yellow, 2=Green, 3=Cyan, 4=Blue, 5=Indigo
    std::map<std::string, std::pair<int, int>> regionColors;
    
    // 检测到的卡片信息
    std::vector<Card> cards;         // 配对后的卡片列表
    
    DetectionResult() : angle(0.0), success(false) {}
};

/**
 * 加载图像
 * @param path 图像路径
 * @return 加载的图像，如果失败返回空Mat
 */
cv::Mat loadImage(const std::string& path);

/**
 * 点卡预处理
 * @param img 输入图像
 * @param debug 是否显示调试信息
 * @return 预处理后的二值化图像
 */
cv::Mat dotPreprocess(const cv::Mat& img, bool debug = true);

/**
 * 检查轮廓是否为正方形边缘
 * @param approx 近似轮廓点
 * @return 是否为正方形
 */
bool checkSquareEdges(const std::vector<cv::Point>& approx);

/**
 * 验证白色像素比例
 * @param approx 轮廓点
 * @param thresholdImg 二值化图像
 * @param minRatio 最小白色像素比例
 * @return 是否满足白色像素比例要求
 */
bool verifyWhitePixelRatio(const std::vector<cv::Point>& approx, 
                          const cv::Mat& thresholdImg, 
                          double minRatio = 0.8);

/**
 * 检查扩展区域的颜色
 * @param img 输入图像（会被修改用于绘制）
 * @param approx 检测到的矩形轮廓
 * @param hsv HSV颜色空间的图像
 * @param colorRanges 颜色范围映射
 * @return 点掩码和旋转角度的pair
 */
std::pair<cv::Mat, double> checkExtendedRegionsForColors(
    cv::Mat& img,
    const std::vector<cv::Point>& approx,
    const cv::Mat& hsv,
    const std::map<std::string, ColorRange>& colorRanges
);

/**
 * 检查扩展区域的颜色（优化版本）
 * @param img 输入图像（会被修改用于绘制）
 * @param approx 检测到的矩形轮廓
 * @param hsv HSV颜色空间的图像
 * @param colorRanges 颜色范围映射
 * @param precomputedColorMasks 预计算的颜色掩码
 * @return 点掩码和旋转角度的pair
 */
std::tuple<cv::Mat, double, std::map<std::string, std::pair<int, int>>> checkExtendedRegionsForColorsOptimized(
    cv::Mat& img,
    const std::vector<cv::Point>& approx,
    const cv::Mat& hsv,
    const std::map<std::string, ColorRange>& colorRanges,
    const std::map<std::string, cv::Mat>& precomputedColorMasks
);

/**
 * 获取默认颜色范围
 * @return 颜色范围映射
 */
std::map<std::string, ColorRange> getDefaultColorRanges();

/**
 * 主要的点卡检测函数
 * @param img 输入图像
 * @param debug 是否显示调试信息
 * @return 检测结果
 */
DetectionResult detectDotCards(const cv::Mat& img, bool debug = true);

/**
 * 显示颜色掩码（调试用）
 * @param hsv HSV图像
 * @param colorRanges 颜色范围
 */
void showColorMasks(const cv::Mat& hsv, const std::map<std::string, ColorRange>& colorRanges);

/**
 * 创建红色掩码
 * @param hsv HSV图像
 * @param colorRanges 颜色范围映射
 * @return 红色掩码
 */
cv::Mat createRedMask(const cv::Mat& hsv, const std::map<std::string, ColorRange>& colorRanges);

/**
 * 四角mark配对算法 - 将检测到的矩形mark组合成完整的卡片
 * @param rectangles 检测到的矩形轮廓列表
 * @param img 原始图像
 * @return 配对后的卡片列表
 */
// 辅助函数声明
double evaluateRectangularity(const std::vector<cv::Point2f>& points);
std::vector<cv::Point2f> sortRectangleCorners(const std::vector<cv::Point2f>& points);
std::vector<Card> pairRectanglesIntoCards(const std::vector<std::vector<cv::Point>>& rectangles, const cv::Mat& img);

} // namespace DotCardDetect

#endif // DOT_CARD_DETECT_H