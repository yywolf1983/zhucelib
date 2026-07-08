import java.io.*;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public class EncryptConfig {
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

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java EncryptConfig <input_json> <output_dat>");
            System.exit(1);
        }

        File inputFile = new File(args[0]);
        File outputFile = new File(args[1]);

        if (!inputFile.exists()) {
            System.err.println("Error: Input file not found: " + inputFile);
            System.exit(1);
        }

        byte[] content = readFile(inputFile);
        byte[] encrypted = encrypt(content);

        writeFile(outputFile, encrypted);
        System.out.println("Encrypted: " + inputFile + " -> " + outputFile);
    }

    private static byte[] readFile(File file) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = is.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(data);
        }
    }

    private static byte[] encrypt(byte[] plaintext) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyHash = digest.digest(MASTER_KEY);
        SecretKey key = new SecretKeySpec(keyHash, "AES");

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        byte[] ciphertext = cipher.doFinal(plaintext);

        byte[] result = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

        return result;
    }
}