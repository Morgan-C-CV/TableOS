#include "shape_detector.h"
#include <iostream>
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <string>
#include <map>

namespace ShapeDetector {

cv::Mat loadImage(const std::string& path) {
    cv::Mat image = cv::imread(path);
    if (image.empty()) {
        std::cerr << "Error: Could not load image from " << path << std::endl;
    }
    return image;
}

std::map<std::string, ColorRange> getDefaultColorRanges() {
    std::map<std::string, ColorRange> colorRanges;
    
    // 大幅扩展所有颜色的HSV识别范围以提高识别效果
    // 降低饱和度和亮度的最低要求，扩大色调范围
    
    // 黄色 - 进一步扩展范围，包含更广泛的黄色色调
    // 色调范围: 10-65° (扩大范围，包含橙黄、纯黄、黄绿)
    // 饱和度: 20-255 (进一步降低要求，包含更浅的黄色)
    // 亮度: 60-255 (降低要求，包含更暗的黄色)
    colorRanges["Yellow"] = ColorRange(cv::Scalar(20, 20, 100), cv::Scalar(55, 255, 255));
    
    // 绿色 - 扩展范围，包含黄绿到青绿色调
    // 色调范围: 40-85° (包含黄绿、纯绿、青绿)
    // 饱和度: 30-255 (降低要求，包含浅绿色)
    // 亮度: 60-255 (降低要求，包含暗绿色)
    colorRanges["Green"] = ColorRange(cv::Scalar(40, 30, 60), cv::Scalar(85, 255, 255));
    
    // 青色 - 扩展范围，包含绿青到蓝青色调
    // 色调范围: 75-115° (包含绿青、纯青、蓝青)
    // 饱和度: 40-255 (降低要求，包含浅青色)
    // 亮度: 70-255 (降低要求，包含暗青色)
    colorRanges["Cyan"] = ColorRange(cv::Scalar(90, 40, 160), cv::Scalar(105, 255, 255));
    
    // 蓝色 - 扩展范围，包含青蓝到紫蓝色调
    // 色调范围: 100-140° (包含青蓝、纯蓝、紫蓝)
    // 饱和度: 40-255 (降低要求，包含浅蓝色)
    // 亮度: 60-255 (降低要求，包含暗蓝色)
    colorRanges["Blue"] = ColorRange(cv::Scalar(100, 40, 60), cv::Scalar(140, 255, 255));
    
    // 黑色 - 缩小范围，更精确识别黑色
    // 色调范围: 0-180° (全色调，保持不变)
    // 饱和度: 0-50 (降低饱和度上限，减少误识别)
    // 亮度: 0-40 (降低亮度上限，更严格的黑色识别)
    colorRanges["Black"] = ColorRange(cv::Scalar(0, 0, 0), cv::Scalar(180, 50, 40));
    
    return colorRanges;
}

cv::Mat preprocessImage(const cv::Mat& image) {
    cv::Mat rotated, blurred;
    
    // 校正相机旋转：逆时针旋转90度来校正顺时针旋转的相机画面
    cv::rotate(image, rotated, cv::ROTATE_90_COUNTERCLOCKWISE);
    
    // 高斯模糊减少噪声
    cv::GaussianBlur(rotated, blurred, cv::Size(5, 5), 0);
    
    return blurred;
}

cv::Mat detectColorRegions(const cv::Mat& hsv, const ColorRange& colorRange) {
    cv::Mat mask;
    cv::inRange(hsv, colorRange.lower, colorRange.upper, mask);
    
    // 分离HSV通道进行更精细的控制
    std::vector<cv::Mat> hsvChannels;
    cv::split(hsv, hsvChannels);
    cv::Mat hChannel = hsvChannels[0];  // 色调
    cv::Mat sChannel = hsvChannels[1];  // 饱和度
    cv::Mat vChannel = hsvChannels[2];  // 亮度
    
    // 创建饱和度mask - 放宽饱和度要求，允许更多颜色通过
    cv::Mat saturationMask;
    cv::threshold(sChannel, saturationMask, 30, 255, cv::THRESH_BINARY);  // 降低饱和度阈值，允许更淡的颜色
    
    // 创建亮度mask - 放宽亮度范围，适应更多光照条件
    cv::Mat brightnessMask;
    cv::Mat darkMask, brightMask;
    cv::threshold(vChannel, darkMask, 40, 255, cv::THRESH_BINARY);      // 降低暗区域阈值，允许更暗的颜色
    cv::threshold(vChannel, brightMask, 240, 255, cv::THRESH_BINARY_INV); // 提高亮区域阈值，允许更亮的颜色
    cv::bitwise_and(darkMask, brightMask, brightnessMask);
    
    // 组合所有mask
    cv::Mat combinedMask;
    cv::bitwise_and(mask, saturationMask, combinedMask);
    cv::bitwise_and(combinedMask, brightnessMask, combinedMask);
    
    // 更宽松的形态学操作 - 减少噪声过滤以保留更多目标
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(3, 3));  // 进一步减小核大小
    cv::morphologyEx(combinedMask, combinedMask, cv::MORPH_OPEN, kernel);
    cv::morphologyEx(combinedMask, combinedMask, cv::MORPH_CLOSE, kernel);
    
