# 夜莺 NightSound — 睡眠与鼾声记录（Android）

睡前点一下"开始监测"，手机放枕边；早上看一份**大白话的睡眠报告**：睡了多久、睡得稳不稳、打鼾重不重、夜里有没有疑似呼吸暂停。声音只在手机里分析，应用**没有联网权限**。

> **状态：早期版本（0.2.2）。** 还没有在大量真机上验证过。检测模型没有用真实卧室录音微调，评估只用了 ESC-50 加合成场景，详见下面的"已知限制"。夜莺**不是医疗器械**，不能诊断睡眠呼吸暂停或任何疾病。

*English: NightSound is a quiet Android sleep & snoring log. Put the phone by the pillow, get a plain-language report in the morning. An on-device sound model (Google YAMNet, pure Java, no native libs) recognises snoring; "suspected breathing pauses" are inferred from sudden silences inside a regular snoring run. No INTERNET permission, no account, no ads, no analytics. Early version, not a medical device. Apache-2.0.*

## 安装

1. 到本仓库的 **Releases** 页面下载 `nightsound-0.2.2.apk`（页面上附有 SHA-256 校验值）。
2. 在手机上打开 APK，按系统提示允许"安装未知应用"。个别手机的安全软件会对陌生 APK 报风险，因为它没有上架任何商店，这是正常现象。
3. 打开夜莺，按 5 步引导走：手机怎么放、允许麦克风和通知、设置后台不被关掉、试一下麦克风。

需要 Android 8.0（API 26）及以上。

## 它做什么

- **晨间看板**：一句话结论 + 评分；四张卡（睡了多久、打鼾、疑似呼吸暂停、睡得稳不稳）；全夜时间线（每 10 分钟一根柱子，点一下可回放当时的录音）；和最近几晚对比；白天困倦自评。
- **声音识别在手机上完成**：Google YAMNet（521 类）的纯 Java 实现；鼾声要连成规律的一串才确认；鼾声里突然安静 10 秒以上记为"疑似呼吸暂停"；磨牙检测是实验功能，可关闭。
- **隐私**：没有联网权限、没有账号、没有广告和统计；事件录音片段可关闭、到期自动删除；一键删除全部数据。详见 [隐私政策](docs/privacy-policy-zh.md)（[English](docs/privacy-policy-en.md)）。

## 结构

- `src/com/yehang/app/core/YamNet.java` + `assets/yamnet.bin`：YAMNet 的前向推理与权重（float16，7.5 MB，由 `ml/export_weights.py` 从官方 Keras 权重折叠 BatchNorm 后导出）。没有用 TFLite，APK 里没有原生库。
- `core/Analyzer.java`：检测流水线。能量门限 → 切段 → 模型判断 → 鼾声串确认 → 疑似呼吸暂停推断 → 磨牙（实验）。
- `core/Summary.java`、`core/ReportBuilder.java`、`core/Advice.java`：睡眠统计、评分，以及把一晚变成大白话看板的数据（不依赖 Android，可在桌面 JVM 上测试）。
- `assets/ui/`：界面，一个 WebView 里的本地网页（`index.html` / `app.css` / `ui.js` / `app.js`），通过 `Bridge.java`（`window.YH`）和系统交互。WebView 被锁死：不联网、不读任意文件、不跳转到其他页面。
- `MonitorService.java`：后台录音前台服务。`MainActivity.java`：WebView 外壳、权限、系统栏与亮度。
- `test/`：JVM 上运行的测试与评估（`FixtureGen` 用真实 Java 代码生成界面预览数据；ESC-50 等音频数据需自行下载）。
- `docs/`：隐私政策、状态说明、商店文案。`fastlane/metadata/`：F-Droid / IzzyOnDroid 用的商店元数据。

## 构建（不需要 Gradle）

需要：JDK 17+、`aapt2`、`dalvik-exchange`（dx）、`zipalign`、`apksigner`（Ubuntu: `apt install aapt dalvik-exchange zipalign apksigner`），以及 API 34 的 `android.jar`。

```
ANDROID_JAR=/path/to/android-34.jar ./build.sh
```

首次运行会在 `tools/` 下生成签名密钥（已被 `.gitignore` 忽略）。**正式发布请用 `KEYSTORE` / `KS_PASS` / `KS_ALIAS` 指定你自己的密钥并妥善保管**：以后每个版本都必须用同一把密钥签名，才能直接覆盖安装。

源码不使用 lambda 和 Kotlin（dx 不能脱糖），回调都写成匿名内部类。迁移到 Gradle 时可以放心改写。

## 已知限制

- 没有在真机上测试过整晚运行的情况；不同手机的麦克风增益、摆放位置、后台策略都会影响结果。
- 在 ESC-50 代理数据上：分钟级鼾声检出率 100%，但呼吸声等仍有误报（呼吸声约 42%）；合成场景里 ≥14 秒的暂停召回约 65%。
- YAMNet 的梅尔频谱从 125 Hz 起，很闷的低频鼾声它几乎看不见，程序有保底规则，但会漏。
- 疑似呼吸暂停靠"鼾声串里的长静默"推断，手机听不到气流和血氧，只能叫"疑似"。
- 磨牙检测没有真实磨牙录音验证过。
- 分贝是相对值，没有标定；睡眠分期由体动、屏幕与声音推测，没有心率，不含 REM。

**如果你愿意贡献真实的鼾声 / 磨牙 / 呼吸暂停录音（或脱敏后的检测结果），欢迎开 issue 聊聊。**

## 许可

[Apache License 2.0](LICENSE)。第三方成果见 [NOTICE](NOTICE)：YAMNet（Google，Apache-2.0）、Google Material Design 图标（Apache-2.0）。
