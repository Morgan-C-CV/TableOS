#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>
#include <opencv2/opencv.hpp>
#include <opencv2/imgproc.hpp>
#include <vector>
#include <map>
#include <cmath>
#include <sys/stat.h>
#include <errno.h>
#include "shape_detector_c_api.h"

#define LOG_TAG "ShapeDetectorJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// 颜色识别相关结构和常量
struct HullInfo {
    std::vector<cv::Point> hull;
    cv::Point center;
    double area;
    std::string color;
    
    HullInfo(const std::vector<cv::Point>& h, const cv::Point& c, double a, const std::string& col) 
        : hull(h), center(c), area(a), color(col) {}
};

// 5帧稳定性检测
static std::vector<std::vector<HullInfo>> frameBuffer(5);
static int currentFrameIndex = 0;
static int frameCount = 0;
static const double DISTANCE_THRESHOLD = 80.0;
static const int REQUIRED_FRAMES = 3;

// 颜色定义
static std::vector<std::string> colorNames = {"Yellow", "Green", "Cyan", "Blue", "Black"};
static std::map<std::string, cv::Scalar> colorMap = {
    {"Yellow", cv::Scalar(0, 255, 255)},
    {"Green", cv::Scalar(0, 255, 0)},
    {"Cyan", cv::Scalar(255, 255, 0)},
    {"Blue", cv::Scalar(255, 0, 0)},
    {"Black", cv::Scalar(128, 128, 128)}
};

// 计算两点间距离
double calculateDistance(const cv::Point& p1, const cv::Point& p2) {
    return std::sqrt(std::pow(p1.x - p2.x, 2) + std::pow(p1.y - p2.y, 2));
}

// 创建目录的函数
bool createDirectory(const std::string& path) {
    struct stat st = {0};
    if (stat(path.c_str(), &st) == -1) {
        if (mkdir(path.c_str(), 0755) == 0) {
            LOGI("成功创建目录: %s", path.c_str());
            return true;
        } else {
            LOGE("创建目录失败: %s, 错误: %s", path.c_str(), strerror(errno));
            return false;
        }
    } else {
        LOGI("目录已存在: %s", path.c_str());
        return true;
    }
}

