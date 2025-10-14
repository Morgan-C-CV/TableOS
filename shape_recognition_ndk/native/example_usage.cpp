#include "shape_detector_c_api.h"
#include <iostream>
#include <string>
#include <fstream>
#include <cstring>

/**
 * 简化示例程序：演示如何使用Shape Recognition NDK库的C API
 * 
 * 注意：此示例不包含实际的图像加载代码，仅演示API调用流程
 * 在实际使用中，需要从Android Bitmap或其他图像源获取图像数据
 */

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

// 模拟图像数据（实际使用中应从文件或Android Bitmap获取）
ImageData createMockImageData() {
    ImageData imageData;
    imageData.width = 640;
    imageData.height = 480;
    imageData.channels = 3;
    
    // 分配模拟图像数据（实际使用中应包含真实图像数据）
    int dataSize = imageData.width * imageData.height * imageData.channels;
    imageData.data = new uint8_t[dataSize];
    
    // 填充一些模拟数据（实际使用中不需要这步）
    memset(imageData.data, 128, dataSize); // 灰色背景
    
    return imageData;
}

void freeMockImageData(ImageData& imageData) {
    if (imageData.data) {
        delete[] imageData.data;
        imageData.data = nullptr;
    }
}

int main() {
    std::cout << "=== Shape Recognition NDK C API 示例程序 ===" << std::endl;
    std::cout << "版本: " << shape_detector_get_version() << std::endl;
    
    // 1. 初始化形状检测器
    std::cout << "\n1. 初始化形状检测器..." << std::endl;
    if (!shape_detector_init()) {
        std::cerr << "错误: 初始化失败 - " << shape_detector_get_last_error() << std::endl;
        return -1;
    }
    std::cout << "✓ 初始化成功" << std::endl;
    
    // 2. 准备图像数据（模拟）
    std::cout << "\n2. 准备图像数据..." << std::endl;
    ImageData imageData = createMockImageData();
    std::cout << "✓ 图像数据准备完成 (" << imageData.width << "x" << imageData.height 
              << ", " << imageData.channels << " 通道)" << std::endl;
    
    // 3. 执行形状检测
    std::cout << "\n3. 执行形状检测..." << std::endl;
    DetectionResult* result = shape_detector_detect(&imageData, false);
    if (!result) {
        std::cerr << "错误: 形状检测失败 - " << shape_detector_get_last_error() << std::endl;
        freeMockImageData(imageData);
        shape_detector_cleanup();
        return -1;
    }
    
    // 4. 显示检测结果
    std::cout << "✓ 检测完成，发现 " << result->shape_count << " 个形状" << std::endl;
    
    if (result->shape_count > 0) {
        std::cout << "\n4. 检测结果详情:" << std::endl;
        std::cout << "----------------------------------------" << std::endl;
        
        for (int i = 0; i < result->shape_count; i++) {
            const DetectedShape& shape = result->shapes[i];
            std::cout << "形状 " << (i + 1) << ":" << std::endl;
            std::cout << "  ID: " << shape.id << std::endl;
            std::cout << "  代码: " << shape.shape_code << std::endl;
            std::cout << "  类型: " << getShapeTypeName(shape.type) << std::endl;
            std::cout << "  颜色: " << getColorName(shape.color) << std::endl;
            std::cout << "  中心: (" << shape.center.x << ", " << shape.center.y << ")" << std::endl;
            std::cout << "  面积: " << shape.area << std::endl;
            std::cout << "  长宽比: " << shape.aspect_ratio << std::endl;
            std::cout << "  方向角: " << shape.orientation_angle << "°" << std::endl;
            std::cout << "  方向线: (" << shape.direction_line_start.x << ", " 
                      << shape.direction_line_start.y << ") -> (" 
                      << shape.direction_line_end.x << ", " 
                      << shape.direction_line_end.y << ")" << std::endl;
            std::cout << "----------------------------------------" << std::endl;
        }
        
        // 5. 生成JSON输出
        std::cout << "\n5. 生成JSON输出..." << std::endl;
        char* jsonStr = shape_detector_generate_json(result);
        if (jsonStr) {
            std::cout << "✓ JSON生成成功" << std::endl;
            std::cout << "\nJSON结果:" << std::endl;
            std::cout << jsonStr << std::endl;
            
            // 保存JSON到文件
            std::string jsonPath = "detection_result.json";
            std::ofstream jsonFile(jsonPath);
            if (jsonFile.is_open()) {
                jsonFile << jsonStr;
                jsonFile.close();
                std::cout << "✓ JSON结果已保存到: " << jsonPath << std::endl;
            }
            
            shape_detector_free_json(jsonStr);
        } else {
            std::cerr << "错误: JSON生成失败 - " << shape_detector_get_last_error() << std::endl;
        }
        
        // 6. 生成标注图像
        std::cout << "\n6. 生成标注图像..." << std::endl;
        ImageData annotatedData;
        if (shape_detector_annotate_image(&imageData, result, &annotatedData)) {
            std::cout << "✓ 图像标注成功 (" << annotatedData.width << "x" 
                      << annotatedData.height << ")" << std::endl;
            
            // 在实际应用中，这里可以将annotatedData转换回Android Bitmap
            std::cout << "  标注图像数据已生成，可转换为显示格式" << std::endl;
            
            shape_detector_free_image(&annotatedData);
        } else {
            std::cerr << "错误: 图像标注失败 - " << shape_detector_get_last_error() << std::endl;
        }
    } else {
        std::cout << "\n4. 未检测到任何形状（这是正常的，因为使用的是模拟数据）" << std::endl;
    }
    
    // 7. 清理资源
    std::cout << "\n7. 清理资源..." << std::endl;
    shape_detector_free_result(result);
    freeMockImageData(imageData);
    shape_detector_cleanup();
    std::cout << "✓ 清理完成" << std::endl;
    
    std::cout << "\n=== API调用流程演示完成 ===" << std::endl;
    std::cout << "\n使用说明:" << std::endl;
    std::cout << "1. 在实际Android应用中，从Bitmap获取图像数据" << std::endl;
    std::cout << "2. 调用shape_detector_detect()进行检测" << std::endl;
    std::cout << "3. 处理DetectionResult结果" << std::endl;
    std::cout << "4. 可选：生成JSON或标注图像" << std::endl;
    std::cout << "5. 记得释放所有分配的内存" << std::endl;
    
    return 0;
}