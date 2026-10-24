# 0.1.1-test.2：左右边界间歇性提前停住修复记录

日期：2026-10-24。基线：`v0.1.1-test.1` / `main` 提交 `a1ab5a9`。开发分支：`deepseek/0.1.1-edge-clamp`。本记录只描述边界修复；游戏模式与自然行为另见 [v0.1.2 计划](../0.1.2/游戏模式与自然行为实施计划.md)。

## 1. 用户可见问题

- 多数时候能拖到左右边缘，但偶尔提前停住；快速拖拽更明显。
- 到边缘后松手，角色有时会自己向屏幕内部回弹。
- 换动作后，原本贴边的角色可能离开边缘。

## 2. 定位到的根因

### 2.1 甩动速度用错坐标系（主因）

旧实现使用 `VelocityTracker` + `MotionEvent` 的 **view-local** 坐标。为了修复窗口跟随滞后，拖动时宠物 view 会在手指下移动，局部坐标的 X 方向与手指在屏幕上的移动方向可能相反。结果：

- 快速向左甩到左边缘，速度却被算成 **正数（向右）**；
- `step()` 在左边缘把角色向右推，立即离开边缘；
- 向右甩同理，速度方向可能错误。

源码位置：`PetService.Pet.touch()` 的 `velocity.computeCurrentVelocity()`。修复：不再用 view-local VelocityTracker，改为记录 `getRawX()/getRawY()` 的最近 6 个样本，按事件时间计算屏幕速度。

### 2.2 裁切小窗口跟随滞后，触摸被系统取消

窗口会按动画可见范围改变宽高。窗口跟手移动有一帧左右的布局延迟；快速拖到边缘时，手指可能已经离开小窗口，Android 随后发送 `ACTION_CANCEL`，拖动在离边缘几十像素处停止。

修复：拖动开始（越过 8dp 阈值）时把窗口临时扩为全屏，只移动窗口内的图片内容；手指始终处于窗口内。松手/取消后恢复裁切窗口，并设置最终坐标。全屏载窗只存在于手指按下的拖动过程，不增加平时点击面积，也不改变长按菜单。

源码位置：`PetService.Pet.expandForDrag()`、`restoreAfterDrag()`、`touch()`。

### 2.3 动画换尺寸时没有“贴边意图”

旧 `play()` 只按身体锚点重算窗口坐标。新动画可见宽度不同，窗口边缘可能重新离开屏幕边；用户已贴边，但动作切换后出现空隙。

修复：每只宠物维护 `Edge.NONE/LEFT/RIGHT`。用户拖到边缘时记录；后续动画改变窗口大小时直接吸附到对应边缘。用户主动拖离边缘后清除。移动类动作在贴边时暂停主动位移，避免自动漫游把贴边角色带走。

### 2.4 甩动碰撞反弹

旧 `step()` 在越界时把速度乘 `-0.65`，快速甩到边缘会反弹回屏幕内。修复为：碰撞时只清零朝屏幕外的速度分量；朝屏幕内的正常惯性保留。这样仍有正常甩动，但不会在边缘反弹。

### 2.5 屏幕坐标系不够稳定

旧 `measureScreen()` 使用 `getDefaultDisplay().getSize()`。修复：

- Android 11+ 使用 `WindowManager.getCurrentWindowMetrics().getBounds()`；窗口坐标以当前 WindowMetrics 为准。
- Android 9–10 保留 `getSize()` 兼容路径。
- 记录 `WindowInsets`（状态栏/导航栏/刘海）用于诊断；不把 insets 直接从水平可移动范围扣除，因为该悬浮窗的坐标框架是 WindowMetrics 的边界，直接扣除反而会造成另一侧提前停住。
- 注册 `DisplayManager.DisplayListener`，并在配置变化后延迟 250ms 重测，避免旋转/导航模式切换时使用旧尺寸重建。
- 尺寸变化时保存锚点、边缘状态，再重建宠物。

源码位置：`PetService.measureScreen()`、`scheduleScreenRefresh()`、`onConfigurationChanged()`、`Pet` 构造器。

## 3. 改动文件

- `app/src/main/java/com/lanse/fish/PetService.java`
- `app/src/main/java/com/lanse/fish/MainActivity.java`（调试日志开关、版本号）
- `app/build.gradle`（versionCode 3 / 0.1.1-test.2）
- `scripts/edge_smoke_test.py`（新增交互回归脚本）
- `scripts/verify_assets.py`（新增 106 段 `visual` 边界校验）
- 本记录与 v0.1.2 计划文档

没有重写动画体系、没有迁移 Kotlin、没有删除 106 段/27 表情/工作状态/聊天/余额/DSH 代码。

