#!/usr/bin/env bash
# 一键构建与安装。
#   ./build.sh                构建 debug APK 并安装到真机（开发用，约 34MB，未裁剪）
#   ./build.sh release        构建已签名 release APK（不安装，约 3.5MB，已 R8 裁剪）
#   ./build.sh release-install 构建已签名 release 并安装（发布/真机用，体积小）
#   ./build.sh all            同时构建 debug 和 release
set -euo pipefail
cd "$(dirname "$0")"

# 定位 adb：先 PATH，再 SDK platform-tools。
find_adb() {
    command -v adb 2>/dev/null && return
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [ -z "$sdk" ] && sdk="$(grep '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2-)"
    [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ] && echo "$sdk/platform-tools/adb"
}

install_apk() {
    local apk="$1"
    local adb
    adb="$(find_adb)"
    [ -z "$adb" ] && { echo "==> 未找到 adb，跳过安装。可手动: adb install -r $apk"; return 0; }
    if "$adb" devices | grep -q 'device$'; then
        echo "==> 检测到真机，安装中（OPPO/ColorOS 需在手机上点「安装」，或开启开发者选项里的「USB 安装」）..."
        if ! timeout 300 "$adb" install -r "$apk"; then
            echo "==> 安装超时或失败：请检查手机上的安装确认弹窗，或在开发者选项开启「USB 安装」。"
        fi
    else
        echo "==> 未检测到真机，跳过安装。可手动: $adb install -r $apk"
    fi
}

build_debug() {
    echo "==> 构建 debug APK ..."
    ./gradlew :app:assembleDebug --console=plain
    local apk
    apk="$(ls app/build/outputs/apk/debug/app-arm64-v8a-debug.apk 2>/dev/null \
        || ls app/build/outputs/apk/debug/app-debug.apk 2>/dev/null \
        || ls app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1)"
    [ -n "$apk" ] || { echo "未找到 debug APK"; return 1; }
    echo "==> 产物: $apk"
    install_apk "$apk"
}

build_release() {
    local do_install="${1:-0}"
    echo "==> 构建 release APK ..."
    ./gradlew :app:assembleRelease --console=plain
    local apk
    apk="$(ls app/build/outputs/apk/release/app-arm64-v8a-release.apk 2>/dev/null \
        || ls app/build/outputs/apk/release/app-release.apk 2>/dev/null \
        || ls app/build/outputs/apk/release/*-release.apk 2>/dev/null | head -1)"
    [ -n "$apk" ] || { echo "未找到 release APK"; return 1; }
    echo "==> 产物: $apk"
    ls -lh "$apk" 2>/dev/null || true
    [ "$do_install" = "1" ] && install_apk "$apk"
}

case "${1:-}" in
    release)         build_release 0 ;;
    release-install) build_release 1 ;;
    all)             build_debug && echo && build_release 0 ;;
    *)               build_debug ;;
esac
