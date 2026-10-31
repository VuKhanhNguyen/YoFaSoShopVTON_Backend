package com.yofaso.shop.scoring;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

/** API chấm quy tắc catalog, tách rõ khỏi quality gate của diffusion. */
@RestController
@RequestMapping("/api/v1/scoring")
public class ScoringController {
  public record Outfit(
      @NotBlank @Size(max = 20) String firstVariantId,
      @Size(max = 20) String secondVariantId,
      boolean tucked) {}

  private final ScoringService scoring;
  private final com.yofaso.shop.common.Db db;

  /** Nhận bộ chấm điểm dùng workbook. */
  public ScoringController(ScoringService scoring, com.yofaso.shop.common.Db db) {
    this.scoring = scoring;
    this.db = db;
  }

  /** Trả bảng gốc đã trích để đối chiếu rule, không nhận điểm client gửi. */
  @GetMapping("/rules")
  public JsonNode rules() {
    return scoring.rules();
  }

  /** Ước tính điểm set bằng SKU và giả định sơ vin đã khai báo. */
  @PostMapping("/evaluate")
  public Map<String, Object> evaluate(@Valid @RequestBody Outfit b) {
    return scoring.evaluate(b.firstVariantId(), b.secondVariantId(), b.tucked());
  }

  /** Gợi ý SKU thật còn hàng có điểm dự kiến ít nhất 90. */
  @GetMapping("/recommendations")
  public List<Map<String, Object>> recommend(
      @RequestParam String variantId, @RequestParam(defaultValue = "false") boolean tucked) {
    return scoring.recommend(variantId, tucked);
  }

  /** Lưu điểm ước tính catalog vào phiên thật đã hoàn tất; không chấm ảnh mock thành điểm thật. */
  @PostMapping("/sessions/{id}")
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> sessionScore(
      @PathVariable String id,
      @Valid @RequestBody Outfit b,
      org.springframework.security.core.Authentication auth) {
    var session =
        db.one(
            "SELECT MaBienThe,TrangThaiPhien FROM PhienThuDoAo WHERE MaPhienThuDo=? AND"
                + " MaNguoiDung=? FOR UPDATE",
            id,
            auth.getName());
    com.yofaso.shop.common.Problem.require(
        "THANH_CONG".equals(session.get("TrangThaiPhien")),
        409,
        "SESSION_NOT_SCOREABLE",
        "Cần phiên thật đã hoàn tất, không dùng kết quả mock.");
    com.yofaso.shop.common.Problem.require(
        b.firstVariantId().equals(session.get("MaBienThe"))
            || Objects.equals(b.secondVariantId(), session.get("MaBienThe")),
        422,
        "OUTFIT_SESSION_MISMATCH",
        "Set đồ phải chứa SKU của phiên thử đồ.");
    var score = scoring.evaluate(b.firstVariantId(), b.secondVariantId(), b.tucked());
    db.exec(
        "UPDATE PhienThuDoAo SET"
            + " DiemTongThe=?,DiemTiLeVocDang=?,DiemHoaHopMauSac=?,DiemCanBangHoaTiet=?,DiemTruPhat=?,QuyTacTiLeApDung=?,XepHangThamMy=?,LoiNhanXet=?"
            + " WHERE MaPhienThuDo=?",
        score.get("total"),
        score.get("proportion"),
        score.get("color"),
        score.get("pattern"),
        score.get("penalty"),
        score.get("proportionRule"),
        score.get("tier"),
        "Ước tính quy tắc catalog; " + score.get("advice"),
        id);
    return score;
  }
}
