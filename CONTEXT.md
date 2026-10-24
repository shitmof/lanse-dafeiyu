# 蓝色大肥鱼项目上下文

- 本工程权威路径：`E:\ChatGPT实例\01_项目\应用工程\blue-fat-fish`。
- 与相邻 `手机APP`（同为）工程独立，不修改同为及其 Git 历史。
- 用户目标：Android 独立桌宠 APK，应用名「蓝色大肥鱼」，完整角色动画、跨普通应用悬浮、适配桌面端可用能力，并上传用户自己的 GitHub 私有仓库。
- Android 9+，原生 Java，Android SDK 35，Gradle 9.1.0 / AGP 9.0.1。
- 上游 `PC2005-cloud/dsh-pet` 0.3.8，固定提交 `593b9b0785f883630243ef6213542f4603ef69ad`。
- 本版功能和局限见 `README.md`、`docs/ACCEPTANCE.md`。不要宣称所有 Electron/DSH 插件功能已实现，也不要宣称能覆盖系统安全页面。
- 开发 Debug 与个人 Release 使用不同签名；首次从测试版换装发行版只可在专用模拟器卸载测试包。用户手机日后升级须保留发行证书，不得随意重建签名。
- 源码和资源清单入库；生成的动画、表情包、研究源码、APK 和本机签名不入 Git。发行 APK 上传私有仓库 Release。
