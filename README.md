# githup UI（Kotlin / Jetpack Compose 复刻版）

把 githup 那套 **WebView 承载的前端 UI** 用 Kotlin + Jetpack Compose 原样重写了一遍。
配色、尺寸、圆角、动效参数全部取自源码里的 `css/app.css` 与 `index.html`，不是照截图估的。

## 对应关系

| 原项目（前端） | 本项目（Compose） |
| --- | --- |
| `css/app.css` 的 `:root` / `[data-theme="dark"]` | `theme/Theme.kt`（`GithupTokens` 亮/暗两套） |
| `--appbar-h` / `--tabbar-h` / `--tabbar-lift` 等 | `theme/Theme.kt` 的 `GithupDimens`、`GithupType` |
| `index.html` 的 `#appbar` + `#view` + `.fab` + `#tabbar` | `MainActivity.kt` 的 `GithupApp` |
| `#tabbar` + `.tab-ind`（悬浮胶囊 + 滑动玻璃指示器） | `components/TabBar.kt` |
| `backdrop-filter` + SVG 位移滤镜（液态玻璃） | `components/LiquidGlass.kt` |
| `.card` / `.list-row` / `.btn` / `.seg` / `.chip` / `.avatar` / `.state` | `components/Primer.kt` |
| `.sheet` / `.toast` / 图片查看器 | `components/Sheet.kt` |
| `js/icons.js`（Octicons） | `components/Octicons.kt`（138 个，PascalCase 命名） |
| `page-home.js` 等各页面 | `screens/*.kt` |

## 五个 Tab

首页 / 通知 / 探索 / 搜索 / 我的，外加二级页「仓库详情」。

## 运行

用 Android Studio（Koala 或更新）打开本目录，Gradle 同步后直接 Run。

- `minSdk 26`：液态玻璃若要用真实背景模糊需要 `RenderEffect`（26+），
  当前实现是「半透明面 + 精细高光」，26 以下同样能跑。
- 无第三方依赖，只用 Compose BOM + Material3 + Navigation。

## 与 WebView 版的两处已知差异

1. **背景模糊**：Compose 没有 `backdrop-filter`。原版靠它 + `feDisplacementMap` 做折射；
   这里按源码注释里那段实测结论（「玻璃感来自边缘高光和背景透出，不来自面的颜色」）
   用半透明面 + 三段式高光还原，静态观感接近，滚动时的帧率更好。
2. **图片加载**：`Avatar` 只给了形状和占位，接 Coil / Glide 时替换 `rememberAsyncPlaceholder` 即可。

## 未做编译验证

本环境没有 Android SDK，代码未经 `gradle build` 跑通。导入 IDE 后如有小错（多为 import 或
Compose 版本差异），按 IDE 提示修即可。
