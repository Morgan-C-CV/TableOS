#include "dot_card_detect.h"
#include "image_processing.h"
#include <cmath>
#include <algorithm>
#include <iostream>
#include <iomanip>
#include <thread>
#include <mutex>
#include <future>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace DotCardDetect {

cv::Mat loadImage(const std::string& path) {
    return cv::imread(path);
}

cv::Mat dotPreprocess(const cv::Mat& img, bool debug) {
    auto result = ImageProcessing::preprocessImage(img, true, true, "fixed");
    cv::Mat grayscale = result.first;
    cv::Mat threshold = result.second;
    
    if (debug) {
        cv::imshow("original", img);
        cv::imshow("grayscale", grayscale);
        cv::imshow("threshold", threshold);
        cv::waitKey(0);
        cv::destroyAllWindows();
    }
    
    return threshold;
}

bool checkSquareEdges(const std::vector<cv::Point>& approx) {
    if (approx.size() < 4) {
        return false;
    }
    
    std::vector<double> edges;
    for (size_t i = 0; i < approx.size(); i++) {
        cv::Point p1 = approx[i];
        cv::Point p2 = approx[(i + 1) % approx.size()];
        double edgeLength = std::sqrt(std::pow(p1.x - p2.x, 2) + std::pow(p1.y - p2.y, 2));
        edges.push_back(edgeLength);
    }
    
    std::vector<double> edgesSorted = edges;
    std::sort(edgesSorted.rbegin(), edgesSorted.rend());
    
    if (approx.size() == 4) {
        double tolerance = 0.3;
        double minEdge = *std::min_element(edges.begin(), edges.end());
        double maxEdge = *std::max_element(edges.begin(), edges.end());
        
        if ((maxEdge - minEdge) / maxEdge > tolerance) {
            return false;
        }
        return true;
    }
    else if (approx.size() > 4) {
        std::vector<double> mainEdges(edgesSorted.begin(), edgesSorted.begin() + 4);
        std::vector<double> otherEdges(edgesSorted.begin() + 4, edgesSorted.end());
        
        double tolerance = 0.3;
        double minMain = *std::min_element(mainEdges.begin(), mainEdges.end());
        double maxMain = *std::max_element(mainEdges.begin(), mainEdges.end());
        
        if ((maxMain - minMain) / maxMain > tolerance) {
            return false;
        }
        
        if (!otherEdges.empty()) {
            double maxOther = *std::max_element(otherEdges.begin(), otherEdges.end());
            if (minMain / maxOther < 1.5) {
                return false;
            }
        }
        return true;
    }
    
    return false;
}

bool verifyWhitePixelRatio(const std::vector<cv::Point>& approx, 
                          const cv::Mat& thresholdImg, 
                          double minRatio) {
    cv::Mat mask = cv::Mat::zeros(thresholdImg.size(), CV_8UC1);
    std::vector<std::vector<cv::Point>> contours = {approx};
    cv::fillPoly(mask, contours, cv::Scalar(255));
    
    cv::Mat maskedRegion;
    cv::bitwise_and(thresholdImg, mask, maskedRegion);
    
    int totalPixels = cv::countNonZero(mask);
    int whitePixels = cv::countNonZero(maskedRegion);
    
    if (totalPixels == 0) {
        return false;
    }
    
    double whiteRatio = static_cast<double>(whitePixels) / totalPixels;
    return whiteRatio >= minRatio;
}

