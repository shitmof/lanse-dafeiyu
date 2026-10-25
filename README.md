# 蓝色大肥鱼

能离开应用、在手机桌面和其他普通应用上方陪伴的 Android 桌宠。应用名「蓝色大肥鱼」，包名 `com.lanse.fish`，支持 Android 9 及以上。

基于原作者 **PC2005-cloud** 的 [dsh-pet 0.3.8](https://github.com/PC2005-cloud/dsh-pet/tree/593b9b0785f883630243ef6213542f4603ef69ad) 改版；手机端为原生 Android 实现。**代码 MIT；原版动画、提示词、源视频等素材另有禁止商用及二创署名要求**，不能把素材一概当作 MIT。详见[第三方来源与许可](THIRD_PARTY_NOTICES.md)。

## 当前版本

正式版 **0.1.1**，Android 9+，完整离线动画。下载：[蓝色大肥鱼 0.1.1](https://github.com/shitmof/lanse-dafeiyu/releases/tag/v0.1.1)。修正、安装迁移、验证范围和回退见 [正式版说明](docs/0.1.1/正式版发布说明.md)。

应用和文档统一使用中文名「蓝色大肥鱼」。GitHub 仓库 URL 保留 `lanse-dafeiyu`；GitHub 不支持中文仓库路径。来源与代码、素材的不同许可继续保留。

## 安装与升级

1. 从上面的正式发布页下载 `蓝色大肥鱼-0.1.1.apk`。
2. **已安装 0.1.0 正式版：**直接覆盖安装，沿用原正式证书。
3. **已安装 test.1–test.5 debug 测试包：**先在旧应用「设置 → 导出聊天记录」保存到手机公共文件夹，再卸载测试包并安装正式版。进入「设置 → 导入已导出的聊天记录」恢复消息；API 密钥及个人设置需重新填写。不同证书无法直接覆盖，卸载会清除应用内部数据。
4. 打开「蓝色大肥鱼」并开始陪伴，允许「显示在其他应用上层」和通知。需要长期运行时，在系统电池设置中允许后台运行；部分手机还需开启自启动。
5. 返回桌面或普通应用：点击有 Q弹和回应，拖动换位置，长按打开紧凑菜单。在悬浮菜单中选动作会直接播放；「回到小窝」才打开主应用。

系统权限、锁屏及主动禁止悬浮窗的页面可能隐藏宠物。手机系统可以终止后台进程。本应用不申请无障碍、读屏、通知读取权限，也不会绕过系统限制。

## 已包含

- 原版 **106 段完整动作**、**27 张表情包**，随 APK 离线提供，不需要常驻 DSH 网页。
- 透明原生动画，点按回应、长按菜单、拖动、甩动回弹、随机走动、屏幕边界限制。
- 吃饭和睡眠时段、随机日常动作、可关闭的碎碎念、熄屏暂停动画。
- 1–3 只宠物、尺寸、不透明度、固定位置、名字和人设设置。
- 离线固定回应；独立 AI 对话支持 OpenAI 兼容 Chat Completions 接口。
- AI 上下文轮数、本机最近 100 轮聊天、JSON 导出、导入和清空；Android Keystore 加密保存密钥。
- DeepSeek 官方余额查询；通过 DSH 连接支持电脑端余额、气泡、动画点播和六档工作状态。
- 前台服务、通知停止入口、可选重启恢复，不申请无障碍、读屏或通知读取权限。

原版 640×360 视频转换为 480×270 透明 Animated WebP，保留完整动作时间线。包体约 250 MiB，主要来自完整离线动画。

### 联网配置

「设置 → 对话连接」选择独立 AI，填写服务根地址、模型名和你自己的 API 密钥，再点「保存并检查地址与模型列表」，随后按需点击「发送真实测试消息」。DeepSeek 默认地址 `https://api.deepseek.com`；OpenAI 兼容服务通常需要填写含 `/v1` 的根地址。模型列表通过不等于聊天已验证。若服务报长度参数不兼容，可切换「回复长度参数」为 `max_completion_tokens`；默认每次上限 1024 token，不自动重试付费聊天。只有你启用的请求会发送给该服务；自动 AI 碎碎念默认关闭。

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

签名脚本只使用已有的个人发行证书（绝不创建替代密钥），默认读取 `%LOCALAPPDATA%\BlueFatFish\signing\`；它是后续覆盖升级所必需的，请自行安全备份。证书、密码、连接令牌和本机配置均不提交到仓库。GitHub Actions 生成调试版供验证，不使用个人发行证书。

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
