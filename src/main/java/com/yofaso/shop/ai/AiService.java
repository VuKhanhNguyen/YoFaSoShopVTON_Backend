package com.yofaso.shop.ai;

import com.yofaso.shop.common.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Manifest trên volume và polling một Backend; MySQL chỉ giữ trạng thái nghiệp vụ ERD. */
@Service
public class AiService {
  private final Db db;
  private final AiClient ai;
  private final MediaService media;
  private final JsonMapper json;
  private final Idempotency keys;
  private final boolean enabled;

  /** Không load model trong JVM; mọi inference chạy trong FastAPI/worker. */
  public AiService(
      Db db,
      AiClient ai,
      MediaService media,
      JsonMapper json,
      Idempotency keys,
      @Value("${app.ai.enabled}") boolean enabled) {
    this.db = db;
    this.ai = ai;
    this.media = media;
    this.json = json;
    this.keys = keys;
    this.enabled = enabled;
  }

  /** Tạo VTON từ SKU/ảnh người đã thuộc user; lấy category và garment từ catalog. */
  @Transactional
  public Map<String, Object> create(String owner, String key, AiController.TryOn b) {
    Problem.require(b.consent(), 422, "CONSENT_REQUIRED", "Cần đồng ý xử lý ảnh.");
    String old = keys.existing(owner, "TRY_ON", key, b);
    if (old != null) return operation(old, owner);
    var person = media.owned(b.personAssetId(), owner);
    Problem.require(
        "person".equals(person.get("RoleName")),
        422,
        "ASSET_ROLE_INVALID",
        "Cần ảnh tải lên với role person.");
    var v =
        db.one(
            "SELECT b.MaSanPham,p.PhanLoaiVTON,p.TieuLoaiTrangPhuc,p.ChieuDaiTrangPhuc FROM"
                + " BienTheSanPham b JOIN SanPham p ON p.MaSanPham=b.MaSanPham WHERE b.MaBienThe=?"
                + " AND b.TrangThai='DANG_BAN' AND p.TrangThaiSanPham='DANG_KINH_DOANH'",
            b.variantId());
    var image =
        db.one(
            "SELECT h.DuongDanAnhBocNen FROM HinhAnhSanPham h JOIN BienTheSanPham b ON b.MaSanPham=h.MaSanPham"
                + " WHERE b.MaBienThe=? AND h.MaMauSac=b.MaMauSac"
                + " AND h.DuongDanAnhBocNen IS NOT NULL ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1",
            b.variantId());
    Problem.require(
        db.runtime().find("operations", r -> owner.equals(r.get("OwnerId")) && Set.of("PENDING_SUBMIT","QUEUED","PROCESSING","RECONCILING","SYNC_FAILED").contains(r.get("StateName"))).size()
            < 3,
        429,
        "TRY_ON_LIMIT",
        "Bạn đang có ba phiên chưa hoàn tất.");
    String session = keys.resourceId(owner, "TRY_ON", key, "TD");
    db.exec(
        "INSERT INTO"
            + " PhienThuDoAo(MaPhienThuDo,MaNguoiDung,MaBienThe,LoaiKhungAnhNguoi,DuongDanAnhNguoiDung)"
            + " VALUES(?,?,?,'KHONG_HOP_LE',?)",
        session,
        owner,
        b.variantId(),
        b.personAssetId());
    var payload = new LinkedHashMap<String, Object>();
    payload.put("sessionId", session);
    payload.put("productId", v.get("MaSanPham"));
    payload.put("variantId", b.variantId());
    payload.put("personAssetId", b.personAssetId());
    payload.put("garmentAssetId", image.get("DuongDanAnhBocNen"));
    payload.put("vtonCategory", v.get("PhanLoaiVTON"));
    payload.put("garmentSubtype", v.get("TieuLoaiTrangPhuc"));
    payload.put("garmentLength", length(Db.text(v, "ChieuDaiTrangPhuc")));
    payload.put("seed", b.seed());
    payload.put("steps", b.steps());
    payload.put("samples", b.samples());
    insert(session, owner, "TRY_ON", session, payload);
    keys.complete(owner, "TRY_ON", key, session);
    return operation(session, owner);
  }