std::tuple<cv::Mat, double, std::map<std::string, std::pair<int, int>>> checkExtendedRegionsForColorsOptimized(
    cv::Mat& img,
    const std::vector<cv::Point>& approx,
    const cv::Mat& hsv,
    const std::map<std::string, ColorRange>& colorRanges,
    const std::map<std::string, cv::Mat>& precomputedColorMasks) {
    
    cv::Mat dotMask = cv::Mat::zeros(img.rows, img.cols, CV_8UC1);
    
    std::map<std::string, int> colorNameToId = {
        {"Red", 0}, {"Red2", 0},
        {"Yellow", 1},
        {"Green", 2},
        {"Cyan", 3},
        {"Blue", 4},
        {"Indigo", 5}
    };
    
    std::map<std::string, std::string> directionToCode = {
        {"up", "U"}, {"down", "D"}, {"left", "L"}, {"right", "R"}
    };
    
    std::map<std::string, std::pair<int, int>> regionColors;
    
    cv::Rect boundingRect = cv::boundingRect(approx);
    int x = boundingRect.x;
    int y = boundingRect.y;
    int w = boundingRect.width;
    int h = boundingRect.height;
    
    int boundingArea = w * h;
    double actualArea = cv::contourArea(approx);
    double maskRatio = boundingArea > 0 ? actualArea / boundingArea : 0;
    
    bool isRotated = maskRatio < 0.9;
    
    int imgHeight = img.rows;
    int imgWidth = img.cols;
    
    int extendW = static_cast<int>(w * 2);
    int extendH = static_cast<int>(h * 2);
    
    std::map<std::string, cv::Rect> regions;
    std::map<std::string, cv::Mat> triangularMasks;
    
    auto createTriangularMask = [&](const cv::Rect& rect, const std::string& direction) -> cv::Mat {
        cv::Mat mask = cv::Mat::zeros(rect.height, rect.width, CV_8UC1);
        
        if (direction == "up") {
            std::vector<cv::Point> triangle = {
                cv::Point(0, rect.height - 1),
                cv::Point(rect.width - 1, rect.height - 1),
                cv::Point(rect.width / 2, 0)
            };
            cv::fillPoly(mask, triangle, cv::Scalar(255));
        } else if (direction == "down") {
            std::vector<cv::Point> triangle = {
                cv::Point(0, 0),
                cv::Point(rect.width - 1, 0),
                cv::Point(rect.width / 2, rect.height - 1)
            };
            cv::fillPoly(mask, triangle, cv::Scalar(255));
        } else if (direction == "left") {
            std::vector<cv::Point> triangle = {
                cv::Point(rect.width - 1, 0),
                cv::Point(rect.width - 1, rect.height - 1),
                cv::Point(0, rect.height / 2)
            };
            cv::fillPoly(mask, triangle, cv::Scalar(255));
        } else if (direction == "right") {
            std::vector<cv::Point> triangle = {
                cv::Point(0, 0),
                cv::Point(0, rect.height - 1),
                cv::Point(rect.width - 1, rect.height / 2)
            };
            cv::fillPoly(mask, triangle, cv::Scalar(255));
        }
        
        return mask;
    };
    
    regions["up"] = cv::Rect(std::max(0, x), std::max(0, y - extendH), 
                            std::min(imgWidth - std::max(0, x), w), 
                            std::min(imgHeight - std::max(0, y - extendH), y - std::max(0, y - extendH)));
    regions["down"] = cv::Rect(std::max(0, x), std::min(imgHeight, y + h), 
                              std::min(imgWidth - std::max(0, x), w), 
                              std::min(imgHeight - std::min(imgHeight, y + h), extendH));
    regions["left"] = cv::Rect(std::max(0, x - extendW), std::max(0, y), 
                              std::min(imgWidth - std::max(0, x - extendW), x - std::max(0, x - extendW)), 
                              std::min(imgHeight - std::max(0, y), h));
    regions["right"] = cv::Rect(std::min(imgWidth, x + w), std::max(0, y), 
                               std::min(imgWidth - std::min(imgWidth, x + w), extendW), 
                               std::min(imgHeight - std::max(0, y), h));
    
    for (const auto& regionPair : regions) {
        const std::string& direction = regionPair.first;
        const cv::Rect& rect = regionPair.second;
        if (rect.width > 0 && rect.height > 0) {
            triangularMasks[direction] = createTriangularMask(rect, direction);
        }
    }
    
    double angle = 0;
    
    if (isRotated) {
        cv::RotatedRect rotatedRect = cv::minAreaRect(approx);
        angle = rotatedRect.angle;
        
        double normalizedAngle = fmod(fabs(angle), 90.0);
        if (normalizedAngle > 45.0) {
            normalizedAngle = 90.0 - normalizedAngle;
        }
        if (normalizedAngle < 1.0) {
            angle = 0;
            isRotated = false;
        }
        
        std::cout << "Rectangle angle: " << angle << std::endl;
        
        cv::Point2f boundingCenter(x + w / 2.0f, y + h / 2.0f);
        
        for (const auto& regionPair : regions) {
            const std::string& direction = regionPair.first;
            const cv::Rect& rect = regionPair.second;
            
            if (rect.width <= 0 || rect.height <= 0) continue;

            std::vector<cv::Point2f> corners = {
                cv::Point2f(rect.x, rect.y),
                cv::Point2f(rect.x + rect.width, rect.y),
                cv::Point2f(rect.x + rect.width, rect.y + rect.height),
                cv::Point2f(rect.x, rect.y + rect.height)
            };
            
            cv::Mat rotationMatrix = cv::getRotationMatrix2D(boundingCenter, -angle, 1.0);
            std::vector<cv::Point2f> rotatedCorners;
            cv::transform(corners, rotatedCorners, rotationMatrix);
            
            std::vector<cv::Point> rotatedCornersInt;
            for (const auto& corner : rotatedCorners) {
                rotatedCornersInt.push_back(cv::Point(static_cast<int>(corner.x), static_cast<int>(corner.y)));
            }

            cv::Mat mask = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);

            std::vector<std::vector<cv::Point>> contours = {rotatedCornersInt};
            cv::Mat rectMask = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
            cv::fillPoly(rectMask, contours, cv::Scalar(255));

            if (triangularMasks.find(direction) != triangularMasks.end()) {
                cv::Mat triangleMask = triangularMasks[direction];

                cv::Mat transformedTriangleMask = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
                cv::Mat triangleFullSize = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
                triangleMask.copyTo(triangleFullSize(rect));
                
                cv::warpAffine(triangleFullSize, transformedTriangleMask, rotationMatrix, cv::Size(imgWidth, imgHeight));

                cv::bitwise_and(rectMask, transformedTriangleMask, mask);
            } else {
                mask = rectMask;
            }
            
            cv::polylines(img, contours, true, cv::Scalar(255, 0, 0), 2);
            
            int regionArea = cv::countNonZero(mask);
            if (regionArea == 0) continue;
            
            bool colorDetected = false;
            std::vector<std::pair<std::string, double>> detectedColors;

            for (const auto& maskPair : precomputedColorMasks) {
                const std::string& colorName = maskPair.first;
                const cv::Mat& colorMask = maskPair.second;
                
                cv::Mat regionColorMask;
                cv::bitwise_and(colorMask, mask, regionColorMask);
                
                int maskPixels = cv::countNonZero(regionColorMask);
                double maskRatioColor = regionArea > 0 ? static_cast<double>(maskPixels) / regionArea : 0;
                
                if (maskRatioColor > 0.1) {
                    colorDetected = true;
                    detectedColors.push_back({colorName, maskRatioColor});
                    std::cout << "Detected " << colorName << " in " << direction 
                             << " region with ratio: " << std::fixed << std::setprecision(3) 
                             << maskRatioColor << std::endl;
                }
            }

            if (detectedColors.size() >= 2) {
                std::sort(detectedColors.begin(), detectedColors.end(), 
                         [](const auto& a, const auto& b) { return a.second > b.second; });
                
                std::string regionCode = directionToCode[direction];
                int nearColorId = colorNameToId[detectedColors[0].first];
                int farColorId = colorNameToId[detectedColors[1].first];
                regionColors[regionCode] = {nearColorId, farColorId};
            } else if (detectedColors.size() == 1) {
                std::string regionCode = directionToCode[direction];
                int colorId = colorNameToId[detectedColors[0].first];
                // 若仅检测到一种颜色，则近/远都使用该颜色以符合简化4元组语义
                regionColors[regionCode] = {colorId, colorId};
            }
            
            if (colorDetected) {
                cv::polylines(img, contours, true, cv::Scalar(0, 0, 255), 2);

                cv::bitwise_or(dotMask, mask, dotMask);
            }
        }
        
        return std::make_tuple(dotMask, angle, regionColors);
    }

    for (const auto& regionPair : regions) {
        const std::string& direction = regionPair.first;
        const cv::Rect& rect = regionPair.second;
        
        if (rect.width <= 0 || rect.height <= 0) continue;
        
        cv::rectangle(img, rect, cv::Scalar(255, 0, 0), 2);

        cv::Mat regionMask = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
        cv::Mat rectMask = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
        rectMask(rect) = cv::Scalar(255);

        if (triangularMasks.find(direction) != triangularMasks.end()) {
            cv::Mat triangleMask = triangularMasks[direction];
            cv::Mat triangleFullSize = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
            triangleMask.copyTo(triangleFullSize(rect));
            cv::bitwise_and(rectMask, triangleFullSize, regionMask);
        } else {
            regionMask = rectMask;
        }
        
        int regionArea = cv::countNonZero(regionMask);
        bool colorDetected = false;
        std::vector<std::pair<std::string, double>> detectedColors;

        for (const auto& maskPair : precomputedColorMasks) {
            const std::string& colorName = maskPair.first;
            const cv::Mat& colorMask = maskPair.second;
            
            cv::Mat regionColorMask;
            cv::bitwise_and(colorMask, regionMask, regionColorMask);
            
            int maskPixels = cv::countNonZero(regionColorMask);
            double maskRatioColor = regionArea > 0 ? static_cast<double>(maskPixels) / regionArea : 0;
            
            if (maskRatioColor > 0.1) {
                colorDetected = true;
                detectedColors.push_back({colorName, maskRatioColor});
                std::cout << "Detected " << colorName << " in " << direction 
                         << " region with ratio: " << std::fixed << std::setprecision(3) 
                         << maskRatioColor << std::endl;
            }
        }

        if (detectedColors.size() >= 2) {
            std::sort(detectedColors.begin(), detectedColors.end(), 
                     [](const auto& a, const auto& b) { return a.second > b.second; });
            
            std::string regionCode = directionToCode[direction];
            int nearColorId = colorNameToId[detectedColors[0].first];
            int farColorId = colorNameToId[detectedColors[1].first];
            regionColors[regionCode] = {nearColorId, farColorId};
        } else if (detectedColors.size() == 1) {
            std::string regionCode = directionToCode[direction];
            int colorId = colorNameToId[detectedColors[0].first];
            regionColors[regionCode] = {colorId, colorId};
        }
        
        if (colorDetected) {
            cv::rectangle(img, rect, cv::Scalar(0, 0, 255), 2);
            cv::bitwise_or(dotMask, regionMask, dotMask);
        }
    }
    

    if (!regionColors.empty()) {
        // 按固定顺序输出 U, R, D, L
        std::string jsonStr = "{";
        bool first = true;
        std::vector<std::string> orderedRegions = {"U", "R", "D", "L"};
        for (const auto& key : orderedRegions) {
            auto it = regionColors.find(key);
            if (it == regionColors.end()) continue;
            if (!first) jsonStr += ", ";
            jsonStr += "\"" + key + "\":(" + std::to_string(it->second.first) + "," + std::to_string(it->second.second) + ")";
            first = false;
        }
        jsonStr += "}";

        cv::Rect boundingRect = cv::boundingRect(approx);
        cv::Point textPos(boundingRect.x + boundingRect.width/2 - 50, 
                         boundingRect.y + boundingRect.height/2);

        cv::putText(img, jsonStr, textPos, cv::FONT_HERSHEY_SIMPLEX, 0.5, 
                   cv::Scalar(64, 64, 64), 2, cv::LINE_AA);
    }
    
    return std::make_tuple(dotMask, angle, regionColors);
}

