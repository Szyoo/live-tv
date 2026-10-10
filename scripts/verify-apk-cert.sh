#!/bin/bash
# 用法: verify-apk-cert.sh <apk> <期望证书SHA-256>；不匹配则退出码 1
# 需要环境变量 ANDROID_HOME（或 APKSIGNER_JAR 直接指定 apksigner.jar）
set -euo pipefail
apk="$1"; expected="$(echo "$2" | tr -d ': ' | tr 'A-F' 'a-f')"
jar="${APKSIGNER_JAR:-$ANDROID_HOME/build-tools/37.0.0/lib/apksigner.jar}"
actual="$(java -jar "$jar" verify --print-certs "$apk" | grep -m1 'certificate SHA-256 digest' | sed 's/.*: *//' | tr -d ': ' | tr 'A-F' 'a-f')"
if [ -z "$actual" ]; then echo "::error::无法读取 APK 签名证书"; exit 1; fi
if [ "$actual" != "$expected" ]; then
  echo "::error::APK 签名证书指纹不符（实际 $actual，期望 $expected），拒绝发布"; exit 1
fi
echo "签名证书指纹校验通过: $actual"
