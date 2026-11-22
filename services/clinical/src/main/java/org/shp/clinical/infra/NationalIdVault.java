package org.shp.clinical.infra;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The citizen identity number (CCCD) at rest (01 §4.1; 03 §2.2): AES-256-GCM ciphertext ({@code
 * search.nationalIdEnc}), HMAC-SHA-256 for uniqueness ({@code search.nationalIdHash}) and the last
 * four digits for display. The clear number is never stored, logged or put in an event.
 *
 * <p>Keys: {@code shp.clinical.national-id.enc-key} and {@code hash-key} (base64, 32 bytes each;
 * environment {@code SHP_CLINICAL_NATIONAL_ID_ENC_KEY} / {@code ..._HASH_KEY}, Key Vault in the
 * cloud). Without them a random key is generated at startup with a warning, as {@code PagingConfig}
 * does: nothing secret is in the code, but stored numbers cannot be decrypted after a restart, so
 * every real deployment must set them.
 */
@Component
public class NationalIdVault {
  private static final Logger LOG = LoggerFactory.getLogger(NationalIdVault.class);
  static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec encKey;
  private final SecretKeySpec hashKey;
  private final SecureRandom random = new SecureRandom();

  public NationalIdVault(
      @Value("${shp.clinical.national-id.enc-key:}") String encKey,
      @Value("${shp.clinical.national-id.hash-key:}") String hashKey) {
    this.encKey = new SecretKeySpec(key(encKey, "enc-key"), "AES");
    this.hashKey = new SecretKeySpec(key(hashKey, "hash-key"), "HmacSHA256");
  }

  private byte[] key(String configured, String name) {
    if (configured == null || configured.isBlank()) {
      LOG.warn(
          "shp.clinical.national-id.{} is not set: using a random per-process key; stored national"
              + " ID numbers cannot be read after a restart",
          name);
      byte[] k = new byte[KEY_BYTES];
      random.nextBytes(k);
      return k;
    }
    byte[] k = Base64.getDecoder().decode(configured.strip());
    if (k.length != KEY_BYTES) {
      throw new IllegalStateException("shp.clinical.national-id." + name + " must be 32 bytes");
    }
    return k;
  }

  /** base64(iv || ciphertext+tag). */
  public String encrypt(String number) {
    try {
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = c.doFinal(number.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("national ID encryption failed", e);
    }
  }

  public String decrypt(String stored) {
    try {
      byte[] all = Base64.getDecoder().decode(stored);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
      return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("national ID decryption failed", e);
    }
  }

  /** Keyed hash, so equal numbers can be found without storing them (hex). */
  public String hash(String number) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hashKey);
      return HexFormat.of().formatHex(mac.doFinal(number.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("national ID hashing failed", e);
    }
  }

  /** {@code ********1234}: what every response shows. */
  public static String mask(String last4) {
    return "********" + last4;
  }
}