    // 宽松的噪声过滤 - 允许更小的连通区域通过
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(combinedMask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
    
    cv::Mat filteredMask = cv::Mat::zeros(combinedMask.size(), CV_8UC1);
    for (const auto& contour : contours) {
        double area = cv::contourArea(contour);
        if (area > 100) {  // 进一步降低最小面积阈值，检测更小的目标
            cv::fillPoly(filteredMask, std::vector<std::vector<cv::Point>>{contour}, cv::Scalar(255));
        }
    }
    
    return filteredMask;
}

bool isLongRectangle(const std::vector<cv::Point>& contour, double& aspectRatio) {
    // 使用最小外接矩形
    cv::RotatedRect rotatedRect = cv::minAreaRect(contour);
    
    double width = rotatedRect.size.width;
    double height = rotatedRect.size.height;
    
    // 确保width是长边
    if (width < height) {
        std::swap(width, height);
    }
    
    aspectRatio = width / height;
    
    // 长矩形：长边超过短边2倍的都算是长矩形
    return (aspectRatio >= 2.0);
}

bool isTriangle(const std::vector<cv::Point>& contour) {
    // 使用更严格的多边形逼近
    std::vector<cv::Point> approx;
    double epsilon = 0.015 * cv::arcLength(contour, true);
    cv::approxPolyDP(contour, approx, epsilon, true);
    
    if (approx.size() != 3) {
        return false;
    }
    
    // 检查轮廓面积与凸包面积的比例，确保形状规整 - 放宽要求
    double contourArea = cv::contourArea(contour);
    std::vector<cv::Point> hull;
    cv::convexHull(contour, hull);
    double hullArea = cv::contourArea(hull);
    
    if (hullArea > 0 && (contourArea / hullArea) < 0.7) {  // 放宽面积比例阈值
        return false;
    }
    
    // 检查三角形的边长比例，避免过于细长的形状
    std::vector<double> sideLengths;
    for (int i = 0; i < 3; i++) {
        cv::Point p1 = approx[i];
        cv::Point p2 = approx[(i + 1) % 3];
        double length = sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y));
        sideLengths.push_back(length);
    }
    
    // 检查最长边与最短边的比例
    double maxSide = *std::max_element(sideLengths.begin(), sideLengths.end());
    double minSide = *std::min_element(sideLengths.begin(), sideLengths.end());
    
    if (maxSide / minSide > 5.0) {  // 避免过于细长的三角形
        return false;
    }
    
    return true;
}

