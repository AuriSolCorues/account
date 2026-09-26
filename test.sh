#!/usr/bin/env bash
# 自动测试：本地单元测试 → 构建 debug → 安装到真机。
# 用法:
#   ./test.sh              跑单元测试 + 构建 debug + 安装
#   ./test.sh unit         只跑单元测试
#   ./test.sh build        只构建 debug（不装）
#   ./test.sh androidTest  只跑仪器化测试（需连着真机/模拟器，不在默认流程里）
set -euo pipefail
cd "$(dirname "$0")"

run_unit() {
    echo "==> 单元测试 :app:testDebugUnitTest ..."
    ./gradlew :app:testDebugUnitTest --console=plain
    echo "==> 测试通过"
}

run_build() {
    echo "==> 构建 debug APK ..."
    ./gradlew :app:assembleDebug --console=plain
}

run_android_test() {
    echo "==> 仪器化测试 :app:connectedDebugAndroidTest ..."
    ./gradlew :app:connectedDebugAndroidTest --console=plain
}

case "${1:-}" in
    unit)        run_unit ;;
    build)       run_build ;;
    androidTest) run_android_test ;;
    *)           run_unit && ./build.sh ;;
esac
