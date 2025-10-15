#include <opencv2/opencv.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/highgui.hpp>
#include <opencv2/videoio.hpp>
#include <iostream>
#include <map>
#include <string>
#include <cstring>
#include "shape_detector_c_api.h"

using namespace cv;
using namespace std;

// 将cv::Mat转换为ImageData
ImageData matToImageData(const Mat& mat) {
    ImageData imageData;
    imageData.width = mat.cols;
    imageData.height = mat.rows;
    imageData.channels = mat.channels();
    
    // 分配内存并复制数据
    int dataSize = mat.rows * mat.cols * mat.channels();
    imageData.data = new uint8_t[dataSize];
    memcpy(imageData.data, mat.data, dataSize);
    
    return imageData;
}

// 释放ImageData内存
void freeImageData(ImageData& imageData) {
    if (imageData.data) {
        delete[] imageData.data;
        imageData.data = nullptr;
    }
}

// 计算两个向量之间的角度（度数）
double calculateAngle(const Point& p1, const Point& p2, const Point& p3) {
    // 向量 p2->p1 和 p2->p3
    Point v1 = p1 - p2;
    Point v2 = p3 - p2;
    
    // 计算点积和向量长度
    double dot = v1.x * v2.x + v1.y * v2.y;
    double len1 = sqrt(v1.x * v1.x + v1.y * v1.y);
    double len2 = sqrt(v2.x * v2.x + v2.y * v2.y);
    
    if (len1 == 0 || len2 == 0) return 0;
    
    // 计算角度（弧度转度数）
    double angle = acos(dot / (len1 * len2)) * 180.0 / CV_PI;
    return angle;
}

// 验证凸包角度是否符合要求（70-120度和175度以上）
bool isValidHullAngles(const vector<Point>& hull) {
    if (hull.size() < 3) {
        cout << "  凸包点数不足: " << hull.size() << endl;
        return false;
    }
    
    cout << "  检查凸包角度 (点数: " << hull.size() << "):" << endl;
    bool isValid = true;
    
    for (int i = 0; i < hull.size(); i++) {
        int prev = (i - 1 + hull.size()) % hull.size();
        int next = (i + 1) % hull.size();
        
        double angle = calculateAngle(hull[prev], hull[i], hull[next]);
        
        // 检查角度是否在允许范围内：70-120度或175度以上
        bool angleValid = ((angle >= 50 && angle <= 130) || angle >= 160);
        
        cout << "    点" << i << ": " << angle << "° " << (angleValid ? "✓" : "✗") << endl;
        
        if (!angleValid) {
            isValid = false;
        }
    }
    
    cout << "  凸包角度验证结果: " << (isValid ? "通过" : "不通过") << endl;
    return isValid;
}

// 创建颜色mask
Mat createColorMask(const Mat& image, const string& colorName) {
    Mat hsv, mask;
    cvtColor(image, hsv, COLOR_BGR2HSV);
    
    // 定义颜色范围 (调整后的范围，避免Green和Cyan混淆)
    map<string, pair<Scalar, Scalar>> colorRanges = {
        {"Yellow", {Scalar(10, 20, 60), Scalar(65, 255, 255)}},  // 扩大黄色范围
        {"Green", {Scalar(40, 40, 60), Scalar(75, 255, 255)}},   // 绿色范围：40-75
        {"Cyan", {Scalar(80, 50, 70), Scalar(115, 255, 255)}},   // 青色范围：80-115，避免与绿色重叠
        {"Blue", {Scalar(100, 40, 60), Scalar(140, 255, 255)}},
        {"Black", {Scalar(0, 0, 0), Scalar(180, 50, 40)}}       // 缩小黑色范围
    };
    
    if (colorRanges.find(colorName) != colorRanges.end()) {
        inRange(hsv, colorRanges[colorName].first, colorRanges[colorName].second, mask);
    } else {
        mask = Mat::zeros(image.size(), CV_8UC1);
    }
    
    return mask;
}