bool isRectangle(const std::vector<cv::Point>& contour, double& aspectRatio) {
    // 基于直线检测的矩形识别算法
    
    // 1. 基本面积过滤
    double contourArea = cv::contourArea(contour);
    if (contourArea < 50) {  // 面积太小
        return false;
    }
    
    // 2. 创建轮廓的掩码图像用于直线检测
    cv::Rect boundingRect = cv::boundingRect(contour);
    cv::Mat mask = cv::Mat::zeros(boundingRect.height + 20, boundingRect.width + 20, CV_8UC1);
    
    // 将轮廓坐标转换为相对于边界矩形的坐标
    std::vector<cv::Point> adjustedContour;
    for (const auto& point : contour) {
        adjustedContour.push_back(cv::Point(point.x - boundingRect.x + 10, point.y - boundingRect.y + 10));
    }
    
    // 绘制轮廓到掩码
    std::vector<std::vector<cv::Point>> contours = {adjustedContour};
    cv::fillPoly(mask, contours, cv::Scalar(255));
    
    // 3. 边缘检测
    cv::Mat edges;
    cv::Canny(mask, edges, 50, 150);
    
    // 4. 霍夫直线变换检测直线
    std::vector<cv::Vec4i> lines;
    cv::HoughLinesP(edges, lines, 1, CV_PI/180, 30, 20, 10);
    
    if (lines.size() < 4) {  // 至少需要4条直线
        return false;
    }
    
    // 5. 分析直线长度和方向
    std::vector<double> lengths;
    std::vector<double> angles;
    
    for (const auto& line : lines) {
        double length = sqrt(pow(line[2] - line[0], 2) + pow(line[3] - line[1], 2));
        double angle = atan2(line[3] - line[1], line[2] - line[0]) * 180.0 / M_PI;
        
        // 将角度标准化到0-180度
        if (angle < 0) angle += 180;
        
        lengths.push_back(length);
        angles.push_back(angle);
    }
    
    // 6. 按角度分组直线（水平和垂直方向）
    std::vector<double> horizontalLengths, verticalLengths;
    
    for (size_t i = 0; i < angles.size(); i++) {
        double angle = angles[i];
        double length = lengths[i];
        
        // 水平线（角度接近0或180度）
        if ((angle < 15 || angle > 165) || (angle > 75 && angle < 105)) {
            if (angle < 15 || angle > 165) {
                horizontalLengths.push_back(length);
            } else {
                verticalLengths.push_back(length);
            }
        }
    }
    
    // 7. 检查是否有两组显著的长边
    if (horizontalLengths.size() < 2 || verticalLengths.size() < 2) {
        return false;
    }
    
    // 排序找到最长的边
    std::sort(horizontalLengths.rbegin(), horizontalLengths.rend());
    std::sort(verticalLengths.rbegin(), verticalLengths.rend());
    
    // 8. 检查每组中最长的两条边长度是否相近（矩形特性）
    double hRatio = horizontalLengths[0] / horizontalLengths[1];
    double vRatio = verticalLengths[0] / verticalLengths[1];
    
    if (hRatio > 2.0 || hRatio < 0.5 || vRatio > 2.0 || vRatio < 0.5) {
        return false;
    }
    
    // 9. 检查两组边的长度比例（长宽比）
    double avgHorizontal = (horizontalLengths[0] + horizontalLengths[1]) / 2.0;
    double avgVertical = (verticalLengths[0] + verticalLengths[1]) / 2.0;
    
    aspectRatio = std::max(avgHorizontal, avgVertical) / std::min(avgHorizontal, avgVertical);
    
    // 10. 检查轮廓填充比例
    double boundingArea = boundingRect.width * boundingRect.height;
    double fillRatio = contourArea / boundingArea;
    
    if (fillRatio < 0.5 || fillRatio > 0.98) {
        return false;
    }
    
    return true;
}

void calculateLongRectangleOrientation(const std::vector<cv::Point>& contour, DetectedShape& shape) {
    // 使用最小外接矩形
    cv::RotatedRect rotatedRect = cv::minAreaRect(contour);
    
    // 获取矩形的四个顶点
    cv::Point2f vertices[4];
    rotatedRect.points(vertices);
    
    // 找到短边的两个中点
    cv::Point2f side1_mid = (vertices[0] + vertices[1]) * 0.5f;
    cv::Point2f side2_mid = (vertices[2] + vertices[3]) * 0.5f;
    cv::Point2f side3_mid = (vertices[1] + vertices[2]) * 0.5f;
    cv::Point2f side4_mid = (vertices[3] + vertices[0]) * 0.5f;
    
    // 计算边长
    double side1_length = cv::norm(vertices[1] - vertices[0]);
    double side2_length = cv::norm(vertices[2] - vertices[1]);
    
    // 确定短边的中点连线
    if (side1_length < side2_length) {
        // side1和side3是短边
        shape.directionLineStart = side1_mid;
        shape.directionLineEnd = side2_mid;
    } else {
        // side2和side4是短边
        shape.directionLineStart = side3_mid;
        shape.directionLineEnd = side4_mid;
    }
    
    // 计算方向向量
    cv::Point2f direction = shape.directionLineEnd - shape.directionLineStart;
    
    // 计算与垂直方向（向上为负y方向）的夹角
    double angle = atan2(direction.x, -direction.y) * 180.0 / M_PI;
    
    // 确保角度在0-180度范围内
    if (angle < 0) angle += 180.0;
    if (angle > 180) angle -= 180.0;
    
    shape.orientationAngle = angle;
}

