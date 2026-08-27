# VibeUsage — 液态玻璃风格 Token 用量客户端

一个为 [vibecafe.ai](https://vibecafe.ai/usage) 打造的 Android Token 用量统计客户端，
基于 **Jetpack Compose** 与 **液态玻璃（Liquid Glass）** 设计语言，拥有精致的折射、模糊与高光质感。

## ✨ 功能

- **用量概览**：总消耗（USD，数字滚动动效）、趋势百分比（当前范围 vs 等长上周期）、月度消耗预测、Tokens / 模型 / 应用 / 会话统计
- **时间范围**：今日 / 24 小时 / 7 天 / 30 天 / 90 天 / 全部，外加**自定义日期范围**（液态玻璃日历面板，服务端 from/to 查询）
- **数据可视化**：用量趋势平滑面积图（日/小时粒度，逐点波浪入场 + 数据点回弹落位）、金额/Tokens 双指标玻璃滑块切换、均值虚线、峰值标注、整数刻度轴、**点按/拖动查看任意点数值气泡**；应用占比环形图、模型消耗行内进度条
- **桌面小组件**：2x2 今日消耗速览（Glance），每 30 分钟 + App 刷新时同步，点击直达 App
- **分享卡片**：玻璃风格总结图（1080x1350），一键分享到社交平台
- **预算提醒**：按日均预测整月消耗，超过预算阈值（默认 $500）发本地通知
- **多设备筛选**：按主机名筛选全部统计数据
- **暗色模式**：深色液态玻璃主题 + **AMOLED 纯黑**变体，跟随系统 / 浅色 / 深色 / 纯黑四种外观，即时生效并持久化
- **丰富动效**：卡片与列表分段入场、总消耗数字滚动、液态玻璃滑块 Q 弹折射、光斑流动背景（暗色下自发光）、骨架屏加载、长按/切换触感反馈
- **应用分布 & 模型消耗**：Top 列表 + 官方品牌彩色 Logo（Claude、OpenAI、Gemini、DeepSeek、Qwen、Kimi、混元、GLM、MiMo 等，自动模糊匹配），长按查看应用/模型细分详情（含缓存命中率）
- **液态玻璃 UI**：GPU RuntimeShader 折射 / 模糊 / 高光、玻璃卡片（顶部水晶高光）、玻璃设置菜单
- **自定义背景**：从相册选图作为背景，重启保留
- **安全更新**：应用内检查更新 + 下载 SHA-256 校验
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

> 注意：项目路径包含非 ASCII 字符（如 `F:\项目`）时，`gradle.properties` 已加入
> `android.overridePathCheck=true` 放行 AGP 路径检查。

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
├── MainActivity.kt          # 入口 / 登录页 / 主题切换
├── core/                    # ApiKeyStore / BackgroundStore / ThemeStore 本地持久化
├── data/                    # Retrofit 网络层（vibecafe.ai API）
├── stats/                   # 统计计算引擎（含单元测试）
├── share/                   # 玻璃风格总结分享卡
├── budget/                  # 月度预算超支本地通知
├── widget/                  # 桌面小组件（Glance + WorkManager 同步）
└── ui/
    ├── DashboardScreen.kt   # 主面板（时间选择器 / 图表 / 动效 / 设置菜单）
    ├── MainViewModel.kt     # 状态管理（后台线程统计计算）
    ├── ToolIcons.kt         # 品牌 Logo 模糊匹配
    ├── anim/                # 数字滚动 / 分段入场动效
    ├── charts/              # 趋势面积图 / 占比环形图
    ├── glass/               # 液态玻璃组件（背景/卡片/滑块/动画）
    └── theme/               # 亮暗纯黑三主题设计令牌（GlassPalette）/ 排版 / 思源黑体
```

## 📄 许可证

[MIT](LICENSE)
