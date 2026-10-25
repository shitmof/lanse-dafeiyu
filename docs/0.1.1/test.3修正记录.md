# 0.1.1-test.3：test.2 验收失败后的最小修正记录

日期：2026-10-25。基线：`0.1.1-test.2`（commit `cb601d5`）。新分支：`deepseek/0.1.1-test.3`。版本：`0.1.1-test.3`，versionCode 4。**未合并 main，未覆盖任何已有 Release。**

## 1. 本轮修了什么

### 1.1 验收脚本：不再用 mAttrs 请求参数冒充实际 frame

旧 `scripts/edge_smoke_test.py` 从 `mAttrs`（请求布局参数）取坐标，没有核对实际 `frame`，也不按包名/窗口 token 过滤，因此全屏未恢复、请求 x=0 实际 inset 80px、其他应用小悬浮窗都能 PASS。这是已确认的假通过，不再把“220+ 次通过”作为完整触摸证据。

新脚本：

- 按 `com.lanse.fish` 包名和稳定 `Window{hash}` 跟踪同一窗口；
- 读取 `Frames: ... frame=[x,y][x2,y2]` 的实际 frame（兼容 `mFrame=Rect(...)`）；
- 断言松手后不是全屏载窗、实际窗口已到边、没有本应用全屏残留；
- ADB 非零退出直接失败；
- `--window-hash` 和 `--all` 支持多宠物逐窗口测试；
- 不再用“最小面积 overlay”猜宠物。

### 1.2 移除全屏拖动载窗，恢复小窗拖动 + Q弹

test.2 的 `expandForDrag()` 直接把 `PetView` 本身扩成全屏，再给整个 View 设置 translation。它同时改变窗口、布局尺寸、镜像枢轴和绘制位置；用户实测触摸不灵、Q弹感丢失。本轮已删除整个全屏载窗/translation/15 秒恢复路径。

现在：

- 拖动始终使用原来的裁切小窗，逐 MOVE 更新 `lp.x/lp.y`、clamp、update；
- 甩动速度仍用屏幕原始坐标样本，避免 view-local 符号反转；
- 恢复 0.1.0 的拖拽反馈：拖动阈值播放“被鼠标拖拽悬空反馈”，松手播放“原地漂浮踏步”；
- 恢复速度阻尼：非自动漫游阶段按 0.88/帧衰减；自动漫游保留约 4.5 秒原速；
- 增加独立按压 Q弹：ACTION_DOWN 时 `scaleY=0.95`（70ms），UP/CANCEL 恢复 1.0（120ms），与朝向 `scaleX` 分开；
- 松手后 drop 反馈不覆盖实际 fling 速度；
- 贴边/动画切换仍保留 `Edge.LEFT/RIGHT` 状态和吸附；贴边时移动类动作不自动把角色带走；
- 越界只清零朝屏幕外的速度，不向内回弹。

### 1.3 CANCEL / 自动续播

`onEnd()` 在 `dragging` 时设置 `interruptedEnd=true` 而不是直接丢失。CANCEL 时若当前片已在按住期间结束，会恢复自动调度；UP/CANCEL 都恢复 scaleY，隐藏菜单，不会留下全屏遮挡。

### 1.4 API 聊天诊断与真实试聊

- 保存地址时拒绝完整 `/chat/completions` 端点，提示填写服务根地址（含 `/v1` 的根地址允许）；不再静默拼成双 `/chat/completions`。
- “保存并测试连接”改名为“保存并检查地址与模型列表（不产生聊天）”，不再声称“模型可用/聊天可用”。
- 新增“发送真实测试消息（会产生一次模型请求）”：用户明确确认后调用与聊天相同的发送链路；不自动后台消耗额度。
- HTTP 非 2xx 时提取服务端限长/脱敏 `error.code/message`；区分 401/403、429、404、其他状态；不透传 Authorization/密钥/聊天正文。
- 聊天响应支持 `content` 字符串和文本数组；`reasoning_content` 不被当作正文；空正文带 `finish_reason` 提示；SSE/非 JSON 返回明确“仅支持非流式/协议不兼容”。
- 聊天页显示当前模式/主机/模型；失败在消息区保留错误卡片和“重试”，不再只弹短暂 Toast；输入草稿保留。
- 模型名/API 地址保存前校验；离线/DSH 模式不发起聊天。
- 真实测试与聊天均只在用户点击时请求，动画/工作姿态/余额不走聊天接口。

### 1.5 调试

保留“拖动边界调试日志”开关；debug 构建输出 `FishEdge` 动作、visual、WindowMetrics/insets、lp、实际 View 位置、边缘、速度、CANCEL/恢复；不含密钥/聊天。

## 2. 验证

### 2.1 已通过

