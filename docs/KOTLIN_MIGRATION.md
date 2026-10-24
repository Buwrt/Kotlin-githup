# githup-release：Kotlin 迁移可行性 + 第一批落地

> 基于仓库 `Buwrt/githup-release`（私有库，main 分支，最新提交 `0429934f`，2026-10-17，v1.2.16 / versionCode 1002016）实测分析。
> 本文所有数字来自实际拉取的源码统计，不是估算。

---

## 一、结论先说

**能重构，而且比想象中便宜。** Kotlin 与 Java 在 Android 上是 100% 双向互操作、可以同目录混编，不需要「整体重写完才能编译」—— 你完全可以一个文件一个文件地搬，搬一个编译一次。

但有一件事必须先说清楚，它直接决定你要不要做这件事：

**光把原生层转成 Kotlin，Kotlin 当不上 GitHub 的主语言。**

理由是仓库的语言构成（GitHub linguist 按字节算）：

| 语言 | 字节数 | 占比 | 说明 |
|---|---|---|---|
| JavaScript | 1,303,696 | 57.2% | WebView 前端，29 个文件 26,575 行 |
| Java | 721,110 | 31.7% | 原生层，22 个文件 15,445 行 |
| CSS | 147,343 | 6.5% | 前端样式 |
| Python | 53,881 | 2.4% | tools/ 下的校验脚本 |
| Shell | 28,433 | 1.2% | 构建脚本 |
| HTML | 26,658 | 1.2% | 预览页 |

**前端 JS 比原生层还多 70%**（26,575 行 vs 15,445 行，`page-repo.js` 一个文件就 5,895 行）。即便把 22 个 Java 全转成 Kotlin，JS 仍然占大头，侧边栏显示的主语言还是 JavaScript。

所以「让 Kotlin 成为主语言」有三条路，代价差很多：

| 方案 | 做什么 | 主语言结果 | 代价 |
|---|---|---|---|
| A | 只转原生层 22 个 Java | 仍是 JavaScript | 最小，1～2 天 |
| B | A + `.gitattributes` 把前端资源标为 vendored | **Kotlin** | A 的成本 + 3 行配置 |
| C | 前端逐步 Compose 化，JS 逐页替换 | **Kotlin（名副其实）** | 长期工程，且会丢掉热更新与整页翻译 |

方案 B 的三行配置长这样（要不要加你自己定，见第四节末尾的提醒）：

```gitattributes
# 前端是随包发布的运行资源，不参与语言统计
app/src/main/assets/web/**          linguist-vendored
preview/**                          linguist-vendored
tools/**                            linguist-vendored
```

---

## 二、本次已经做了什么（第一批：12% 的原生代码）

按你选的「先做迁移验证」路线，我挑了**依赖最薄、最容易验证、转错了立刻能发现**的 9 个类先落地：

| 原 Java | 新 Kotlin | 行数 | 选它的理由 |
|---|---|---|---|
| `Totp.java` | `Totp.kt` | 149 | 纯算法，转完跟另一个验证器 App 对一下码就知道对不对 |
| `KeyTool.kt` ← `KeyTool.java` | `KeyTool.kt` | 220 | 确定性生成，同一个 seed 两次结果必须字节相等 |
| `SecurePrefs.java` | `SecurePrefs.kt` | 105 | 令牌加密存储，安全相关，越早验证越好 |
| `SignCheck.java` | `SignCheck.kt` | 101 | 签名自校验，同上 |
| `CrashLog.java` | `CrashLog.kt` | 158 | 崩溃自记，是后面所有排查的依据 |
| `App.java` | `App.kt` | 70 | 进程入口 |
| `BootReceiver.java` | `BootReceiver.kt` | 45 | 开机恢复通知 |
| `LogProvider.java` | `LogProvider.kt` + `PrivateFileProvider.kt` | 82 | 与下面那个合并，见下 |
| `ApkProvider.java` | `ApkProvider.kt` | 76 | 同上 |

**顺手清掉的第一处重复**：`LogProvider` 和 `ApkProvider` 原本是两份几乎一字不差的 Java（只有目录名和 MIME 不同）。迁到 Kotlin 时合成了一个抽象基类 `PrivateFileProvider(dirName, mimeType)`，两个子类各只剩 20 多行常量。这是迁移带来的第一个真实收益，不是单纯换语言。

当前状态：

