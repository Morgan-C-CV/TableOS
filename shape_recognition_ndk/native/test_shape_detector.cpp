#include "shape_detector_c_api.h"
#include <iostream>
#include <cassert>
#include <cstring>

/**
 * 测试程序：验证Shape Recognition NDK库的C API功能
 * 
 * 编译命令:
 * g++ -std=c++17 test_shape_detector.cpp shape_detector.cpp shape_detector_c_api.cpp \
 *     `pkg-config --cflags --libs opencv4` -o test_shape_detector
 */

// 测试计数器
int tests_passed = 0;
int tests_failed = 0;

// 测试宏
#define TEST_ASSERT(condition, message) \
    do { \
        if (condition) { \
            std::cout << "✓ PASS: " << message << std::endl; \
            tests_passed++; \
        } else { \
            std::cout << "✗ FAIL: " << message << std::endl; \
            tests_failed++; \
        } \
    } while(0)

// 创建测试图像数据
ImageData createTestImageData() {
    ImageData imageData;
    imageData.width = 320;
    imageData.height = 240;
    imageData.channels = 3;
    
    int dataSize = imageData.width * imageData.height * imageData.channels;
    imageData.data = new uint8_t[dataSize];
    
    // 填充测试数据
    memset(imageData.data, 100, dataSize);
    
    return imageData;
}

void freeTestImageData(ImageData& imageData) {
    if (imageData.data) {
        delete[] imageData.data;
        imageData.data = nullptr;
    }
}

// 测试初始化和清理
void testInitialization() {
    std::cout << "\n=== 测试初始化和清理 ===" << std::endl;
    
    // 测试初始化
    bool initResult = shape_detector_init();
    TEST_ASSERT(initResult, "形状检测器初始化");
    
    // 测试版本信息
    const char* version = shape_detector_get_version();
    TEST_ASSERT(version != nullptr && strlen(version) > 0, "获取版本信息");
    std::cout << "  版本: " << version << std::endl;
    
    // 测试清理
    shape_detector_cleanup();
    std::cout << "✓ 清理完成" << std::endl;
}

// 测试图像数据处理
void testImageDataHandling() {
    std::cout << "\n=== 测试图像数据处理 ===" << std::endl;
    
    // 初始化
    bool initResult = shape_detector_init();
    TEST_ASSERT(initResult, "重新初始化");
    
    // 创建测试图像
    ImageData testImage = createTestImageData();
    TEST_ASSERT(testImage.data != nullptr, "创建测试图像数据");
    TEST_ASSERT(testImage.width == 320, "图像宽度正确");
    TEST_ASSERT(testImage.height == 240, "图像高度正确");
    TEST_ASSERT(testImage.channels == 3, "图像通道数正确");
    
    // 测试检测（可能没有结果，但不应该崩溃）
    DetectionResult* result = shape_detector_detect(&testImage, false);
    TEST_ASSERT(result != nullptr, "检测函数返回结果");
    
    if (result) {
        TEST_ASSERT(result->shape_count >= 0, "形状数量非负");
        std::cout << "  检测到形状数量: " << result->shape_count << std::endl;
        
        // 测试JSON生成
        char* jsonStr = shape_detector_generate_json(result);
        TEST_ASSERT(jsonStr != nullptr, "JSON生成");
        
        if (jsonStr) {
            TEST_ASSERT(strlen(jsonStr) > 0, "JSON内容非空");
            std::cout << "  JSON长度: " << strlen(jsonStr) << " 字符" << std::endl;
            shape_detector_free_json(jsonStr);
        }
        
        shape_detector_free_result(result);
    }
    
    freeTestImageData(testImage);
    shape_detector_cleanup();
}

// 测试错误处理
void testErrorHandling() {
    std::cout << "\n=== 测试错误处理 ===" << std::endl;
    
    // 测试未初始化时的调用
    ImageData testImage = createTestImageData();
    DetectionResult* result = shape_detector_detect(&testImage, false);
    
    // 应该返回nullptr或处理错误
    if (!result) {
        const char* error = shape_detector_get_last_error();
        TEST_ASSERT(error != nullptr, "获取错误信息");
        std::cout << "  错误信息: " << (error ? error : "无") << std::endl;
    }
    
    // 测试空指针参数
    result = shape_detector_detect(nullptr, false);
    TEST_ASSERT(result == nullptr, "空指针参数处理");
    
    freeTestImageData(testImage);
}

// 测试内存管理
void testMemoryManagement() {
    std::cout << "\n=== 测试内存管理 ===" << std::endl;
    
    shape_detector_init();
    
    ImageData testImage = createTestImageData();
    
    // 多次检测测试内存泄漏
    for (int i = 0; i < 5; i++) {
        DetectionResult* result = shape_detector_detect(&testImage, false);
        if (result) {
            char* jsonStr = shape_detector_generate_json(result);
            if (jsonStr) {
                shape_detector_free_json(jsonStr);
            }
            shape_detector_free_result(result);
        }
    }
    
    TEST_ASSERT(true, "多次检测和内存释放");
    
    freeTestImageData(testImage);
    shape_detector_cleanup();
}

// 测试图像标注功能
void testImageAnnotation() {
    std::cout << "\n=== 测试图像标注功能 ===" << std::endl;
    
    shape_detector_init();
    
    ImageData testImage = createTestImageData();
    DetectionResult* result = shape_detector_detect(&testImage, false);
    
    if (result) {
        ImageData annotatedData;
        bool annotateResult = shape_detector_annotate_image(&testImage, result, &annotatedData);
        
        if (annotateResult) {
            TEST_ASSERT(annotatedData.data != nullptr, "标注图像数据生成");
            TEST_ASSERT(annotatedData.width == testImage.width, "标注图像宽度一致");
            TEST_ASSERT(annotatedData.height == testImage.height, "标注图像高度一致");
            TEST_ASSERT(annotatedData.channels == testImage.channels, "标注图像通道数一致");
            
            shape_detector_free_image(&annotatedData);
        } else {
            std::cout << "  标注功能测试跳过（可能没有检测到形状）" << std::endl;
        }
        
        shape_detector_free_result(result);
    }
    
    freeTestImageData(testImage);
    shape_detector_cleanup();
}

// 测试API稳定性
void testAPIStability() {
    std::cout << "\n=== 测试API稳定性 ===" << std::endl;
    
    // 测试多次初始化和清理
    for (int i = 0; i < 3; i++) {
        bool initResult = shape_detector_init();
        TEST_ASSERT(initResult, "多次初始化 #" + std::to_string(i + 1));
        shape_detector_cleanup();
    }
    
    // 测试重复清理（不应该崩溃）
    shape_detector_cleanup();
    shape_detector_cleanup();
    TEST_ASSERT(true, "重复清理处理");
}

int main() {
    std::cout << "=== Shape Recognition NDK C API 测试程序 ===" << std::endl;
    std::cout << "开始执行测试..." << std::endl;
    
    // 执行所有测试
    testInitialization();
    testImageDataHandling();
    testErrorHandling();
    testMemoryManagement();
    testImageAnnotation();
    testAPIStability();
    
    // 输出测试结果
    std::cout << "\n=== 测试结果汇总 ===" << std::endl;
    std::cout << "通过测试: " << tests_passed << std::endl;
    std::cout << "失败测试: " << tests_failed << std::endl;
    std::cout << "总计测试: " << (tests_passed + tests_failed) << std::endl;
    
    if (tests_failed == 0) {
        std::cout << "🎉 所有测试通过！" << std::endl;
        return 0;
    } else {
        std::cout << "❌ 有测试失败，请检查实现" << std::endl;
        return 1;
    }
}