| 项 | 结果 |
| --- | --- |
| `assembleDebug` / `lintDebug` | 通过 |
| 新 `edge_smoke_test.py` 实际 frame 单宠物 | 30 轮 × 左右慢/快 = 60 次，`RESULT PASS`；日志含 `before_actual/after_actual`，无全屏残留 |
| 点击 | 可命中，点击回应播放；不出现全屏载窗 |
| CANCEL 模拟（`input motionevent DOWN/MOVE/CANCEL`） | 小窗位置停在最后有效拖动位置，无全屏残留；自动链恢复 |
| 长按菜单/关闭后拖动 | 可操作；全屏载窗已移除，不存在菜单被全屏挡板影响 |
| 换动作/贴边 | 实际 frame 到左右边；贴边后自动动画保持贴边 |
| 3 只宠物 | 3 个窗口同时存在；`--all` 测试中 2/3 通过，第三只快速拖拽被**重叠的上一只宠物窗口**接收，脚本标记 FAIL；这是测试/重叠场景，不能算 3 只全部通过 |
| API 隔离夹具 | 正常回复、400 服务端错误详情、完整端点拒绝、`reasoningOnly` 空正文、content 数组、SSE 明确失败、离线均符合预期 |
| Android 实机 API UI（本地夹具） | 配置 AI→保存并检查模型列表→真实测试消息→聊天页发送，均成功；聊天页显示“夹具回复：聊天链路成功。” |\n| 聊天失败持久提示（本地 400 夹具） | 消息区显示“上次发送失败…HTTP 400…unsupported_parameter: max_tokens is not supported”，保留输入草稿和“重试”，不再只弹 Toast |
| 106 段/27 表情 | `verify_assets.py` 通过（哈希/alpha/时长/visual） |

### 2.2 未通过/未测

- **3 只宠物逐只完整边界测试未完成**（脚本已能按 token 跟踪，但默认位置重叠会使后一只拖动命中前一只；需要用户先拖开或测试前隐藏其他宠物）。
- **真机未测**：用户手机 iq15 的触摸、Q弹主观感受、API 真实服务、系统返回手势均未执行。
- **API 真实账户未测**：只使用本地 HTTP 夹具；不能据此断言用户的服务配置/密钥/模型已经修好。真实错误仍需用户复现后对应。
- **全屏系统手势**：未在用户手机上验证；不再依赖全屏载窗。
- **Q弹**：代码已恢复拖拽/松手动画和按压 scaleY，但尚未在用户手机上确认主观手感。
- **API 输入框键盘遮挡**：未在手机软键盘上逐项验证；`adjustResize` 保留。
- **正式 release 签名**：本机 `assembleRelease` 仍报 `release.jks` 不在 `signing.properties` 指向路径；没有生成新密钥。test.1/test.2/test.3 的 debug 证书相同（`1f6a95…`），debug 包可覆盖 test.2；0.1.0 release 证书不同（`5be09c…`），不能直接覆盖。

## 3. 构建产物

- `output/candidate-0.1.1-test.3/blue-fat-fish-0.1.1-test.3-debug.apk`
- 262,583,270 字节
- SHA-256 `9dd1fe55a1aedd0bec8e07b78fe35652e654b34e9bc6bdb9dda37cc255abc40e`
- 证书：Android Debug，SHA-256 `1f6a95c4786f85d1f39deddbf61d0bcc56beb2d9a59f1a3ca43aaf70b8a8391a`
- versionCode 4 / versionName 0.1.1-test.3

不覆盖 `v0.1.0`、`v0.1.1-test.1`、`v0.1.1-test.2`；不合并 main；不生成/替换签名密钥。

## 4. 真机必须复验

1. 中间/左/右各点击 10 次，确认每次有即时 Q弹/点击反馈。
2. 左右慢拖、快拖、移动动画镜像姿态；确认跟手、贴边、可再次拖离，拖动中不消失、松手不跳回起点。
3. 拖动中触发系统返回/熄屏/旋转/CANCEL；确认无全屏挡板、无自动播放永久停住。
4. 四角菜单打开/关闭后点下层应用，确认下层可点、菜单可操作。
5. 用真实 API 配置：保存→真实测试消息→聊天页发送；失败时提供脱敏错误原文，不提供密钥。
6. 3 只宠物先把彼此拖开，再逐只测边界；默认重叠时不应把后一只的拖动算在前一只上。

## 5. 参考

- test.2 历史记录：`docs/0.1.1/test.2边界修复记录.md`（其中 220+ 次通过依据本轮已判定不充分）
- v0.1.2 计划：`docs/0.1.2/游戏模式与自然行为实施计划.md`
- 原项目：https://github.com/PC2005-cloud/dsh-pet
