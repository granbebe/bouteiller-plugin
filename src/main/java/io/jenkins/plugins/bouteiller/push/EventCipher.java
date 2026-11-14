package io.jenkins.plugins.bouteiller.push;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of an event with the AES-256 key of the device.
 *
 * <p>Output: {@code base64(nonce (12 bytes) || ciphertext || tag (16 bytes))}, the "combined" representation that
 * CryptoKit's {@code AES.GCM.SealedBox(combined:)} reads in the notification service extension.
 */
public final class EventCipher {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private EventCipher() {}

    public static String encrypt(byte[] key, byte[] plaintext) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        return encrypt(key, nonce, plaintext);
    }

    /** Encryption with a given nonce, for the test vector shared with the app only. */
    static String encrypt(byte[] key, byte[] nonce, byte[] plaintext) throws GeneralSecurityException {
        if (key.length != 32) {
            throw new IllegalArgumentException("The device key must hold 32 bytes");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        byte[] sealed = cipher.doFinal(plaintext);
        byte[] combined = new byte[nonce.length + sealed.length];
        System.arraycopy(nonce, 0, combined, 0, nonce.length);
        System.arraycopy(sealed, 0, combined, nonce.length, sealed.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    /** Decryption, for the tests. */
    static byte[] decrypt(byte[] key, String combined) throws GeneralSecurityException {
        byte[] bytes = Base64.getDecoder().decode(combined);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_LENGTH));
        return cipher.doFinal(bytes, NONCE_LENGTH, bytes.length - NONCE_LENGTH);
    }
}
