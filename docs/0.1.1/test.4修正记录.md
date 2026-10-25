# 0.1.1-test.4：test.3 复核补修记录

日期：2026-10-25。基线：`deepseek/0.1.1-test.3`，commit `6af5a72`。新分支：`deepseek/0.1.1-test.4`。版本：`0.1.1-test.4`，versionCode 5。未合并 main；不覆盖 test.1/test.2/test.3 及任何 Release。

本页只记录 test.4 的补修。test.3 的历史结论若与本页冲突，以本页为准；test.3 页面已加更正说明。

## 1. 复核四项的修正

### R1 · CANCEL 状态收尾

问题：`playWhileDragging()` 设 `manualPlay=true`；拖拽反馈动画在按住期间结束后，`onEnd()` 只设 `interruptedEnd=true` 就返回；CANCEL 虽调度，但 `scheduleNext`/`autoTask` 仍被 `manualPlay` 挡住。

修正：

- `onEnd()` 在 `dragging` 时除设置 `interruptedEnd` 外，同时清 `manualPlay=false`。
- CANCEL 收到已结束动画时，统一清 `interruptedEnd`/`manualPlay` 再调度；不额外永久补定时器。
- 保留 `workHold`：工作保持为真时仍不自动换动作；`screenOn`、菜单、`auto_play` 条件不变。
- UP 路径重置 `interruptedEnd`，避免跨手势残留。

### R2 · 错误脱敏

问题：`httpError()` 只压缩空白、取 `error.message` 并截断；JSON/纯文本夹具都能把模拟 Authorization 值和请求文本回显到界面。属于“截断”，不是脱敏。

修正：

- `request()` 把本次 token 和请求 `JSONObject` 传入错误构造，统一在展示边界脱敏。
- 先屏蔽本次 token、`Bearer ...`、`sk-...`、`authorization/api_key/token/password/secret: value`。
- 再递归收集请求体里的字符串值（用户正文、人设、模型等），从错误消息中替换为 `[请求内容已隐藏]`，避免服务端回显聊天正文。
- 非 JSON/HTML 错误不再返回原始正文，只返回 HTTP 状态 + “正文已隐藏”。
- JSON 错误只取 `error.code/error.message`（或顶层 `code/message`），保留参数错误诊断，如 `unsupported_parameter`。
- 脱敏发生在 UI 显示和异常消息之前；没有把变量改名为 `safe` 了事。

隔离验证（虚构凭据，无真实账户）：`echoCredentialJson`、`echoCredentialText` 两个夹具的“密钥出现/请求文本出现”均为 `false`；正常聊天、400 参数错误、完整端点拒绝、空正文、SSE、离线结果仍符合预期。

### R3 · 拖拽坐标连续性

问题：DOWN 记录旧裁切窗口的 `startX/startY`；超过阈值切到拖拽反馈裁切后，MOVE 仍用旧起点覆盖新窗口位置，实际目录回放出现约 −29.8dp 水平偏移。

修正：

- 首次进入拖动、播放“被鼠标拖拽悬空反馈”后，立即把 `startX/startY` 重定到新裁切窗的 `lp.x/lp.y`，并把 `downX/downY` 重定到当前手指原始坐标。
- 后续 MOVE 只用重定后的起点；UP/CANCEL 也使用同一组起点，不再混用两套裁切原点。
- 保留屏幕原始坐标、镜像 `scaleX`、贴边和最终 clamp；动作切换只重算裁切，不要求前后窗口尺寸相同。

验证：实机模拟器从“凭空生花”进入拖动反馈，未再出现反馈后额外 30dp 跳位；这是代码/日志级检查，未做手机像素级轨迹测量。

### R4 · 失败卡片可见性

问题：错误卡片先插到历史前面，随后历史全部插入，最后滚到底部；长对话中错误卡片在顶部，看不见。

修正：

- 错误卡片改为插在历史之后、输入框之前，即消息区末尾。
- 仍保留草稿和“重试”；发送成功后清 `chatError`。
- `fullScroll(DOWN)` 显示的是最新错误卡片。

验证：test.4 在 400 错误后，错误卡片出现在消息区底部（y≈535），输入草稿（y≈717）仍在；未完成“超过两屏历史 + 软键盘”的独立长历史用例。

