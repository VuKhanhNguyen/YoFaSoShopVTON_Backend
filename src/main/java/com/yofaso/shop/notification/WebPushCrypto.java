package com.yofaso.shop.notification;

import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Triển khai chuẩn RFC 8291 (aes128gcm) và RFC 8292 (VAPID ES256) bằng JDK chuẩn,
 * không kéo thêm dependency BouncyCastle hay Apache HttpClient cũ.
 */
public final class WebPushCrypto {
  private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
  private static final SecureRandom RNG = new SecureRandom();
  private static final ECParameterSpec P256_SPEC = initP256Spec();
  private static final byte[] INFO_WEBPUSH_PREFIX = "WebPush: info\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] INFO_CEK = "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] INFO_NONCE = "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII);
  private static final int RECORD_SIZE = 4096;

  private WebPushCrypto() {}

  public record VapidKeyPair(String publicKey, String privateKey) {}

  private static ECParameterSpec initP256Spec() {
    try {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
      gen.initialize(new ECGenParameterSpec("secp256r1"));
      return ((ECPublicKey) gen.generateKeyPair().getPublic()).getParams();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("JVM không hỗ trợ đường cong secp256r1.", e);
    }
  }

  /** Sinh cặp khóa VAPID chuẩn Base64URL (65 byte public, 32 byte private). */
  public static VapidKeyPair generateVapidKeyPair() throws GeneralSecurityException {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"), RNG);
    KeyPair kp = gen.generateKeyPair();
    byte[] pub = encodeUncompressedPublicKey((ECPublicKey) kp.getPublic());
    byte[] priv = toFixedUnsigned(((ECPrivateKey) kp.getPrivate()).getS(), 32);
    return new VapidKeyPair(B64_ENC.encodeToString(pub), B64_ENC.encodeToString(priv));
  }

  /** Tạo header Authorization theo chuẩn RFC 8292: vapid t=<jwt>, k=<publicKey>. */
  public static String createVapidAuthHeader(
      String endpoint, String subject, String publicKeyB64, String privateKeyB64)
      throws GeneralSecurityException {
    URI uri = URI.create(endpoint);
    String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    long exp = Instant.now().plusSeconds(12 * 3600).getEpochSecond();
    String sub = (subject == null || subject.isBlank()) ? "mailto:admin@yofasoshop.vn" : subject.trim();

    String headerJson = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";
    String payloadJson =
        "{\"aud\":\"" + escapeJson(audience) + "\",\"exp\":" + exp + ",\"sub\":\"" + escapeJson(sub) + "\"}";
    String signingInput =
        B64_ENC.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
            + "."
            + B64_ENC.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

    ECPrivateKey privKey = decodePrivateKey(privateKeyB64);
    Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
    sig.initSign(privKey, RNG);
    sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    String jwt = signingInput + "." + B64_ENC.encodeToString(sig.sign());
    return "vapid t=" + jwt + ", k=" + publicKeyB64.trim();
  }

  /** Mã hóa payload Web Push theo chuẩn RFC 8291 (Content-Encoding: aes128gcm). */
  public static byte[] encryptAes128Gcm(byte[] plaintext, String userPublicKeyB64, String authSecretB64)
      throws GeneralSecurityException {
    byte[] uaPublicBytes = decodeBase64Url(userPublicKeyB64);
    byte[] authSecret = decodeBase64Url(authSecretB64);
    if (uaPublicBytes.length != 65 || uaPublicBytes[0] != 0x04) {
      throw new IllegalArgumentException("p256dh phải là khóa công khai P-256 uncompressed 65 byte.");
    }
    if (authSecret.length != 16) {
      throw new IllegalArgumentException("auth secret phải có độ dài đúng 16 byte.");
    }

    ECPublicKey uaPublic = decodeUncompressedPublicKey(uaPublicBytes);
    KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"), RNG);
    KeyPair asPair = gen.generateKeyPair();
    byte[] asPublicBytes = encodeUncompressedPublicKey((ECPublicKey) asPair.getPublic());

    byte[] salt = new byte[16];
    RNG.nextBytes(salt);

    byte[] sharedSecret = ecdh(asPair.getPrivate(), uaPublic);
    byte[] keyInfo = concat(INFO_WEBPUSH_PREFIX, uaPublicBytes, asPublicBytes);
    byte[] prkKey = hmacSha256(authSecret, sharedSecret);
    byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

    byte[] prk = hmacSha256(salt, ikm);
    byte[] cek = hkdfExpand(prk, INFO_CEK, 16);
    byte[] nonce = hkdfExpand(prk, INFO_NONCE, 12);

    byte[] padded = new byte[plaintext.length + 1];
    System.arraycopy(plaintext, 0, padded, 0, plaintext.length);
    padded[plaintext.length] = 0x02; // final record delimiter

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] ciphertext = cipher.doFinal(padded);

    // RFC 8188 header: salt(16) || rs(4) || idlen(1) || asPublic(65)
    ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublicBytes.length + ciphertext.length);
    out.put(salt);
    out.putInt(RECORD_SIZE);
    out.put((byte) asPublicBytes.length);
    out.put(asPublicBytes);
    out.put(ciphertext);
    return out.array();
  }

  /** Giải mã RFC 8291 (dùng cho unit test xác minh vòng lặp mã hóa/giải mã). */
  static byte[] decryptAes128Gcm(
      byte[] body, ECPrivateKey uaPrivate, ECPublicKey uaPublic, byte[] authSecret)
      throws GeneralSecurityException {
    ByteBuffer buf = ByteBuffer.wrap(body);
    byte[] salt = new byte[16];
    buf.get(salt);
    buf.getInt(); // rs
    int idLen = Byte.toUnsignedInt(buf.get());
    byte[] asPublicBytes = new byte[idLen];
    buf.get(asPublicBytes);
    byte[] ciphertext = new byte[buf.remaining()];
    buf.get(ciphertext);

    byte[] uaPublicBytes = encodeUncompressedPublicKey(uaPublic);
    ECPublicKey asPublic = decodeUncompressedPublicKey(asPublicBytes);
    byte[] sharedSecret = ecdh(uaPrivate, asPublic);
    byte[] keyInfo = concat(INFO_WEBPUSH_PREFIX, uaPublicBytes, asPublicBytes);
    byte[] prkKey = hmacSha256(authSecret, sharedSecret);
    byte[] ikm = hkdfExpand(prkKey, keyInfo, 32);

    byte[] prk = hmacSha256(salt, ikm);
    byte[] cek = hkdfExpand(prk, INFO_CEK, 16);
    byte[] nonce = hkdfExpand(prk, INFO_NONCE, 12);

    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
    byte[] padded = cipher.doFinal(ciphertext);
    int end = padded.length - 1;
    while (end >= 0 && padded[end] == 0x00) end--;
    if (end < 0 || padded[end] != 0x02) {
      throw new GeneralSecurityException("Sai byte kết thúc bản ghi RFC 8188.");
    }
    return Arrays.copyOf(padded, end);
  }

  static ECPublicKey decodeUncompressedPublicKey(byte[] uncompressed) throws GeneralSecurityException {
    BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
    BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
    KeyFactory kf = KeyFactory.getInstance("EC");
    return (ECPublicKey) kf.generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256_SPEC));
  }

  static byte[] encodeUncompressedPublicKey(ECPublicKey key) {
    byte[] x = toFixedUnsigned(key.getW().getAffineX(), 32);
    byte[] y = toFixedUnsigned(key.getW().getAffineY(), 32);
    byte[] out = new byte[65];
    out[0] = 0x04;
    System.arraycopy(x, 0, out, 1, 32);
    System.arraycopy(y, 0, out, 33, 32);
    return out;
  }

  static ECPrivateKey decodePrivateKey(String base64Url) throws GeneralSecurityException {
    byte[] raw = decodeBase64Url(base64Url);
    KeyFactory kf = KeyFactory.getInstance("EC");
    if (raw.length == 32) {
      return (ECPrivateKey) kf.generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256_SPEC));
    }
    return (ECPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(raw));
  }

  private static byte[] ecdh(PrivateKey priv, PublicKey pub) throws GeneralSecurityException {
    KeyAgreement ka = KeyAgreement.getInstance("ECDH");
    ka.init(priv);
    ka.doPhase(pub, true);
    return ka.generateSecret();
  }

  private static byte[] hmacSha256(byte[] key, byte[] data) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  private static byte[] hkdfExpand(byte[] prk, byte[] info, int len) throws GeneralSecurityException {
    byte[] input = new byte[info.length + 1];
    System.arraycopy(info, 0, input, 0, info.length);
    input[info.length] = 0x01;
    byte[] full = hmacSha256(prk, input);
    return Arrays.copyOf(full, len);
  }

  private static byte[] decodeBase64Url(String raw) {
    String clean = raw == null ? "" : raw.trim().replace('+', '-').replace('/', '_');
    return B64_DEC.decode(clean);
  }

  private static byte[] toFixedUnsigned(BigInteger val, int length) {
    byte[] src = val.toByteArray();
    byte[] out = new byte[length];
    if (src.length == length) return src;
    if (src.length > length) {
      System.arraycopy(src, src.length - length, out, 0, length);
    } else {
      System.arraycopy(src, 0, out, length - src.length, src.length);
    }
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int total = 0;
    for (byte[] p : parts) total += p.length;
    byte[] out = new byte[total];
    int pos = 0;
    for (byte[] p : parts) {
      System.arraycopy(p, 0, out, pos, p.length);
      pos += p.length;
    }
    return out;
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
