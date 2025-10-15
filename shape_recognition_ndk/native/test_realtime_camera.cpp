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

// 凸包信息结构体
struct HullInfo {
    vector<Point> hull;
    Point center;
    double area;
    string color;
    
    HullInfo(const vector<Point>& h, const Point& c, double a, const string& col) 
        : hull(h), center(c), area(a), color(col) {}
};

// 计算两个中心点之间的距离
double calculateDistance(const Point& p1, const Point& p2) {
    return sqrt(pow(p1.x - p2.x, 2) + pow(p1.y - p2.y, 2));
}

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
        {"Green", {Scalar(40, 40, 60), Scalar(85, 255, 255)}},   // 绿色范围：40-75
        {"Cyan", {Scalar(95, 50, 110), Scalar(120, 255, 255)}},   // 青色范围：80-115，避免与绿色重叠
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
    cout << "=== 实时摄像头视频流分析程序 (5帧稳定性检测) ===" << endl;
    
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
    namedWindow("Video Stream Analysis", WINDOW_AUTOSIZE);
    moveWindow("Video Stream Analysis", 50, 50);
    resizeWindow("Video Stream Analysis", 640, 480);
    
    cout << "✓ 窗口创建成功" << endl;
    cout << "\n4. 开始视频流分析..." << endl;
    cout << "控制说明:" << endl;
    cout << "  ESC - 退出程序" << endl;
    cout << "  's' - 保存当前帧" << endl;
    cout << "===================" << endl;
    
    // 5帧缓冲区，存储每一帧检测到的凸包信息
    vector<vector<HullInfo>> frameBuffer(5);
    int currentFrameIndex = 0;
    int frameCount = 0;
    
    // 颜色定义
    vector<string> colorNames = {"Yellow", "Green", "Cyan", "Blue", "Black"};
    map<string, Scalar> colorMap = {
        {"Yellow", Scalar(0, 255, 255)},
        {"Green", Scalar(0, 255, 0)},
        {"Cyan", Scalar(255, 255, 0)},
        {"Blue", Scalar(255, 0, 0)},
        {"Black", Scalar(128, 128, 128)}
    };
    
    // 稳定性检测参数
    const double DISTANCE_THRESHOLD = 50.0;  // 中心点距离阈值
    const int REQUIRED_FRAMES = 5;           // 需要连续检测到的帧数
    
    Mat frame;
    
    while (true) {
        // 读取新帧
        cap >> frame;
        if (frame.empty()) {
            cerr << "❌ 无法读取摄像头帧" << endl;
            break;
        }
        
        frameCount++;
        
        // 清空当前帧的检测结果
        frameBuffer[currentFrameIndex].clear();
        
        // 创建显示帧
        Mat displayFrame = frame.clone();
        
        // 对每种颜色进行检测
        for (const string& colorName : colorNames) {
            Mat mask = createColorMask(frame, colorName);
            
            // 找到轮廓
            vector<vector<Point>> contours;
            vector<Vec4i> hierarchy;
            findContours(mask, contours, hierarchy, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
            
            // 处理每个轮廓
            for (const auto& contour : contours) {
                double area = contourArea(contour);
                
                if (area > 900 && area <1400) {  // 过滤小轮廓
                    cout << " (符合面积要求)" << endl;
                    vector<Point> hull;
                    convexHull(contour, hull);
                    
                    // 计算中心点
                    Moments m = moments(contour);
                    if (m.m00 != 0) {
                        Point center(m.m10 / m.m00, m.m01 / m.m00);
                        
                        // 存储当前帧的凸包信息
                        frameBuffer[currentFrameIndex].emplace_back(hull, center, area, colorName);
                    }
                }
            }
        }
        
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
                
                // 如果稳定，则绘制凸包
                if (isStable) {
                    Scalar color = colorMap[currentHull.color];
                    
                    // 绘制凸包轮廓
                    vector<vector<Point>> hullContours = {currentHull.hull};
                    drawContours(displayFrame, hullContours, -1, color, 3);
                    
                    // 绘制中心点
                    circle(displayFrame, currentHull.center, 5, color, -1);
                    
                    // 添加标签
                    putText(displayFrame, currentHull.color, 
                           Point(currentHull.center.x - 20, currentHull.center.y - 10),
                           FONT_HERSHEY_SIMPLEX, 0.5, color, 2);
                }
            }
        }
        
        // 显示帧信息
        string frameInfo = "Frame: " + to_string(frameCount) + " | Stable hulls displayed";
        putText(displayFrame, frameInfo, Point(10, 30), FONT_HERSHEY_SIMPLEX, 0.7, Scalar(255, 255, 255), 2);
        
        // 显示结果
        imshow("Video Stream Analysis", displayFrame);
        
        // 更新帧索引
        currentFrameIndex = (currentFrameIndex + 1) % 5;
        
        // 处理按键
        char key = waitKey(1) & 0xFF;
        if (key == 27) { // ESC键
            cout << "\n用户按下ESC，退出程序..." << endl;
            break;
        } else if (key == 's' || key == 'S') { // 保存键
            string filename = "video_frame_" + to_string(frameCount) + ".jpg";
            imwrite(filename, displayFrame);
            cout << "💾 保存帧: " << filename << endl;
        }
        
        // 简单的帧率控制
        waitKey(33); // 约30FPS
    }
    
    // 清理资源
    cout << "\n5. 清理资源..." << endl;
    cap.release();
    destroyAllWindows();
    shape_detector_cleanup();
    cout << "✓ 程序结束" << endl;
    
    return 0;
}