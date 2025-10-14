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
    
    // 定义各种颜色的HSV范围
    colorRanges["Blue"] = ColorRange(cv::Scalar(100, 50, 50), cv::Scalar(130, 255, 255));
    colorRanges["Black"] = ColorRange(cv::Scalar(0, 0, 0), cv::Scalar(180, 255, 50));  // 低明度
    colorRanges["Cyan"] = ColorRange(cv::Scalar(80, 50, 50), cv::Scalar(100, 255, 255));
    colorRanges["Yellow"] = ColorRange(cv::Scalar(18, 40, 40), cv::Scalar(36, 255, 255));
    colorRanges["Green"] = ColorRange(cv::Scalar(40, 50, 50), cv::Scalar(80, 255, 255));
    
    return colorRanges;
}

cv::Mat preprocessImage(const cv::Mat& image) {
    cv::Mat blurred, gray;
    
    // 高斯模糊减少噪声
    cv::GaussianBlur(image, blurred, cv::Size(5, 5), 0);
    
    return blurred;
}

cv::Mat detectColorRegions(const cv::Mat& hsv, const ColorRange& colorRange) {
    cv::Mat mask;
    cv::inRange(hsv, colorRange.lower, colorRange.upper, mask);
    
    // 形态学操作去除噪声
    cv::Mat kernel = cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(5, 5));
    cv::morphologyEx(mask, mask, cv::MORPH_OPEN, kernel);
    cv::morphologyEx(mask, mask, cv::MORPH_CLOSE, kernel);
    
    return mask;
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
    // 使用多边形逼近
    std::vector<cv::Point> approx;
    double epsilon = 0.02 * cv::arcLength(contour, true);
    cv::approxPolyDP(contour, approx, epsilon, true);
    
    return (approx.size() == 3);
}

bool isRectangle(const std::vector<cv::Point>& contour, double& aspectRatio) {

    std::vector<cv::Point> approx;
    double epsilon = 0.02 * cv::arcLength(contour, true);
    cv::approxPolyDP(contour, approx, epsilon, true);
    
    if (approx.size() != 4) {
        return false;
    }
    
    // 检查是否为矩形（角度接近90度）
    std::vector<double> angles;
    for (int i = 0; i < 4; i++) {
        cv::Point p1 = approx[i];
        cv::Point p2 = approx[(i + 1) % 4];
        cv::Point p3 = approx[(i + 2) % 4];
        
        cv::Point v1 = p1 - p2;
        cv::Point v2 = p3 - p2;
        
        double dot = v1.x * v2.x + v1.y * v2.y;
        double mag1 = sqrt(v1.x * v1.x + v1.y * v1.y);
        double mag2 = sqrt(v2.x * v2.x + v2.y * v2.y);
        
        if (mag1 > 0 && mag2 > 0) {
            double angle = acos(dot / (mag1 * mag2)) * 180.0 / M_PI;
            angles.push_back(angle);
        }
    }
    
    // 检查角度是否接近90度
    for (double angle : angles) {
        if (abs(angle - 90.0) > 20.0) {  // 允许20度误差
            return false;
        }
    }
    
    // 计算长宽比
    cv::Rect boundingRect = cv::boundingRect(contour);
    aspectRatio = (double)boundingRect.width / boundingRect.height;
    if (aspectRatio < 1.0) {
        aspectRatio = 1.0 / aspectRatio;
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
            
            // 过滤太小的轮廓
            if (area < 500) {
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
        // 绘制轮廓
        cv::Scalar color = colorMap.count(shape.color) ? colorMap[shape.color] : cv::Scalar(128, 128, 128);
        cv::drawContours(annotated, std::vector<std::vector<cv::Point>>{shape.contour}, -1, color, 2);
        
        // 绘制边界矩形
        cv::rectangle(annotated, shape.boundingRect, color, 1);
        
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