  /** Bắt đầu phân tích một sản phẩm/nhiều ảnh, chỉ admin gọi controller này. */
  @Transactional
  public Map<String, Object> analyze(
      String owner, String key, String product, AiController.Analysis b) {
    String old = keys.existing(owner, "ANALYSIS", key, b.toString() + product);
    if (old != null) return operation(old, owner);
    db.one("SELECT MaSanPham FROM SanPham WHERE MaSanPham=? FOR UPDATE", product);
    for (String id : b.productAssetIds())
      Problem.require(
          "garment".equals(media.owned(id, owner).get("RoleName")),
          422,
          "ASSET_ROLE_INVALID",
          "Ảnh sản phẩm cần role garment.");
    for (String id : b.referenceAssetIds())
      Problem.require(
          "reference".equals(media.owned(id, owner).get("RoleName")),
          422,
          "ASSET_ROLE_INVALID",
          "Ảnh mẫu cần role reference.");
    Problem.require(
        db.runtime().find("operations", r -> product.equals(r.get("ResourceId")) && Set.of("PENDING_SUBMIT","QUEUED","PROCESSING","RECONCILING","SYNC_FAILED").contains(r.get("StateName"))).size()
            == 0,
        409,
        "ANALYSIS_ACTIVE",
        "Sản phẩm đang có phiên phân tích.");
    String id = keys.resourceId(owner, "ANALYSIS", key, "PA");
    var body = new LinkedHashMap<String, Object>();
    body.put("analysisId", id);
    body.put("productId", product);
    body.put("productAssetIds", b.productAssetIds());
    body.put("referenceAssetIds", b.referenceAssetIds());
    insert(id, owner, "PRODUCT_ANALYSIS", product, body);
    db.exec("UPDATE SanPham SET TrangThaiSanPham='DANG_XU_LY_AI' WHERE MaSanPham=?", product);
    keys.complete(owner, "ANALYSIS", key, id);
    return operation(id, owner);
  }

