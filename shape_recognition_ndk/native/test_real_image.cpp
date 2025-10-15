#include "shape_detector_c_api.h"
#include "shape_detector.h"
#include <opencv2/opencv.hpp>
#include <iostream>
#include <string>
#include <cstring>
#include <map>

// 从OpenCV Mat创建ImageData
ImageData matToImageData(const cv::Mat& mat) {
    ImageData imageData;
    imageData.width = mat.cols;
    imageData.height = mat.rows;
    imageData.channels = mat.channels();
    
    // 计算数据大小
    size_t data_size = mat.total() * mat.elemSize();
    
    // 分配内存并复制数据
    imageData.data = new uint8_t[data_size];
    memcpy(imageData.data, mat.data, data_size);
    
    return imageData;
}

// 释放ImageData内存
void freeImageData(ImageData& imageData) {
    if (imageData.data) {
        delete[] imageData.data;
        imageData.data = nullptr;
    }
}

// 打印形状类型名称
const char* getShapeTypeName(ShapeType type) {
    switch (type) {
        case SHAPE_TYPE_RECTANGLE: return "Rectangle";
        case SHAPE_TYPE_LONG_RECTANGLE: return "Long Rectangle";
        case SHAPE_TYPE_TRIANGLE: return "Triangle";
        default: return "Unknown";
    }
}

// 打印颜色名称
const char* getColorName(ColorType color) {
    switch (color) {
        case COLOR_RED: return "Red";
        case COLOR_GREEN: return "Green";
        case COLOR_BLUE: return "Blue";
        case COLOR_YELLOW: return "Yellow";
        case COLOR_CYAN: return "Cyan";
        case COLOR_MAGENTA: return "Magenta";
        case COLOR_BLACK: return "Black";
        case COLOR_WHITE: return "White";
        default: return "Unknown";
    }
}

