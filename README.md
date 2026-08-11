# VibeUsage — 液态玻璃风格 Token 用量客户端

一个为 [vibecafe.ai](https://vibecafe.ai/usage) 打造的 Android Token 用量统计客户端，
基于 **Jetpack Compose** 与 **液态玻璃（Liquid Glass）** 设计语言，拥有精致的折射、模糊与高光质感。

## ✨ 功能

- **用量概览**：总消耗（USD）、趋势百分比、Tokens / 模型 / 应用 / 会话统计
- **时间范围切换**：今日 / 24 小时 / 7 天 / 30 天 / 全部，液态玻璃折射滑块
- **应用分布 & 模型消耗**：Top 列表 + 官方品牌彩色 Logo（Claude、OpenAI、Gemini、DeepSeek、Qwen、Kimi、混元、GLM、MiMo 等，自动模糊匹配）
- **液态玻璃 UI**：GPU RuntimeShader 折射 / 模糊 / 高光、玻璃卡片、玻璃设置菜单
- **自定义背景**：从相册选图作为背景，重启保留
- **全局思源黑体**（Source Han Sans / Noto Sans SC），中英文统一
- **官网同款 V 字标**：启动器图标与应用内 Logo

## 🛠 技术栈

- Kotlin 2.2 + Jetpack Compose（BOM 2026.01.00）+ Material 3
- [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass) — 液态玻璃（RuntimeShader 折射）
- Retrofit / OkHttp / Gson — 网络请求
- minSdk 26 · targetSdk 34 · compileSdk 36

## 🚀 构建

环境要求：JDK 17、Android SDK（compileSdk 36）、Gradle 8.13（wrapper 已内置）。

```bash
./gradlew assembleDebug
```

APK 输出于 `app/build/outputs/apk/debug/app-debug.apk`。
也可用 Android Studio 直接打开本项目运行。

## 📲 安装使用

1. 安装 APK（可前往 [Releases](https://github.com/poying2018/VibeUsage/releases) 下载）
2. 首次打开，在玻璃登录页输入你的 VibeCafe API Key
3. 进入 Dashboard 查看用量

## 🔒 隐私说明

- API Key **仅保存在本机** `SharedPreferences`，不会写入代码或上传
- 应用只请求 `INTERNET` 与 `ACCESS_NETWORK_STATE` 权限
- 仅与 `vibecafe.ai` 官方接口通信

## 📁 项目结构

```
app/src/main/java/ai/vibecafe/usage/
├── MainActivity.kt          # 入口 / 登录页
├── core/                    # ApiKeyStore / BackgroundStore 本地持久化
├── data/                    # Retrofit 网络层（vibecafe.ai API）
├── stats/                   # 统计计算引擎
└── ui/
    ├── DashboardScreen.kt   # 主面板
    ├── MainViewModel.kt     # 状态管理
    ├── glass/               # 液态玻璃组件（背景/卡片/滑块/动画）
    └── theme/               # 设计令牌（颜色/排版/思源黑体）
```

## 📄 许可证

[MIT](LICENSE)
