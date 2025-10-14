# Shape Recognition NDK

一个用于Android系统的形状识别NDK库，可以检测图像中的几何形状（矩形、长矩形、三角形、圆形）并提供详细的形状信息，包括位置、颜色、方向角等。

## 功能特性

- **多种形状检测**: 支持矩形、长矩形、三角形、圆形的检测
- **颜色识别**: 识别红、绿、蓝、黄、青、洋红、黑、白等颜色
- **方向角计算**: 
  - 长矩形：计算连接短边中点的直线与垂直方向的夹角
  - 三角形：计算从顶点到底边的直线与垂直方向的夹角
- **JSON输出**: 提供结构化的JSON格式检测结果
- **图像标注**: 在原图上标注检测结果和方向线
- **C API接口**: 提供简洁的C语言接口，便于Android JNI调用

## 目录结构

```
shape_recognition_ndk/
├── README.md                    # 本文档
├── native/                      # 原生代码目录
│   ├── CMakeLists.txt          # CMake构建配置
│   ├── Android.mk              # ndk-build构建配置
│   ├── Application.mk          # 应用程序级别配置
│   ├── shape_detector.h        # 核心形状检测头文件
│   ├── shape_detector.cpp      # 核心形状检测实现
│   ├── shape_detector_c_api.h  # C API头文件
│   ├── shape_detector_c_api.cpp # C API实现
│   ├── test_shape_detector.cpp # 测试程序
│   └── example_usage.cpp       # 使用示例
└── examples/                    # 示例和测试图片
    └── test_images/
```

## 依赖要求

### 系统要求
- Android NDK r21或更高版本
- OpenCV for Android 4.5.0或更高版本
- CMake 3.18.1或更高版本（如果使用CMake构建）

### OpenCV配置
1. 下载OpenCV Android SDK
2. 设置环境变量 `OPENCV_ANDROID_SDK` 指向OpenCV SDK路径

## 编译方法

### 方法1: 使用CMake (推荐)

```bash
# 创建构建目录
mkdir build && cd build

# 配置CMake
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-21 \
      -DOpenCV_DIR=$OPENCV_ANDROID_SDK/sdk/native/jni \
      ../native

# 编译
make -j4
```

### 方法2: 使用ndk-build

```bash
# 设置环境变量
export OPENCV_ANDROID_SDK=/path/to/opencv-android-sdk

# 编译
cd native
ndk-build
```

## API 参考

### 数据结构

#### Point2f
```c
typedef struct {
    float x;
    float y;
} Point2f;
```

#### DetectedShape
```c
typedef struct {
    int id;                          // 形状ID
    ShapeType type;                  // 形状类型
    ColorType color;                 // 形状颜色
    Point2f center;                  // 中心位置
    float area;                      // 面积
    float aspect_ratio;              // 长宽比
    float orientation_angle;         // 方向角（度）
    Point2f direction_line_start;    // 方向线起点
    Point2f direction_line_end;      // 方向线终点
    char shape_code[8];              // 形状代码（如"RRE", "GLR"）
} DetectedShape;
```

#### DetectionResult
```c
typedef struct {
    DetectedShape* shapes;           // 检测到的形状数组
    int shape_count;                 // 形状数量
    int total_count;                 // 总数量
} DetectionResult;
```

#### ImageData
```c
typedef struct {
    uint8_t* data;                   // 图像数据（BGR格式）
    int width;                       // 图像宽度
    int height;                      // 图像高度
    int channels;                    // 通道数（BGR为3）
} ImageData;
```

### 核心API函数

#### 初始化和清理
```c
// 初始化形状检测器
bool shape_detector_init();

// 清理形状检测器
void shape_detector_cleanup();
```

#### 形状检测
```c
// 检测图像中的形状
DetectionResult* shape_detector_detect(const ImageData* image_data, bool debug);

// 释放检测结果内存
void shape_detector_free_result(DetectionResult* result);
```

#### JSON输出
```c
// 生成JSON格式的检测结果
char* shape_detector_generate_json(const DetectionResult* result);

// 释放JSON字符串内存
void shape_detector_free_json(char* json_str);
```

#### 图像标注
```c
// 在图像上标注检测结果
bool shape_detector_annotate_image(const ImageData* image_data, 
                                   const DetectionResult* result, 
                                   ImageData* output_data);

// 释放图像数据内存
void shape_detector_free_image(ImageData* image_data);
```

#### 工具函数
```c
// 获取版本信息
const char* shape_detector_get_version();

// 获取最后的错误信息
const char* shape_detector_get_last_error();
```

## 使用示例

### C语言示例