std::pair<cv::Mat, double> checkExtendedRegionsForColors(
    cv::Mat& img,
    const std::vector<cv::Point>& approx,
    const cv::Mat& hsv,
    const std::map<std::string, ColorRange>& colorRanges) {
    
    cv::Mat dotMask = cv::Mat::zeros(img.rows, img.cols, CV_8UC1);

    cv::Rect boundingRect = cv::boundingRect(approx);
    int x = boundingRect.x;
    int y = boundingRect.y;
    int w = boundingRect.width;
    int h = boundingRect.height;

    int boundingArea = w * h;
    double actualArea = cv::contourArea(approx);
    double maskRatio = boundingArea > 0 ? actualArea / boundingArea : 0;
    
    bool isRotated = maskRatio < 0.9;

    int imgHeight = img.rows;
    int imgWidth = img.cols;
    
    int extendW = static_cast<int>(w * 2.5);
    int extendH = static_cast<int>(h * 2.5);

    std::map<std::string, cv::Rect> regions;
    regions["up"] = cv::Rect(std::max(0, x), std::max(0, y - extendH), 
                            std::min(imgWidth - std::max(0, x), w), 
                            std::min(imgHeight - std::max(0, y - extendH), y - std::max(0, y - extendH)));
    regions["down"] = cv::Rect(std::max(0, x), std::min(imgHeight, y + h), 
                              std::min(imgWidth - std::max(0, x), w), 
                              std::min(imgHeight - std::min(imgHeight, y + h), extendH));
    regions["left"] = cv::Rect(std::max(0, x - extendW), std::max(0, y), 
                              std::min(imgWidth - std::max(0, x - extendW), x - std::max(0, x - extendW)), 
                              std::min(imgHeight - std::max(0, y), h));
    regions["right"] = cv::Rect(std::min(imgWidth, x + w), std::max(0, y), 
                               std::min(imgWidth - std::min(imgWidth, x + w), extendW), 
                               std::min(imgHeight - std::max(0, y), h));
    
    double angle = 0;
    
    if (isRotated) {
        cv::RotatedRect rotatedRect = cv::minAreaRect(approx);
        angle = rotatedRect.angle;
        std::cout << "Rectangle angle: " << angle << std::endl;
        
        cv::Point2f boundingCenter(x + w / 2.0f, y + h / 2.0f);
        
        for (const auto& regionPair : regions) {
            const std::string& direction = regionPair.first;
            const cv::Rect& rect = regionPair.second;
            
            if (rect.width <= 0 || rect.height <= 0) continue;

            std::vector<cv::Point2f> corners = {
                cv::Point2f(rect.x, rect.y),
                cv::Point2f(rect.x + rect.width, rect.y),
                cv::Point2f(rect.x + rect.width, rect.y + rect.height),
                cv::Point2f(rect.x, rect.y + rect.height)
            };
            
            cv::Mat rotationMatrix = cv::getRotationMatrix2D(boundingCenter, -angle, 1.0);
            std::vector<cv::Point2f> rotatedCorners;
            cv::transform(corners, rotatedCorners, rotationMatrix);
            
            std::vector<cv::Point> rotatedCornersInt;
            for (const auto& corner : rotatedCorners) {
                rotatedCornersInt.push_back(cv::Point(static_cast<int>(corner.x), static_cast<int>(corner.y)));
            }
            
            cv::Mat mask = cv::Mat::zeros(imgHeight, imgWidth, CV_8UC1);
            std::vector<std::vector<cv::Point>> contours = {rotatedCornersInt};
            cv::fillPoly(mask, contours, cv::Scalar(255));
            cv::polylines(img, contours, true, cv::Scalar(255, 0, 0), 2);
            
            int regionArea = cv::countNonZero(mask);
            if (regionArea == 0) continue;
            
            bool colorDetected = false;
            
            for (const auto& colorPair : colorRanges) {
                const std::string& colorName = colorPair.first;
                const ColorRange& colorRange = colorPair.second;
                
                cv::Mat colorMask;
                if (colorName == "Red") {
                    cv::Mat mask1, mask2;
                    cv::inRange(hsv, colorRange.lower, colorRange.upper, mask1);
                    auto red2It = colorRanges.find("Red2");
                    if (red2It != colorRanges.end()) {
                        cv::inRange(hsv, red2It->second.lower, red2It->second.upper, mask2);
                        cv::bitwise_or(mask1, mask2, colorMask);
                    } else {
                        colorMask = mask1;
                    }
                } else if (colorName == "Red2") {
                    continue; 
                } else {
                    cv::inRange(hsv, colorRange.lower, colorRange.upper, colorMask);
                }
                
                cv::Mat regionColorMask;
                cv::bitwise_and(colorMask, mask, regionColorMask);
                
                int maskPixels = cv::countNonZero(regionColorMask);
                double maskRatioColor = regionArea > 0 ? static_cast<double>(maskPixels) / regionArea : 0;
                
                if (maskRatioColor > 0.1) {
                    colorDetected = true;
                    std::cout << "Detected " << colorName << " in " << direction 
                             << " region with ratio: " << std::fixed << std::setprecision(3) 
                             << maskRatioColor << std::endl;
                    break;
                }
            }
            
            if (colorDetected) {
                cv::polylines(img, contours, true, cv::Scalar(0, 0, 255), 2);
                cv::fillPoly(dotMask, contours, cv::Scalar(255));
            }
        }
        
        return std::make_pair(dotMask, angle);
    }

    for (const auto& regionPair : regions) {
        const std::string& direction = regionPair.first;
        const cv::Rect& rect = regionPair.second;
        
        if (rect.width <= 0 || rect.height <= 0) continue;
        
        cv::rectangle(img, rect, cv::Scalar(255, 0, 0), 2);
        
        int regionArea = rect.width * rect.height;
        bool colorDetected = false;
        
        for (const auto& colorPair : colorRanges) {
            const std::string& colorName = colorPair.first;
            const ColorRange& colorRange = colorPair.second;
            
            cv::Mat colorMask;
            if (colorName == "Red") {
                cv::Mat mask1, mask2;
                cv::inRange(hsv(rect), colorRange.lower, colorRange.upper, mask1);
                auto red2It = colorRanges.find("Red2");
                if (red2It != colorRanges.end()) {
                    cv::inRange(hsv(rect), red2It->second.lower, red2It->second.upper, mask2);
                    cv::bitwise_or(mask1, mask2, colorMask);
                } else {
                    colorMask = mask1;
                }
            } else if (colorName == "Red2") {
                continue;
            } else {
                cv::inRange(hsv(rect), colorRange.lower, colorRange.upper, colorMask);
            }
            
            int maskPixels = cv::countNonZero(colorMask);
            double maskRatioColor = regionArea > 0 ? static_cast<double>(maskPixels) / regionArea : 0;
            
            if (maskRatioColor > 0.1) {
                colorDetected = true;
                std::cout << "Detected " << colorName << " in " << direction 
                         << " region with ratio: " << std::fixed << std::setprecision(3) 
                         << maskRatioColor << std::endl;
                break;
            }
        }
        
        if (colorDetected) {
            cv::rectangle(img, rect, cv::Scalar(0, 0, 255), 2);
            cv::rectangle(dotMask, rect, cv::Scalar(255), -1);
        }
    }
    
    return std::make_pair(dotMask, angle);
}

