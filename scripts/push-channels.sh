#!/usr/bin/env bash
# 把本地频道清单推送到电视上的 App（清单不进仓库，也不内置在 APK 里）。
# 用法：push-channels.sh [设备地址] [清单文件] [代理文件]
#   设备地址：第 1 个参数或环境变量 ANDROID_SERIAL，形如 <电视 IP>:5555（模拟器 emulator-5554）
#   清单文件：第 2 个参数或环境变量 LIVETV_CHANNELS，默认当前目录的 channels.m3u（*.m3u 已被 .gitignore 忽略）
#   代理文件（可选）：第 3 个参数或环境变量 LIVETV_PROXY，不设就不推送。单行 socks5://主机:端口，
#     空行和 # 开头的行忽略；推送后 App 的「拉流代理」才有可用的代理。代理地址不进仓库，脚本也不会打印它。
set -euo pipefail

PKG=xyz.szyyw.livetv
DEST_DIR=/sdcard/Android/data/$PKG/files
DEST=$DEST_DIR/channels.m3u
TMP=$DEST_DIR/channels.m3u.tmp
PROXY_DEST=$DEST_DIR/proxy.txt
PROXY_TMP=$DEST_DIR/proxy.txt.tmp

DEVICE="${1:-${ANDROID_SERIAL:-}}"
FILE="${2:-${LIVETV_CHANNELS:-./channels.m3u}}"
PROXY_FILE="${3:-${LIVETV_PROXY:-}}"

die() { echo "错误：$*" >&2; exit 1; }

[ -n "$DEVICE" ] || die "未指定设备：传第 1 个参数或设置 ANDROID_SERIAL（形如 设备IP:5555）"

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

# 代理文件：设置了且文件存在才推送（先推临时文件再原子 mv）；格式不对只警告，不打印内容
PROXY_MSG="未推送代理文件"
if [ -n "$PROXY_FILE" ]; then
  if [ ! -f "$PROXY_FILE" ]; then
    echo "警告：代理文件不存在，已跳过：$PROXY_FILE" >&2
  else
    grep -Eqi '^[[:space:]]*socks5h?://[^[:space:]]+:[0-9]+[[:space:]]*$' "$PROXY_FILE" \
      || echo "警告：代理文件里没有形如 socks5://主机:端口 的行，App 会把它当作未配置" >&2
    A push "$PROXY_FILE" "$PROXY_TMP" >/dev/null
    A shell mv -f "$PROXY_TMP" "$PROXY_DEST"
    PROXY_MSG="已推送代理文件"
  fi
fi
# 显式组件广播：App 在前台时重新加载，不在前台什么也不做
A shell am broadcast -n "$PKG/.ReloadReceiver" -a "$PKG.RELOAD" >/dev/null

# 频道数 = 不同频道名的个数（同名合并为多条线路）：取 #EXTINF 行最后一个逗号后的名字，或 txt 的「名字,http」
CHANNELS=$(awk '/^#EXTINF/ { sub(/^.*,/, ""); print; next } /^[^#].*,https?:\/\// { sub(/,https?:.*$/, ""); print }' "$FILE" | sort -u | grep -c . || true)
LINES=$(grep -Ec '^https?://|,https?://' "$FILE" || true)
SIZE=$(A shell "wc -c < $DEST" | tr -d '[:space:]')
echo "已推送到 $DEVICE：$DEST"
echo "频道数：$CHANNELS，线路数：$LINES，设备上文件大小：$SIZE 字节；$PROXY_MSG"