// 创建颜色掩码
cv::Mat createColorMask(const cv::Mat& image, const std::string& colorName) {
    cv::Mat hsv, mask;
    cv::cvtColor(image, hsv, cv::COLOR_BGR2HSV);
    
    // 定义颜色范围
    std::map<std::string, std::pair<cv::Scalar, cv::Scalar>> colorRanges = {
        {"Yellow", {cv::Scalar(10, 20, 60), cv::Scalar(65, 255, 255)}},
        {"Green", {cv::Scalar(40, 40, 60), cv::Scalar(85, 255, 255)}},
        {"Cyan", {cv::Scalar(95, 50, 110), cv::Scalar(120, 255, 255)}},
        {"Blue", {cv::Scalar(100, 40, 60), cv::Scalar(140, 255, 255)}},
        {"Black", {cv::Scalar(0, 0, 0), cv::Scalar(180, 50, 40)}}
    };
    
    if (colorRanges.find(colorName) != colorRanges.end()) {
        cv::inRange(hsv, colorRanges[colorName].first, colorRanges[colorName].second, mask);
    } else {
        mask = cv::Mat::zeros(image.size(), CV_8UC1);
    }
    
    return mask;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_init(JNIEnv *env, jclass clazz) {
    LOGI("Initializing shape detector");
    bool result = shape_detector_init();
    if (result) {
        LOGI("Shape detector initialized successfully");
    } else {
        LOGE("Failed to initialize shape detector");
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_cleanup(JNIEnv *env, jclass clazz) {
    LOGI("Cleaning up shape detector");
    shape_detector_cleanup();
}

JNIEXPORT jstring JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_detectShapesFromBitmap(JNIEnv *env, jclass clazz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    
    // Get bitmap info
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return env->NewStringUTF("{}");
    }
    
    // Lock bitmap pixels
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return env->NewStringUTF("{}");
    }
    
    // Convert RGBA to BGR for OpenCV
    int width = info.width;
    int height = info.height;
    cv::Mat frame(height, width, CV_8UC3);
    
    uint8_t* rgba_pixels = (uint8_t*)pixels;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int rgba_idx = (y * width + x) * 4;
            int bgr_idx = y * width + x;
            frame.at<cv::Vec3b>(y, x)[0] = rgba_pixels[rgba_idx + 2]; // B
            frame.at<cv::Vec3b>(y, x)[1] = rgba_pixels[rgba_idx + 1]; // G
            frame.at<cv::Vec3b>(y, x)[2] = rgba_pixels[rgba_idx + 0]; // R
        }
    }
    
    // Unlock bitmap
    AndroidBitmap_unlockPixels(env, bitmap);
    
    // 更新帧计数和索引
    frameCount++;
    currentFrameIndex = frameCount % 5;
    
    // 清空当前帧的检测结果
    frameBuffer[currentFrameIndex].clear();
    
    // 对每种颜色进行检测
    for (const std::string& colorName : colorNames) {
        cv::Mat mask = createColorMask(frame, colorName);
        
        // 找到轮廓
        std::vector<std::vector<cv::Point>> contours;
        std::vector<cv::Vec4i> hierarchy;
        cv::findContours(mask, contours, hierarchy, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
        
        // 处理每个轮廓
        for (const auto& contour : contours) {
            double area = cv::contourArea(contour);
            
            if (area > 800) {  // 过滤小轮廓，与保存图片检测保持一致
                std::vector<cv::Point> hull;
                cv::convexHull(contour, hull);
                
                // 计算中心点
                cv::Moments m = cv::moments(contour);
                if (m.m00 != 0) {
                    cv::Point center(m.m10 / m.m00, m.m01 / m.m00);
                    
                    // 存储当前帧的凸包信息
                    frameBuffer[currentFrameIndex].emplace_back(hull, center, area, colorName);
                }
            }
        }
    }
    
    // 收集稳定的凸包
    std::vector<HullInfo> stableHulls;
    
    // 如果已经有足够的帧数，进行稳定性检测
    if (frameCount >= REQUIRED_FRAMES) {
        // 检查当前帧的每个凸包是否在过去的帧中都有相似位置的凸包
        for (const auto& currentHull : frameBuffer[currentFrameIndex]) {
            bool isStable = true;
            
            // 检查过去的帧
            for (int i = 1; i < REQUIRED_FRAMES; i++) {
                int prevFrameIndex = (currentFrameIndex - i + 5) % 5;
                bool foundSimilar = false;
                
                // 在前一帧中寻找相似位置和颜色的凸包
                for (const auto& prevHull : frameBuffer[prevFrameIndex]) {
                    if (prevHull.color == currentHull.color) {
                        double distance = calculateDistance(currentHull.center, prevHull.center);
                        if (distance <= DISTANCE_THRESHOLD) {
                            foundSimilar = true;
                            break;
                        }
                    }
                }
                
                if (!foundSimilar) {
                    isStable = false;
                    break;
                }
            }
            
            // 如果稳定，添加到结果中
            if (isStable) {
                stableHulls.push_back(currentHull);
            }
        }
    }
    
    // 构建简化的JSON结果，兼容现有解析逻辑
    std::string result = "";
    for (size_t i = 0; i < stableHulls.size(); ++i) {
        const auto& hull = stableHulls[i];
        result += "{\n";
        result += "  \"id\": " + std::to_string(i) + ",\n";
        result += "  \"position\": {\n";
        result += "    \"x\": " + std::to_string(hull.center.x) + ",\n";
        result += "    \"y\": " + std::to_string(hull.center.y) + "\n";
        result += "  },\n";
        result += "  \"color\": \"" + hull.color + "\"\n";
        result += "}\n";
        if (i < stableHulls.size() - 1) result += "\n";
    }
    
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT jobject JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_annotateImage(JNIEnv *env, jclass clazz, jobject bitmap) {
    AndroidBitmapInfo info;
    void* pixels;
    
    // Get bitmap info
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return nullptr;
    }
    
    // Lock bitmap pixels
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return nullptr;
    }
    
    // Convert RGBA to BGR for OpenCV
    int width = info.width;
    int height = info.height;
    cv::Mat frame(height, width, CV_8UC3);
    cv::Mat displayFrame(height, width, CV_8UC4);
    
    uint8_t* rgba_pixels = (uint8_t*)pixels;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int rgba_idx = (y * width + x) * 4;
            // 复制原始RGBA到显示帧
            displayFrame.at<cv::Vec4b>(y, x)[0] = rgba_pixels[rgba_idx + 2]; // B
            displayFrame.at<cv::Vec4b>(y, x)[1] = rgba_pixels[rgba_idx + 1]; // G
            displayFrame.at<cv::Vec4b>(y, x)[2] = rgba_pixels[rgba_idx + 0]; // R
            displayFrame.at<cv::Vec4b>(y, x)[3] = rgba_pixels[rgba_idx + 3]; // A
            
            // BGR for processing
            frame.at<cv::Vec3b>(y, x)[0] = rgba_pixels[rgba_idx + 2]; // B
            frame.at<cv::Vec3b>(y, x)[1] = rgba_pixels[rgba_idx + 1]; // G
            frame.at<cv::Vec3b>(y, x)[2] = rgba_pixels[rgba_idx + 0]; // R
        }
    }
    
    // 绘制稳定的凸包
    if (frameCount >= REQUIRED_FRAMES) {
        for (const auto& currentHull : frameBuffer[currentFrameIndex]) {
            bool isStable = true;
            
            // 检查稳定性
            for (int i = 1; i < REQUIRED_FRAMES; i++) {
                int prevFrameIndex = (currentFrameIndex - i + 5) % 5;
                bool foundSimilar = false;
                
                for (const auto& prevHull : frameBuffer[prevFrameIndex]) {
                    if (prevHull.color == currentHull.color) {
                        double distance = calculateDistance(currentHull.center, prevHull.center);
                        if (distance <= DISTANCE_THRESHOLD) {
                            foundSimilar = true;
                            break;
                        }
                    }
                }
                
                if (!foundSimilar) {
                    isStable = false;
                    break;
                }
            }
            
            // 如果稳定，绘制凸包
            if (isStable) {
                cv::Scalar color = colorMap[currentHull.color];
                
                // 绘制凸包轮廓
                std::vector<std::vector<cv::Point>> hullContours = {currentHull.hull};
                cv::drawContours(displayFrame, hullContours, -1, color, 3);
                
                // 绘制中心点
                cv::circle(displayFrame, currentHull.center, 5, color, -1);
                
                // 添加标签
                cv::putText(displayFrame, currentHull.color, 
                           cv::Point(currentHull.center.x - 20, currentHull.center.y - 10),
                           cv::FONT_HERSHEY_SIMPLEX, 0.5, color, 2);
            }
        }
    }
    
    // 将结果复制回bitmap
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int rgba_idx = (y * width + x) * 4;
            rgba_pixels[rgba_idx + 0] = displayFrame.at<cv::Vec4b>(y, x)[2]; // R
            rgba_pixels[rgba_idx + 1] = displayFrame.at<cv::Vec4b>(y, x)[1]; // G
            rgba_pixels[rgba_idx + 2] = displayFrame.at<cv::Vec4b>(y, x)[0]; // B
            rgba_pixels[rgba_idx + 3] = displayFrame.at<cv::Vec4b>(y, x)[3]; // A
        }
    }
    
    // Unlock bitmap
    AndroidBitmap_unlockPixels(env, bitmap);
    
    return bitmap;
}

