#!/bin/bash
# =============================================================================
# Android 注册系统 - 构建脚本
# =============================================================================
# 用法:
#   ./build.sh                  # 构建所有模块(debug)
#   ./build.sh release          # 构建所有模块(release)
#   ./build.sh lib              # 仅构建注册库(AAR)
#   ./build.sh keygen           # 仅构建注册机(APK)
#   ./build.sh clean            # 清理构建产物
#   ./build.sh init             # 初始化 Gradle Wrapper(国内源)
#   ./build.sh help             # 显示帮助
# =============================================================================

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 项目配置
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

GRADLE_VERSION="8.5"
GRADLE_DIST="gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_MIRRORS=(
    "https://mirrors.cloud.tencent.com/gradle"
    "https://mirrors.aliyun.com/macports/distfiles/gradle"
    "https://services.gradle.org/distributions"
)

# Gradle 8.x / AGP 8.x 兼容的 Java 版本范围 (Java 21 需 Gradle 8.5+)
JAVA_MIN=11
JAVA_MAX=21

# 模块定义: name|task_prefix|output_subpath
#   output_subpath 中 {TYPE} 会被替换为 debug/release
MODULES=(
    "registration-lib|registration-lib|build/outputs/aar/registration-lib-{TYPE}.aar"
    "keygen-app|keygen-app|build/outputs/apk/{TYPE}/keygen-app-{TYPE}.apk"
    "demo-app|demo-app|build/outputs/apk/{TYPE}/demo-app-{TYPE}.apk"
)

# 全局状态
BUILD_TYPE="debug"
TARGET="all"
GRADLE_CMD=""

# -----------------------------------------------------------------------------
# 工具函数
# -----------------------------------------------------------------------------

# 获取 java 二进制的版本号(主版本),失败返回空
java_major_version() {
    local java_bin="$1"
    [ -x "$java_bin" ] || return 1
    "$java_bin" -version 2>&1 | head -1 \
        | grep -o 'version "[^"]*"' | cut -d'"' -f2 | cut -d'.' -f1
}

# 判断版本是否在兼容范围
java_is_compatible() {
    local ver="$1"
    [ -n "$ver" ] && [ "$ver" -ge "$JAVA_MIN" ] 2>/dev/null && [ "$ver" -le "$JAVA_MAX" ] 2>/dev/null
}

# 探测兼容的 JAVA_HOME(按优先级),成功则赋值 _COMPAT_JAVA_HOME 并返回 0
find_compatible_java() {
    _COMPAT_JAVA_HOME=""

    # 1. Android Studio 内置 JDK / JRE(macOS)
    local as_jdk="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    local as_jre="/Applications/Android Studio.app/Contents/jre/Contents/Home"
    local cand
    for cand in "$as_jdk" "$as_jre"; do
        if [ -x "$cand/bin/java" ]; then
            local ver=$(java_major_version "$cand/bin/java")
            if java_is_compatible "$ver"; then
                echo "  找到 Android Studio JDK (Java $ver)"
                _COMPAT_JAVA_HOME="$cand"
                return 0
            fi
        fi
    done

    # 2. macOS java_home -V 列出所有版本
    if [ -f /usr/libexec/java_home ]; then
        local versions=$(/usr/libexec/java_home -V 2>&1 | tail -n +2 | awk '{print $1}')
        while IFS= read -r ver; do
            [ -z "$ver" ] && continue
            if java_is_compatible "$ver"; then
                local jh=$(/usr/libexec/java_home -v "$ver" 2>/dev/null)
                if [ -n "$jh" ] && [ -x "$jh/bin/java" ]; then
                    echo "  找到系统 Java $ver"
                    _COMPAT_JAVA_HOME="$jh"
                    return 0
                fi
            fi
        done <<< "$versions"
    fi

    # 3. 系统 java 命令
    if command -v java &>/dev/null; then
        local java_bin="$(command -v java)"
        local jh="$(dirname "$(dirname "$java_bin")")"
        local ver=$(java_major_version "$java_bin")
        if java_is_compatible "$ver"; then
            echo "  找到系统 Java $ver"
            _COMPAT_JAVA_HOME="$jh"
            return 0
        fi
    fi

    return 1
}

