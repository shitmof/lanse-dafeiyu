# 蓝色大肥鱼

能离开应用、在手机桌面和其他普通应用上方陪伴的 Android 桌宠。应用名「蓝色大肥鱼」，包名 `com.lanse.fish`，支持 Android 9 及以上。

基于原作者 **PC2005-cloud** 的 [dsh-pet 0.3.8](https://github.com/PC2005-cloud/dsh-pet/tree/593b9b0785f883630243ef6213542f4603ef69ad) 改版；手机端为原生 Android 实现。**代码 MIT；原版动画、提示词、源视频等素材另有禁止商用及二创署名要求**，不能把素材一概当作 MIT。详见[第三方来源与许可](THIRD_PARTY_NOTICES.md)。

## 当前进度

可下载正式版本仍为 **0.1.0**。0.1.1 的基础体验已合并到 `main`（[PR #1](https://github.com/shitmof/lanse-dafeiyu/pull/1)），并发布 **debug 测试预发布** [v0.1.1-test.1](https://github.com/shitmof/lanse-dafeiyu/releases/tag/v0.1.1-test.1)；它不是正式版，不能覆盖 0.1.0。实现记录：[实现与测试记录](docs/0.1.1/实现与测试记录.md)。原方案、回退与验收仍见[现版优化方案](docs/0.1.1/蓝色大肥鱼_现版优化方案与验收.md)、[需求要点与结论](docs/0.1.1/需求要点与结论.md)和[给 DeepSeek 的完整实施提示词](docs/0.1.1/交给DeepSeek的完整实施提示词.md)。**本机原发行签名密钥缺失，debug 候选不能覆盖安装 0.1.0；用户手机需卸载旧包或提供原密钥后再出正式同签名包。**

仓库源码与 0.1.0 Release 已按用户要求公开；显示名称统一“蓝色大肥鱼”。GitHub 仓库 URL 不支持中文，因此保留原地址，不另起英文产品名。公开不等于取消素材的禁止商用与署名限制。

## 安装

从本仓库的 [Releases](https://github.com/shitmof/lanse-dafeiyu/releases) 下载 `lanse-dafeiyu-0.1.0.apk`。旧版作为当前可用包与回退基线保留，上述已知问题尚未修复。请同时阅读 Release 附带的素材许可说明。

1. 在手机安装 APK，按系统提示允许此次安装来源。
2. 打开「蓝色大肥鱼」，点击「开始陪伴」，允许「显示在其他应用上层」。
3. 允许通知，方便随时从通知栏停止陪伴。需要长期运行时，在电池设置中允许后台运行；部分品牌还需要开启自启动。
4. 返回桌面或打开普通应用，拖动宠物调整位置，长按选择动作、聊天、余额或停止。

系统权限确认、锁屏、支付等主动禁止悬浮窗的界面可能隐藏宠物。Android 或手机厂商也可能终止后台进程；不承诺绕过系统安全界面或永久不可停止。

## 已包含

- 原版 **106 段完整动作**、**27 张表情包**，随 APK 离线提供，不需要常驻 DSH 网页。
- 透明原生动画，点按回应、长按菜单、拖动、甩动回弹、随机走动、屏幕边界限制。
- 吃饭和睡眠时段、随机日常动作、可关闭的碎碎念、熄屏暂停动画。
- 1–3 只宠物、尺寸、不透明度、固定位置、名字和人设设置。
- 离线固定回应；独立 AI 对话支持 OpenAI 兼容 Chat Completions 接口。
- AI 上下文轮数、本机最近 100 轮聊天、JSON 导出和清空；Android Keystore 加密保存密钥。
- DeepSeek 官方余额查询；通过 DSH 连接支持电脑端余额、气泡、动画点播和六档工作状态。
- 前台服务、通知停止入口、可选重启恢复，不申请无障碍、读屏或通知读取权限。

原版 640×360 视频转换为 480×270 透明 Animated WebP，保留完整动作时间线。包体约 250 MiB，主要来自完整离线动画。

### 联网配置

「设置 → 对话连接」选择独立 AI，填写服务根地址、模型名和你自己的 API 密钥，再点「保存并测试连接」。DeepSeek 默认地址 `https://api.deepseek.com`；OpenAI 兼容服务通常需要填写含 `/v1` 的根地址。只有你启用的请求会发送给该服务；自动 AI 碎碎念默认关闭。

连接 DSH 的方式见 [DSH 手机连接](docs/DSH_CONNECTION.md)。电脑要保持运行，并且手机能访问桥接服务；首次安装没有自动读取电脑密钥，也没有自动连上 QQ 机器人。

## 功能边界

这是一版可安装使用的手机桌宠。原版的 Electron 托盘、多显示器配置、DSH 插件安装管理、第三方插件菜单/占用锁/播放回执以及任意外部动画 URL，并未复制成 Android 本地功能。三只宠物共用手机上的人设和连接配置。电脑上的模型及任务由 DSH 执行，通过连接同步表现。

详见 [功能对应与验收](docs/ACCEPTANCE.md)，不以界面按钮存在代替真实连通验证。

## 本地构建

需要 JDK 17、Android SDK 35、Python 3.10+、FFmpeg（包含 libvpx-vp9 和 libwebp_anim）。

```powershell
python -m pip install Pillow==11.3.0 numpy
python scripts/prepare_assets.py
python scripts/verify_assets.py
.\gradlew.bat :app:assembleDebug :app:lintDebug
.\scripts\build-release.ps1
```

在 `local.properties` 配置自己的 `sdk.dir`，或通过 `ANDROID_HOME` 指定 SDK。下载固定上游提交 `593b9b0785f883630243ef6213542f4603ef69ad` 的素材并验证原视频 SHA-256。生成的动画与表情包不放入 Git，构建脚本会重新准备；已发布 APK 内包含全部资源。

签名脚本将个人发行证书存放在 `%LOCALAPPDATA%\BlueFatFish\signing\`；它是后续覆盖升级所必需的，请自行安全备份。证书、密码、连接令牌和本机配置均不提交到仓库。GitHub Actions 生成调试版供验证，不使用个人发行证书。

## 设备测试

在专用测试模拟器上安装 Debug APK 与 `app-debug-androidTest.apk`，授予悬浮窗和通知权限后执行：

```powershell
adb shell am instrument -w com.lanse.fish.test/com.lanse.fish.FishTestRunner
```

测试会修改测试应用的本机配置、聊天记录，并创建临时本机 HTTP 服务，请勿对已有真实聊天数据的手机执行。测试涵盖所有动画解码与透明通道、加密凭据、真实本机 HTTP 请求、错误处理、单只/多只悬浮窗生命周期和停止清理。

## 来源

- 改版来源：[dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，PC2005-cloud。代码 MIT；素材禁止商用且须保留原作者链接，详见[第三方说明](THIRD_PARTY_NOTICES.md)。
- Android 悬浮窗：[WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)。
- 前台服务：[specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)。
- DeepSeek：[余额接口](https://api-docs.deepseek.com/api/get-user-balance/)、[对话接口](https://api-docs.deepseek.com/api/create-chat-completion/)。
