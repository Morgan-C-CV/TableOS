Android NDK 功能包（Projection Cards）

概述
- 提供点卡检测与编码解码的原生实现（OpenCV），用于在 Android App 中实时识别卡片并获取其 ID 与包围盒。
- 该目录可直接拷贝到你的 Android 项目中，通过 CMake 构建出共享库 `projectioncards` 并在 JNI 中调用。

目录结构
- 头文件：`detect_decode_api.h`、`card_encoder_decoder_c_api.h`、`dot_card_detect.h`、`image_processing.h`
- 源码：`detect_decode_api.cpp`、`card_encoder_decoder_c_api.cpp`、`card_encoder_decoder.cpp`、`dot_card_detect.cpp`、`image_processing.cpp`
- 构建：`CMakeLists.txt`（生成共享库 `projectioncards`）

依赖与环境
- Android NDK（建议 r25 及以上）
- OpenCV Android SDK（需提供 `OpenCV_DIR`）
- 最低 API/ABI：根据你的 App 目标设定；请在 Gradle 的 `abiFilters` 中添加所需架构（例如 `arm64-v8a`、`armeabi-v7a`）。

集成步骤（Android App）
1) 拷贝目录：
   - 将 `android_ndk_package/native` 拷贝到应用模块，例如：`app/src/main/cpp`。

2) Gradle 连接 CMake（示例）：
   - 在 `app/build.gradle` 中：
     ```groovy
     android {
       defaultConfig {
         externalNativeBuild {
           cmake {
             // 传递 OpenCV 的路径（替换为你的实际路径）
             arguments "-DOpenCV_DIR=/absolute/path/OpenCV-android-sdk/sdk/native/jni"
           }
         }
         ndk {
           abiFilters "arm64-v8a", "armeabi-v7a"
         }
       }
       externalNativeBuild {
         cmake { path "src/main/cpp/CMakeLists.txt" }
       }
     }
     ```

3) CMake 配置（示例）：
   - 在 `app/src/main/cpp/CMakeLists.txt` 中确保引入本包的源码或把本目录作为子工程；本包自带的 `CMakeLists.txt` 会生成 `projectioncards`：
     ```cmake
     # 假设已设置 OpenCV_DIR
     find_package(OpenCV REQUIRED)
     add_subdirectory(${CMAKE_SOURCE_DIR}/src/main/cpp) # 或将本目录纳入
     # 最终在目标应用中链接
     target_link_libraries(your_native_target PRIVATE projectioncards ${OpenCV_LIBS})
     ```

4) 在 App 中加载与调用（JNI/Java 示例）：
   - 加载库：
     ```java
     static {
       System.loadLibrary("projectioncards");
     }
     ```
   - 定义与调用原生方法：
     ```java
     // 与 C API 的结构保持一致
     public static class DetectedCard {
       public int card_id;    // -1 表示未解码
       public int group_type; // 0=A,1=B,-1=未知
       public int tl_x, tl_y, br_x, br_y; // 包围盒（左上/右下）
     }

     // NV21 输入（Camera2 通常产出此格式）
     public static native int detectDecodeCardsNV21(byte[] nv21, int width, int height,
                                                    DetectedCard[] outCards, int maxOutCards);

     // BGR8 输入（如你自己做颜色转换）
     public static native int detectDecodeCardsBGR8(byte[] bgr, int width, int height,
                                                    DetectedCard[] outCards, int maxOutCards);
     ```
   - 对应的 C API（来自 `detect_decode_api.h`）：
     ```c
     typedef struct {
       int card_id;
       int group_type; // 0=A,1=B,-1=unknown
       int tl_x, tl_y, br_x, br_y; // bounding box
     } DetectedCard;

     int detect_decode_cards_nv21(const unsigned char* nv21, int width, int height,
                                  DetectedCard* out_cards, int max_out_cards);

     int detect_decode_cards_bgr8(const unsigned char* bgr, int width, int height,
                                  DetectedCard* out_cards, int max_out_cards);
     ```
   - 运行流程建议：
     - 从 Camera2 获取 NV21 帧，开线程调用 `detectDecodeCardsNV21`。
     - 返回的 `DetectedCard` 中 `card_id` 为解码到的卡片 ID（未解码则为 -1），`group_type` 表示 A/B 组别。
     - 使用 `tl_x, tl_y, br_x, br_y` 在画面上绘制包围框或进行后续业务处理。