std::map<std::string, ColorRange> getDefaultColorRanges() {
    std::map<std::string, ColorRange> colorRanges;
    
    // 略微放宽红/黄阈值以提升鲁棒性（更宽Hue、降低S/V下限）
    colorRanges["Red"] = ColorRange(cv::Scalar(0, 100, 90), cv::Scalar(12, 255, 255));
    colorRanges["Red2"] = ColorRange(cv::Scalar(168, 100, 90), cv::Scalar(180, 255, 255));
    colorRanges["Yellow"] = ColorRange(cv::Scalar(18, 40, 40), cv::Scalar(36, 255, 255));
    colorRanges["Green"] = ColorRange(cv::Scalar(40, 50, 50), cv::Scalar(80, 255, 255));
    colorRanges["Cyan"] = ColorRange(cv::Scalar(80, 50, 50), cv::Scalar(100, 255, 255));
    colorRanges["Blue"] = ColorRange(cv::Scalar(100, 50, 50), cv::Scalar(130, 255, 255));
    colorRanges["Indigo"] = ColorRange(cv::Scalar(130, 50, 50), cv::Scalar(170, 255, 255));
    
    return colorRanges;
}

void showColorMasks(const cv::Mat& hsv, const std::map<std::string, ColorRange>& colorRanges) {
    for (const auto& colorPair : colorRanges) {
        const std::string& colorName = colorPair.first;
        const ColorRange& colorRange = colorPair.second;
        
        cv::Mat mask;
        if (colorName == "Red2") {
            auto redIt = colorRanges.find("Red");
            if (redIt != colorRanges.end()) {
                cv::Mat redMask1, redMask2;
                cv::inRange(hsv, redIt->second.lower, redIt->second.upper, redMask1);
                cv::inRange(hsv, colorRange.lower, colorRange.upper, redMask2);
                cv::bitwise_or(redMask1, redMask2, mask);
                cv::imshow("Red mask", mask);
            }
        } else if (colorName == "Red") {
            continue;
        } else {
            cv::inRange(hsv, colorRange.lower, colorRange.upper, mask);
            cv::imshow(colorName + " mask", mask);
        }
    }
}

