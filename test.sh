#!/usr/bin/env bash
# 自动测试：本地单元测试 → 构建 debug → 安装到真机。
# 用法:
#   ./test.sh          跑单元测试 + 构建 debug + 安装
#   ./test.sh unit     只跑单元测试
#   ./test.sh build    只构建 debug（不装）
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

case "${1:-}" in
    unit)  run_unit ;;
    build) run_build ;;
    *)     run_unit && ./build.sh ;;
esac