int main(int argc, char* argv[]) {
    std::cout << "=== Shape Recognition NDK 真实图像测试 ===" << std::endl;
    
    // 检查命令行参数
    std::string imagePath = "../../example/t.png";
    if (argc > 1) {
        imagePath = argv[1];
    }
    
    std::cout << "使用图像: " << imagePath << std::endl;
    
    // 1. 初始化形状检测器
    std::cout << "\n1. 初始化形状检测器..." << std::endl;
    if (!shape_detector_init()) {
        std::cerr << "❌ 初始化失败: " << shape_detector_get_last_error() << std::endl;
        return -1;
    }
    std::cout << "✓ 初始化成功" << std::endl;
    std::cout << "版本: " << shape_detector_get_version() << std::endl;
    
    // 2. 加载图像
    std::cout << "\n2. 加载图像..." << std::endl;
    cv::Mat image = cv::imread(imagePath);
    if (image.empty()) {
        std::cerr << "❌ 无法加载图像: " << imagePath << std::endl;
        shape_detector_cleanup();
        return -1;
    }
    
    std::cout << "✓ 图像加载成功" << std::endl;
    std::cout << "  尺寸: " << image.cols << "x" << image.rows << std::endl;
    std::cout << "  通道数: " << image.channels() << std::endl;
    
    // 3. 转换为ImageData
    std::cout << "\n3. 转换图像数据..." << std::endl;
    ImageData imageData = matToImageData(image);
    std::cout << "✓ 图像数据转换完成" << std::endl;
    
    // 4. 显示每个颜色的二值化mask
    std::cout << "\n4. 显示每个颜色的二值化mask..." << std::endl;
    
    // 转换为HSV
    cv::Mat hsv;
    cv::cvtColor(image, hsv, cv::COLOR_BGR2HSV);
    
    // 获取颜色范围
    std::map<std::string, ShapeDetector::ColorRange> colorRanges = ShapeDetector::getDefaultColorRanges();
    
    // 为每种颜色生成并显示mask
    for (const auto& colorPair : colorRanges) {
        const std::string& colorName = colorPair.first;
        const ShapeDetector::ColorRange& colorRange = colorPair.second;
        
        std::cout << "\n  处理颜色: " << colorName << std::endl;
        std::cout << "    HSV范围: [" << colorRange.lower[0] << "," << colorRange.lower[1] << "," << colorRange.lower[2] 
                  << "] - [" << colorRange.upper[0] << "," << colorRange.upper[1] << "," << colorRange.upper[2] << "]" << std::endl;
        
        // 生成mask
        cv::Mat mask = ShapeDetector::detectColorRegions(hsv, colorRange);
        
        // 统计mask中的白色像素数量
        int whitePixels = cv::countNonZero(mask);
        std::cout << "    检测到的像素数量: " << whitePixels << std::endl;
        
        // 显示mask
        std::string windowName = colorName + " Mask";
        cv::namedWindow(windowName, cv::WINDOW_AUTOSIZE);
        cv::imshow(windowName, mask);
        
        // 保存mask图像
        std::string maskFilename = colorName + "_mask.jpg";
        cv::imwrite(maskFilename, mask);
        std::cout << "    Mask已保存到: " << maskFilename << std::endl;
    }
    
    std::cout << "\n✓ 所有颜色mask已生成，按任意键继续..." << std::endl;
    cv::waitKey(0);
    cv::destroyAllWindows();

    // 5. 执行形状检测
    std::cout << "\n5. 执行形状检测..." << std::endl;
    DetectionResult* result = shape_detector_detect(&imageData, true);
    
    if (!result) {
        std::cerr << "❌ 检测失败: " << shape_detector_get_last_error() << std::endl;
        freeImageData(imageData);
        shape_detector_cleanup();
        return -1;
    }
    
    std::cout << "✓ 检测完成" << std::endl;
    std::cout << "  检测到形状数量: " << result->shape_count << std::endl;
    std::cout << "  总计数量: " << result->total_count << std::endl;
    
    // 5. 显示检测结果
    if (result->shape_count > 0) {
        std::cout << "\n5. 检测到的形状详情:" << std::endl;
        for (int i = 0; i < result->shape_count; i++) {
            const DetectedShape& shape = result->shapes[i];
            std::cout << "  形状 #" << (i + 1) << ":" << std::endl;
            std::cout << "    ID: " << shape.id << std::endl;
            std::cout << "    类型: " << getShapeTypeName(shape.type) << std::endl;
            std::cout << "    颜色: " << getColorName(shape.color) << std::endl;
            std::cout << "    中心: (" << shape.center.x << ", " << shape.center.y << ")" << std::endl;
            std::cout << "    面积: " << shape.area << std::endl;
            std::cout << "    长宽比: " << shape.aspect_ratio << std::endl;
            std::cout << "    方向角: " << shape.orientation_angle << "°" << std::endl;
            std::cout << "    形状代码: " << shape.shape_code << std::endl;
        }
    } else {
        std::cout << "\n5. 未检测到任何形状" << std::endl;
    }
    
    // 6. 生成JSON
    std::cout << "\n6. 生成JSON结果..." << std::endl;
    char* json = shape_detector_generate_json(result);
    if (json) {
        std::cout << "✓ JSON生成成功" << std::endl;
        std::cout << "JSON内容:\n" << json << std::endl;
        shape_detector_free_json(json);
    } else {
        std::cout << "❌ JSON生成失败" << std::endl;
    }
    
    // 7. 显示原图
    std::cout << "\n7. 显示原图..." << std::endl;
    cv::imshow("原始图像", image);
    std::cout << "✓ 原图显示完成，按任意键继续..." << std::endl;
    cv::waitKey(0);
    
    // 8. 生成并显示标注图像
    std::cout << "\n8. 生成标注图像..." << std::endl;
    ImageData annotatedData;
    if (shape_detector_annotate_image(&imageData, result, &annotatedData)) {
        std::cout << "✓ 标注图像生成成功" << std::endl;
        
        // 转换回OpenCV Mat并显示
        cv::Mat annotatedMat(annotatedData.height, annotatedData.width, 
                           CV_8UC3, annotatedData.data);
        
        // 显示标注图像
        cv::imshow("检测结果", annotatedMat);
        std::cout << "✓ 标注图像显示完成，按任意键继续..." << std::endl;
        cv::waitKey(0);
        
        // 保存标注图像
        std::string outputPath = "annotated_output.jpg";
        if (cv::imwrite(outputPath, annotatedMat)) {
            std::cout << "✓ 标注图像已保存到: " << outputPath << std::endl;
        } else {
            std::cout << "❌ 保存标注图像失败" << std::endl;
        }
        
        shape_detector_free_image(&annotatedData);
    } else {
        std::cout << "❌ 标注图像生成失败" << std::endl;
    }
    
    // 9. 关闭所有窗口
    cv::destroyAllWindows();
    
    // 10. 清理资源
    std::cout << "\n10. 清理资源..." << std::endl;
    shape_detector_free_result(result);
    freeImageData(imageData);
    shape_detector_cleanup();
    std::cout << "✓ 清理完成" << std::endl;
    
    std::cout << "\n=== 测试完成 ===" << std::endl;
    return 0;
}