cv::Mat createRedMask(const cv::Mat& hsv, const std::map<std::string, ColorRange>& colorRanges) {
    cv::Mat redMask;
    auto redIt = colorRanges.find("Red");
    auto red2It = colorRanges.find("Red2");
    
    if (redIt != colorRanges.end() && red2It != colorRanges.end()) {
        cv::Mat mask1, mask2;
        cv::inRange(hsv, redIt->second.lower, redIt->second.upper, mask1);
        cv::inRange(hsv, red2It->second.lower, red2It->second.upper, mask2);
        cv::bitwise_or(mask1, mask2, redMask);
    }
    
    return redMask;
}

double evaluateRectangularity(const std::vector<cv::Point2f>& points) {
    if (points.size() != 4) return 0.0;
    
    std::vector<cv::Point2f> sortedPoints = sortRectangleCorners(points);
    
    double side1 = cv::norm(sortedPoints[0] - sortedPoints[1]);
    double side2 = cv::norm(sortedPoints[1] - sortedPoints[2]);
    double side3 = cv::norm(sortedPoints[2] - sortedPoints[3]);
    double side4 = cv::norm(sortedPoints[3] - sortedPoints[0]);
    
    double diag1 = cv::norm(sortedPoints[0] - sortedPoints[2]);
    double diag2 = cv::norm(sortedPoints[1] - sortedPoints[3]);
    
    double oppositeSideRatio1 = std::min(side1, side3) / std::max(side1, side3);
    double oppositeSideRatio2 = std::min(side2, side4) / std::max(side2, side4);

    double diagonalRatio = std::min(diag1, diag2) / std::max(diag1, diag2);

    double expectedDiag1 = std::sqrt(side1 * side1 + side2 * side2);
    double expectedDiag2 = std::sqrt(side2 * side2 + side3 * side3);
    double diagAccuracy1 = std::min(expectedDiag1, diag1) / std::max(expectedDiag1, diag1);
    double diagAccuracy2 = std::min(expectedDiag2, diag2) / std::max(expectedDiag2, diag2);

    auto calculateAngle = [](const cv::Point2f& p1, const cv::Point2f& p2, const cv::Point2f& p3) {
        cv::Point2f v1 = p1 - p2;
        cv::Point2f v2 = p3 - p2;
        double dot = v1.x * v2.x + v1.y * v2.y;
        double norm1 = cv::norm(v1);
        double norm2 = cv::norm(v2);
        if (norm1 == 0 || norm2 == 0) return 0.0;
        double cosAngle = dot / (norm1 * norm2);
        cosAngle = std::max(-1.0, std::min(1.0, cosAngle)); 
        return std::acos(cosAngle) * 180.0 / M_PI;
    };
    
    double angle1 = calculateAngle(sortedPoints[3], sortedPoints[0], sortedPoints[1]);
    double angle2 = calculateAngle(sortedPoints[0], sortedPoints[1], sortedPoints[2]);
    double angle3 = calculateAngle(sortedPoints[1], sortedPoints[2], sortedPoints[3]);
    double angle4 = calculateAngle(sortedPoints[2], sortedPoints[3], sortedPoints[0]);

    double angleScore1 = 1.0 - std::abs(angle1 - 90.0) / 90.0;
    double angleScore2 = 1.0 - std::abs(angle2 - 90.0) / 90.0;
    double angleScore3 = 1.0 - std::abs(angle3 - 90.0) / 90.0;
    double angleScore4 = 1.0 - std::abs(angle4 - 90.0) / 90.0;
    double avgAngleScore = (angleScore1 + angleScore2 + angleScore3 + angleScore4) / 4.0;
    
    double rectangularity = (oppositeSideRatio1 + oppositeSideRatio2 + diagonalRatio + 
                           (diagAccuracy1 + diagAccuracy2) / 2.0 + avgAngleScore) / 5.0;

    if (oppositeSideRatio1 < 0.8 || oppositeSideRatio2 < 0.8 || 
        diagonalRatio < 0.8 || avgAngleScore < 0.7) {
        rectangularity *= 0.5;
    }
    
    return rectangularity;
}

std::vector<cv::Point2f> sortRectangleCorners(const std::vector<cv::Point2f>& points) {
    if (points.size() != 4) return points;
    
    std::vector<cv::Point2f> sorted = points;

    cv::Point2f center(0, 0);
    for (const auto& p : points) {
        center.x += p.x;
        center.y += p.y;
    }
    center.x /= 4.0f;
    center.y /= 4.0f;

    std::sort(sorted.begin(), sorted.end(), [center](const cv::Point2f& a, const cv::Point2f& b) {
        double angleA = std::atan2(a.y - center.y, a.x - center.x);
        double angleB = std::atan2(b.y - center.y, b.x - center.x);
        return angleA < angleB;
    });

    int topLeftIdx = 0;
    for (int i = 1; i < 4; ++i) {
        if (sorted[i].y < sorted[topLeftIdx].y || 
            (sorted[i].y == sorted[topLeftIdx].y && sorted[i].x < sorted[topLeftIdx].x)) {
            topLeftIdx = i;
        }
    }

    std::vector<cv::Point2f> result;
    for (int i = 0; i < 4; ++i) {
        result.push_back(sorted[(topLeftIdx + i) % 4]);
    }
    
    return result;
}

