#!/usr/bin/env bash
# 把本地频道清单推送到电视上的 App（清单不进仓库，也不内置在 APK 里）。
# 用法：push-channels.sh [设备地址] [清单文件]
#   设备地址默认 192.0.2.10:5555（也可用 ANDROID_SERIAL 指定，模拟器 emulator-5554）
#   清单文件默认 ./secrets/channels.m3u
set -euo pipefail

PKG=xyz.szyyw.livetv
DEST_DIR=/sdcard/Android/data/$PKG/files
DEST=$DEST_DIR/channels.m3u
TMP=$DEST_DIR/channels.m3u.tmp

DEVICE="${1:-${ANDROID_SERIAL:-192.0.2.10:5555}}"
FILE="${2:-$HOME/secrets/channels.m3u}"

die() { echo "错误：$*" >&2; exit 1; }

command -v adb >/dev/null 2>&1 || die "找不到 adb"
[ -f "$FILE" ] || die "清单文件不存在：$FILE"
[ -s "$FILE" ] || die "清单文件是空的：$FILE"
grep -Eq '^#EXTINF|,https?://' "$FILE" || die "清单里没有 #EXTINF 或 ,http 行，不像频道清单：$FILE"

# 设备地址形如 ip:port 或纯 IP 时先 connect
case "$DEVICE" in
  *[0-9].*[0-9].*[0-9].*[0-9]*)
    case "$DEVICE" in *:*) ;; *) DEVICE="$DEVICE:5555" ;; esac
    adb connect "$DEVICE" >/dev/null 2>&1 || true
    ;;
esac

A() { adb -s "$DEVICE" "$@"; }

[ "$(A get-state 2>/dev/null || true)" = "device" ] || die "设备不可用：$DEVICE（adb devices 看看）"
A shell pm list packages "$PKG" | grep -q "^package:$PKG\$" || die "设备上没有安装 $PKG，请先安装 App"

A shell mkdir -p "$DEST_DIR"
A push "$FILE" "$TMP" >/dev/null
A shell mv -f "$TMP" "$DEST"
# 显式组件广播：App 在前台时重新加载，不在前台什么也不做
A shell am broadcast -n "$PKG/.ReloadReceiver" -a "$PKG.RELOAD" >/dev/null

# 频道数 = 不同频道名的个数（同名合并为多条线路）：取 #EXTINF 行最后一个逗号后的名字，或 txt 的「名字,http」
CHANNELS=$(awk '/^#EXTINF/ { sub(/^.*,/, ""); print; next } /^[^#].*,https?:\/\// { sub(/,https?:.*$/, ""); print }' "$FILE" | sort -u | grep -c . || true)
LINES=$(grep -Ec '^https?://|,https?://' "$FILE" || true)
SIZE=$(A shell "wc -c < $DEST" | tr -d '[:space:]')
echo "已推送到 $DEVICE：$DEST"
echo "频道数：$CHANNELS，线路数：$LINES，设备上文件大小：$SIZE 字节"
