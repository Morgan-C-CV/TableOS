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

// 创建颜色mask
Mat createColorMask(const Mat& image, const string& colorName) {
    Mat hsv, mask;
    cvtColor(image, hsv, COLOR_BGR2HSV);
    
    // 定义颜色范围 (调整后的范围)
    map<string, pair<Scalar, Scalar>> colorRanges = {
        {"Yellow", {Scalar(10, 20, 60), Scalar(65, 255, 255)}},  // 扩大黄色范围
        {"Green", {Scalar(40, 30, 60), Scalar(85, 255, 255)}},
        {"Cyan", {Scalar(75, 40, 70), Scalar(115, 255, 255)}},
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
    cout << "\n4. 开始实时识别..." << endl;
    cout << "控制说明:" << endl;
    cout << "  ESC - 退出程序" << endl;
    cout << "  空格 - 暂停/继续" << endl;
    cout << "  's' - 保存当前帧" << endl;
    cout << "===================" << endl;
    
    Mat frame;
    int frameCount = 0;
    bool paused = false;
    
    while (true) {
        if (!paused) {
            cap >> frame;
            if (frame.empty()) {
                cerr << "❌ 无法读取摄像头帧" << endl;
                break;
            }
            frameCount++;
        }
        
        // 显示原图
        imshow("Original", frame);
        
        // 创建并显示各颜色mask
        for (int i = 1; i < windowNames.size(); i++) {
            Mat mask = createColorMask(frame, colorNames[i]);
            imshow(windowNames[i], mask);
        }
        
        // 每30帧输出一次处理信息
        if (frameCount % 30 == 0) {
            cout << "已处理 " << frameCount << " 帧" << endl;
        }
        
        // 处理键盘输入
        char key = waitKey(1) & 0xFF;
        if (key == 27) { // ESC键
            cout << "\n用户按下ESC，退出程序..." << endl;
            break;
        } else if (key == ' ') { // 空格键
            paused = !paused;
            cout << (paused ? "⏸️ 暂停" : "▶️ 继续") << endl;
        } else if (key == 's' || key == 'S') { // 保存键
            string filename = "captured_frame_" + to_string(frameCount) + ".jpg";
            imwrite(filename, frame);
            cout << "💾 保存帧: " << filename << endl;
        }
    }
    
    // 清理资源
    cout << "\n5. 清理资源..." << endl;
    cap.release();
    destroyAllWindows();
    shape_detector_cleanup();
    cout << "✓ 程序结束" << endl;
    
    return 0;
}