void calculateTriangleOrientation(const std::vector<cv::Point>& contour, DetectedShape& shape) {
    // 使用多边形逼近获取三角形顶点
    std::vector<cv::Point> approx;
    double epsilon = 0.02 * cv::arcLength(contour, true);
    cv::approxPolyDP(contour, approx, epsilon, true);
    
    if (approx.size() != 3) {
        // 如果不是三角形，使用重心作为方向线
        shape.directionLineStart = shape.center;
        shape.directionLineEnd = cv::Point2f(shape.center.x, shape.center.y - 50);
        shape.orientationAngle = 0.0;
        return;
    }
    
    // 找到最高的顶点（y坐标最小）
    int topVertexIndex = 0;
    for (int i = 1; i < 3; i++) {
        if (approx[i].y < approx[topVertexIndex].y) {
            topVertexIndex = i;
        }
    }
    
    // 获取另外两个顶点
    int vertex1 = (topVertexIndex + 1) % 3;
    int vertex2 = (topVertexIndex + 2) % 3;
    
    // 计算底边中点
    cv::Point2f baseMidpoint = (cv::Point2f(approx[vertex1]) + cv::Point2f(approx[vertex2])) * 0.5f;
    cv::Point2f topVertex = cv::Point2f(approx[topVertexIndex]);
    
    // 设置方向线（从顶点到底边中点）
    shape.directionLineStart = topVertex;
    shape.directionLineEnd = baseMidpoint;
    
    // 计算方向向量
    cv::Point2f direction = shape.directionLineEnd - shape.directionLineStart;
    
    // 计算与垂直方向的夹角
    double angle = atan2(direction.x, -direction.y) * 180.0 / M_PI;
    
    // 确保角度在0-180度范围内
    if (angle < 0) angle += 180.0;
    if (angle > 180) angle -= 180.0;
    
    shape.orientationAngle = angle;
}

double calculateShapeConfidence(const std::vector<cv::Point>& contour, ShapeType shapeType) {
    double confidence = 0.0;
    
    // 基础置信度：轮廓面积与凸包面积的比例
    double contourArea = cv::contourArea(contour);
    std::vector<cv::Point> hull;
    cv::convexHull(contour, hull);
    double hullArea = cv::contourArea(hull);
    
    double areaRatio = (hullArea > 0) ? (contourArea / hullArea) : 0.0;
    confidence += areaRatio * 0.4;  // 40%权重
    
    // 轮廓周长与边界矩形周长的比例
    double perimeter = cv::arcLength(contour, true);
    cv::Rect boundingRect = cv::boundingRect(contour);
    double rectPerimeter = 2.0 * (boundingRect.width + boundingRect.height);
    double perimeterRatio = (rectPerimeter > 0) ? (perimeter / rectPerimeter) : 0.0;
    
    // 根据形状类型调整周长比例的期望值
    double expectedPerimeterRatio = 1.0;
    if (shapeType == ShapeType::TRIANGLE) {
        expectedPerimeterRatio = 0.8;  // 三角形周长通常小于矩形
    } else if (shapeType == ShapeType::RECTANGLE || shapeType == ShapeType::LONG_RECTANGLE) {
        expectedPerimeterRatio = 1.0;  // 矩形周长接近边界矩形
    }
    
    double perimeterScore = 1.0 - abs(perimeterRatio - expectedPerimeterRatio);
    confidence += std::max(0.0, perimeterScore) * 0.3;  // 30%权重
    
    // 形状规整度：多边形逼近的顶点数量
    std::vector<cv::Point> approx;
    double epsilon = 0.015 * cv::arcLength(contour, true);
    cv::approxPolyDP(contour, approx, epsilon, true);
    
    double shapeScore = 0.0;
    if (shapeType == ShapeType::TRIANGLE && approx.size() == 3) {
        shapeScore = 1.0;
    } else if ((shapeType == ShapeType::RECTANGLE || shapeType == ShapeType::LONG_RECTANGLE) && approx.size() == 4) {
        shapeScore = 1.0;
    } else {
        shapeScore = 0.5;  // 部分匹配
    }
    
    confidence += shapeScore * 0.3;  // 30%权重
    
    return std::min(1.0, confidence);
}