## 4. 调试开关

设置页新增“拖动边界调试日志（仅内部测试）”。debug APK 且该开关开启时，用：

```bash
adb logcat -s FishEdge:D
```

记录：动作 ID/类别/visual 范围、屏幕与 WindowMetrics 边界、insets、lp 窗口坐标、拖动开始/展开/恢复、UP 原始坐标、边缘状态、速度、碰撞。日志不含密钥、令牌、聊天内容或通知内容；release 构建不输出（`FLAG_DEBUGGABLE` 判断）。

## 5. 测试结果

环境：MuMu 实例「蓝色大肥鱼 APK 测试」，Android 15 / API 35，屏 1600×900 / 900×1600；`com.lanse.fish`，versionCode 3。

| 测试 | 结果 |
| --- | --- |
| `assembleDebug` / `lintDebug` | 通过 |
| `scripts/verify_assets.py` | 通过：106 段动画、27 表情、24354 帧、哈希/透明/时长/visual 边界全部通过 |
| 单只宠物，自动播放开，全面屏手势导航，横屏 | 30 轮 × 左右两侧慢/快拖 = 60 次，`RESULT PASS` |
| 单只宠物，自动播放开，全面屏手势导航，竖屏 | 30 轮 × 左右两侧 = 60 次，`RESULT PASS` |
| 单只宠物，自动播放开，三键导航 | 20 轮 × 左右两侧 = 40 次，`RESULT PASS` |
| 贴边后等待自动动画 | 自动动作轮换期间窗口保持贴边；移动类动作在贴边时不把角色带走 |
| 长按菜单打开/关闭后拖动 | 通过；全屏载窗只在拖动移动阶段出现，菜单不再被全屏窗口影响 |
| 点击 | 通过；点击不展开全屏载窗，正常播放点击回应 |
| 切到桌面普通应用 | 通过；桌面/其他应用上仍可拖到两侧 |
| 横竖屏旋转 | 最终 APK 在 900×1600 与 1600×900 各做 5 轮（10 次）边界拖拽，`RESULT PASS`；配置变化后按新 WindowMetrics 重建，贴边保留 |
| 3 只宠物 | 3 个 overlay 窗口同时存在、无崩溃；自动边界脚本只跟踪单个窗口，三只逐只自动化边界未完成 |
| 真机 | **未执行**。只完成模拟器；不得把模拟器结果当作真机结论 |
| API 28/29 | **未执行**。仅编译兼容，未跑设备 |
| 完整 106 段逐段实机画面 | **未逐段截图**；已做 106 段元数据/alpha 边界校验，未逐段人工看窗口 |

### 失败的旧行为复现

在修复前，用同一测试脚本曾复现：左/右快速甩动后窗口停在离边 28–152 px 的位置；日志中 `vx=45` 而手指向左；`step hit=true edge=NONE`，即到边后立刻被错误速度带回内部。修复后同样的 60 次连续测试通过。

## 6. 构建产物

- 版本：`0.1.1-test.2`，versionCode 3。
- 本机路径：`output/candidate-0.1.1-test.2/blue-fat-fish-0.1.1-test.2-debug.apk`；262,569,582 字节；SHA-256 `78cc37780fc3da93e2bd97dd6c9d9218259aebc5e29e32fa6d700d187752c292`；debug 证书 SHA-256 `1f6a95c4786f85d1f39deddbf61d0bcc56beb2d9a59f1a3ca43aaf70b8a8391a`。
- 这是 **debug 签名**，不能覆盖 0.1.0/0.1.1-test.1；测试前需使用测试机或卸载旧测试包。正式同签名包仍需原发行 keystore。
- 不覆盖 `v0.1.0`、不覆盖 `v0.1.1-test.1`；不向 `main` 推送未验收更改。
- GitHub 测试预发布：https://github.com/shitmof/lanse-dafeiyu/releases/tag/v0.1.1-test.2
- 分支：`deepseek/0.1.1-edge-clamp`。

## 7. 已知边界与后续

1. 真机、API 28/29、厂商 ROM 未测；全屏导航/手势的实际系统版本差异仍需用户真机确认。
2. 全屏载窗只在拖动中短暂存在，不会改变日常点击面积；但拖动期间会暂时接管全屏触摸，松手即恢复。
3. 3 只宠物逐只边缘自动化未完成；现有代码每只独立维护 edge/velocity，需补测试。
4. 横竖屏最终构建各做 5 轮边界脚本（10 次）通过；未做每个动画逐段旋转截图。
5. 本次不实施游戏模式、自然行为状态机、语音/QQ 或包体压缩；对应计划见 [v0.1.2 计划](../0.1.2/游戏模式与自然行为实施计划.md)。
