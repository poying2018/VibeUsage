# VibeUsage — Liquid Glass Android App

A beautiful token-usage stats client for [vibecafe.ai](https://vibecafe.ai/usage),
built with Jetpack Compose and a liquid-glass design language.

## Build

Requirements: JDK 17, Android SDK (API 34), Gradle 8.10+ (wrapper included).

### Option A — system Gradle
```bash
gradle wrapper
./gradlew assembleDebug
```

### Option B — Android Studio
Open the `VibeUsage` folder in Android Studio and run `app`.

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install on phone (already ADB-connected)
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then launch from the home screen. On first run, paste your `vbu_…` API key on the
glass login screen. The key is stored only in local SharedPreferences.

## Design — how the liquid glass works

The glass aesthetic is layered from these techniques (all in
`app/src/main/java/ai/vibecafe/usage/ui/glass/LiquidGlass.kt`):

1. **Animated aurora backdrop** — three drifting radial color fields (violet,
   cyan, pink) drawn with `drawContent` + `drawCircle`, each on its own
   `infiniteRepeatable` animation. They give the glass surfaces something to
   refract, which is what sells the "liquid" look.
2. **Backdrop blur (API 31+)** — `RenderEffect.createBlurEffect` applied via
   `graphicsLayer.renderEffect`. Below API 31 the translucent fill still reads
   as glass; the blur is a progressive enhancement.
3. **Translucent gradient fill** — vertical `Brush.verticalGradient` from a
   brighter `GlassWhiteStrong` at the top to a faint `GlassWhite` at the bottom,
   mimicking how light pools on a curved glass edge.
4. **Specular rim highlight** — a white-to-transparent gradient painted across
   the top 40% of each card, plus a faint inner shadow along the bottom. This
   top-edge sheen is the signature glass cue.
5. **Surface-tension press** — cards scale to 0.97× on press via `Animatable`
   + a spring, as if the surface has tension.
6. **Floating glass orbs** — blurred gradient circles drifting behind the login
   card for depth.

## Architecture

```
data/      Retrofit client, API interface, models, repository
stats/     Pure-Kotlin statistics engine (time filtering, aggregation)
ui/        ViewModel + screen composables
ui/glass   Liquid-glass primitives (AuroraBackground, LiquidGlassCard, FrostedPanel)
ui/theme   Palette + typography tokens
```

## Stats engine

The stats engine follows the spec in
`统计逻辑与登录校验代码..md` exactly — including the critical note that the
API's `totalTokens` field excludes cached input tokens, so the full total is
`input + output + reasoning + cached`, computed via `Bucket.fullTokens()`.