std::vector<Card> pairRectanglesIntoCards(const std::vector<std::vector<cv::Point>>& rectangles, const cv::Mat& img) {
    std::vector<Card> cards;
    std::vector<bool> used(rectangles.size(), false);

    std::vector<cv::Point2f> centers;
    std::vector<double> areas;
    for (const auto& rect : rectangles) {
        cv::Moments m = cv::moments(rect);
        cv::Point2f center(m.m10 / m.m00, m.m01 / m.m00);
        centers.push_back(center);
        areas.push_back(cv::contourArea(rect));
    }

    double globalBestScore = 0;
    std::vector<int> globalBestIndices;

    for (size_t a = 0; a < rectangles.size(); ++a) {
        for (size_t b = a + 1; b < rectangles.size(); ++b) {
            for (size_t c = b + 1; c < rectangles.size(); ++c) {
                for (size_t d = c + 1; d < rectangles.size(); ++d) {

                    double minArea = std::min({areas[a], areas[b], areas[c], areas[d]});
                    double maxArea = std::max({areas[a], areas[b], areas[c], areas[d]});
                    double areaRatio = minArea / maxArea;
                    if (areaRatio < 0.5) continue;
                    
                    std::vector<cv::Point2f> fourPoints = {
                        centers[a], centers[b], centers[c], centers[d]
                    };

                    double rectangularity = evaluateRectangularity(fourPoints);

                    double totalScore = rectangularity * 0.8 + areaRatio * 0.2;
                    
                    if (totalScore > globalBestScore && rectangularity > 0.75) {
                        globalBestScore = totalScore;
                        globalBestIndices = {static_cast<int>(a), static_cast<int>(b), 
                                           static_cast<int>(c), static_cast<int>(d)};
                    }
                }
            }
        }
    }

    if (!globalBestIndices.empty() && globalBestScore > 0.8) {
        Card card;
        std::vector<cv::Point2f> cardCorners;
        
        for (int idx : globalBestIndices) {
            card.cornerIndices.push_back(idx);
            cardCorners.push_back(centers[idx]);
            used[idx] = true;
        }

        cardCorners = sortRectangleCorners(cardCorners);

        card.corners.clear();
        for (const auto& corner : cardCorners) {
            card.corners.push_back(cv::Point(static_cast<int>(corner.x), static_cast<int>(corner.y)));
        }

        card.boundingRect = cv::boundingRect(card.corners);
        cards.push_back(card);
    }

    while (true) {
        double bestScore = 0;
        std::vector<int> bestIndices;

        std::vector<int> remainingIndices;
        for (size_t i = 0; i < used.size(); ++i) {
            if (!used[i]) remainingIndices.push_back(i);
        }
        
        if (remainingIndices.size() < 4) break;
        
        for (size_t a = 0; a < remainingIndices.size(); ++a) {
            for (size_t b = a + 1; b < remainingIndices.size(); ++b) {
                for (size_t c = b + 1; c < remainingIndices.size(); ++c) {
                    for (size_t d = c + 1; d < remainingIndices.size(); ++d) {
                        int idxA = remainingIndices[a];
                        int idxB = remainingIndices[b];
                        int idxC = remainingIndices[c];
                        int idxD = remainingIndices[d];
                        
                        double minArea = std::min({areas[idxA], areas[idxB], areas[idxC], areas[idxD]});
                        double maxArea = std::max({areas[idxA], areas[idxB], areas[idxC], areas[idxD]});
                        double areaRatio = minArea / maxArea;
                        if (areaRatio < 0.5) continue;
                        
                        std::vector<cv::Point2f> fourPoints = {
                            centers[idxA], centers[idxB], centers[idxC], centers[idxD]
                        };
                        
                        double rectangularity = evaluateRectangularity(fourPoints);
                        double totalScore = rectangularity * 0.8 + areaRatio * 0.2;
                        
                        if (totalScore > bestScore && rectangularity > 0.75) {
                            bestScore = totalScore;
                            bestIndices = {idxA, idxB, idxC, idxD};
                        }
                    }
                }
            }
        }

        if (!bestIndices.empty() && bestScore > 0.8) {
            Card card;
            std::vector<cv::Point2f> cardCorners;
            
            for (int idx : bestIndices) {
                card.cornerIndices.push_back(idx);
                cardCorners.push_back(centers[idx]);
                used[idx] = true;
            }
            
            cardCorners = sortRectangleCorners(cardCorners);
            
            card.corners.clear();
            for (const auto& corner : cardCorners) {
                card.corners.push_back(cv::Point(static_cast<int>(corner.x), static_cast<int>(corner.y)));
            }
            
            card.boundingRect = cv::boundingRect(card.corners);
            cards.push_back(card);
        } else {
            break;
        }
    }
    
    // 处理剩余的单个角点：为每个未使用的矩形创建单角点卡片
    for (size_t i = 0; i < rectangles.size(); ++i) {
        if (used[i]) continue;
        
        Card singleCornerCard;
        singleCornerCard.cornerIndices.push_back(i);
        
        // 使用矩形的中心作为单个角点
        cv::Point2f center = centers[i];
        singleCornerCard.corners.push_back(cv::Point(static_cast<int>(center.x), static_cast<int>(center.y)));
        
        // 使用原始矩形的边界作为卡片的边界
        singleCornerCard.boundingRect = cv::boundingRect(rectangles[i]);
        
        cards.push_back(singleCornerCard);
    }
    
    return cards;
}