```c
#include "shape_detector_c_api.h"
#include <stdio.h>
#include <stdlib.h>

int main() {
    // 初始化
    if (!shape_detector_init()) {
        printf("初始化失败: %s\n", shape_detector_get_last_error());
        return -1;
    }
    
    // 准备图像数据（这里假设已经加载了图像）
    ImageData image_data = {
        .data = image_buffer,    // 你的图像数据
        .width = image_width,
        .height = image_height,
        .channels = 3
    };
    
    // 检测形状
    DetectionResult* result = shape_detector_detect(&image_data, false);
    if (!result) {
        printf("检测失败: %s\n", shape_detector_get_last_error());
        shape_detector_cleanup();
        return -1;
    }
    
    // 打印结果
    printf("检测到 %d 个形状:\n", result->shape_count);
    for (int i = 0; i < result->shape_count; i++) {
        DetectedShape* shape = &result->shapes[i];
        printf("形状 %d: 类型=%d, 颜色=%d, 中心=(%.1f,%.1f), 角度=%.1f°\n",
               shape->id, shape->type, shape->color,
               shape->center.x, shape->center.y, shape->orientation_angle);
    }
    
    // 生成JSON
    char* json = shape_detector_generate_json(result);
    if (json) {
        printf("JSON结果:\n%s\n", json);
        shape_detector_free_json(json);
    }
    
    // 清理
    shape_detector_free_result(result);
    shape_detector_cleanup();
    
    return 0;
}
```

### Android JNI示例

```java
public class ShapeDetector {
    static {
        System.loadLibrary("shape_detector_ndk");
    }
    
    // JNI方法声明
    public native boolean init();
    public native void cleanup();
    public native DetectionResult detect(byte[] imageData, int width, int height, boolean debug);
    public native String generateJson(DetectionResult result);
    public native byte[] annotateImage(byte[] imageData, int width, int height, DetectionResult result);
    
    // 使用示例
    public void detectShapes(Bitmap bitmap) {
        if (!init()) {
            Log.e("ShapeDetector", "初始化失败");
            return;
        }
        
        // 转换Bitmap为字节数组
        byte[] imageData = bitmapToByteArray(bitmap);
        
        // 检测形状
        DetectionResult result = detect(imageData, bitmap.getWidth(), bitmap.getHeight(), false);
        if (result != null) {
            // 生成JSON
            String json = generateJson(result);
            Log.i("ShapeDetector", "检测结果: " + json);
            
            // 标注图像
            byte[] annotatedData = annotateImage(imageData, bitmap.getWidth(), bitmap.getHeight(), result);
            if (annotatedData != null) {
                Bitmap annotatedBitmap = byteArrayToBitmap(annotatedData, bitmap.getWidth(), bitmap.getHeight());
                // 显示标注后的图像
            }
        }
        
        cleanup();
    }
}
```

## 形状代码说明

形状代码由颜色首字母和形状类型缩写组成：

### 颜色代码
- R: Red (红色)
- G: Green (绿色)  
- B: Blue (蓝色)
- Y: Yellow (黄色)
- C: Cyan (青色)
- M: Magenta (洋红)
- K: Black (黑色)
- W: White (白色)

### 形状代码
- RE: Rectangle (矩形)
- LR: Long Rectangle (长矩形)
- TR: Triangle (三角形)
- CI: Circle (圆形)

### 示例
- "RRE": 红色矩形
- "GLR": 绿色长矩形
- "BTR": 蓝色三角形
- "YCI": 黄色圆形

## 方向角计算

### 长矩形
- 计算短边中点连线与垂直方向的夹角
- 角度范围：0°-180°
- 0°表示垂直方向

### 三角形
- 计算从最高顶点到底边中点的直线与垂直方向的夹角
- 角度范围：0°-180°
- 0°表示垂直向下

## JSON输出格式

```json
{
  "shapes": [
    {
      "shape_code": "GLR",
      "id": 1,
      "position": {
        "x": 238,
        "y": 435
      },
      "orientation_angle": 98.76,
      "color": "Green",
      "type": "Long Rectangle",
      "area": 16658,
      "aspect_ratio": 3.23,
      "direction_line": {
        "start": {"x": 79.45, "y": 410.53},
        "end": {"x": 397.53, "y": 459.55}
      }
    }
  ],
  "total_count": 1
}
```

## 性能优化建议

1. **图像预处理**: 适当缩放输入图像以提高处理速度
2. **内存管理**: 及时释放检测结果和JSON字符串内存
3. **线程安全**: 在多线程环境中使用时注意线程安全
4. **错误处理**: 始终检查返回值和错误信息

## 故障排除

### 常见问题

1. **编译错误**: 确保OpenCV路径配置正确
2. **运行时崩溃**: 检查图像数据格式和内存管理
3. **检测结果不准确**: 调整颜色范围和形状检测参数
4. **性能问题**: 考虑图像预处理和参数优化

### 调试模式

启用调试模式可以获得更详细的检测信息：

```c
DetectionResult* result = shape_detector_detect(&image_data, true); // 启用调试
```

## 版本历史

- **v1.0.0**: 初始版本
  - 基本形状检测功能
  - C API接口
  - JSON输出支持
  - 图像标注功能

## 许可证

本项目采用MIT许可证，详见LICENSE文件。

## 贡献

欢迎提交Issue和Pull Request来改进这个项目。

## 联系方式

如有问题或建议，请通过以下方式联系：
- 项目Issues: [GitHub Issues](https://github.com/your-repo/shape_recognition_ndk/issues)
- 邮箱: your-email@example.com