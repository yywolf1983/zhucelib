package com.keygen.app;

/**
 * Crockford Base32 编解码(与注册库 com.reggate.lib.Base32 完全一致)。
 *
 * 字母表: 0123456789ABCDEFGHJKMNPQRSTVWXYZ(排除 I/L/O/U)
 */
final class Base32 {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int[] DECODE_TABLE = buildDecodeTable();

    private Base32() {}

    static String encode(byte[] data) {
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

    static byte[] decode(String s) {
        if (s == null) return null;
        String clean = s.replaceAll("[\\s-]", "").toUpperCase();
        if (clean.length() == 0) return new byte[0];

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

    static String group(String s, int groupSize) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length() + s.length() / groupSize);
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && i % groupSize == 0) sb.append('-');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    static String ungroup(String s) {
        if (s == null) return null;
        return s.replaceAll("[\\s-]", "").toUpperCase();
    }

    private static int[] buildDecodeTable() {
        int[] t = new int[128];
        for (int i = 0; i < 128; i++) t[i] = -1;
        for (int i = 0; i < ALPHABET.length(); i++) {
            t[ALPHABET.charAt(i)] = i;
            t[Character.toLowerCase(ALPHABET.charAt(i))] = i;
        }
        t['O'] = 0; t['o'] = 0;
        t['I'] = 1; t['i'] = 1;
        t['L'] = 1; t['l'] = 1;
        return t;
    }
}
