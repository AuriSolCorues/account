#!/usr/bin/env bash
# 一键构建与安装。
#   ./build.sh                构建 debug APK 并安装到真机（开发用，约 37MB，未裁剪）
#   ./build.sh release        构建已签名 release APK（不安装，约 3.7MB，已 R8 裁剪）
#   ./build.sh release-install 构建已签名 release 并安装（发布/真机用，体积小）
#   ./build.sh all            同时构建 debug 和 release
#   ./build.sh plugin         构建扩展模块 release 并安装（extension/ 目录存在时；不随仓库发布，本地放置）
#   ./build.sh all-install    release 本体 + 扩展模块一键双装（发版装机用）
#   ./build.sh ssh            代码同步到服务器(112核)编译 release+扩展模块，APK 拉回本地 gradle 输出目录再安装
# 版本号：唯一事实源是 version.properties（发版只改它）；环境变量可临时覆盖：
#   ACCOUNT_VERSION_CODE=58 ACCOUNT_BUILD_ID=40 ./build.sh release-install
set -euo pipefail
trap 'echo "==> 构建失败，exit $?" >&2' ERR
cd "$(dirname "$0")"

# 版本号唯一事实源：version.properties（发版只改它一处；build.gradle.kts 也从这读默认值）。
# 环境变量 ACCOUNT_VERSION_CODE/ACCOUNT_BUILD_ID 仍可临时覆盖（如重打同版本包）。
VERSION_FILE="$(dirname "$0")/version.properties"
get_prop() { grep "^$1=" "$VERSION_FILE" 2>/dev/null | head -1 | cut -d= -f2-; }
VERSION_CODE="${ACCOUNT_VERSION_CODE:-$(get_prop versionCode)}"
BUILD_ID="${ACCOUNT_BUILD_ID:-$(get_prop buildId)}"
VERSION_NAME="${ACCOUNT_VERSION_NAME:-$(get_prop versionName)}"
[ -n "$VERSION_CODE" ] && [ -n "$BUILD_ID" ] || { echo "版本号缺失：version.properties 缺 versionCode/buildId" >&2; exit 1; }

# 远程构建目标（./build.sh ssh）：代码同步到这里编译，rsync 排除清单见 sync_code()
REMOTE=out4090
RDIR=/app/account

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
        if ! out="$(timeout 300 "$adb" install -r "$apk" 2>&1)"; then
            if echo "$out" | grep -q 'INSTALL_FAILED_UPDATE_INCOMPATIBLE'; then
                echo "==> 签名不匹配：设备上装的是另一个签名（release/debug）的包，无法覆盖。"
                echo "    装 release 用 ./build.sh release-install；装 debug 需先 adb uninstall com.copy.account。"
            else
                echo "==> 安装超时或失败：请检查手机上的安装确认弹窗，或在开发者选项开启「USB 安装」。"
                echo "$out" | tail -3
            fi
        fi
    else
        echo "==> 未检测到真机，跳过安装。可手动: $adb install -r $apk"
    fi
}

build_debug() {
    echo "==> 构建 debug APK ..."
    ./gradlew :app:assembleDebug --console=plain
    local apk
    apk="$(ls app/build/outputs/apk/debug/account-*-arm64-v8a-debug.apk 2>/dev/null \
        || ls app/build/outputs/apk/debug/*-arm64-v8a-debug.apk 2>/dev/null \
        || ls app/build/outputs/apk/debug/*-debug.apk 2>/dev/null | head -1)"
    [ -n "$apk" ] || { echo "未找到 debug APK"; return 1; }
    echo "==> 产物: $apk"
    install_apk "$apk"
}

build_release() {
    local do_install="${1:-0}"
    echo "==> 构建 release APK (versionCode=$VERSION_CODE buildId=$BUILD_ID) ..."
    ./gradlew :app:assembleRelease -Paccount.versionName="$VERSION_NAME" -Paccount.versionCode="$VERSION_CODE" -Paccount.buildId="$BUILD_ID" --console=plain
    local apk
    apk="$(ls app/build/outputs/apk/release/account-*-arm64-v8a.apk 2>/dev/null \
        || ls app/build/outputs/apk/release/*-arm64-v8a.apk 2>/dev/null \
        || ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
    [ -n "$apk" ] || { echo "未找到 release APK"; return 1; }
    echo "==> 产物: $apk"
    ls -lh "$apk" 2>/dev/null || true
    if [ "$do_install" = "1" ]; then install_apk "$apk"; fi
}

build_plugin() {
    local do_install="${1:-0}"
    [ -d extension ] || { echo "extension/ 目录不存在（扩展模块不随仓库发布，本地放置后可用）"; return 1; }
    echo "==> 构建扩展模块 release APK ..."
    ./gradlew :extension:assembleRelease --console=plain
    local apk="extension/build/outputs/apk/release/extension-release.apk"
    [ -f "$apk" ] || { echo "未找到扩展模块 APK"; return 1; }
    ls -lh "$apk" 2>/dev/null || true
    [ "$do_install" = "1" ] || return 0
    local adb
    adb="$(find_adb)"
    [ -z "$adb" ] && { echo "==> 未找到 adb，跳过安装。可手动: adb install -r $apk"; return 0; }
    if ! "$adb" devices | grep -q 'device$'; then
        echo "==> 未检测到真机，跳过安装。可手动: $adb install -r $apk"
        return 0
    fi
    install_apk "$apk"
}

