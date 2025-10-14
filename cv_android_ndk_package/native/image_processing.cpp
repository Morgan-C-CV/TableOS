#include "image_processing.h"
#include <stdexcept>
#include <iostream>

namespace ImageProcessing {

cv::Mat convertToGrayscale(const cv::Mat& image) {
    cv::Mat gray;
    
    if (image.channels() == 3) {
        cv::cvtColor(image, gray, cv::COLOR_BGR2GRAY);
    } else if (image.channels() == 4) {
        cv::cvtColor(image, gray, cv::COLOR_BGRA2GRAY);
    } else {
        gray = image.clone();
    }
    
    return gray;
}

cv::Mat adaptiveThreshold(const cv::Mat& image, 
                         int maxValue,
                         int adaptiveMethod,
                         int thresholdType,
                         int blockSize,
                         int cConstant) {
    cv::Mat processedImage = image;
    
    if (image.channels() != 1) {
        processedImage = convertToGrayscale(image);
    }
    
    if (blockSize % 2 == 0) {
        blockSize += 1;
    }
    if (blockSize < 3) {
        blockSize = 3;
    }
    
    cv::Mat thresholdImg;
    cv::adaptiveThreshold(processedImage, thresholdImg, maxValue, 
                         adaptiveMethod, thresholdType, blockSize, cConstant);
    
    return thresholdImg;
}

std::pair<cv::Mat, double> otsuThreshold(const cv::Mat& image, 
                                        int maxValue,
                                        int thresholdType) {
    cv::Mat processedImage = image;
    
    if (image.channels() != 1) {
        processedImage = convertToGrayscale(image);
    }
    
    cv::Mat thresholdImg;
    double thresholdValue = cv::threshold(processedImage, thresholdImg, 0, maxValue, 
                                         thresholdType | cv::THRESH_OTSU);
    
    return std::make_pair(thresholdImg, thresholdValue);
}

std::pair<cv::Mat, cv::Mat> preprocessImage(const cv::Mat& image,
                                           bool applyGrayscale,
                                           bool applyThreshold,
                                           const std::string& thresholdMethod,
                                           const std::map<std::string, int>& thresholdParams) {
    cv::Mat grayImage, thresholdImage;
    
    std::map<std::string, int> defaultParams;
    if (thresholdMethod == "adaptive") {
        defaultParams["max_value"] = 255;
        defaultParams["adaptive_method"] = cv::ADAPTIVE_THRESH_GAUSSIAN_C;
        defaultParams["threshold_type"] = cv::THRESH_BINARY;
        defaultParams["block_size"] = 11;
        defaultParams["c_constant"] = 2;
    } else {
        defaultParams["max_value"] = 255;
        defaultParams["threshold_type"] = cv::THRESH_BINARY_INV;
    }
    
    for (const auto& param : thresholdParams) {
        defaultParams[param.first] = param.second;
    }
    
    if (applyGrayscale) {
        grayImage = convertToGrayscale(image);
    } else {
        grayImage = image.clone();
    }
    
    if (applyThreshold) {
        if (thresholdMethod == "adaptive") {
            thresholdImage = adaptiveThreshold(grayImage, 
                                             defaultParams["max_value"],
                                             defaultParams["adaptive_method"],
                                             defaultParams["threshold_type"],
                                             defaultParams["block_size"],
                                             defaultParams["c_constant"]);
        } else if (thresholdMethod == "otsu") {
            auto result = otsuThreshold(grayImage, 
                                       defaultParams["max_value"],
                                       defaultParams["threshold_type"]);
            thresholdImage = result.first;
        } else {
            throw std::invalid_argument("不支持的阈值化方法: " + thresholdMethod + 
                                      "。支持的方法: 'adaptive', 'otsu'");
        }
    } else {
        thresholdImage = grayImage.clone();
    }
    
    return std::make_pair(grayImage, thresholdImage);
}

std::tuple<cv::Mat, cv::Mat, cv::Mat> loadAndPreprocess(const std::string& imagePath,
                                                       bool applyGrayscale,
                                                       bool applyThreshold,
                                                       const std::string& thresholdMethod,
                                                       const std::map<std::string, int>& thresholdParams) {
    cv::Mat originalImage = cv::imread(imagePath);
    if (originalImage.empty()) {
        throw std::runtime_error("无法加载图像: " + imagePath);
    }
    
    auto result = preprocessImage(originalImage, applyGrayscale, applyThreshold, 
                                 thresholdMethod, thresholdParams);
    
    return std::make_tuple(originalImage, result.first, result.second);
}

} // namespace ImageProcessing