ShapeType analyzeContourShape(const std::vector<cv::Point>& contour) {
    double aspectRatio;
    
    // 首先检查是否为三角形
    if (isTriangle(contour)) {
        return ShapeType::TRIANGLE;
    }
    
    // 检查是否为长矩形
    if (isLongRectangle(contour, aspectRatio)) {
        return ShapeType::LONG_RECTANGLE;
    }
    
    // 检查是否为普通矩形
    if (isRectangle(contour, aspectRatio)) {
        return ShapeType::RECTANGLE;
    }
    
    // 默认返回普通矩形
    return ShapeType::RECTANGLE;
}

DetectionResult detectShapes(const cv::Mat& image, bool debug) {
    DetectionResult result;
    
    if (image.empty()) {
        std::cerr << "Error: Empty image" << std::endl;
        return result;
    }
    
    // 预处理图像
    cv::Mat processed = preprocessImage(image);
    cv::Mat hsv;
    cv::cvtColor(processed, hsv, cv::COLOR_BGR2HSV);
    
    // 获取颜色范围
    auto colorRanges = getDefaultColorRanges();
    
    // 对每种颜色进行检测
    int shapeIdCounter = 1;  // 形状ID计数器
    for (const auto& colorPair : colorRanges) {
        const std::string& colorName = colorPair.first;
        const ColorRange& colorRange = colorPair.second;
        
        // 检测颜色区域
        cv::Mat colorMask = detectColorRegions(hsv, colorRange);
        
        if (debug) {
            // cv::imshow is not supported on Android platform
            std::cout << "Debug: Processing " << colorName << " color mask" << std::endl;
        }
        
        // 查找轮廓
        std::vector<std::vector<cv::Point>> contours;
        cv::findContours(colorMask, contours, cv::RETR_EXTERNAL, cv::CHAIN_APPROX_SIMPLE);
        
        // 分析每个轮廓
        for (const auto& contour : contours) {
            double area = cv::contourArea(contour);
            
            // 过滤太小的轮廓 - 大幅降低阈值以检测更小的形状
            if (area < 50) {  // 大幅降低面积阈值以检测更小的形状
                continue;
            }
            
            DetectedShape shape;
            shape.color = colorName;
            shape.contour = contour;
            shape.boundingRect = cv::boundingRect(contour);
            shape.area = area;
            shape.type = analyzeContourShape(contour);
            shape.shapeId = shapeIdCounter++;
            
            // 计算中心点
            cv::Moments moments = cv::moments(contour);
            if (moments.m00 != 0) {
                shape.center.x = moments.m10 / moments.m00;
                shape.center.y = moments.m01 / moments.m00;
            }
            
            // 计算长宽比
            shape.aspectRatio = (double)shape.boundingRect.width / shape.boundingRect.height;
            if (shape.aspectRatio < 1.0) {
                shape.aspectRatio = 1.0 / shape.aspectRatio;
            }
            
            // 计算置信度分数
            double confidence = calculateShapeConfidence(contour, shape.type);
            
            // 过滤低置信度的检测结果 - 大幅降低阈值
            if (confidence < 0.1) {  // 大幅降低置信度阈值
                continue;
            }
            
            // 计算方向角和方向线
            if (shape.type == ShapeType::LONG_RECTANGLE) {
                calculateLongRectangleOrientation(contour, shape);
            } else if (shape.type == ShapeType::TRIANGLE) {
                calculateTriangleOrientation(contour, shape);
            } else {
                // 普通矩形默认方向角为0
                shape.orientationAngle = 0.0;
                shape.directionLineStart = shape.center;
                shape.directionLineEnd = cv::Point2f(shape.center.x, shape.center.y - 30);
            }
            
            result.shapes.push_back(shape);
        }
    }
    
    // 创建标注图像
    result.annotatedImage = annotateShapes(image, result.shapes);
    result.success = !result.shapes.empty();
    
    // Note: cv::imshow is not supported on Android platform
    // Debug visualization should be handled by the calling application
    if (debug) {
        // On Android, the annotated image will be returned to the calling application
        // for display purposes instead of using cv::imshow
        std::cout << "Debug mode: annotated image generated for display" << std::endl;
    }
    
    return result;
}

