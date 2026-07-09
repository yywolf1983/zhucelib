package com.reggate.lib;

import android.util.Base64;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class RegGateCrypto {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_LENGTH = 256;

    private static final byte[] MASTER_KEY = new byte[]{
        (byte) 0x52, (byte) 0x65, (byte) 0x67, (byte) 0x47,
        (byte) 0x61, (byte) 0x74, (byte) 0x65, (byte) 0x4C,
        (byte) 0x69, (byte) 0x62, (byte) 0x32, (byte) 0x30,
        (byte) 0x32, (byte) 0x34, (byte) 0x4B, (byte) 0x65,
        (byte) 0x79, (byte) 0x79, (byte) 0x4E, (byte) 0x6F,
        (byte) 0x6E, (byte) 0x65, (byte) 0x73, (byte) 0x54,
        (byte) 0x6F, (byte) 0x70, (byte) 0x41, (byte) 0x70,
        (byte) 0x70, (byte) 0x4B, (byte) 0x65, (byte) 0x79
    };

    public static byte[] encrypt(String plaintext) throws GeneralSecurityException {
        return encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
        byte[] iv = generateIV();
        SecretKey key = deriveKey();

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

        return result;
    }

    public static String decrypt(byte[] encrypted) throws GeneralSecurityException {
        byte[] decryptedBytes = decryptToBytes(encrypted);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    public static byte[] decryptToBytes(byte[] encrypted) throws GeneralSecurityException {
        if (encrypted.length < GCM_IV_LENGTH) {
            throw new InvalidKeyException("Invalid encrypted data");
        }

        byte[] iv = Arrays.copyOfRange(encrypted, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, GCM_IV_LENGTH, encrypted.length);

        SecretKey key = deriveKey();

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        return cipher.doFinal(ciphertext);
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static byte[] generateIV() {
        byte[] iv = new byte[GCM_IV_LENGTH];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    private static SecretKey deriveKey() throws NoSuchAlgorithmException {
        byte[] keyHash = sha256(MASTER_KEY);
        return new SecretKeySpec(keyHash, ALGORITHM);
    }

    private static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(data);
    }

    public static String encryptToBase64(String plaintext) throws GeneralSecurityException {
        byte[] encrypted = encrypt(plaintext);
        return Base64.encodeToString(encrypted, Base64.DEFAULT);
    }

    public static String decryptFromBase64(String base64Encrypted) throws GeneralSecurityException {
        byte[] encrypted = Base64.decode(base64Encrypted, Base64.DEFAULT);
        return decrypt(encrypted);
    }
}