### R5 · 边缘验收器反例

新增约束：

- `inside`：实际 frame 必须在显示区域内（允许 2px 误差），完全移出屏幕判 FAIL。
- `small`：宽 ≤ 0.98×屏宽、高 ≤ 0.88×屏高、面积 ≤ 0.55×屏幕面积；覆盖可用区的大窗（如 `[0,80][1080,2320]`）判 FAIL，同时仍允许合法的宽而短的动画裁切。
- 保留实际 `frame`、包名、稳定 token、ADB 非零退出失败、无全屏残留检查。
- 多宠物测试增加“触点是否被更高 z-order 的同应用窗口覆盖”检查；被覆盖时直接 FAIL，不把别的窗口收到拖动算成功。

用 Codex 的反例脚本重跑：原来 3 个反例仍 FAIL；新增“可用区大窗左/右”“完全越界左/右”均按预期 FAIL；合法左右贴边 PASS。单宠物实际设备（模拟器）30 轮 × 左右慢/快 = 60 次 PASS。

## 2. 测试证据

| 项 | 结果 |
| --- | --- |
| `assembleDebug` / `lintDebug` | 通过 |
| 边缘实际 frame（单宠物） | 60 次 PASS；无全屏残留 |
| 反例夹具 | 9 个场景全部 `matches_expectation=true`（合法左右 PASS；3 个旧反例 + 4 个新增反例 FAIL） |
| 长按拖动→CANCEL | 25 秒按住后 CANCEL，自动后续恢复；日志有后续动作 |
| 点击/长按菜单/贴边 | 可操作；全屏载窗已删除 |
| API 隔离 | 正常、400 参数错误、完整端点拒绝、空正文、content 数组、SSE、离线；JSON/纯文本凭据回显均未泄漏 |
| API UI | 120 条历史（超过两屏）下发送失败，只滚出最后两条历史，错误卡片位于消息区底部、草稿保留、重试可见；软键盘展开未测 |
| 106 段/27 表情 | 资产校验沿用 test.3；本轮未改动画资产 |

## 3. 未通过/未测

- **用户 iq15 真机未连接**：触摸跟手、Q弹主观手感、系统返回手势、真实 API 服务均未在此环境验证。
- **真实 API 账户未测**：只用本地 HTTP 夹具；400 参数错误仍需用户真实服务复现。
- **三宠物逐只完整边界未完成**：验证器已能识别覆盖目标窗口并判 FAIL，但没有把三只都测到通过。
- **长历史 + 软键盘展开**未独立完成：已用 120 条历史验证底部错误卡可见，但未在软键盘弹出时逐项验证。
- **正式 release APK 未构建**：本机 `:app:validateSigningRelease --no-daemon --rerun-tasks` 仍报 `release.jks` 路径不存在；Codex 的 fresh log 显示该验证曾通过。没有生成/替换密钥，也没有正式签名包。
- 动作切换的 30dp 误差已从代码路径移除，但未做手机像素级测量。

## 4. 构建产物

- `output/candidate-0.1.1-test.4/blue-fat-fish-0.1.1-test.4-debug.apk`
- 版本：versionCode 5 / 0.1.1-test.4
- 签名：Android Debug（与 test.1/test.2/test.3 相同，可覆盖 test.2/test.3；不能覆盖 0.1.0 release）
- 262,437,629 字节；SHA-256 `46376cb5382d17e5e63363be6be9b2e79ecf4aa45cb80b8622b3c7a24ea85b21`。

## 5. 真机复验

1. 多种动作（含蓝鲸现世、凭空生花等宽/高裁切）起拖，确认无 30dp 跳位。
2. 按住拖动超过反馈动画时长，再 CANCEL；确认自动恢复，不是永久停住。
3. 长历史下发送失败，确认底部错误卡片、草稿、重试可见。
4. 用真实 API 触发错误，确认错误摘要不含密钥/令牌/聊天正文；不要把错误卡片整段当公开证据。
5. 三宠物先拖开再逐只测；不要用默认重叠位置代替逐只。

## 6. 参考

- test.3 历史记录：`docs/0.1.1/test.3修正记录.md`（已加更正）
- test.2 历史记录：`docs/0.1.1/test.2边界修复记录.md`
- 复核报告（本机 output，未提交）：`output/review-test3/`