技术细节说明
- 检测与解码管线：
  - 先通过 OpenCV 的轮廓与几何规则在图像中寻找候选矩形（角点标记），再将四个角点配对成一张卡片。
  - 解码时会在扩展区域内统计角点颜色，生成编码比特，使用 `card_encoder_decoder_c_api.h` 中的解码器验证并得到 `card_id` 与 `group_type`。
  - C API 返回的是卡片包围盒与 ID；如需完整几何与角度，可用 CLI 查看（见下文）。
- 颜色索引与含义：0=Red，1=Yellow，2=Green，3=Cyan，4=Blue，5=Indigo（内部已考虑红色的双阈值）。
- 性能建议：
  - 将检测调用放在单独线程，尽量复用缓冲区；移动端上建议 NV21→BGR 转换由本库完成（更少拷贝）。
  - `max_out_cards` 建议根据你的应用场景设置（例如 8），超过值的检测结果将被截断。

调试与 CLI 输出（可选）
- 本包提供示例 CLI `detect_decode_cli`（在桌面或开发机上构建）用于可视化与 JSON 输出：
  ```bash
  ./detect_decode_cli path/to/image.png
  ```

- CLI 输出格式说明：
  1. **检测过程信息**：显示颜色检测、角点识别等详细过程
  2. **卡片统计**：`Detected cards: N` 显示检测到的卡片数量
  3. **卡片信息**：每张卡片的ID、组别、包围盒和颜色信息
     ```
     #0 id: 141 group: 0 bbox: [13,14,264,387] colors: (-1,-1,-1,-1)
     ```
  4. **角点颜色信息**：每个矩形的四个角点颜色索引（0=Red，1=Yellow，2=Green，3=Cyan，4=Blue，5=Indigo，-1=未识别）
     ```
     Rectangles corner colors:
     Rect1:
       Corner1: 0
       Corner2: 1
       Corner3: -1
       Corner4: 0
     ```
  5. **JSON 输出**：完整的矩形几何信息

- **Rectangles JSON 格式**：
  - **四角点卡片**（完整检测）：
    - 角点顺序为 `Corner1=TL, Corner2=TR, Corner3=BR, Corner4=BL`，`center` 为中心点
    - 方向角 `angle` 使用"左下到左上（BL→TL）的向量"相对垂直方向的偏移角度，逆时针为正、顺时针为负
    ```json
    {
      "Rect1": {
        "id": 141,
        "posi": {
          "Corner1": [1271, 345],
          "Corner2": [1772, 572],
          "Corner3": [1434, 1316],
          "Corner4": [933, 1089],
          "center": [1352, 830]
        },
        "angle": -24.432,
        "direction": -24.432
      }
    }
    ```
  
  - **单角点卡片**（新增功能）：
    - 当只检测到单个角点时，系统会基于角点的边界框生成完整的矩形输出
    - 四个角点坐标基于边界框计算，角度固定为 0.0
    ```json
    {
      "Rect1": {
        "id": 141,
        "posi": {
          "Corner1": [13, 14],
          "Corner2": [263, 14],
          "Corner3": [263, 386],
          "Corner4": [13, 386],
          "center": [138, 200]
        },
        "angle": 0.000,
        "direction": 0.000
      }
    }
    ```

- **输出特性**：
  - 支持混合输出：同一次检测可能包含完整的四角点卡片和单角点卡片
  - 单角点处理：确保即使只检测到部分角点也能输出完整的矩形信息
  - ID 一致性：单角点使用原始角点的 ID，保持数据的连续性
  - 该 CLI 输出仅用于调试与验证几何；Android 端默认通过 C API 获取卡片 ID 与包围盒

常见问题
- OpenCV 找不到：请确认 `OpenCV_DIR` 指向 `OpenCV-android-sdk/sdk/native/jni`，并在 CMake 中 `find_package(OpenCV REQUIRED)`。
- ABI/架构不匹配：在 `abiFilters` 中加入目标架构；确保第三方库（如 OpenCV `.so`）同样包含这些架构。
- 返回 `card_id=-1`：说明此帧未成功解码（颜色识别不足或角点不完整），可提高拍摄分辨率与曝光、减少运动模糊。
- 帧格式：若不是 NV21，请自行转换为 BGR8 后调用 `detect_decode_cards_bgr8`。

许可与扩展
- 本包不包含 Android UI；你可以在 Java/Kotlin 层自由绘制 Overlay 或结合业务逻辑。
- 如需在 Android 端直接拿到角点坐标与角度，可在 JNI 层补充接口，调用 `dot_card_detect` 产生的卡片几何并以自定义结构返回。