JNIEXPORT jstring JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_getVersion(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF("BeakerLab Color Detection v1.0 with 5-frame stability");
}

JNIEXPORT jstring JNICALL
Java_com_tableos_beakerlab_ShapeDetectorJNI_saveDebugImages(JNIEnv *env, jclass clazz, jobject bitmap, jstring savePath) {
    AndroidBitmapInfo info;
    void* pixels;
    
    LOGI("开始保存调试图片");
    
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("Failed to get bitmap info");
        return env->NewStringUTF("Failed to get bitmap info");
    }
    
    LOGI("Bitmap info: width=%d, height=%d, format=%d", info.width, info.height, info.format);
    
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("Failed to lock bitmap pixels");
        return env->NewStringUTF("Failed to lock bitmap pixels");
    }
    
    // 转换为OpenCV Mat
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels);
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
    
    LOGI("图像转换完成: BGR size=%dx%d, channels=%d", bgr.cols, bgr.rows, bgr.channels());
    
    // 获取保存路径
    const char* pathStr = env->GetStringUTFChars(savePath, nullptr);
    std::string basePath(pathStr);
    env->ReleaseStringUTFChars(savePath, pathStr);
    
    LOGI("保存路径: %s", basePath.c_str());
    
    try {
        // 检查图像是否为空
        if (bgr.empty()) {
            LOGE("BGR图像为空");
            AndroidBitmap_unlockPixels(env, bitmap);
            return env->NewStringUTF("Error: BGR image is empty");
        }
        
        // 创建目录（如果不存在）
        if (!createDirectory(basePath)) {
            LOGE("无法创建目录: %s", basePath.c_str());
            return env->NewStringUTF("Failed to create directory");
        }
        
        // 保存原始图像
        std::string originalPath = basePath + "/original_image.jpg";
        bool success = cv::imwrite(originalPath, bgr);
        LOGI("保存原始图像: %s, 成功: %d", originalPath.c_str(), success);
        
        // 为每种颜色创建mask并保存
        for (const auto& colorName : colorNames) {
            LOGI("处理颜色: %s", colorName.c_str());
            
            cv::Mat mask = createColorMask(bgr, colorName);
            LOGI("创建mask完成: %s, size=%dx%d, 非零像素数=%d", 
                 colorName.c_str(), mask.cols, mask.rows, cv::countNonZero(mask));
            
            // 保存mask图像
            std::string maskPath = basePath + "/" + colorName + "_mask.jpg";
            bool maskSuccess = cv::imwrite(maskPath, mask);
            LOGI("保存mask: %s, 成功: %d", maskPath.c_str(), maskSuccess);
            
            // 在原图上绘制该颜色的检测结果
            cv::Mat colorResult = bgr.clone();
            std::vector<std::vector<cv::Point>> contours;
            cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
            
            LOGI("找到轮廓数量: %zu", contours.size());
            
            int validContours = 0;
            for (const auto& contour : contours) {
                double area = cv::contourArea(contour);
                if (area > 500) {
                    validContours++;
                    std::vector<cv::Point> hull;
                    cv::convexHull(contour, hull);
                    
                    // 绘制凸包
                    cv::Scalar drawColor = colorMap[colorName];
                    cv::polylines(colorResult, hull, true, drawColor, 3);
                    
                    // 计算并绘制中心点
                    cv::Moments moments = cv::moments(contour);
                    if (moments.m00 > 0) {
                        cv::Point center(moments.m10 / moments.m00, moments.m01 / moments.m00);
                        cv::circle(colorResult, center, 8, drawColor, -1);
                        
                        // 添加颜色标签
                        cv::putText(colorResult, colorName, 
                                  cv::Point(center.x - 30, center.y - 15),
                                  cv::FONT_HERSHEY_SIMPLEX, 0.7, drawColor, 2);
                        
                        LOGI("绘制%s检测结果: 中心点(%d,%d), 面积=%.1f", 
                             colorName.c_str(), center.x, center.y, area);
                    }
                }
            }
            
            LOGI("有效轮廓数量: %d", validContours);
            
            std::string resultPath = basePath + "/" + colorName + "_result.jpg";
            bool resultSuccess = cv::imwrite(resultPath, colorResult);
            LOGI("保存结果图像: %s, 成功: %d", resultPath.c_str(), resultSuccess);
        }
        
        // 创建综合结果图像
        LOGI("创建综合结果图像");
        cv::Mat combinedResult = bgr.clone();
        int totalDetections = 0;
        
        for (const auto& colorName : colorNames) {
            cv::Mat mask = createColorMask(bgr, colorName);
            std::vector<std::vector<cv::Point>> contours;
            cv::findContours(mask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
            
            for (const auto& contour : contours) {
                if (cv::contourArea(contour) > 500) {
                    totalDetections++;
                    std::vector<cv::Point> hull;
                    cv::convexHull(contour, hull);
                    
                    cv::Scalar drawColor = colorMap[colorName];
                    cv::polylines(combinedResult, hull, true, drawColor, 3);
                    
                    cv::Moments moments = cv::moments(contour);
                    if (moments.m00 > 0) {
                        cv::Point center(moments.m10 / moments.m00, moments.m01 / moments.m00);
                        cv::circle(combinedResult, center, 8, drawColor, -1);
                        cv::putText(combinedResult, colorName, 
                                  cv::Point(center.x - 30, center.y - 15),
                                  cv::FONT_HERSHEY_SIMPLEX, 0.7, drawColor, 2);
                    }
                }
            }
        }
        
        LOGI("综合结果图像总检测数量: %d", totalDetections);
        
        std::string combinedPath = basePath + "/combined_result.jpg";
        bool combinedSuccess = cv::imwrite(combinedPath, combinedResult);
        LOGI("保存综合结果图像: %s, 成功: %d", combinedPath.c_str(), combinedSuccess);
        
        AndroidBitmap_unlockPixels(env, bitmap);
        
        return env->NewStringUTF("Debug images saved successfully");
        
    } catch (const std::exception& e) {
        AndroidBitmap_unlockPixels(env, bitmap);
        std::string error = "Error saving debug images: " + std::string(e.what());
        return env->NewStringUTF(error.c_str());
    }
}

} // extern "C"