DetectionResult detectDotCards(const cv::Mat& img, bool debug) {
    DetectionResult result;
    
    if (img.empty()) {
        std::cerr << "Error: Input image is empty" << std::endl;
        return result;
    }
    
    cv::Mat hsv, gray;
    cv::cvtColor(img, hsv, cv::COLOR_BGR2HSV);
    cv::cvtColor(img, gray, cv::COLOR_BGR2GRAY);
    
    std::map<std::string, ColorRange> colorRanges = getDefaultColorRanges();
    

    std::map<std::string, cv::Mat> precomputedColorMasks;
    for (const auto& colorPair : colorRanges) {
        const std::string& colorName = colorPair.first;
        const ColorRange& colorRange = colorPair.second;
        
        cv::Mat colorMask;
        if (colorName == "Red") {
            cv::Mat mask1, mask2;
            cv::inRange(hsv, colorRange.lower, colorRange.upper, mask1);
            auto red2It = colorRanges.find("Red2");
            if (red2It != colorRanges.end()) {
                cv::inRange(hsv, red2It->second.lower, red2It->second.upper, mask2);
                cv::bitwise_or(mask1, mask2, colorMask);
            } else {
                colorMask = mask1;
            }
        } else if (colorName != "Red2") {
            cv::inRange(hsv, colorRange.lower, colorRange.upper, colorMask);
        }
        
        if (!colorMask.empty()) {
            precomputedColorMasks[colorName] = colorMask;
        }
    }
    
    if (debug) {
        showColorMasks(hsv, colorRanges);
        cv::imshow("original", img);
        cv::waitKey(0);
        cv::destroyAllWindows();
    }
    
    cv::Mat imgThreshold = dotPreprocess(img, debug);
    
    result.rectMask = cv::Mat::zeros(img.rows, img.cols, CV_8UC1);
    result.dotMask = cv::Mat::zeros(img.rows, img.cols, CV_8UC1);
    
    std::vector<std::vector<cv::Point>> contours;
    std::vector<cv::Vec4i> hierarchy;
    cv::findContours(imgThreshold, contours, hierarchy, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    
    cv::Mat imgCopy = img.clone();
    

    result.rectangles.reserve(50);

    const size_t numThreads = std::min(static_cast<size_t>(std::thread::hardware_concurrency()), contours.size());
    std::vector<std::future<std::vector<std::vector<cv::Point>>>> futures;
    std::mutex resultMutex;
    
    auto processContourBatch = [&](size_t start, size_t end) -> std::vector<std::vector<cv::Point>> {
        std::vector<std::vector<cv::Point>> localRectangles;
        
        for (size_t i = start; i < end; ++i) {
            const auto& contour = contours[i];
            double area = cv::contourArea(contour);

            if (area < 36) {
                continue;
            }
            

            if (area > 50000) {
                continue;
            }
            

            cv::Rect boundingRect = cv::boundingRect(contour);
            double aspectRatio = static_cast<double>(boundingRect.width) / boundingRect.height;
            if (aspectRatio < 0.5 || aspectRatio > 2.0) {
                continue;
            }
            
            double perimeter = cv::arcLength(contour, true);
            

            if (perimeter < 16 || perimeter > 1000) {
                continue;
            }
            

            double compactness = 4 * M_PI * area / (perimeter * perimeter);
            if (compactness < 0.3) {
                continue;
            }
            

            std::vector<cv::Point> hull;
            cv::convexHull(contour, hull);
            double hullArea = cv::contourArea(hull);
            double convexityRatio = area / hullArea;
            if (convexityRatio < 0.85) {
                continue;
            }
            
            std::vector<cv::Point> approx;
            cv::approxPolyDP(contour, approx, 0.01 * perimeter, true);
            
            if (approx.size() >= 4 && approx.size() <= 6) {
                if (checkSquareEdges(approx)) {
                    int x = boundingRect.x;
                    int y = boundingRect.y;
                    int w = boundingRect.width;
                    int h = boundingRect.height;

                    if (w > 10 && h > 10) {
                        if (verifyWhitePixelRatio(approx, imgThreshold, 0.6)) {
                            localRectangles.push_back(approx);
                        }
                    }
                }
            }
        }
        return localRectangles;
    };
    

    if (numThreads > 1 && contours.size() > 100) {
        const size_t batchSize = contours.size() / numThreads;
        
        for (size_t t = 0; t < numThreads; ++t) {
            size_t start = t * batchSize;
            size_t end = (t == numThreads - 1) ? contours.size() : (t + 1) * batchSize;
            
            futures.push_back(std::async(std::launch::async, processContourBatch, start, end));
        }
        

        for (auto& future : futures) {
            auto localRectangles = future.get();
            for (const auto& approx : localRectangles) {
                cv::Rect boundingRect = cv::boundingRect(approx);
                int x = boundingRect.x;
                int y = boundingRect.y;
                int w = boundingRect.width;
                int h = boundingRect.height;
                

                std::vector<std::vector<cv::Point>> contours_fill = {approx};
                cv::fillPoly(result.rectMask, contours_fill, cv::Scalar(255));
                cv::rectangle(imgCopy, cv::Point(x, y), cv::Point(x + w, y + h), cv::Scalar(0, 255, 0), 2);
                
                result.rectangles.push_back(approx);
                

                auto dotResult = checkExtendedRegionsForColorsOptimized(imgCopy, approx, hsv, colorRanges, precomputedColorMasks);
                cv::Mat dotMask = std::get<0>(dotResult);
                result.angle = std::get<1>(dotResult);
                auto regionColors = std::get<2>(dotResult);
                

                for (const auto& regionColor : regionColors) {
                    result.regionColors[regionColor.first] = regionColor.second;
                }
                

                if (!regionColors.empty()) {
                    std::string jsonStr = "{";
                    bool first = true;
                    
                    for (const auto& regionColor : regionColors) {
                        const std::string& region = regionColor.first;
                        int nearColor = regionColor.second.first;
                        int farColor = regionColor.second.second;
                        
                        if (!first) {
                            jsonStr += ", ";
                        }
                        jsonStr += "\"" + region + "\":(" + std::to_string(nearColor);
                        if (farColor >= 0) {
                            jsonStr += "," + std::to_string(farColor);
                        }
                        jsonStr += ")";
                        first = false;
                    }
                    jsonStr += "}";
                    
                    cv::Point textPos(x, y + h + 15);
                    
                    if (textPos.y > imgCopy.rows - 10) {
                        textPos.y = y - 5;
                    }
                    
                    cv::putText(imgCopy, jsonStr, textPos, cv::FONT_HERSHEY_SIMPLEX, 0.6, cv::Scalar(64, 64, 64), 2);
                }
                
                cv::bitwise_or(result.dotMask, dotMask, result.dotMask);
            }
        }
    } else {
        auto rectangles = processContourBatch(0, contours.size());
        for (const auto& approx : rectangles) {
            cv::Rect boundingRect = cv::boundingRect(approx);
            int x = boundingRect.x;
            int y = boundingRect.y;
            int w = boundingRect.width;
            int h = boundingRect.height;
            
            std::vector<std::vector<cv::Point>> contours_fill = {approx};
            cv::fillPoly(result.rectMask, contours_fill, cv::Scalar(255));
            cv::rectangle(imgCopy, cv::Point(x, y), cv::Point(x + w, y + h), cv::Scalar(0, 255, 0), 2);
            
            result.rectangles.push_back(approx);

            auto dotResult = checkExtendedRegionsForColorsOptimized(imgCopy, approx, hsv, colorRanges, precomputedColorMasks);
            cv::Mat dotMask = std::get<0>(dotResult);
            result.angle = std::get<1>(dotResult);
            auto regionColors = std::get<2>(dotResult);

            for (const auto& regionColor : regionColors) {
                result.regionColors[regionColor.first] = regionColor.second;
            }
            
            if (!regionColors.empty()) {
                std::string jsonStr = "{";
                bool first = true;
                
                for (const auto& regionColor : regionColors) {
                    const std::string& region = regionColor.first;
                    int nearColor = regionColor.second.first;
                    int farColor = regionColor.second.second;
                    
                    if (!first) {
                        jsonStr += ", ";
                    }
                    jsonStr += "\"" + region + "\":(" + std::to_string(nearColor);
                    if (farColor >= 0) {
                        jsonStr += "," + std::to_string(farColor);
                    }
                    jsonStr += ")";
                    first = false;
                }
                jsonStr += "}";
                

                cv::Point textPos(x, y + h + 15);
                

                if (textPos.y > imgCopy.rows - 10) {
                    textPos.y = y - 5;
                }
                

                cv::putText(imgCopy, jsonStr, textPos, cv::FONT_HERSHEY_SIMPLEX, 0.6, cv::Scalar(64, 64, 64), 2);
            }
            
            cv::bitwise_or(result.dotMask, dotMask, result.dotMask);
        }
    }
    
    result.cards = pairRectanglesIntoCards(result.rectangles, img);
    
    for (const auto& card : result.cards) {
        for (size_t i = 0; i < card.corners.size(); ++i) {
            cv::Point start = card.corners[i];
            cv::Point end = card.corners[(i + 1) % card.corners.size()];
            cv::line(imgCopy, start, end, cv::Scalar(255, 0, 0), 3);
        }
        
        cv::Point center = cv::Point(card.boundingRect.x + card.boundingRect.width / 2,
                                   card.boundingRect.y + card.boundingRect.height / 2);
        std::string cardInfo = "Card " + std::to_string(&card - &result.cards[0] + 1);
        cv::putText(imgCopy, cardInfo, center, cv::FONT_HERSHEY_SIMPLEX, 0.8, cv::Scalar(255, 0, 0), 2);
    }

    if (debug) {
        cv::imshow("rect_mask", result.rectMask);
        cv::imshow("original", imgCopy);
        cv::imshow("all_dot_mask", result.dotMask);
        

        std::cout << "\n=== Dot Mask ROI Analysis ===" << std::endl;
        std::cout << "Total dot mask pixels: " << cv::countNonZero(result.dotMask) << std::endl;
        
        std::vector<std::vector<cv::Point>> dotContours;
        cv::findContours(result.dotMask, dotContours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
        std::cout << "Number of dot contours found: " << dotContours.size() << std::endl;
        

        std::cout << "\n=== Region Color Information ===" << std::endl;
        if (result.regionColors.empty()) {
            std::cout << "No region colors detected." << std::endl;
        } else {
            for (const auto& regionColor : result.regionColors) {
                const std::string& region = regionColor.first;
                int nearColor = regionColor.second.first;
                int farColor = regionColor.second.second;
                
                std::cout << "Region " << region << ": ";
                if (nearColor >= 0) {
                    std::cout << "Near=" << nearColor;
                }
                if (farColor >= 0) {
                    std::cout << ", Far=" << farColor;
                } else if (nearColor >= 0) {
                    std::cout << " (only one color detected)";
                }
                std::cout << std::endl;
            }
        }
        
        for (size_t i = 0; i < dotContours.size(); ++i) {
            const auto& contour = dotContours[i];
            

             cv::Rect boundingRect = cv::boundingRect(contour);
             

             boundingRect.x = std::max(0, boundingRect.x);
             boundingRect.y = std::max(0, boundingRect.y);
             boundingRect.width = std::min(boundingRect.width, img.cols - boundingRect.x);
             boundingRect.height = std::min(boundingRect.height, img.rows - boundingRect.y);
             

             if (boundingRect.width < 5 || boundingRect.height < 5) {
                 continue;
             }
             

             cv::Mat roiImage = img(boundingRect).clone();
             cv::Mat roiMask = result.dotMask(boundingRect).clone();
            

            cv::Mat visualImage = roiImage.clone();
            cv::Mat colorMask;
            cv::cvtColor(roiMask, colorMask, cv::COLOR_GRAY2BGR);
            cv::Mat redMask = cv::Mat::zeros(colorMask.size(), CV_8UC3);
            redMask.setTo(cv::Scalar(0, 0, 255), roiMask);
            cv::addWeighted(visualImage, 0.7, redMask, 0.3, 0, visualImage);
            

            std::string windowName = "Dot ROI " + std::to_string(i + 1);
            cv::imshow(windowName, visualImage);

            std::string roiWindowName = "Original ROI " + std::to_string(i + 1);
            std::string maskWindowName = "Mask " + std::to_string(i + 1);
            cv::imshow(roiWindowName, roiImage);
            cv::imshow(maskWindowName, roiMask);
        }
        
        cv::waitKey(0);
        cv::destroyAllWindows();
    }
    
    result.success = !result.rectangles.empty();
    return result;
}

} // namespace DotCardDetect