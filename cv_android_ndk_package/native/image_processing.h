#ifndef IMAGE_PROCESSING_H
#define IMAGE_PROCESSING_H

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
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