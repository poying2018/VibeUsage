# VibeUsage — 液态玻璃风格 Token 用量客户端

一个 Android 原生 App：既统计 [vibecafe.ai](https://vibecafe.ai/usage) 的 Token 用量，
也能在一个面板里聚合 **13 家 AI 供应商的订阅额度**（含 Antigravity 独立面板）。
基于 **Jetpack Compose** 与 **液态玻璃（Liquid Glass）** 设计语言。

当前版本 **2.21.2**（versionCode 69）· MIT

## ✨ 核心功能

### 用量概览（vibecafe.ai）

- 总消耗（USD，数字滚动动效）、趋势百分比（当前范围 vs 等长上周期）、月度消耗预测
- Tokens / 模型 / 应用 / 会话四维统计
- 6 档时间范围（今日 / 24 小时 / 7 天 / 30 天 / 90 天 / 全部）+ **自定义日期范围**
- 趋势平滑面积图、金额/Tokens 双指标切换、点按拖动查看任意点数值气泡
- 应用占比环形图、模型消耗行内进度条、品牌彩色 Logo 自动模糊匹配
- 桌面小组件（Glance 2x2，每 30 分钟 + App 刷新时同步）
- 玻璃风格总结分享卡（1080x1350）

### 额度聚合（13 家供应商 + Antigravity）

| 类别 | 供应商 |
|---|---|
| 凭据型 | 小米 MiMo、阿里百炼、火山方舟、豆包、智谱 GLM、Kimi、MiniMax、无问芯穹、DeepSeek、Agnes、CommandCode |
| 一键授权 | GitHub Copilot（设备码）、OpenRouter（OAuth） |
| 独立面板 | Antigravity（Google 反重力，OAuth refresh_token） |

- 按时间窗口分组展示（5 小时滚动窗口 / 每周窗口），显示剩余百分比、已用比例、重置倒计时
- 每个供应商附**接入指引文案**（去哪拿凭据、填什么），凭据类型不同（单 Key / AK-SK 双凭据 / Cookie / 账号密码）
- 各家实际可用模型列表（走账号维度接口，拿不到则隐藏模型区，不影响额度展示）
- 凭据备份/恢复：**口令加密**（PBKDF2-HMAC-SHA256 派生密钥 + AES-256-GCM），口令不写入文件、不上传，备份文件离开设备也解不开；兼容旧版明文 / 内置密钥备份的导入

### 其他

- 预算提醒：按日均预测整月消耗，超阈值发本地通知（阈值可配置）
- 设备筛选、按 tokens / 费用 / 名称排序、最近会话列表
- 用量卡分享 / 保存到系统相册（MediaStore，免权限）
- 多设备筛选、暗色 / AMOLED 纯黑主题（跟随系统 / 浅色 / 深色 / 纯黑四种）
- 自定义背景（相册选图，重启保留）
- 液态玻璃参数可调：亮度 / 对比度 / 饱和度 / 模糊 / 白色叠加，实时全局生效
- 应用内检查更新 + 下载 SHA-256 校验
- 全局思源黑体（Source Han Sans / Noto Sans SC）

## 🛠 技术栈

- Kotlin 2.2 + Jetpack Compose（BOM 2026.01.00）+ Material 3
- [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass) — 液态玻璃（GPU RuntimeShader 折射），vendor 自 2.0.1
- Retrofit / OkHttp / Gson
- Glance（小组件）+ WorkManager（定时同步）
- minSdk 26 · targetSdk 34 · compileSdk 36

## 🚀 构建

环境要求：JDK 17、Android SDK（compileSdk 36 + build-tools 36.0.0）、Gradle 8.13（wrapper 已内置）。

```bash
./gradlew assembleDebug      # 调试包 → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest   # 全部单元测试
./gradlew assembleRelease    # 发布包（强制签名）
```

Maven 依赖已配置阿里云镜像（`settings.gradle.kts`），Gradle 发行版走腾讯云镜像
（`gradle/wrapper/gradle-wrapper.properties`，带 `distributionSha256Sum` 校验）。

> - 项目路径包含非 ASCII 字符时，`gradle.properties` 已加 `android.overridePathCheck=true` 放行 AGP 路径检查。
> - `app/build.gradle.kts` **强制签名**：缺少 `keystore.properties` 会直接构建失败，绝不产出未签名 APK。
> - 发布走 `publish.ps1`：版本号自增 → 签名构建 → 验签 → 拷贝 `dist/` → 提交推送 → 创建 GitHub Release。

## 📲 安装使用

1. 安装 APK（可前往 [Releases](https://github.com/poying2018/VibeUsage/releases) 下载）
2. 首次打开，在玻璃登录页输入你的 VibeCafe API Key
3. 进入 Dashboard；切到「额度」页可接入各家供应商

## 🔒 隐私说明

- 所有凭据（VibeCafe Key、供应商 API Key、Cookie）**仅保存在本机** SharedPreferences
- 只请求 `INTERNET` / `ACCESS_NETWORK_STATE` / `POST_NOTIFICATIONS` / `REQUEST_INSTALL_PACKAGES`
- 只与对应厂商官方域名通信；部分境外/中转场景走内置 Cloudflare Worker 中转

## 📁 项目结构

```
app/src/main/java/ai/vibecafe/usage/
├── MainActivity.kt                 # 入口 / 登录页 / 主题与玻璃样式初始化
├── core/                           # ApiKeyStore / ThemeStore / BackgroundStore / GlassStyleStore
├── data/
│   ├── VibeCafeApi.kt              # vibecafe.ai 用量接口（Retrofit）
│   ├── quota/                      # 「额度」页：各家供应商查询客户端
│   │   ├── ExtraQuotaApi.kt        #   共用类型（Bar/Usage/QuotaException）+ 格式化 + 兜底策略
│   │   ├── QuotaHttp.kt            #   HTTP 底座（直连 ⇄ 中转，含中转熔断）—— 同文件内
│   │   ├── MiniMax.kt / Glm.kt / Kimi.kt / DeepSeek.kt / Infini.kt /
│   │   │ Bailian.kt / Ark.kt / GitHubCopilot.kt / OpenRouter.kt /
│   │   │ Doubao.kt / Agnes.kt / Mimo.kt / CommandCode.kt   #   每家供应商一个文件
│   │   ├── OAuthFlow.kt            #   GitHub 设备码 / OpenRouter OAuth
│   │   └── DnsResolver.kt          #   系统 DNS + DoH 兜底
│   ├── ag/AgQuotaApi.kt            # Antigravity（Google 反重力）配额
│   ├── UpdateChecker.kt            # 应用内更新 + SHA-256 校验
│   └── CredentialBackup.kt         # 凭据加密备份/恢复
├── stats/StatsEngine.kt            # 统计计算引擎
├── share/ShareCard.kt              # 玻璃风格分享卡
├── budget/BudgetNotify.kt          # 预算超支通知
├── widget/UsageWidget.kt           # Glance 小组件
├── render/GlassEngine.kt           # 着色器
└── ui/
    ├── DashboardScreen.kt          # 主面板
    ├── SettingsScreen.kt           # 设置
    ├── MainViewModel.kt            # 状态管理
    ├── ag/                         # 「额度」页 UI + 供应商目录（ExtraProvidersCatalog）
    ├── anim/                       # 数字滚动 / 分段入场动效
    ├── charts/                     # 趋势面积图 / 占比环形图
    ├── glass/                      # 液态玻璃组件（卡片/滑块/底栏/分段控件）
    └── theme/                      # 亮/暗/纯黑设计令牌 + 排版
```

测试见 `app/src/test/`，覆盖统计引擎（时间窗口 / 趋势 / 设备筛选）、GLM / MiMo / MiniMax / CommandCode 解析、host 兜底策略、图标匹配、分享卡与凭据加密。

## 📄 许可证

[MIT](LICENSE)