#!/usr/bin/env bash
# 构建「体重记」APK：不依赖 Gradle，直接用 Android SDK 的 aapt2/javac/d8/apksigner。
#
# 用法（在本仓库根目录执行）：
#   bash build.sh                # → apks/weightlog-<version>.apk
#   bash build.sh --install      # 构建并安装到已连接的手机（adb）
#   bash build.sh --version 1.2 --code 3
#   bash build.sh --no-preset    # 不注入仓库外 env 文件里的预绑定 S400
#
# 预绑定：默认读取仓库外的 env 文件（默认 ../scale/.env）里的 S400_MAC / S400_BLE_KEY（可选 S400_NAME），
# 写进 BuildConfig.PRESET_SCALE_*；APK 首次启动即已绑定这台秤，不用再扫描添加。
# BLE key 只进 build/ 生成物和 APK，不打印、不进日志 / 导出。
set -euo pipefail

VERSION="1.2"
VERSION_CODE="3"
DO_INSTALL=0
PRESET_ENABLED=1

while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help)
      sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    --install)
      DO_INSTALL=1
      shift
      ;;
    --no-preset)
      PRESET_ENABLED=0
      shift
      ;;
    --version)
      VERSION="$2"
      shift 2
      ;;
    --code)
      VERSION_CODE="$2"
      shift 2
      ;;
    *)
      echo "[FAIL] 未知参数：$1" >&2
      exit 1
      ;;
  esac
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
MIJIA="$(cd "$ROOT/.." && pwd)"
APK_OUT="${WEIGHTLOG_APK_OUT:-$ROOT/apks/weightlog-${VERSION}.apk}"
PKG="com.weightlog.app"
PKG_PATH="com/weightlog/app"

# 预绑定 S400：从 scale/.env 读 MAC / BLE key，注入 BuildConfig。
# .env 不在仓库里（600、gitignore），换秤/换 key 只改它再重新构建。
PRESET_MAC=""
PRESET_KEY=""
PRESET_NAME=""
if [ "$PRESET_ENABLED" = "1" ]; then
  PRESET_ENV="${WEIGHTLOG_PRESET_ENV:-$MIJIA/scale/.env}"
  if [ -f "$PRESET_ENV" ]; then
    PRESET_MAC="$(sed -n 's/^S400_MAC=//p' "$PRESET_ENV" | tr -d '"'"'"' \r' | head -1)"
    PRESET_KEY="$(sed -n 's/^S400_BLE_KEY=//p' "$PRESET_ENV" | tr -d '"'"'"' \r' | head -1)"
    PRESET_NAME="$(sed -n 's/^S400_NAME=//p' "$PRESET_ENV" | tr -d '"\r' | head -1)"
  fi
  if ! printf '%s' "$PRESET_MAC" | grep -Eq '^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$' \
      || ! printf '%s' "$PRESET_KEY" | grep -Eq '^[0-9A-Fa-f]{32}$'; then
    echo "[WARN] $PRESET_ENV 里没有合法的 S400_MAC / S400_BLE_KEY，本次构建不预绑定" >&2
    PRESET_MAC=""
    PRESET_KEY=""
    PRESET_NAME=""
  fi
fi
if [ -n "$PRESET_MAC" ] && [ -z "$PRESET_NAME" ]; then
  PRESET_NAME="Xiaomi Scale S400 $(printf '%s' "$PRESET_MAC" | tr -d ':' | tail -c 4 | tr 'a-f' 'A-F')"
fi
# Java 字符串里不能留引号 / 反斜杠 / 换行
PRESET_MAC="$(printf '%s' "$PRESET_MAC" | tr -d '"\\\r\n')"
PRESET_KEY="$(printf '%s' "$PRESET_KEY" | tr -d '"\\\r\n')"
PRESET_NAME="$(printf '%s' "$PRESET_NAME" | tr -d '"\\\r\n')"

SDK=""
for cand in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk"; do
  if [ -n "$cand" ] && [ -d "$cand/build-tools" ] && [ -d "$cand/platforms" ]; then
    SDK="$cand"
    break
  fi
done
[ -n "$SDK" ] || { echo "[FAIL] 找不到有效 Android SDK"; exit 1; }
BT="$SDK/build-tools/37.0.0"
# 需要 API 31+ 的 android.jar：manifest 的 usesPermissionFlags 和 MediaStore.Downloads 都要求
PLATFORM=""
for api in 35 34 33 32 31; do
  if [ -f "$SDK/platforms/android-$api/android.jar" ]; then
    PLATFORM="$SDK/platforms/android-$api/android.jar"
    break
  fi
done
[ -n "$PLATFORM" ] || { echo "[FAIL] 需要 android-31 及以上的 platform（当前只有：$(ls "$SDK/platforms" | tr '\n' ' ')）"; exit 1; }

OUT="$ROOT/build"
KS="${WEIGHTLOG_KEYSTORE:-$ROOT/debug.keystore}"
KS_PASS="${WEIGHTLOG_KEYSTORE_PASS:-android}"
KS_ALIAS="${WEIGHTLOG_KEYSTORE_ALIAS:-weightlog}"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM"; do
  [ -e "$f" ] || { echo "[FAIL] 缺少 $f"; exit 1; }