int main() {
    cout << "=== 实时摄像头颜色识别程序 ===" << endl;
    
    // 初始化形状检测器
    cout << "1. 初始化形状检测器..." << endl;
    if (!shape_detector_init()) {
        cerr << "❌ 形状检测器初始化失败" << endl;
        return -1;
    }
    cout << "✓ 初始化成功" << endl;
    cout << "版本: " << shape_detector_get_version() << endl;
    
    // 打开摄像头
    cout << "\n2. 打开摄像头..." << endl;
    VideoCapture cap(0);
    if (!cap.isOpened()) {
        cerr << "❌ 无法打开摄像头" << endl;
        shape_detector_cleanup();
        return -1;
    }
    
    // 设置摄像头参数
    cap.set(CAP_PROP_FRAME_WIDTH, 640);
    cap.set(CAP_PROP_FRAME_HEIGHT, 480);
    cap.set(CAP_PROP_FPS, 30);
    
    cout << "✓ 摄像头打开成功" << endl;
    cout << "分辨率: " << cap.get(CAP_PROP_FRAME_WIDTH) << "x" << cap.get(CAP_PROP_FRAME_HEIGHT) << endl;
    cout << "帧率: " << cap.get(CAP_PROP_FPS) << " FPS" << endl;
    
    // 创建显示窗口
    cout << "\n3. 创建显示窗口..." << endl;
    vector<string> windowNames = {"Original", "Yellow Mask", "Green Mask", "Cyan Mask", "Blue Mask", "Black Mask"};
    vector<string> colorNames = {"", "Yellow", "Green", "Cyan", "Blue", "Black"};
    
    for (const auto& name : windowNames) {
        namedWindow(name, WINDOW_AUTOSIZE);
    }
    
    // 窗口布局 (2行3列)
    int windowWidth = 320, windowHeight = 240;
    for (int i = 0; i < windowNames.size(); i++) {
        int x = (i % 3) * (windowWidth + 10) + 50;
        int y = (i / 3) * (windowHeight + 50) + 50;
        moveWindow(windowNames[i], x, y);
        resizeWindow(windowNames[i], windowWidth, windowHeight);
    }
    
    cout << "✓ 窗口创建成功" << endl;
    cout << "\n4. 开始按帧分析..." << endl;
    cout << "控制说明:" << endl;
    cout << "  ESC - 退出程序" << endl;
    cout << "  空格 - 下一帧" << endl;
    cout << "  's' - 保存当前帧" << endl;
    cout << "===================" << endl;
    
    Mat frame;
    int frameCount = 0;
    bool frameReady = false;
    
    // 捕获第一帧
    cap >> frame;
    if (frame.empty()) {
        cerr << "❌ 无法读取摄像头帧" << endl;
        cap.release();
        destroyAllWindows();
        shape_detector_cleanup();
        return -1;
    }
    frameCount++;
    frameReady = true;
    cout << "✓ 已捕获第一帧，按空格键进行下一帧分析" << endl;
    
    while (true) {
        if (!frameReady) {
            // 等待用户按键
            char key = waitKey(0) & 0xFF;
            if (key == 27) { // ESC键
                cout << "\n用户按下ESC，退出程序..." << endl;
                break;
            } else if (key == ' ') { // 空格键 - 捕获下一帧
                cap >> frame;
                if (frame.empty()) {
                    cerr << "❌ 无法读取摄像头帧" << endl;
                    break;
                }
                frameCount++;
                frameReady = true;
                cout << "✓ 已捕获第 " << frameCount << " 帧" << endl;
            } else if (key == 's' || key == 'S') { // 保存键
                if (!frame.empty()) {
                    string filename = "captured_frame_" + to_string(frameCount) + ".jpg";
                    imwrite(filename, frame);
                    cout << "💾 保存帧: " << filename << endl;
                }
            }
            continue;
        }
        
        // 创建带检测结果的原图副本
        Mat annotatedFrame = frame.clone();
        
        // 创建并显示各颜色mask，同时进行形状检测
        for (int i = 1; i < windowNames.size(); i++) {
            Mat mask = createColorMask(frame, colorNames[i]);
            
            // 进行形状检测
            ImageData imageData = matToImageData(mask);
            DetectionResult* result = shape_detector_detect(&imageData, false);
            
            // 在mask上绘制检测结果
            Mat maskWithDetection = mask.clone();
            cvtColor(maskWithDetection, maskWithDetection, COLOR_GRAY2BGR);
            
            // 直接从mask中找到轮廓并绘制凸包
            vector<vector<Point>> contours;
            vector<Vec4i> hierarchy;
            findContours(mask, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
            
            // 设置颜色
            Scalar color;
            if (colorNames[i] == "Yellow") color = Scalar(0, 255, 255);
            else if (colorNames[i] == "Green") color = Scalar(0, 255, 0);
            else if (colorNames[i] == "Cyan") color = Scalar(255, 255, 0);
            else if (colorNames[i] == "Blue") color = Scalar(255, 0, 0);
            else if (colorNames[i] == "Black") color = Scalar(128, 128, 128);
            
            // 对每个轮廓计算并绘制凸包
            cout << colorNames[i] << " 颜色检测到 " << contours.size() << " 个轮廓" << endl;
            
            int validHullCount = 0;
            for (int j = 0; j < contours.size(); j++) {
                const auto& contour = contours[j];
                double area = contourArea(contour);
                cout << "轮廓 " << j << ": 面积 = " << area;
                
                if (area > 900 && area <1500) {  // 过滤小轮廓
                    cout << " (符合面积要求)" << endl;
                    vector<Point> hull;
                    convexHull(contour, hull);
                    
                    validHullCount++;
                    cout << "  -> 绘制凸包 #" << validHullCount << endl;
                    
                    // 在mask上绘制凸包轮廓
                    vector<vector<Point>> hullContours = {hull};
                    drawContours(maskWithDetection, hullContours, -1, color, 3);
                    
                    // 计算并绘制中心点
                    Moments m = moments(contour);
                    if (m.m00 != 0) {
                        Point center(m.m10 / m.m00, m.m01 / m.m00);
                        circle(maskWithDetection, center, 3, color, -1);
                    }
                } else {
                    cout << " (面积太小，跳过)" << endl;
                }
            }
            
            cout << colorNames[i] << " 最终绘制了 " << validHullCount << " 个有效凸包" << endl << endl;
            
            // 显示带检测结果的mask
            imshow(windowNames[i], maskWithDetection);
            
            // 清理内存
            freeImageData(imageData);
            if (result) {
                shape_detector_free_result(result);
            }
        }
        
        // 显示带注释的原图
        imshow("Original", annotatedFrame);
        
        cout << "已处理第 " << frameCount << " 帧，按空格键继续下一帧，ESC退出" << endl;
        
        // 标记当前帧处理完成，等待下一次按键
        frameReady = false;
    }
    
    // 清理资源
    cout << "\n5. 清理资源..." << endl;
    cap.release();
    destroyAllWindows();
    shape_detector_cleanup();
    cout << "✓ 程序结束" << endl;
    
    return 0;
}