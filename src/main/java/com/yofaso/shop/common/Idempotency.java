package com.yofaso.shop.common;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Fingerprint bền vững trước SQL; ID đơn/kho xác định và đối chiếu SQL khi retry. */
@Service
public class Idempotency {
  private final Db db;
  private final JsonMapper json;

  /** Nhận DB và JSON mapper cho fingerprint payload. */
  public Idempotency(Db db, JsonMapper json) {
    this.db = db;
    this.json = json;
  }

  /** SHA256 dùng cho refresh token và fingerprint; không lưu token thô. */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Khóa user trước để hai request cùng người không cùng tạo một tài nguyên. */
  public String existing(String owner, String scope, String key, Object payload) {
    Problem.require(
        key != null && key.matches("[A-Za-z0-9_-]{8,80}"),
        400,
        "IDEMPOTENCY_KEY_INVALID",
        "Idempotency-Key cần 8..80 chữ/số/-/_.");
    db.one("SELECT MaNguoiDung FROM NguoiDung WHERE MaNguoiDung=? FOR UPDATE", owner);
    String fingerprint = hash(json.writeValueAsString(payload));
    String identity = hash(owner + ":" + scope + ":" + key);
    var rows = db.runtime().find("idempotency", r -> identity.equals(r.get("identity")));
    if (!rows.isEmpty()) {
      var r = rows.getFirst();
      Problem.require(
          fingerprint.equals(r.get("Fingerprint")),
          409,
          "IDEMPOTENCY_CONFLICT",
          "Key đã dùng với nội dung khác.");
      String id = Db.text(r, "ResourceId");
      if (scope.equals("ORDER") || scope.equals("POS_ORDER")) {
        if(id.isEmpty()) id=resourceId(owner,scope,key,"DH");
        return db.count("SELECT COUNT(*) FROM DonHang WHERE MaDonHang=?", id) > 0 ? id : null;
      }
      if(scope.equals("INVENTORY_ADJUST")) {
        if(id.isEmpty()) id=resourceId(owner,scope,key,"BDK");
        return db.count("SELECT COUNT(*) FROM BienDongKho WHERE MaBienDong=?",id)>0 ? id : null;
      }
      if (id.isEmpty()) return null;
      if(scope.equals("TRY_ON") && db.count("SELECT COUNT(*) FROM PhienThuDoAo WHERE MaPhienThuDo=?",id)==0) return null;
      final String resource=id;
      if(Set.of("TRY_ON","ANALYSIS").contains(scope) && db.runtime().find("operations",op -> resource.equals(op.get("OperationId"))).isEmpty()) return null;
      return id;
    }
    db.runtime().put("idempotency", identity, java.util.Map.of("identity", identity, "Fingerprint", fingerprint));
    if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override public void afterCompletion(int status) {
            if(status==STATUS_ROLLED_BACK) db.runtime().remove("idempotency",identity);
          }
        });
    return null;
  }

  /** Gắn ID; retry phải đối chiếu tài nguyên SQL vì manifest không chung transaction MySQL. */
  public void complete(String owner, String scope, String key, String id) {
    db.runtime().patch("idempotency", hash(owner + ":" + scope + ":" + key), "ResourceId", id);
  }
  public String resourceId(String owner, String scope, String key, String prefix) {
    return prefix + hash(owner + ":" + scope + ":" + key).substring(0, 20-prefix.length());
  }
}