done
command -v javac >/dev/null || { echo "[FAIL] 找不到 javac"; exit 1; }
command -v keytool >/dev/null || { echo "[FAIL] 找不到 keytool"; exit 1; }
command -v zip >/dev/null || { echo "[FAIL] 找不到 zip"; exit 1; }
command -v python3 >/dev/null || { echo "[FAIL] 找不到 python3"; exit 1; }

[ -d "$ROOT/assets/www" ] || { echo "[FAIL] 缺少 assets/www"; exit 1; }

echo "==> 体重记 v${VERSION}（${VERSION_CODE}）→ $APK_OUT"

rm -rf "$OUT"
mkdir -p "$OUT/res-src" "$OUT/gen" "$OUT/classes" "$OUT/dex"
cp -R "$ROOT/res/." "$OUT/res-src/"

# 应用名注入
python3 - "$OUT/res-src/values/strings.xml" "体重记" <<'PY'
import re, sys
from xml.sax.saxutils import escape
p, label = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
s = re.sub(r'(<string name="app_name">).*?(</string>)',
           lambda m: m.group(1) + escape(label) + m.group(2), s)
open(p, "w", encoding="utf-8").write(s)
PY

# Manifest：只替换版本号（包名固定）
python3 - "$ROOT/AndroidManifest.xml" "$OUT/AndroidManifest.xml" "$VERSION" "$VERSION_CODE" <<'PY'
import sys
src, dst, version, code = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
s = open(src, encoding="utf-8").read()
s = s.replace('android:versionCode="1"', 'android:versionCode="%s"' % code, 1)
s = s.replace('android:versionName="1.0"', 'android:versionName="%s"' % version, 1)
open(dst, "w", encoding="utf-8").write(s)
PY

echo "==> 1/5 编译资源"
"$BT/aapt2" compile --dir "$OUT/res-src" -o "$OUT/res.zip"

echo "==> 2/5 链接 APK（minSdk 23 / targetSdk 31 / assets）"
"$BT/aapt2" link \
  -o "$OUT/unsigned.apk" \
  -I "$PLATFORM" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --auto-add-overlay \
  --java "$OUT/gen" \
  --min-sdk-version 23 \
  --target-sdk-version 31 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION" \
  -A "$ROOT/assets"

echo "==> 3/5 生成 BuildConfig + 编译 Java + dex"
# 版本号唯一来源：manifest 与 BuildConfig 都用上面的 VERSION / VERSION_CODE
# 预绑定 S400：把 scale/.env 的 MAC / bind key 写进 BuildConfig（--no-preset 时为空）
if [ -n "$PRESET_MAC" ]; then
  echo "    预绑定 S400: $PRESET_NAME ($PRESET_MAC)，bind key 已注入（不打印）"
else
  echo "    预绑定 S400: 无（首次启动需要手动绑定或扫描添加）"
fi
mkdir -p "$OUT/gen/$PKG_PATH"
cat > "$OUT/gen/$PKG_PATH/BuildConfig.java" <<EOF
package com.weightlog.app;

/** 由 build.sh 生成，不要手改、不要提交。 */
public final class BuildConfig {
    public static final String VERSION_NAME = "${VERSION}";
    public static final int VERSION_CODE = ${VERSION_CODE};

    /** 预绑定 S400（build.sh 从 scale/.env 注入；空 = 不预绑定） */
    public static final String PRESET_SCALE_MAC = "${PRESET_MAC}";
    public static final String PRESET_SCALE_BIND_KEY = "${PRESET_KEY}";
    public static final String PRESET_SCALE_NAME = "${PRESET_NAME}";

    private BuildConfig() {
    }
}
EOF
JAVA_SRCS=$(find "$ROOT/src" -name '*.java' -print)
javac -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM" \
  -d "$OUT/classes" \
  "$OUT/gen/$PKG_PATH/R.java" \
  "$OUT/gen/$PKG_PATH/BuildConfig.java" \
  $JAVA_SRCS

"$BT/d8" --lib "$PLATFORM" --min-api 23 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class' -print)

echo "==> 4/5 打包 + 对齐"
( cd "$OUT" && zip -uj unsigned.apk dex/classes.dex >/dev/null )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 5/5 签名"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v \
    -keystore "$KS" \
    -alias "$KS_ALIAS" \
    -storepass "$KS_PASS" \
    -keypass "$KS_PASS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=WeightLog Debug, O=WeightLog, C=CN" >/dev/null 2>&1
  echo "[WARN] 已生成本地 debug 证书：$KS" >&2
  echo "       换机 / 换目录后签名会变，已装的 App 必须先卸载重装；想复用旧证书用 WEIGHTLOG_KEYSTORE 指向它。" >&2
fi

mkdir -p "$(dirname "$APK_OUT")"
rm -f "$APK_OUT"
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --v4-signing-enabled false \
  --out "$APK_OUT" \
  "$OUT/aligned.apk"

"$BT/apksigner" verify --verbose --print-certs "$APK_OUT" | sed -n '1,12p'
ls -lh "$APK_OUT"
shasum -a 256 "$APK_OUT"
echo
echo "[OK] APK: $APK_OUT"
echo "安装: adb install -r $APK_OUT"

if [ "$DO_INSTALL" = "1" ]; then
  adb install -r "$APK_OUT"
  adb shell am start -n "$PKG/.MainActivity"
fi
