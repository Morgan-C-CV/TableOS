# 在应用中集成 TableOS 的矩形透视变换（Keystone Warp）

本文档说明如何为任意 Android 应用添加 TableOS 支持的矩形透视变换，让应用的界面根据全局校正配置进行四点透视显示。内容涵盖依赖、布局改造、配置读取、绘制算法、日志与排查，以及常见问题处理。

## 背景与坐标约定

- 变换由四个归一化坐标点定义，按顺序：`左上(top-left)；右上(top-right)；右下(bottom-right)；左下(bottom-left)`。
- 每个点为 `x,y`，取值范围 `[0,1]`，表示相对于目标视图宽高的比例坐标。
- 全局配置通过 ContentProvider 暴露：
  - 显示矫正（桌面透视）：`content://com.tableos.app.keystone/config`
  - 相机输入最大可视区域：`content://com.tableos.app.keystone/input_region`
  
  两者均返回一条记录，字段 `value` 的格式统一为：`x0,y0;x1,y1;x2,y2;x3,y3`。

示例（单位归一化）：

```
0.02,0.03;0.98,0.02;0.97,0.97;0.03,0.98
```

## 依赖与准备

- 在模块 `build.gradle` 添加：

```
implementation "androidx.constraintlayout:constraintlayout:2.1.4"
```

- 确保 `minSdk` ≥ 21；如需 `Path.op` 差集用于外部黑边绘制，推荐 API 19+。

## 布局改造：用变形容器包裹根视图

将 Activity 的根布局替换为 `KeystoneWarpLayout`，并在其中放置原有内容容器。

XML 示例：

```
<com.tableos.settings.KeystoneWarpLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/keystone_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <FrameLayout
        android:id="@+id/content_container"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

</com.tableos.settings.KeystoneWarpLayout>
```

注意：如在主 `app` 模块集成，可使用对应包名的 `KeystoneWarpLayout`，保持类路径一致即可。

## 生命周期：加载并应用配置

在 Activity 的 `onResume`（或合适的时机）调用：

```
findViewById<com.tableos.settings.KeystoneWarpLayout>(R.id.keystone_root)?.loadConfig()
```

可选：监听配置变化（ContentObserver），在变化时重新 `loadConfig()`。

## 读取配置：ContentProvider 接口

读取 CSV：

```
val uri = Uri.parse("content://com.tableos.app.keystone/config")
context.contentResolver.query(uri, arrayOf("value"), null, null, null)?.use { c ->
    val csv = if (c.moveToFirst()) c.getString(0) else null
}
```

解析 CSV：

```
val parts = csv.split(";")
require(parts.size == 4)
val points = Array(4) { i ->
    val (sx, sy) = parts[i].split(",")
    Pair(sx.toFloat(), sy.toFloat()) // 归一化坐标
}
```

## 绘制算法与安全性

核心流程（伪代码，已在 `KeystoneWarpLayout` 中实现）：

```
// 1) 将归一化坐标转换为像素坐标（视图宽高可用之后）
fun toPx(p: Pair<Float, Float>) = Pair(
    p.first.coerceIn(0f,1f) * width,
    p.second.coerceIn(0f,1f) * height
)

// 2) 组装 src/dst 四点，构建透视矩阵
val src = floatArrayOf(0,0, w,0, w,h, 0,h)
val dst = floatArrayOf(p0.x,p0.y, p1.x,p1.y, p2.x,p2.y, p3.x,p3.y)
warpMatrix.reset()
warpMatrix.setPolyToPoly(src, 0, dst, 0, 4)

// 3) 生成四点多边形 Path 作为选区
path.reset(); path.moveTo(...); path.lineTo(...); path.close()

// 4) 安全性检查，避免黑屏：
// - 视图 size=0 时跳过
// - 多边形边界/凸性校验（点需在范围内，叉积符号一致）
// - 面积阈值（如 area > 1000）

// 5) 绘制策略（避免剪裁错配造成整屏黑）：
// - 先 concat(warpMatrix) 绘制子视图，保证内容可见
// - 再计算 fullRect - path 的差集，填充外部黑边
// - Path.op 失败则兜底：填充黑底后再重绘内容
```

要点：

- 在 `onSizeChanged` 时重建矩阵，以应对首次布局阶段 `width/height` 未就绪的问题。
- 若配置非法（越界、非凸、面积过小）或 `width/height=0`，回退为普通绘制，不应用变形。
- 外部黑边由差集路径绘制，避免整屏黑；如需统一背景色，可自定义 Paint。

