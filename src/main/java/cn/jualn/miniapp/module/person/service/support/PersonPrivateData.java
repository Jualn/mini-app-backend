package cn.jualn.miniapp.module.person.service.support;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;

import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.unavailable;

/**
 * Separate purpose keys, no default secrets. GCM envelope v1 binds ciphertext to its purpose.
 */
@Component
public class PersonPrivateData {
    private final String encryption, matching;
    private final SecureRandom random = new SecureRandom();

    public PersonPrivateData(@Value("${people-qualification.encryption-key:}") String encryption,
                             @Value("${people-qualification.match-key:}") String matching) {
        this.encryption = encryption;
        this.matching = matching;
    }

    private byte[] key(String configured) {
        try {
            var value = Base64.getDecoder().decode(configured);
            if (value.length != 32) throw unavailable();
            return value;
        } catch (IllegalArgumentException e) {
            throw unavailable();
        }
    }

    public String digest(String purpose, String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(matching), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(("v1:" + purpose + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw unavailable();
        }
    }

    public byte[] encrypt(String purpose, byte[] input) {
        var nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(encryption), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("v1:" + purpose).getBytes(StandardCharsets.UTF_8));
            var bytes = cipher.doFinal(input);
            return ByteBuffer.allocate(1 + 12 + bytes.length).put((byte) 1).put(nonce).put(bytes).array();
        } catch (GeneralSecurityException e) {
            throw unavailable();
        }
    }

    public byte[] decrypt(String purpose, byte[] envelope) {
        if (envelope == null || envelope.length < 29 || envelope[0] != 1) throw unavailable();
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(encryption), "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
            cipher.updateAAD(("v1:" + purpose).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(envelope, 13, envelope.length - 13);
        } catch (GeneralSecurityException e) {
            throw unavailable();
        }
    }
}
