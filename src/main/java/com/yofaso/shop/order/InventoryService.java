package com.yofaso.shop.order;

import com.yofaso.shop.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tồn kho single pool; mỗi thay đổi đều khóa SKU và ghi ledger cùng transaction. */
@Service
public class InventoryService {
  private final Db db;

  /** Nhận DB dùng chung với transaction đặt hàng. */
  public InventoryService(Db db) {
    this.db = db;
  }

  /** Điều chỉnh nguyên tử; không trừ thêm khi giao hàng vì checkout đã giữ tồn. */
  @Transactional
  public String move(
      String variant, int delta, String type, String reference, String actor, String note) {
    return moveWithId(variant,delta,type,reference,actor,note,Db.id("BDK"));
  }

  /** ID xác định từ idempotency key cho phiếu điều chỉnh; SQL rollback vẫn có thể retry. */
  @Transactional
  public String moveWithId(String variant,int delta,String type,String reference,String actor,String note,String movement) {
    var row =
        db.one("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=? FOR UPDATE", variant);
    int before = ((Number) row.get("SoLuongTonKho")).intValue();
    long after = (long) before + delta;
    Problem.require(
        delta != 0 && after >= 0 && after <= 1000000000,
        409,
        "STOCK_INSUFFICIENT",
        "Tồn kho không đủ hoặc số lượng điều chỉnh không hợp lệ.");
    db.exec("UPDATE BienTheSanPham SET SoLuongTonKho=? WHERE MaBienThe=?", after, variant);
    db.exec(
        "INSERT INTO"
            + " BienDongKho(MaBienDong,MaBienThe,LoaiBienDong,SoLuongThayDoi,SoLuongTonTruoc,SoLuongTonSau,MaThamChieu,MaNhanVienThucHien,GhiChu)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        movement,
        variant,
        type,
        delta,
        before,
        after,
        reference,
        actor,
        note);
    return movement;
  }
}
