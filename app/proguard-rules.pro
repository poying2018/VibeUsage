# Default proguard rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Gson 反序列化模型：字段名经 @SerializedName 映射，禁止裁剪与混淆
-keep class ai.vibecafe.usage.data.** { <fields>; }
-keepattributes Signature, InnerClasses, EnclosingMethod

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
