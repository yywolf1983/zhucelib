package com.reggate.lib;

/**
 * Crockford Base32 编解码。
 *
 * 字母表: 0123456789ABCDEFGHJKMNPQRSTVWXYZ(排除 I/L/O/U 等易混淆字符)
 * - 解码大小写不敏感
 * - 用于生成"易于输入"的安装码/激活码
 *
 * 该类与注册机端 {@code com.keygen.app.Base32} 完全一致。
 */
public final class Base32 {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int[] DECODE_TABLE = buildDecodeTable();

    private Base32() {}

    /** 编码为无填充的 Crockford Base32 字符串。 */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                int idx = (buffer >> (bitsLeft - 5)) & 0x1F;
                sb.append(ALPHABET.charAt(idx));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            int idx = (buffer << (5 - bitsLeft)) & 0x1F;
            sb.append(ALPHABET.charAt(idx));
        }
        return sb.toString();
    }

    /** 最大允许的解码输入长度(防止恶意超长输入导致 OOM)。 */
    private static final int MAX_DECODE_LENGTH = 1024;

    /** 解码;非法字符返回 null。 */
    public static byte[] decode(String s) {
        if (s == null) return null;
        String clean = s.replaceAll("[\\s-]", "").toUpperCase();
        if (clean.length() == 0) return new byte[0];
        if (clean.length() > MAX_DECODE_LENGTH) return null;

        byte[] out = new byte[clean.length() * 5 / 8];
        int buffer = 0;
        int bitsLeft = 0;
        int idx = 0;
        for (int i = 0; i < clean.length(); i++) {
            int v = DECODE_TABLE[clean.charAt(i)];
            if (v < 0) return null;
            buffer = (buffer << 5) | v;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        if (idx == out.length) return out;
        byte[] trimmed = new byte[idx];
        System.arraycopy(out, 0, trimmed, 0, idx);
        return trimmed;
    }

    /** 按每组 5 字符分组,用 '-' 连接,便于阅读/输入。 */
    public static String group(String s) {
        return group(s, 5);
    }

    /** 按每组 groupSize 字符分组,用 '-' 连接,便于阅读/输入。 */
    public static String group(String s, int groupSize) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length() + s.length() / groupSize);
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % groupSize == 0) sb.append('-');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    /** 去除分组连字符/空白(用户输入后规范化)。 */
    public static String ungroup(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static int[] buildDecodeTable() {
        int[] t = new int[128];
        for (int i = 0; i < 128; i++) t[i] = -1;
        for (int i = 0; i < ALPHABET.length(); i++) {
            t[ALPHABET.charAt(i)] = i;
            // 大小写不敏感
            t[Character.toLowerCase(ALPHABET.charAt(i))] = i;
        }
        // Crockford 兼容映射: O/o -> 0, I/i/L/l -> 1
        t['O'] = 0; t['o'] = 0;
        t['I'] = 1; t['i'] = 1;
        t['L'] = 1; t['l'] = 1;
        return t;
    }
}