  /** Ghi manifest trước khi gọi mạng; rollback SQL gỡ operation, poll đối chiếu nghiệp vụ. */
  private void insert(String id, String owner, String kind, String resource, Object payload) {
    db.runtime().put("operations", id, RuntimeState.row("OperationId",id,"OwnerId",owner,"KindName",kind,"ResourceId",resource,"Payload",json.writeValueAsString(payload),"StateName","PENDING_SUBMIT","Attempts",0,"CreatedAt",System.currentTimeMillis(),"NextAttemptAt",0L,"Simulated",true));
    if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override public void afterCompletion(int status) {
            if(status==STATUS_ROLLED_BACK) db.runtime().remove("operations",id);
          }
        });
  }

  /** Snapshot riêng của owner, ẩn payload/secret và giữ cờ simulated. */
  public Map<String, Object> operation(String id, String owner) {
    var row =
        publicOperation(id, owner);
    if (row.get("result") != null)
      row.put("result", json.readValue(row.get("result").toString(), Object.class));
    return row;
  }

  private Map<String,Object> ownedOperation(String id, String owner) {
    var row = db.runtime().get("operations", id);
    Problem.require(owner.equals(row.get("OwnerId")),404,"NOT_FOUND","Không tìm thấy tác vụ.");
    return row;
  }
  private Map<String,Object> publicOperation(String id, String owner) {
    var r = ownedOperation(id, owner);
    return RuntimeState.row("operationId",id,"kind",r.get("KindName"),"resourceId",r.get("ResourceId"),
      "jobId",r.get("JobId"),"status",r.get("StateName"),"simulated",r.get("Simulated"),
      "error",r.get("LastError"),"result",r.get("ResultJson"),"createdAt",r.get("CreatedAt"));
  }

  /** Poll có retry/backoff tối đa; chưa phải scheduler phân tán nhiều Backend replica. */
  @Scheduled(fixedDelayString = "${app.ai.poll-ms:3000}")
  public void tick() {
    if (enabled) processDue();
  }

  /** Một nhịp xử lý có khóa trong JVM, dùng chung scheduler và test điều khiển thời gian. */
  public synchronized void processDue() {
    for (var op :
        db.runtime().find("operations", r -> Set.of("PENDING_SUBMIT","QUEUED","PROCESSING","RECONCILING").contains(r.get("StateName")) && ((Number)r.get("NextAttemptAt")).longValue() <= System.currentTimeMillis()).stream().limit(5).toList()) {
      try {
        if ("TRY_ON".equals(op.get("KindName")) && db.count("SELECT COUNT(*) FROM PhienThuDoAo WHERE MaPhienThuDo=?",op.get("ResourceId"))==0) continue;
        if ("PRODUCT_ANALYSIS".equals(op.get("KindName")) && "PENDING_SUBMIT".equals(op.get("StateName")) && db.count("SELECT COUNT(*) FROM SanPham WHERE MaSanPham=? AND TrangThaiSanPham='DANG_XU_LY_AI'",op.get("ResourceId"))==0) continue;
        process(op);
      } catch (Problem e) {
        failure(op, e);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(AiService.class)
            .error("ai_sync_failed operationId={}", op.get("OperationId"), e);
        failure(
            op, new Problem(503, "AI_SYNC_FAILED", "Chưa đồng bộ được kết quả AI; thử lại sau."));
      }
    }
  }

  /** Submit idempotent hoặc poll, chỉ hoàn tất sau khi kết quả đã lưu được trên BE. */
  @SuppressWarnings("unchecked")
  private void process(Map<String, Object> op) {
    String id = Db.text(op, "OperationId"),
        owner = Db.text(op, "OwnerId"),
        kind = Db.text(op, "KindName");
    Map<String, Object> payload = json.readValue(Db.text(op, "Payload"), Map.class);
    var originalInputs = kind.equals("PRODUCT_ANALYSIS") ? List.copyOf((List<String>)payload.get("productAssetIds")) : List.<String>of();
    String job = Db.text(op, "JobId");
    Map<String, Object> remote;
    if (job.isEmpty()) {
      if (kind.equals("TRY_ON")) {
        // Tra session trước mỗi submit để khôi phục response bị mất sau timeout.
        try {
          remote = ai.get("/internal/v1/try-on/jobs/" + AiClient.id(id));
        } catch (Problem e) {
          if (e.status != 404) throw e;
          payload.put("personAssetId", media.remote(payload.get("personAssetId").toString()));
          payload.put("garmentAssetId", remoteGarment(payload.get("garmentAssetId").toString()));
          remote = ai.post("/internal/v1/try-on/jobs", payload);
        }
      } else {
        payload.put(
            "productAssetIds",
            ((List<String>) payload.get("productAssetIds")).stream().map(media::remote).toList());
        payload.put(
            "referenceAssetIds",
            ((List<String>) payload.get("referenceAssetIds")).stream().map(media::remote).toList());
        remote = ai.post("/internal/v1/products/analyze", payload);
      }
    } else remote = ai.get("/internal/v1/jobs/" + AiClient.id(job));
    String status = Objects.toString(remote.get("status"), "");
    Problem.require(
        Set.of("QUEUED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED").contains(status),
        502,
        "AI_RESPONSE_INVALID",
        "Trạng thái AI không hợp lệ.");
    job = AiClient.id(Objects.toString(remote.get("jobId"), ""));
    db.runtime().patch("operations", id, "JobId", job);
    boolean simulated = Boolean.TRUE.equals(remote.get("simulated"));
    Object result = remote.get("result");
    if (status.equals("COMPLETED")) {
      Problem.require(result instanceof Map, 502, "AI_RESPONSE_INVALID", "AI chưa có result.");
      var converted = new LinkedHashMap<>((Map<String, Object>) result);
      if (kind.equals("TRY_ON")) {
        var asset = (Map<String, Object>) converted.get("resultAsset");
        Problem.require(asset != null, 502, "AI_RESPONSE_INVALID", "Thiếu ảnh kết quả.");
        String local =
            media.importResult(
                owner,
                "result",
                AiClient.id(Objects.toString(asset.get("assetId"), "")),
                simulated);
        converted.put("resultAssetId", local);
        converted.remove("resultAsset");
        if (!simulated) {
          var quality = (Map<String, Object>) converted.get("quality");
          Problem.require(
              quality != null && Boolean.TRUE.equals(quality.get("evaluated")),
              502,
              "QUALITY_NOT_VERIFIED",
              "Kết quả thật chưa có hậu kiểm.");
        }
        var route = (Map<String, Object>) converted.get("route");
        String frame =
            route == null ? "invalid" : Objects.toString(route.get("personFrame"), "invalid");
        String frameCode =
            switch (frame) {
              case "full_body" -> "FULL_BODY";
              case "half_body" -> "HALF_BODY";
              case "three_quarter" -> "THREE_QUARTER";
              default -> "KHONG_HOP_LE";
            };
        Problem.require(simulated || !frameCode.equals("KHONG_HOP_LE"),502,"AI_RESPONSE_INVALID","Thiếu khung ảnh hợp lệ của kết quả thật.");
        Object duration=converted.get("processingSeconds");
        if(duration!=null) Problem.require(duration instanceof Number && Double.isFinite(((Number)duration).doubleValue())
            && ((Number)duration).doubleValue()>=0 && ((Number)duration).doubleValue()<=9999.99,
            502,"AI_RESPONSE_INVALID","Thời gian xử lý không hợp lệ.");
        db.exec("UPDATE PhienThuDoAo SET LoaiKhungAnhNguoi=? WHERE MaPhienThuDo=?", frameCode, id);
        db.exec(
            "UPDATE PhienThuDoAo SET"
                + " TrangThaiPhien=?,DuongDanAnhKetQuaVTON=?,ThongBaoLoi=?,ThoiGianHoanTat=CURRENT_TIMESTAMP,ThoiGianXuLy=?"
                + " WHERE MaPhienThuDo=?",
            simulated ? "THAT_BAI" : "THANH_CONG",
            local,
            simulated ? "MOCK_RESULT_ONLY" : null,
            duration,
            id);
      } else {
        var analyzed = (List<Map<String,Object>>) converted.get("variants");
        for (var item : analyzed) {
          String source = item.get("sourceAssetId").toString();
          var sourceRow =
              db.runtime().find("media", r -> source.equals(r.get("AiAssetId")) && owner.equals(r.get("OwnerId"))).getFirst();
          item.put("sourceAssetId", sourceRow.get("AssetId"));
          for (String type : List.of("cutout", "mask")) {
            var asset = (Map<String, Object>) item.get(type);
            String local =
                media.importResult(owner, type, asset.get("assetId").toString(), simulated);
            item.put(type + "AssetId", local);
            item.remove(type);
          }
          item.remove("DuongDanAnhBocNen");
          item.remove("DuongDanMatNaVungThayDo");
        }
        if (!simulated) {
          var inputs=originalInputs;
          analyzed.sort(Comparator.comparingInt(v -> inputs.indexOf(v.get("sourceAssetId"))));
          Problem.require(analyzed.size()==inputs.size() && analyzed.stream().map(v -> v.get("sourceAssetId")).distinct().count()==inputs.size()
              && analyzed.stream().allMatch(v -> inputs.contains(v.get("sourceAssetId"))),502,"AI_RESPONSE_INVALID","Kết quả phải khớp mọi ảnh nguồn.");
          for (int index=0;index<analyzed.size();index++) {
            var item=analyzed.get(index);
            String product=op.get("ResourceId").toString();
            item.put("originalKey",media.publishCatalog(product,id,index+1,"original",item.get("sourceAssetId").toString()));
            item.put("cutoutKey",media.publishCatalog(product,id,index+1,"cutout",item.get("cutoutAssetId").toString()));
            item.put("maskKey",media.publishCatalog(product,id,index+1,"mask",item.get("maskAssetId").toString()));
          }
        }
        db.exec(
            "UPDATE SanPham SET TrangThaiSanPham='BAN_NHAP' WHERE MaSanPham=?",
            op.get("ResourceId"));
      }
      result = converted;
    } else if (kind.equals("TRY_ON")) {
      db.exec(
          "UPDATE PhienThuDoAo SET TrangThaiPhien=? WHERE MaPhienThuDo=?",
          status.equals("PROCESSING")
              ? "DANG_RENDER_GPU"
              : Set.of("FAILED", "CANCELLED").contains(status) ? "THAT_BAI" : "CHO_XU_LY",
          id);
    }
    if (kind.equals("PRODUCT_ANALYSIS") && Set.of("FAILED", "CANCELLED").contains(status))
      db.exec(
          "UPDATE SanPham SET TrangThaiSanPham='BAN_NHAP' WHERE MaSanPham=?", op.get("ResourceId"));
    if (kind.equals("TRY_ON") && Set.of("FAILED","CANCELLED").contains(status))
      db.exec("UPDATE PhienThuDoAo SET ThongBaoLoi=?,ThoiGianHoanTat=CURRENT_TIMESTAMP WHERE MaPhienThuDo=?",status,id);
    Object errorCode =
        remote.get("error") instanceof Map<?, ?> error ? error.get("errorCode") : null;
    db.runtime().patch("operations", id, "StateName", status, "Simulated", simulated, "ResultJson", result == null ? null : json.writeValueAsString(result), "LastError", remote.get("error") == null ? null : Objects.toString(errorCode, "AI_JOB_FAILED"), "Attempts", 0, "NextAttemptAt", Timestamp.from(Instant.now().plusSeconds(3)).getTime());
  }

  /** Ảnh cutout cũng có thể dùng làm input garment nhưng role gửi AI luôn đúng. */
  private String remoteGarment(String asset) {
    var rows = db.runtime().find("media", r -> asset.equals(r.get("AssetId")));
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      if (!Db.text(row, "AiAssetId").isEmpty() && "garment".equals(row.get("RoleName")))
        return Db.text(row, "AiAssetId");
    }
    String remote =
        AiClient.id(ai.upload(media.garmentInput(asset), "garment").get("assetId").toString());
    return remote;
  }

  /** Lỗi mạng giữ trạng thái đối soát, không nói job GPU đã hủy/thất bại. */
  private void failure(Map<String, Object> op, Problem e) {
    int attempts = ((Number) op.get("Attempts")).intValue() + 1;
    boolean retry = e.status >= 500 || e.status == 429;
    String state = retry ? (attempts < 10 ? "RECONCILING" : "SYNC_FAILED") : "FAILED";
    if (!retry) {
      if ("TRY_ON".equals(op.get("KindName")))
        db.exec(
            "UPDATE PhienThuDoAo SET TrangThaiPhien='THAT_BAI',ThongBaoLoi=? WHERE MaPhienThuDo=?",
            e.code,
            op.get("OperationId"));
      else
        db.exec(
            "UPDATE SanPham SET TrangThaiSanPham='BAN_NHAP' WHERE MaSanPham=?",
            op.get("ResourceId"));
    }
    db.runtime().patch("operations", op.get("OperationId"), "StateName", state, "Attempts", attempts, "LastError", e.code, "NextAttemptAt", Timestamp.from(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8)))).getTime());
  }

  /** Hủy/retry theo khả năng AI; chưa có jobId không được giả vờ đã hủy GPU. */
  public synchronized Map<String, Object> control(String id, String owner, String action) {
    var op = ownedOperation(id, owner);
    String job = Db.text(op, "JobId");
    if (action.equals("retry") && "SYNC_FAILED".equals(op.get("StateName"))) {
      db.runtime().patch("operations", id, "StateName", "RECONCILING", "Attempts", 0, "NextAttemptAt", 0L);
      return operation(id, owner);
    }
    Problem.require(
        !job.isEmpty(), 409, "JOB_NOT_SUBMITTED", "Job chưa có ID AI; chờ đồng bộ trước.");
    var result = ai.post("/internal/v1/jobs/" + AiClient.id(job) + "/" + action, Map.of());
    db.runtime().patch("operations", id, "StateName", result.get("status"), "NextAttemptAt", 0L);
    if (action.equals("cancel") && "TRY_ON".equals(op.get("KindName")))
      db.exec(
          "UPDATE PhienThuDoAo SET TrangThaiPhien='THAT_BAI',ThongBaoLoi='Đã hủy khi còn chờ' WHERE"
              + " MaPhienThuDo=?",
          id);
    return operation(id, owner);
  }

  /** Map độ dài catalog sang enum AI, không hardcode mọi sản phẩm là áo. */
  private static String length(String value) {
    return switch (value) {
      case "NGAN_TREN_GOI", "AO_CROPTOP" -> "SHORT";
      case "LUNG_NGANG_GOI", "MIDI_QUA_GOI" -> "MIDI";
      case "DAI_CHAM_GOT", "AO_DANG_DAI" -> "LONG";
      default -> "UNKNOWN";
    };
  }
}