## 日志与排查

建议在关键路径添加日志（已在示例类中使用 `Log.d/w/e`）：

- 加载：`loadConfig: csv=... parsed points=... size=WxH hasWarp=...`
- 矩阵：`buildMatrix: dst=[...] area=... hasWarp=...`
- 绘制：`dispatchDraw: applying warp draw`、`draw outside black border`、回退提示。

抓取日志：

```
adb logcat -c
adb logcat -s KeystoneWarpLayout SettingsActivity
```

## 常见问题

- 全屏黑：
  - 配置为空或解析失败（CSV 不符合四段格式）。
  - 视图 `width/height=0` 时尝试绘制；应在 `onSizeChanged` 后重建矩阵。
  - 剪裁坐标系不匹配；使用“先绘制内容，再绘制外部黑边”的策略可规避。

- 显示区域反转或内容被裁掉：
  - 点顺序错误；确保为 TL、TR、BR、BL。
  - 多边形非凸或点越界；检查凸性与边界。

- 未覆盖区域不是黑色：
  - 未绘制差集外部黑边；按本文绘制策略补齐。

- Provider 读取失败：
  - 检查 URI 拼写与权限；确保系统允许跨进程读取。

## 最佳实践与扩展

- 将 `KeystoneWarpLayout` 抽到公共库，供多个模块/应用复用。
- 在设置页保存时做强校验，避免写入越界或非凸的四点坐标。
- 若需实时联动，使用 `ContentObserver` 或广播在配置变更时刷新。

## 参考代码位置

- 变形容器：`settings/src/main/java/com/tableos/settings/KeystoneWarpLayout.kt`
- 布局使用示例：`settings/src/main/res/layout/activity_settings.xml`
- 生命周期加载：`settings/src/main/java/com/tableos/settings/SettingsActivity.kt`

## 验证与回归

- 打开目标应用页面，确认内容按四点透视显示，未覆盖区域为黑边。
- 更新配置（如在“桌面矫正”页保存），返回目标应用应与桌面一致。
- 若异常，按“日志与排查”章节逐项定位并修正。

## 在特定页面禁用变形

某些页面（例如桌面矫正、拍照取景、标定或开发者调试页）需要真实的、未变形的坐标系，否则会出现“二次变形”或坐标偏差。建议在进入这些页面时临时禁用根布局的变形，在退出时恢复。

实现要点：
- 根布局提供开关：`KeystoneWarpLayout.setWarpEnabled(Boolean)`；仅影响绘制阶段，不改动配置加载。
- 绘制判断：只有 `points != null && hasWarp && warpEnabled == true` 才应用透视矩阵；否则走普通绘制。
- 生命周期中设置开关：进入禁用、退出恢复；避免遗留关闭状态影响其他页面。

示例（桌面矫正页面）：

```kotlin
class DesktopCalibrationFragment : Fragment() {
    private lateinit var calibrator: KeystoneCalibratorView
    private var warpLayout: KeystoneWarpLayout? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 进入页面禁用变形，避免“二次型变”误差
        warpLayout = requireActivity().findViewById(R.id.keystone_root)
        warpLayout?.setWarpEnabled(false)
    }

    override fun onResume() {
        super.onResume()
        // 保守确保禁用仍生效（防外部误开）
        warpLayout = requireActivity().findViewById(R.id.keystone_root)
        warpLayout?.setWarpEnabled(false)
    }

    override fun onPause() {
        super.onPause()
        // 离开页面恢复全局变形能力
        warpLayout?.setWarpEnabled(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        warpLayout?.setWarpEnabled(true)
        warpLayout = null
    }
}
```

Activity 级替代方案：
- 在需要禁用的 `Activity` 的 `onResume()` 中调用 `setWarpEnabled(false)`，在 `onPause()` 恢复为 `true`。
- 不建议修改全局 `loadConfig()`；禁用应仅限绘制阶段，保持配置一致性。

最佳实践：
- 始终在退出页面时恢复为 `true`，避免影响其他页面。
- 与对话框、半透明 Fragment 叠加时，以顶层页面的行为为准；确保只在目标期间禁用。
- 建议增加日志：`setWarpEnabled: enabled=false/true`，便于排查状态错配。