# 多镜像下载文件
download_file() {
    local url_path="$1"
    local output="$2"
    local desc="$3"

    for mirror in "${GRADLE_MIRRORS[@]}"; do
        echo -e "  尝试下载: $mirror"
        if curl -fL --connect-timeout 10 --max-time 300 -o "$output" "${mirror}/${url_path}" 2>/dev/null; then
            echo -e "${GREEN}  $desc 下载成功${NC}"
            return 0
        fi
        echo -e "  ${YELLOW}下载失败,尝试下一个镜像...${NC}"
    done
    return 1
}

# -----------------------------------------------------------------------------
# 环境检查
# -----------------------------------------------------------------------------

check_java() {
    echo -e "${YELLOW}检查 Java 环境...${NC}"

    # 取当前 JAVA_HOME(或系统 java)的版本号用于兼容性校验
    local current_home="$JAVA_HOME"
    local current_ver=""
    if [ -n "$current_home" ] && [ -x "$current_home/bin/java" ]; then
        current_ver=$(java_major_version "$current_home/bin/java")
    elif command -v java &>/dev/null; then
        current_ver=$(java_major_version "$(command -v java)")
    fi

    if java_is_compatible "$current_ver"; then
        # 当前已兼容:若 JAVA_HOME 未设,从系统 java 推导
        if [ -z "$JAVA_HOME" ]; then
            local java_bin="$(command -v java)"
            export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
        fi
        echo "  JAVA_HOME: $JAVA_HOME (Java $current_ver, 兼容 Gradle 8.x)"
    elif find_compatible_java; then
        # 当前不兼容,覆盖为找到的兼容版本
        if [ -n "$current_ver" ]; then
            echo -e "  ${YELLOW}当前 Java $current_ver 不兼容,切换到兼容版本${NC}"
        fi
        export JAVA_HOME="$_COMPAT_JAVA_HOME"
        echo "  JAVA_HOME: $JAVA_HOME"
    elif [ -n "$current_ver" ]; then
        # 找不到兼容版本,只能用当前(可能失败)
        if [ -z "$JAVA_HOME" ]; then
            local java_bin="$(command -v java)"
            export JAVA_HOME="$(dirname "$(dirname "$java_bin")")"
        fi
        echo "  JAVA_HOME: $JAVA_HOME"
        echo -e "  ${YELLOW}警告: Java $current_ver 与 Gradle $GRADLE_VERSION 不兼容 (需 Java $JAVA_MIN-$JAVA_MAX)${NC}"
        echo -e "  ${YELLOW}建议: 安装 Java 17 或 21${NC}"
        exit 1
    else
        echo -e "${RED}错误: 未找到 Java,请安装 JDK 11-21 并设置 JAVA_HOME${NC}"
        exit 1
    fi

    if [ -x "$JAVA_HOME/bin/java" ]; then
        echo "  版本: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
    fi
    echo ""
}

check_android_sdk() {
    echo -e "${YELLOW}检查 Android SDK...${NC}"
    if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
        local sdk_path="$HOME/Library/Android/sdk"
        if [ -d "$sdk_path" ]; then
            export ANDROID_HOME="$sdk_path"
            export ANDROID_SDK_ROOT="$sdk_path"
            echo "  ANDROID_HOME (自动检测): $ANDROID_HOME"
        else
            echo -e "${RED}错误: 未找到 Android SDK,请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT${NC}"
            exit 1
        fi
    else
        echo "  ANDROID_HOME: ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
    fi
    echo ""
}

