#!/bin/bash
# =============================================================================
# Demo 应用构建脚本
# =============================================================================
# 用法:
#   ./build.sh                  # 构建 debug 版本
#   ./build.sh release          # 构建 release 版本
#   ./build.sh install          # 构建并安装到设备
#   ./build.sh install release  # 构建 release 并安装
#   ./build.sh uninstall        # 卸载应用
#   ./build.sh help             # 显示帮助
# =============================================================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")"/.. && pwd)"
DEMO_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

BUILD_TYPE="debug"
ACTION="build"

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            release)  BUILD_TYPE="release"; shift ;;
            debug)    BUILD_TYPE="debug";   shift ;;
            install)  ACTION="install";     shift ;;
            uninstall) ACTION="uninstall";  shift ;;
            -h|--help|help) ACTION="help"; shift ;;
            *)
                echo -e "${RED}未知参数: $1${NC}"
                exit 1
                ;;
        esac
    done
}

show_help() {
    cat <<'EOF'
Demo 应用构建脚本

用法: ./build.sh [选项]

选项:
  release          Release 构建(默认 debug)
  install          构建并安装到设备
  uninstall        卸载应用
  help             显示此帮助信息

示例:
  ./build.sh                    # 构建 debug
  ./build.sh release            # 构建 release
  ./build.sh install            # 构建 debug 并安装
  ./build.sh install release    # 构建 release 并安装
  ./build.sh uninstall          # 卸载应用
EOF
}

do_build() {
    echo -e "${YELLOW}构建 demo-app ($BUILD_TYPE)...${NC}"
    local cap_type
    case "$BUILD_TYPE" in
        release) cap_type="Release" ;;
        debug)   cap_type="Debug"   ;;
    esac
    ./gradlew ":demo-app:assemble${cap_type}"

    local output="${DEMO_DIR}/build/outputs/apk/${BUILD_TYPE}/demo-app-${BUILD_TYPE}.apk"
    if [ -f "$output" ]; then
        echo -e "${GREEN}构建成功!${NC}"
        ls -lh "$output" | awk '{print "  大小: " $5}'
        echo "  路径: $output"
    else
        echo -e "${RED}构建失败${NC}"
        exit 1
    fi
}

do_install() {
    do_build
    echo -e "${YELLOW}安装到设备...${NC}"
    local output="${DEMO_DIR}/build/outputs/apk/${BUILD_TYPE}/demo-app-${BUILD_TYPE}.apk"
    adb install -r "$output"
    echo -e "${GREEN}安装成功!${NC}"
}

do_uninstall() {
    echo -e "${YELLOW}卸载应用...${NC}"
    adb uninstall com.reggate.demo
    echo -e "${GREEN}卸载完成${NC}"
}

main() {
    parse_args "$@"

    case "$ACTION" in
        help) show_help ;;
        build) do_build ;;
        install) do_install ;;
        uninstall) do_uninstall ;;
    esac
}

main "$@"
