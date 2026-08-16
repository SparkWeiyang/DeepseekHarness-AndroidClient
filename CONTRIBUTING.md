# Contributing

感谢你的贡献！以下是参与本项目的指引。

## 环境要求

- JDK 17+（推荐 Android Studio 自带 JBR 21）
- Android SDK（`compileSdk 36`）
- Android Studio（可选，含 Gradle）

## 构建与调试

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\spark\AppData\Local\Android\Sdk'
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

- 协议实现依据 [`docs/wire-protocol.md`](docs/wire-protocol.md)，改动协议相关代码前请先核对。
- 无真机时可用 Android Studio 模拟器（需 `adb reverse tcp:3080 tcp:3080` 或 `10.0.2.2:3080` 指向宿主 harness）。

## 代码风格

- Kotlin + Jetpack Compose，Material 3，遵循官方风格。
- UI 组件分文件放在 `ui/`，每个 Tab/屏幕一个文件；网络/数据分层放 `net/`、`data/`。
- 提交信息用中文或英文均可，建议 `feat:` / `fix:` / `docs:` 前缀。

## 提交流程

1. Fork 本仓库并创建特性分支。
2. 本地验证构建通过（`assembleDebug`）。
3. 发起 Pull Request，描述改动与动机。

## 关联项目

- 上游：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
- 配套插件：[SparkWeiyang/dsh-lan](https://github.com/SparkWeiyang/dsh-lan)