cv::Mat annotateShapes(const cv::Mat& image, const std::vector<DetectedShape>& shapes) {
    cv::Mat annotated = image.clone();
    
    // 定义颜色映射
    std::map<std::string, cv::Scalar> colorMap;
    colorMap["Blue"] = cv::Scalar(255, 0, 0);      // 蓝色
    colorMap["Black"] = cv::Scalar(0, 0, 0);       // 黑色
    colorMap["Cyan"] = cv::Scalar(255, 255, 0);    // 青色
    colorMap["Yellow"] = cv::Scalar(0, 255, 255);  // 黄色
    colorMap["Green"] = cv::Scalar(0, 255, 0);     // 绿色
    
    // 定义形状名称映射
    std::map<ShapeType, std::string> shapeNames;
    shapeNames[ShapeType::LONG_RECTANGLE] = "Long Rect";
    shapeNames[ShapeType::TRIANGLE] = "Triangle";
    shapeNames[ShapeType::RECTANGLE] = "Rectangle";
    
    for (const auto& shape : shapes) {
        // 绘制轮廓 - 增强边框效果
        cv::Scalar color = colorMap.count(shape.color) ? colorMap[shape.color] : cv::Scalar(128, 128, 128);
        cv::drawContours(annotated, std::vector<std::vector<cv::Point>>{shape.contour}, -1, color, 3);
        
        // 绘制边界矩形 - 增强边框
        cv::rectangle(annotated, shape.boundingRect, cv::Scalar(0, 255, 0), 2);  // 绿色边界矩形
        
        // 绘制中心点
        cv::circle(annotated, shape.center, 3, color, -1);
        
        // 绘制方向线（对于长矩形和三角形）
        if (shape.type == ShapeType::LONG_RECTANGLE || shape.type == ShapeType::TRIANGLE) {
            cv::line(annotated, shape.directionLineStart, shape.directionLineEnd, 
                    cv::Scalar(0, 255, 0), 3); // 绿色粗线
            
            // 在方向线端点绘制小圆圈
            cv::circle(annotated, shape.directionLineStart, 2, cv::Scalar(0, 255, 0), -1);
            cv::circle(annotated, shape.directionLineEnd, 2, cv::Scalar(0, 255, 0), -1);
        }
        
        // 添加文本标签（包含ID和角度信息）
        std::string label = shape.color + " " + shapeNames[shape.type];
        label += " ID:" + std::to_string(shape.shapeId);
        
        if (shape.type == ShapeType::LONG_RECTANGLE || shape.type == ShapeType::TRIANGLE) {
            label += " Angle:" + std::to_string((int)shape.orientationAngle) + "°";
        }
        
        cv::Point textPos(shape.boundingRect.x, shape.boundingRect.y - 10);
        cv::putText(annotated, label, textPos, cv::FONT_HERSHEY_SIMPLEX, 0.4, color, 1);
        
        // 添加面积信息
        std::string areaText = "Area: " + std::to_string((int)shape.area);
        cv::Point areaPos(shape.boundingRect.x, shape.boundingRect.y + shape.boundingRect.height + 15);
        cv::putText(annotated, areaText, areaPos, cv::FONT_HERSHEY_SIMPLEX, 0.4, color, 1);
    }
    
    return annotated;
}