```
Java   12 个文件  14,176 行   （剩 JsBridge / Http / MainActivity 等）
Kotlin 10 个文件   1,012 行   （新增）
```

构建配置也改好了：根 `build.gradle` 加 `org.jetbrains.kotlin.android 1.9.24`，`app/build.gradle` 加插件 + `kotlinOptions { jvmTarget = '17' }`。

**版本是硬约束，别随手升**：AGP 8.1.4 配 Kotlin 1.9.x；Kotlin 2.0 起要求 AGP 8.2+，要升 Kotlin 就得先升 AGP。Gradle 8.2 与两者都兼容，不用动。

---

## 三、为什么 Java 侧一行都不用改

Kotlin 没有 `static`。如果直接把静态方法搬进 `companion object`，Java 调用方（JsBridge、MainActivity、TotpService 这些还是 Java 的类）就得从 `Totp.compute(...)` 改成 `Totp.Companion.compute(...)` —— 那叫「被迫跟着改」，不叫平滑迁移。

解决办法是三个注解，本次全部按这个规则处理了：

```kotlin
companion object {
    const val AUTHORITY_SUFFIX = ".apks"   // const val → Java 看到的是 static final 字段

    @JvmField var sForeground = false      // @JvmField → Java 可以直接 App.sForeground = true

    @JvmStatic fun compute(acct: JSONObject?): String? = ...  // @JvmStatic → Java 照旧 Totp.compute(...)
}
```

已核对过全部调用点，`MainActivity` 的 `App.sForeground = true`、`JsBridge` 的 `SecurePrefs.get(...)`、`ApkProvider.uriFor(...)`、`KeyTool.makeKeystore(...)` 都不受影响。

---

## 四、体积：实测结果（不是估算）

引入 Kotlin 就会带进 `kotlin-stdlib`（Kotlin 插件自动添加，不需要手写依赖）。当前项目是**零第三方依赖**、迁移前 APK 是 1,109,487 字节（1,083 KB），所以这个增量相对显眼。

**已经实测出来了**，两种构建各打了一次：

| 构建类型 | 大小（字节） | 与迁移前 release 对比 |
|---|---|---|
| release（v1.2.16 + Kotlin） | **1,728,027** | **+618,540（约 +604 KB）** |
| debug（applicationId 带 `.debug`） | 2,186,375 | 仅供参考，口径不同 |

release 包的签名指纹 `863dd1cd3752e591…` 与 `SignCheck` 里写死的官方指纹一致，说明打包链路没被迁移破坏，装上去能正常通过签名自校验。

**真实增量约 604 KB**，比我先前估的 1.3～1.8 MB 小不少 —— 因为项目本身零第三方依赖，Kotlin 标准库被打进包里的部分有限。你说了接受体积增长，那就按这个走。如果哪天想压回去，有两条路，但都有代价：

- **开 R8 裁剪**（`minifyEnabled true`）：stdlib 能压到几百 KB。但本库刻意不混淆（`build.gradle` 里写着「反编译出来就是能读的源码，方便对照阅读」），开了就破坏这个初衷，且要重新配一堆 keep 规则。
- **少用 stdlib 的重型 API**： Collections、sequence、正则这些用得越多，被打进包的越多。属于长期习惯，不是一次性开关。

---

## 五、后面怎么走：JsBridge 是最大的那块

`JsBridge.java` 一个文件 7,000 行、68 个 `@JavascriptInterface`，是原生层最大的单体。它迟早要拆，建议按职责拆成 8 个模块（分组依据是这 68 个接口的实际用途）：

| 模块 | 接口数 | 代表接口 |
|---|---|---|
| `DownloadBridge` | 12 | `download` / `installApk` / `downloadHistory*` |
| `UiBridge`（系统 + 界面） | 16 | `copy` / `share` / `safeInsets` / `systemDark` |
| `NetBridge` | 10 | `http` / `httpB64` / `channelProbe` / `fetchRunLogs` |
| `FileBridge` | 8 | `pickFile` / `uploadMultipart` / `readFileBase64` |
| `CryptoBridge` | 8 | `totpSync` / `hmacSha1` / `makeKeystore` |
| `NotifBridge` | 5 | `notifWatchEnabled` / `checkNotificationsNow` |
| `LogBridge` | 5 | `logError` / `saveLog` / `exportLog` |
| `PrefsBridge` | 4 | `getToken` / `setToken` / `getPref` / `setPref` |

