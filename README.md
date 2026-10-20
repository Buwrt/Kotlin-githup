# githup UI（Kotlin / Jetpack Compose 复刻版）

把 githup 那套 **WebView 承载的前端 UI** 用 Kotlin + Jetpack Compose 原样重写了一遍。
配色、尺寸、圆角、动效参数全部取自源码里的 `css/app.css` 与 `index.html`，不是照截图估的。

## 拿 APK

两条路，都是 CI 自动出的：

- **Release**：本仓库 Releases 里的 `githup-ui-debug.apk`
- **Actions**：`构建 APK` 这次运行底部的 Artifacts（`githup-ui-debug-apk`）

APK 也会随每次构建提交回仓库的 `apk/` 目录（带 `.sha256`）。

## 这一版做到哪儿

按"先复刻一小部分功能"来做：**界面骨架全套 + 本机可用的那部分功能**。

界面（五个 Tab + 二级页）全部复刻：

| 页面 | 内容 |
| --- | --- |
| 首页 | 身份卡、3 列快捷网格、我的仓库列表 |
| 通知 | 全部/未读分段、未读圆点、类型图标 |
| 探索 | 搜索入口、今日/本周/本月、Trending 榜 |
| 搜索 | 输入框、类型分段、排序段控件 + 筛选栏 |
| 我的 | 身份卡、三项统计、分组设置 |
| 仓库详情 | 头部卡、五个分段、文件列表 / 议题列表 |

功能（不依赖网络，装上就能验证）：

- **深色模式**：切换后写入本机，杀掉进程再开还是那个
- **底栏样式**：悬浮玻璃胶囊 ↔ 贴底实心栏（原项目 `data-nav` 的两种形态）
- **本地收藏**：列表行右侧的星标、仓库页的「收藏」按钮，存在 SharedPreferences

没做的：仓库浏览、Issue/PR、Actions、Release、翻译、静默更新 —— 这些都要先接
GitHub API 与令牌体系，属于下一步。

## 对应关系

| 原项目（前端） | 本项目（Compose） |
| --- | --- |
| `css/app.css` 的 `:root` / `[data-theme="dark"]` | `theme/Theme.kt`（`GithupTokens` 亮/暗两套） |
| `--appbar-h` / `--tabbar-h` / `--tabbar-lift` | `theme/Theme.kt` 的 `GithupDimens`、`GithupType` |
| `index.html` 的 `#appbar` + `#view` + `.fab` + `#tabbar` | `MainActivity.kt` 的 `GithupApp` |
| `#tabbar` + `.tab-ind`（悬浮胶囊 + 滑动玻璃指示器） | `components/TabBar.kt` |
| `backdrop-filter` + SVG 位移滤镜（液态玻璃） | `components/LiquidGlass.kt` |
| `.card` / `.list-row` / `.btn` / `.seg` / `.chip` / `.avatar` / `.state` | `components/Primer.kt` |
| `.sheet` / `.toast` / 图片查看器 | `components/Sheet.kt` |
| `js/icons.js`（Octicons） | `components/Octicons.kt`（138 个，PascalCase 命名） |
| `page-home.js` 等各页面 | `screens/*.kt` |

## 本地构建

```bash
./gradlew assembleDebug
```

需要 JDK 17 + Android SDK（compileSdk 34，minSdk 26）。
无第三方依赖，只用 Compose BOM + Material3 + Kotlin 协程。

## 与 WebView 版的两处已知差异

1. **背景模糊**：Compose 没有 `backdrop-filter`。原版靠它 + `feDisplacementMap` 做折射；
   这里按源码注释里那段实测结论（「玻璃感来自边缘高光和背景透出，不来自面的颜色」）
   用半透明面 + 三段式高光还原，静态观感接近，滚动时的帧率更好。
2. **头像**：`Avatar` 现在是占位块（首字符），没有接图片加载库，
   要显示真头像时把 `url` 交给 `rememberAsyncImagePainter` 即可，形状逻辑不用动。