# 注：旧版装机后的 fix_sync_permission（appops 补 MANAGE_EXTERNAL_STORAGE）已删——
# 扩展模块 v5 起废除存储权限（本地数据走本体 ExtensionBridgeProvider 数据桥），覆盖安装
# 「无权限可丢」。TRIGGER 签名权限由系统安装时自动授予，无需任何手动步骤。

# ===== ssh 模式：本地改码，服务器编译。只上传编译必需的代码，plan/文档/git/本地脚本永不上传 =====
sync_code() {
    echo "==> 同步代码 → $REMOTE:$RDIR ..."
    rsync -az --delete \
      --exclude .git/  --exclude .github/  --exclude .gitignore \
      --exclude .opencode/ --exclude .claude/ --exclude .planning/ \
      --exclude .zed/ --exclude .vscode/ --exclude .idea/ \
      --exclude plan/ --exclude docs/ --exclude notes/ \
      --exclude tools/ \
      --exclude '*.md' --exclude '*.txt' --exclude '*.plan' \
      --exclude build/ --exclude .gradle/ --exclude .kotlin/ \
      --exclude local.properties \
      --exclude '*.apk' --exclude '*.aab' \
      --exclude build.sh --exclude test.sh \
      --exclude /app.png --exclude /LICENSE \
      --exclude .DS_Store \
      ./ "$REMOTE:$RDIR/"
}

remote_gradle() {
    # 语法/编译验证与出包统一走服务器：单测（含扩展模块纯 JVM 用例——目录存在才加测，
    # 与 settings.gradle.kts 条件挂载同口径）→ release 双模块。测试失败即中断（&& 链），
    # 不带病出包、更不会把坏包装进真机。
    local ext_test="" ext_build=""
    if [ -d extension ]; then
        ext_test=":extension:testDebugUnitTest"
        ext_build=":extension:assembleRelease"
    fi
    echo "==> 服务器验证+编译 release（versionCode=$VERSION_CODE buildId=$BUILD_ID）..."
    ssh "$REMOTE" "export ANDROID_HOME=/opt/android-sdk; cd $RDIR && \
      ./gradlew :app:testDebugUnitTest $ext_test --console=plain && \
      ./gradlew :app:assembleRelease $ext_build -Paccount.versionName='$VERSION_NAME' -Paccount.versionCode='$VERSION_CODE' -Paccount.buildId='$BUILD_ID' --console=plain"
}

# 拉回到与本地构建相同的 gradle 输出路径：./build.sh 系列与手动 adb install 都在同一处找包。
# 这些 APK 在 build/ 里，天然被 sync_code 排除，永远不会回传服务器。
pull_apks() {
    echo "==> 拉回 APK → app/build/outputs/apk/release/ ..."
    mkdir -p app/build/outputs/apk/release
    rm -f app/build/outputs/apk/release/account-*arm64-v8a.apk
    scp -q "$REMOTE:$RDIR/app/build/outputs/apk/release/account-*-arm64-v8a.apk" app/build/outputs/apk/release/
    if [ -d extension ]; then
        echo "==> 拉回扩展模块 APK ..."
        mkdir -p extension/build/outputs/apk/release
        rm -f extension/build/outputs/apk/release/extension-release.apk
        scp -q "$REMOTE:$RDIR/extension/build/outputs/apk/release/extension-release.apk" extension/build/outputs/apk/release/
    fi
}

run_ssh() {
    sync_code
    remote_gradle
    pull_apks
    local apk
    apk="$(ls app/build/outputs/apk/release/account-*-arm64-v8a.apk 2>/dev/null \
        || ls app/build/outputs/apk/release/*-arm64-v8a.apk 2>/dev/null \
        || ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
    [ -n "$apk" ] || { echo "未找到 release APK"; return 1; }
    echo "==> 产物: $apk"
    ls -lh "$apk" 2>/dev/null || true
    install_apk "$apk"
    if [ -f extension/build/outputs/apk/release/extension-release.apk ]; then
        install_apk extension/build/outputs/apk/release/extension-release.apk
    fi
}

build_all_install() {
    build_release 1
    echo
    if [ -d extension ]; then
        build_plugin 1
    else
        echo "==> 无 extension/ 目录，跳过扩展模块"
    fi
}

case "${1:-}" in
    release)         build_release 0 ;;
    release-install) build_release 1 ;;
    all)             build_debug && echo && build_release 0 ;;
    plugin)          build_plugin 1 ;;
    all-install)     build_all_install ;;
    ssh)             run_ssh ;;
    *)               build_debug ;;
esac
echo "==> 成功，exit 0"