**拆的时候有个坑**：`@JavascriptInterface` 方法必须在注册给 WebView 的那个对象上，拆成 8 个对象要么分别 `addJavascriptInterface` 注册成 8 个命名空间（前端 JS 要跟着改），要么保留一个 `JsBridge` 做转发壳（前端零改动）。**推荐后者** —— 前端 26,575 行不动，风险小得多。

### 桥接契约的机器闸门

JsBridge 上那 68 个方法是原生与前端的唯一契约。少一个方法，前端就是 `undefined is not a function` —— 编译不报错、启动不崩溃，只在用户点到那一刻失效。**这种问题只能靠脚本拦。**

我加了 `tools/bridge-parity.py`，已经把当前 68 个接口的签名存成基线（`tools/bridge-baseline.json`）：

```bash
python3 tools/bridge-parity.py            # 比对，丢了/多了/签名变了就退出码 1
python3 tools/bridge-parity.py --list     # 看看现在有哪些接口
python3 tools/bridge-parity.py --snapshot # 确认拆分无误后，重新存基线
```

它同时认 Java 和 Kotlin 两种写法（`String id` 和 `id: String`），所以拆分过程中每一步都能跑。

**建议**：把 `python3 tools/bridge-parity.py` 加进 `.github/workflows` 的构建流程，跟 `assembleDebug` 一起跑。

---

## 六、验收：三步机器检查 + 四项真机对照

```bash
bash tools/verify-kotlin-migration.sh debug
```

会依次跑：桥接接口一致性 → 编译 → APK 体积。

剩下这四件事机器上跑不出来，但必须做：

1. **两步验证**：App 里的动态码和系统里另一个验证器对同一把密钥必须完全一致（`Totp.kt` 是从 Java 逐行转的，位运算写错这里立刻现形）。
2. **签名自校验**：release 包正常启动（`SignCheck.kt` 校验官方指纹），debug 包也正常（debug 签名放行）。
3. **崩溃自记**：触发一次崩溃记录导出，确认内容完整、分享面板拿得到文件（`LogProvider.kt` / `PrivateFileProvider.kt`）。
4. **更新安装**：点「检查更新」走完下载 + 安装（`ApkProvider.kt` 给系统安装器的临时读权限，坏了的表现是安装界面一闪即退）。

外加一项：同一个 seed 生成两次 keystore，两份字节必须完全相等（`KeyTool.kt` 的确定性保证）。

---

## 七、风险与红线（这几条别踩）

- **沙盒没跑过编译**。这边只有 JRE，没有 javac / kotlinc / Gradle / Android SDK，上面的 Kotlin 代码是按语义逐行转换的，但**没有经过编译器验证**。请在本机先跑一遍 `./gradlew assembleDebug`，有报错属于正常，按提示修即可（多半是空安全上的微调）。
- **版本号锁死，别顺手改**。`version.lock` 锁着 1.2.16 / 1002016，`app/build.gradle` 构建时会比对，对不上直接失败；改版本还要重跑 `tools/gen-guard.py`。本次迁移完全没碰版本。
- **`keystore/` 不在仓库里**（已被 `.gitignore` 排除）。打 release 包需要你本地有密钥，否则会退回 debug 签名。
- **签名指纹常量一个字符都不能错**。`SignCheck.kt` 里那个 `EXPECTED_SHA256` 照抄原值，别用格式化工具重排它。
- **本库无防护链、不开混淆**，这是刻意的设计，迁移时不要顺手打开。

---

## 八、还没做的（等你决定）

- `JsBridge.java`（7,000 行）的拆分 —— 按第五节那张表，建议从最小的 `PrefsBridge`（4 个接口）开始试手。
- 剩余 11 个 Java 文件（`Http` 1,073 行、`MainActivity` 930 行、`ThrottledDownloader` 920 行……）。
- 前端 26,575 行 JS —— 是否走 Compose 化，这是个独立的大决策，涉及要不要放弃 WebView 带来的热更新和整页翻译能力。
- 方案 B 的 `.gitattributes` —— 加不加取决于你怎么看「前端算不算本项目的主要实现语言」。它确实是你自己写的、随包发布的资源，标成 vendored 有合理性；但它终究是项目的主体代码，标了之后语言统计会显得比实际更「Kotlin」。这个判断留给你。
