# Default proguard rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Gson 反序列化模型：字段名经 @SerializedName 映射，禁止裁剪与混淆
-keep class ai.vibecafe.usage.data.** { <fields>; }
-keepattributes Signature, InnerClasses, EnclosingMethod

# 小组件快照：跨版本落盘的 JSON 依赖字段名（costToday/tokensToday/updatedAt），
# 改名后新包读旧快照会全部落默认值，桌面显示 $0.00
-keep class ai.vibecafe.usage.widget.WidgetSnapshot { <fields>; }

# Gson TypeToken 泛型擦除防护
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 更新检查的 GitHub DTO（Gson 手动解析 Array<GitHubTag>）
-keep class ai.vibecafe.usage.data.GitHubTag { <fields>; }
-keep class ai.vibecafe.usage.data.GitHubRelease$* { <fields>; }

# OkHttp / Retrofit / Gson 自带 consumer rules，这里只补平台差异告警
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
