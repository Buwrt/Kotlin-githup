#!/usr/bin/env bash
#
# Kotlin 迁移的本地验收脚本
#
# 沙盒里没有 JDK / Android SDK，构建跑不了；这个脚本是给你在本机或 CI 上
# 一键跑完三道检查用的。任何一道挂了都别继续往下迁。
#
# 用法：
#   bash tools/verify-kotlin-migration.sh            # 默认打 debug 包
#   bash tools/verify-kotlin-migration.sh release    # 打 release 包（需要 keystore/）
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
VARIANT="${1:-debug}"

echo "=============================================="
echo " githup-release · Kotlin 迁移验收"
echo "=============================================="

fail=0

# ---------- 1. 桥接接口一致性 ----------
echo
echo "[1/3] 桥接接口一致性（前端 window.Native.* 的契约）"
if python3 tools/bridge-parity.py; then
    echo "      -> 通过"
else
    echo "      -> 失败：JsBridge 的接口对不上基线"
    fail=1
fi

# ---------- 2. 编译 ----------
echo
echo "[2/3] 编译 ${VARIANT} 包（Java 与 Kotlin 混编是否成立）"
if [ "$VARIANT" = "release" ]; then
    ./gradlew --no-daemon assembleRelease
else
    ./gradlew --no-daemon assembleDebug
fi
if [ $? -eq 0 ]; then
    echo "      -> 编译通过"
else
    echo "      -> 编译失败，先把错误解决再往下走"
    fail=1
fi

# ---------- 3. 体积 ----------
echo
echo "[3/3] APK 体积"
APK="$(ls -1 app/build/outputs/apk/${VARIANT}/*.apk 2>/dev/null | head -1)"
if [ -n "$APK" ]; then
    SIZE="$(stat -c%s "$APK" 2>/dev/null || stat -f%z "$APK")"
    echo "      产物：$APK"
    echo "      大小：${SIZE} 字节（$((SIZE / 1024)) KB）"
    echo "      对照：迁移前 1.2.16 的包是 1,109,487 字节（1,083 KB）"
    echo "      增量就是引入 kotlin-stdlib 的代价，请自己判断能不能接受"
else
    echo "      没找到 APK，跳过体积检查"
fi

echo
echo "=============================================="
if [ $fail -eq 0 ]; then
    echo " 前三道机器检查通过。下面这四件事只能装到真机上看："
else
    echo " 有检查未通过，先修再继续。"
fi
echo "=============================================="
cat <<'EOF'

真机四项对照（机器上跑不出来，但必须做）：

  1. 两步验证：App 里的动态码，和系统里另一个验证器（Google Authenticator
     之类）对同一把密钥显示的码必须完全一致 —— Totp.kt 是从 Java 逐行转的，
     位运算写错这里立刻现形。
  2. 签名自校验：装 release 包必须正常启动（SignCheck.kt 校验官方签名指纹）；
     装 debug 包也必须正常（debug 签名放行）。
  3. 崩溃自记：进设置触发一次崩溃记录导出，确认 last_crash.txt 内容完整、
     分享面板能拿到文件（LogProvider.kt / PrivateFileProvider.kt）。
  4. 更新安装：点「检查更新」走完下载+安装，确认 ApkProvider.kt 给系统
     安装器的临时读权限没坏（这里坏了的表现是安装界面一闪即退）。

另外：同一个 seed 在「云端打包」里生成两次 keystore，两份字节必须完全相等
（KeyTool.kt 是确定性生成，这是它的核心保证）。
EOF

exit $fail
