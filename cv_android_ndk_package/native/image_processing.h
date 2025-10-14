#ifndef IMAGE_PROCESSING_H
#define IMAGE_PROCESSING_H

// 统一的 OpenCV 可用性检测与条件包含，避免编辑器在缺少头文件时报错
#ifndef HAVE_OPENCV
#  if defined(__has_include)
#    if __has_include(<opencv2/core.hpp>)
#      define HAVE_OPENCV 1
#    else
#      define HAVE_OPENCV 0
#    endif
#  else
#    define HAVE_OPENCV 0
#  endif
#endif

#if HAVE_OPENCV
#  include <opencv2/core.hpp>
#  include <opencv2/imgproc.hpp>
#  include <opencv2/imgcodecs.hpp>
#else
namespace cv {
  struct Mat { int cols=0; int rows=0; bool empty() const { return cols==0||rows==0; } };
  // 为默认参数提供占位常量，防止静态分析报未定义
  constexpr int ADAPTIVE_THRESH_GAUSSIAN_C = 1;
  constexpr int THRESH_BINARY = 0;
  constexpr int THRESH_BINARY_INV = 1;
}
#endif
#include <string>
#include <map>
#include <tuple>
#include <utility>

namespace ImageProcessing {

cv::Mat convertToGrayscale(const cv::Mat& image);

cv::Mat adaptiveThreshold(const cv::Mat& image, 
                         int maxValue = 255,
                         int adaptiveMethod = cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                         int thresholdType = cv::THRESH_BINARY,
                         int blockSize = 11,
                         int cConstant = 2);

std::pair<cv::Mat, double> otsuThreshold(const cv::Mat& image, 
                                        int maxValue = 255,
                                        int thresholdType = cv::THRESH_BINARY_INV);

std::pair<cv::Mat, cv::Mat> preprocessImage(const cv::Mat& image,
                                           bool applyGrayscale = true,
                                           bool applyThreshold = true,
                                           const std::string& thresholdMethod = "otsu",
                                           const std::map<std::string, int>& thresholdParams = {});

std::tuple<cv::Mat, cv::Mat, cv::Mat> loadAndPreprocess(const std::string& imagePath,
                                                       bool applyGrayscale = true,
                                                       bool applyThreshold = true,
                                                       const std::string& thresholdMethod = "otsu",
                                                       const std::map<std::string, int>& thresholdParams = {});

} // namespace ImageProcessing

#endif // IMAGE_PROCESSING_H