package com.yofaso.shop.order;

import com.yofaso.shop.common.Db;
import com.yofaso.shop.common.Problem;
import com.yofaso.shop.common.RuntimeState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/staff/inventory/audits")
public class InventoryAuditController {
  private final Db db;
  private final RuntimeState runtime;
  private final InventoryService inventory;

  public InventoryAuditController(Db db, RuntimeState runtime, InventoryService inventory) {
    this.db = db;
    this.runtime = runtime;
    this.inventory = inventory;
  }

  public record Count(@NotBlank @Size(max = 20) String variantId,
      @Min(0) @Max(1000000000) int counted, @Min(0) int book, @Min(0) long revision) {}

  public record Draft(@NotBlank @Size(max = 255) String reason,
      @NotEmpty @Size(max = 500) List<@NotNull @Valid Count> lines) {}

  @PostMapping
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')")
  public Map<String, Object> submit(@Valid @RequestBody Draft body, Authentication actor) {
    var lines = new TreeMap<String, Map<String, Object>>();
    for (var line : body.lines()) {
      Problem.require(line.counted() != line.book() && !lines.containsKey(line.variantId()),
          422, "AUDIT_LINE_INVALID", "Dòng kiểm kê phải có chênh lệch và SKU duy nhất.");
      lines.put(line.variantId(), RuntimeState.row("variantId", line.variantId(),
          "counted", line.counted(), "book", line.book(), "revision", line.revision()));
    }
    String id = Db.id("KK");
    var draft = RuntimeState.row("id", id, "reason", body.reason(), "author", actor.getName(),
        "createdAt", Instant.now().toString(), "status", "PENDING", "lines", new ArrayList<>(lines.values()));
    runtime.put("inventoryaudits", id, draft);
    return draft;
  }

  @GetMapping
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public List<Map<String, Object>> list() {
    var posted = new HashSet<>(db.rows("SELECT DISTINCT MaThamChieu AS id FROM BienDongKho"
        + " WHERE LoaiBienDong='KIEM_KE_CAN_KHO' AND MaThamChieu LIKE 'KK%'")
        .stream().map(row -> Db.text(row, "id")).toList());
    var result = new ArrayList<>(runtime.find("inventoryaudits", row -> true));
    Collections.reverse(result);
    result.forEach(row -> {
      if (posted.contains(row.get("id"))) row.put("status", "APPROVED");
    });
    return result;
  }

  @PostMapping("/{id}/approve")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @Transactional
  @SuppressWarnings("unchecked")
  public Map<String, Object> approve(@PathVariable String id, Authentication actor) {
    var draft = runtime.get("inventoryaudits", id);
    var lines = (List<Map<String, Object>>) draft.get("lines");
    // Lock every SKU before checking the snapshot or looking for a previous approval.
    var stocks = new ArrayList<Map<String, Object>>();
    for (var line : lines)
      stocks.add(db.one("SELECT SoLuongTonKho FROM BienTheSanPham WHERE MaBienThe=? FOR UPDATE",
          line.get("variantId")));
    synchronized (runtime) {
      Problem.require("PENDING".equals(runtime.get("inventoryaudits", id).get("status")),
          409, "AUDIT_CLOSED", "Phiếu đã được xử lý.");
      if (posted(id)) return Map.of("status", "APPROVED");
      for (int i = 0; i < lines.size(); i++) {
        var line = lines.get(i);
        int book = ((Number) line.get("book")).intValue();
        int counted = ((Number) line.get("counted")).intValue();
        String variant = Db.text(line, "variantId");
        Problem.require(((Number) stocks.get(i).get("SoLuongTonKho")).intValue() == book
            && db.count("SELECT COUNT(*) FROM BienDongKho WHERE MaBienThe=?", variant)
                == ((Number) line.get("revision")).longValue(),
            409, "AUDIT_STALE", "SKU đã biến động. Hãy kiểm đếm lại và lập phiếu mới.");
        inventory.move(variant, counted - book, "KIEM_KE_CAN_KHO", id, actor.getName(), Db.text(draft, "reason"));
      }
    }
    return Map.of("status", "APPROVED");
  }

  @PostMapping("/{id}/reject")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @Transactional
  @SuppressWarnings("unchecked")
  public Map<String, Object> reject(@PathVariable String id, Authentication actor) {
    var draft = runtime.get("inventoryaudits", id);
    var lines = (List<Map<String, Object>>) draft.get("lines");
    db.one("SELECT MaBienThe FROM BienTheSanPham WHERE MaBienThe=? FOR UPDATE",
        lines.getFirst().get("variantId"));
    Problem.require("PENDING".equals(runtime.get("inventoryaudits", id).get("status")) && !posted(id),
        409, "AUDIT_CLOSED", "Phiếu đã được xử lý.");
    runtime.patch("inventoryaudits", id, "status", "REJECTED",
        "reviewer", actor.getName(), "reviewedAt", Instant.now().toString());
    return Map.of("status", "REJECTED");
  }

  private boolean posted(String id) {
    return db.count("SELECT COUNT(*) FROM BienDongKho WHERE MaThamChieu=? AND LoaiBienDong='KIEM_KE_CAN_KHO'", id) > 0;
  }
}