init_gradle_wrapper() {
    if [ -f "$PROJECT_DIR/gradlew" ] && [ -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
        echo -e "${GREEN}Gradle Wrapper 已存在${NC}"
        return 0
    fi

    echo -e "${YELLOW}初始化 Gradle Wrapper (Gradle $GRADLE_VERSION)...${NC}"
    echo ""

    local tmp_dir="$(mktemp -d)"
    local gradle_zip="$tmp_dir/$GRADLE_DIST"

    echo -e "${YELLOW}下载 Gradle $GRADLE_VERSION (约 130MB)...${NC}"
    if ! download_file "$GRADLE_DIST" "$gradle_zip" "Gradle $GRADLE_VERSION"; then
        echo -e "${RED}错误: Gradle 下载失败,请检查网络连接${NC}"
        rm -rf "$tmp_dir"
        exit 1
    fi

    echo -e "${YELLOW}解压 Gradle...${NC}"
    unzip -q -o "$gradle_zip" -d "$tmp_dir"

    local gradle_home=$(find "$tmp_dir" -maxdepth 2 -name "gradle-$GRADLE_VERSION" -type d | head -1)
    if [ -z "$gradle_home" ] || [ ! -d "$gradle_home" ]; then
        echo -e "${RED}错误: 解压后未找到 gradle-$GRADLE_VERSION 目录${NC}"
        rm -rf "$tmp_dir"
        exit 1
    fi
    chmod +x "$gradle_home/bin/gradle"
    echo "  Gradle 路径: $gradle_home"

    echo -e "${YELLOW}生成 Gradle Wrapper...${NC}"
    if ! "$gradle_home/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin 2>&1; then
        echo -e "${RED}错误: gradle wrapper 命令执行失败${NC}"
        rm -rf "$tmp_dir"
        exit 1
    fi

    # 修改 distributionUrl 为国内镜像源
    # 注意: properties 文件里冒号被转义为 "\:",所以整行替换最稳妥
    local props="$PROJECT_DIR/gradle/wrapper/gradle-wrapper.properties"
    if [ -f "$props" ]; then
        local dist_url="https\\://mirrors.cloud.tencent.com/gradle/gradle-${GRADLE_VERSION}-bin.zip"
        if [ "$(uname)" = "Darwin" ]; then
            sed -i '' "s|^distributionUrl=.*|distributionUrl=${dist_url}|" "$props"
            # 顺手把 wrapper 默认 10s 超时调大,避免国内网络偶发超时
            sed -i '' "s|^networkTimeout=.*|networkTimeout=60000|" "$props"
        else
            sed -i "s|^distributionUrl=.*|distributionUrl=${dist_url}|" "$props"
            sed -i "s|^networkTimeout=.*|networkTimeout=60000|" "$props"
        fi
        echo "  已切换下载源: 腾讯云镜像"
    fi

    chmod +x "$PROJECT_DIR/gradlew" 2>/dev/null || true
    rm -rf "$tmp_dir"

    echo ""
    echo -e "${GREEN}Gradle Wrapper 初始化完成!${NC}"
    echo "  Gradle 版本: $GRADLE_VERSION"
    echo "  下载源: 腾讯云镜像"
    echo ""
}

check_gradle() {
    echo -e "${YELLOW}检查 Gradle...${NC}"

    if [ -f "$PROJECT_DIR/gradlew" ] && [ -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
        GRADLE_CMD="./gradlew"
        echo "  使用 Gradle Wrapper"
    elif command -v gradle &>/dev/null; then
        GRADLE_CMD="gradle"
        echo "  使用系统 Gradle: $(gradle --version 2>/dev/null | head -1)"
    else
        echo "  未找到 Gradle,正在初始化..."
        init_gradle_wrapper
        GRADLE_CMD="./gradlew"
    fi
    echo ""
}

check_env() {
    check_java
    check_android_sdk
    check_gradle
    echo -e "${GREEN}环境检查通过${NC}"
    echo ""
}

# -----------------------------------------------------------------------------
# 构建逻辑
# -----------------------------------------------------------------------------

# 构建单个模块
#   $1 = 模块定义(name|task_prefix|output_subpath)
build_module() {
    local def="$1"
    local name="${def%%|*}"
    local rest="${def#*|}"
    local task_prefix="${rest%%|*}"
    local out_pattern="${rest#*|}"

    local cap_type
    case "$BUILD_TYPE" in
        release) cap_type="Release" ;;
        debug)   cap_type="Debug"   ;;
    esac
    local task=":${task_prefix}:assemble${cap_type}"
    local output="${out_pattern//\{TYPE\}/$BUILD_TYPE}"
    output="${name}/${output}"

    echo -e "${YELLOW}构建 $name - $BUILD_TYPE${NC}"
    $GRADLE_CMD "$task"

    if [ -f "$output" ]; then
        echo -e "${GREEN}$name 构建成功: $output${NC}"
        ls -lh "$output" | awk '{print "    大小: " $5}'
    else
        echo -e "${RED}$name 构建失败${NC}"
        exit 1
    fi
}