std::string generateJsonOutput(const DetectionResult& result) {
    std::string json = "{\n  \"shapes\": [\n";
    
    for (size_t i = 0; i < result.shapes.size(); i++) {
        const auto& shape = result.shapes[i];
        
        std::string shapeTypeName;
        std::string shapeCode;
        switch (shape.type) {
            case ShapeType::LONG_RECTANGLE:
                shapeTypeName = "Long Rectangle";
                shapeCode = "LR";
                break;
            case ShapeType::TRIANGLE:
                shapeTypeName = "Triangle";
                shapeCode = "TR";
                break;
            case ShapeType::RECTANGLE:
                shapeTypeName = "Rectangle";
                shapeCode = "RE";
                break;
        }
        
        // 颜色编码
        std::string colorCode;
        if (shape.color == "Blue") colorCode = "B";
        else if (shape.color == "Black") colorCode = "K";
        else if (shape.color == "Cyan") colorCode = "C";
        else if (shape.color == "Yellow") colorCode = "Y";
        else if (shape.color == "Green") colorCode = "G";
        else colorCode = "U"; // Unknown
        
        json += "    {\n";
        json += "      \"shape_code\": \"" + colorCode + shapeCode + "\",\n";
        json += "      \"id\": " + std::to_string(shape.shapeId) + ",\n";
        json += "      \"position\": {\n";
        json += "        \"x\": " + std::to_string((int)shape.center.x) + ",\n";
        json += "        \"y\": " + std::to_string((int)shape.center.y) + "\n";
        json += "      },\n";
        json += "      \"orientation_angle\": " + std::to_string(shape.orientationAngle) + ",\n";
        json += "      \"color\": \"" + shape.color + "\",\n";
        json += "      \"type\": \"" + shapeTypeName + "\",\n";
        json += "      \"area\": " + std::to_string((int)shape.area) + ",\n";
        json += "      \"aspect_ratio\": " + std::to_string(shape.aspectRatio) + ",\n";
        json += "      \"direction_line\": {\n";
        json += "        \"start\": {\"x\": " + std::to_string(shape.directionLineStart.x) + ", \"y\": " + std::to_string(shape.directionLineStart.y) + "},\n";
        json += "        \"end\": {\"x\": " + std::to_string(shape.directionLineEnd.x) + ", \"y\": " + std::to_string(shape.directionLineEnd.y) + "}\n";
        json += "      }\n";
        json += "    }";
        
        if (i < result.shapes.size() - 1) {
            json += ",";
        }
        json += "\n";
    }
    
    json += "  ],\n";
    json += "  \"total_count\": " + std::to_string(result.shapes.size()) + "\n";
    json += "}";
    
    return json;
}

void printDetectionResults(const DetectionResult& result) {
    std::cout << "=== Shape Detection Results ===" << std::endl;
    std::cout << "Total shapes detected: " << result.shapes.size() << std::endl;
    std::cout << std::endl;
    
    for (size_t i = 0; i < result.shapes.size(); i++) {
        const auto& shape = result.shapes[i];
        
        std::string shapeTypeName;
        switch (shape.type) {
            case ShapeType::LONG_RECTANGLE:
                shapeTypeName = "Long Rectangle";
                break;
            case ShapeType::TRIANGLE:
                shapeTypeName = "Triangle";
                break;
            case ShapeType::RECTANGLE:
                shapeTypeName = "Rectangle";
                break;
        }
        
        std::cout << "Shape " << (i + 1) << ":" << std::endl;
        std::cout << "  ID: " << shape.shapeId << std::endl;
        std::cout << "  Color: " << shape.color << std::endl;
        std::cout << "  Type: " << shapeTypeName << std::endl;
        std::cout << "  Center: (" << (int)shape.center.x << ", " << (int)shape.center.y << ")" << std::endl;
        std::cout << "  Orientation Angle: " << std::fixed << std::setprecision(1) << shape.orientationAngle << "°" << std::endl;
        std::cout << "  Area: " << (int)shape.area << std::endl;
        std::cout << "  Aspect Ratio: " << std::fixed << std::setprecision(2) << shape.aspectRatio << std::endl;
        std::cout << "  Bounding Rect: [" << shape.boundingRect.x << ", " << shape.boundingRect.y 
                  << ", " << shape.boundingRect.width << ", " << shape.boundingRect.height << "]" << std::endl;
        std::cout << std::endl;
    }
}

} // namespace ShapeDetector