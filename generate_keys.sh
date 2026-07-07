#!/usr/bin/env bash
set -e

KEY_NAME="${1:-reggate}"
OUTPUT_DIR="${2:-keys}"

mkdir -p "$OUTPUT_DIR"

if command -v openssl >/dev/null 2>&1; then
    echo "生成 RSA-2048 私钥..."
    openssl genrsa -out "${OUTPUT_DIR}/${KEY_NAME}_priv.pem" 2048

    echo "导出公钥..."
    openssl rsa -in "${OUTPUT_DIR}/${KEY_NAME}_priv.pem" -pubout -out "${OUTPUT_DIR}/${KEY_NAME}_pub.pem"

    echo "生成 base64 编码的公钥（用于嵌入代码）..."
    openssl rsa -in "${OUTPUT_DIR}/${KEY_NAME}_priv.pem" -pubout -outform PEM \
        | sed '/^-----BEGIN PUBLIC KEY-----$/d' \
        | sed '/^-----END PUBLIC KEY-----$/d' \
        | tr -d '\n' \
        > "${OUTPUT_DIR}/${KEY_NAME}_pub_base64.txt"

    echo ""
    echo "========================================"
    echo "密钥生成完成！"
    echo "========================================"
    echo ""
    echo "私钥文件: ${OUTPUT_DIR}/${KEY_NAME}_priv.pem"
    echo "公钥文件: ${OUTPUT_DIR}/${KEY_NAME}_pub.pem"
    echo "Base64公钥: ${OUTPUT_DIR}/${KEY_NAME}_pub_base64.txt"
    echo ""
    echo "使用说明:"
    echo "  1. 将私钥保存到安全位置（不要提交到版本控制）"
    echo "  2. 将 ${OUTPUT_DIR}/${KEY_NAME}_pub_base64.txt 的内容嵌入注册库源码"
    echo "  3. 在注册机中选择私钥文件进行激活码签名"
    echo ""
else
    echo "错误: 未找到 openssl 命令"
    echo "请安装 OpenSSL: brew install openssl (macOS) 或 sudo apt install openssl (Linux)"
    exit 1
fi