# 构建所有模块
build_all() {
    echo -e "${YELLOW}构建所有模块 - $BUILD_TYPE${NC}"
    echo ""

    if [ "$BUILD_TYPE" = "release" ]; then
        $GRADLE_CMD assembleRelease
    else
        $GRADLE_CMD assembleDebug
    fi

    echo ""
    echo -e "${GREEN}=== 构建产物 ===${NC}"
    local def name out_pattern output
    for def in "${MODULES[@]}"; do
        name="${def%%|*}"
        out_pattern="${def##*|}"
        output="${name}/${out_pattern//\{TYPE\}/$BUILD_TYPE}"
        if [ -f "$output" ]; then
            echo "  $name: $output"
            ls -lh "$output" | awk '{print "    大小: " $5}'
        fi
    done

    echo ""
    echo -e "${GREEN}构建完成!${NC}"
}

do_clean() {
    echo -e "${YELLOW}清理构建产物...${NC}"
    if [ -f "gradlew" ]; then
        ./gradlew clean 2>/dev/null || true
    elif command -v gradle &>/dev/null; then
        gradle clean 2>/dev/null || true
    fi
    rm -rf "registration-lib/build" "keygen-app/build" "build"
    echo -e "${GREEN}清理完成${NC}"
}

# -----------------------------------------------------------------------------
# 参数与帮助
# -----------------------------------------------------------------------------

show_help() {
    cat <<'EOF'
Android 注册系统 - 构建脚本

用法: ./build.sh [选项]

选项:
  release          Release 构建(默认 debug)
  lib              仅构建注册库 (registration-lib)
  keygen           仅构建注册机 (keygen-app)
  demo             仅构建 Demo 应用 (demo-app)
  clean            清理所有构建产物
  init             初始化 Gradle Wrapper(自动从国内源下载)
  help             显示此帮助信息

示例:
  ./build.sh                    # 构建所有模块 (debug)
  ./build.sh release            # 构建所有模块 (release)
  ./build.sh lib release        # 构建注册库 (release)
  ./build.sh keygen debug       # 构建注册机 (debug)
  ./build.sh demo release       # 构建 Demo 应用 (release)
  ./build.sh init               # 初始化 Gradle Wrapper
  ./build.sh clean              # 清理构建产物
EOF
}

parse_args() {
    while [ $# -gt 0 ]; do
        case "$1" in
            release)  BUILD_TYPE="release"; shift ;;
            debug)    BUILD_TYPE="debug";   shift ;;
            lib|registration-lib) TARGET="lib"; shift ;;
            keygen|keygen-app|app) TARGET="keygen"; shift ;;
            demo|demo-app) TARGET="demo"; shift ;;
            clean)    TARGET="clean"; shift ;;
            init|wrapper) TARGET="init"; shift ;;
            -h|--help|help) TARGET="help"; shift ;;
            *)
                echo -e "${RED}未知参数: $1${NC}"
                exit 1
                ;;
        esac
    done
}

# -----------------------------------------------------------------------------
# 主入口
# -----------------------------------------------------------------------------

main() {
    parse_args "$@"

    case "$TARGET" in
        help)
            show_help
            exit 0
            ;;
        clean)
            do_clean
            exit 0
            ;;
        init)
            check_java
            init_gradle_wrapper
            exit 0
            ;;
        *)
            check_env
            ;;
    esac

    case "$TARGET" in
        lib)
            build_module "${MODULES[0]}"
            ;;
        keygen)
            build_module "${MODULES[1]}"
            ;;
        demo)
            build_module "${MODULES[2]}"
            ;;
        all)
            build_all
            ;;
    esac
}

main "$@"
