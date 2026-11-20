package com.yofaso.shop.ai;

import com.yofaso.shop.common.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final com.yofaso.shop.scoring.ScoringService scoring;
  private final boolean enabled;

  /** Tương thích kiểm thử không dùng scoring. */
  public AiService(
      Db db,
      AiClient ai,
      MediaService media,
      JsonMapper json,
      Idempotency keys,
      boolean enabled) {
    this(db, ai, media, json, keys, null, enabled);
  }

  /** Không load model trong JVM; mọi inference chạy trong FastAPI/worker. */
  @Autowired
  public AiService(
      Db db,
      AiClient ai,
      MediaService media,
      JsonMapper json,
      Idempotency keys,
      com.yofaso.shop.scoring.ScoringService scoring,
      @Value("${app.ai.enabled}") boolean enabled) {
    this.db = db;
    this.ai = ai;
    this.media = media;
    this.json = json;
    this.keys = keys;
    this.scoring = scoring;
    this.enabled = enabled;
  }

  /** Tạo VTON từ SKU/ảnh người đã thuộc user; lấy category và garment từ catalog. */
  @Transactional
  public Map<String, Object> create(String owner, String key, AiController.TryOn b) {
    Problem.require(b.consent(), 422, "CONSENT_REQUIRED", "Cần đồng ý xử lý ảnh.");
    Problem.require((b.personAssetId() == null) != (b.shopModelId() == null),
        422, "PERSON_SOURCE_REQUIRED", "Chọn đúng một ảnh cá nhân hoặc mẫu shop.");
    String old = keys.existing(owner, "TRY_ON", key, b);
    if (old != null) return operation(old, owner);
    String sourceSessionId = "";
    if (b.personAssetId() != null) {
      var ownedPerson = media.owned(b.personAssetId(), owner);
      Problem.require("person".equals(ownedPerson.get("RoleName")),
          422, "ASSET_ROLE_INVALID", "Cần ảnh tải lên với role person.");
      sourceSessionId = Db.text(ownedPerson, "SourceSessionId");
    }
    var v =
        db.one(
            "SELECT b.MaSanPham,p.TenSanPham,p.ChatLieu,p.PhanLoaiVTON,p.TieuLoaiTrangPhuc,p.ChieuDaiTrangPhuc,p.KieuDang,p.LoaiCapQuan,p.LoaiHoaTiet,b.KichCo,b.TenMau,b.MaMauSac FROM"
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
    String personId = b.shopModelId() == null ? b.personAssetId() : media.shopPerson(owner, b.shopModelId());
    db.exec(
        "INSERT INTO"
            + " PhienThuDoAo(MaPhienThuDo,MaNguoiDung,MaBienThe,LoaiKhungAnhNguoi,DuongDanAnhNguoiDung)"
            + " VALUES(?,?,?,'KHONG_HOP_LE',?)",
        session,
        owner,
        b.variantId(),
        personId);
    var payload = new LinkedHashMap<String, Object>();
    payload.put("sessionId", session);
    payload.put("productId", v.get("MaSanPham"));
    payload.put("variantId", b.variantId());
    payload.put("personAssetId", personId);
    payload.put("garmentAssetId", image.get("DuongDanAnhBocNen"));
    payload.put("productName", v.get("TenSanPham"));
    payload.put("material", v.get("ChatLieu"));
    payload.put("vtonCategory", v.get("PhanLoaiVTON"));
    payload.put("garmentSubtype", v.get("TieuLoaiTrangPhuc"));
    payload.put("garmentLength", length(Db.text(v, "ChieuDaiTrangPhuc")));
    payload.put("catalogFit", v.get("KieuDang"));
    payload.put("catalogLength", v.get("ChieuDaiTrangPhuc"));
    payload.put("catalogWaist", v.get("LoaiCapQuan"));
    payload.put("pattern", v.get("LoaiHoaTiet"));
    payload.put("size", v.get("KichCo"));
    payload.put("colorName", v.get("TenMau"));
    payload.put("colorHex", v.get("MaMauSac"));
    payload.put("seed", b.seed());
    payload.put("steps", b.steps());
    payload.put("samples", b.samples());
    insert(session, owner, "TRY_ON", session, payload);
    if (!sourceSessionId.isBlank()) {
      db.runtime().patch("operations", session, "SourceSessionId", sourceSessionId);
    }
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
    db.exec("UPDATE SanPham SET TrangThaiSanPham='DANG_XU_LY_AI' WHERE MaSanPham=? AND TrangThaiSanPham='BAN_NHAP'", product);
    keys.complete(owner, "ANALYSIS", key, id);
    return operation(id, owner);
  }

  /** Ghi manifest trước khi gọi mạng; rollback SQL gỡ operation, poll đối chiếu nghiệp vụ. */
  private void insert(String id, String owner, String kind, String resource, Object payload) {
    db.runtime().put("operations", id, RuntimeState.row("OperationId",id,"OwnerId",owner,"KindName",kind,"ResourceId",resource,"Payload",json.writeValueAsString(payload),"StateName","PENDING_SUBMIT","Attempts",0,"CreatedAt",System.currentTimeMillis(),"NextAttemptAt",0L,"Simulated",true,"AwaitingCommit",org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()));
    if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
        new org.springframework.transaction.support.TransactionSynchronization() {
          @Override public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) db.runtime().patch("operations", id, "AwaitingCommit", false);
            else db.runtime().remove("operations", id);
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

  private static final String SESSION_SELECT = """
      SELECT t.MaPhienThuDo AS sessionId,t.MaBienThe AS variantId,p.MaSanPham AS productId,
        p.MaDanhMuc AS categoryId,p.TenSanPham AS productName,p.PhanLoaiVTON AS vtonCategory,
        b.MaQuanLyHangHoa AS sku,b.KichCo AS size,b.TenMau AS colorName,b.MaMauSac AS colorHex,
        t.LoaiKhungAnhNguoi AS personFrame,
        COALESCE(
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham AND h.MaMauSac=b.MaMauSac
             AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
           ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham
             AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
           ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham
           ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1)
        ) AS productImageUrl,
        t.TrangThaiPhien AS status,
        t.DuongDanAnhNguoiDung AS personAssetId,t.DuongDanAnhKetQuaVTON AS resultAssetId,
        t.ThongBaoLoi AS error,t.ThoiGianKhoiTao AS createdAt,t.DiemTongThe AS score,
        t.LoiNhanXet AS advice,t.DiemTiLeVocDang AS proportionScore,
        t.DiemHoaHopMauSac AS colorScore,t.DiemCanBangHoaTiet AS patternScore,
        t.DiemTruPhat AS penaltyScore,t.XepHangThamMy AS tier,t.QuyTacTiLeApDung AS proportionRule
      FROM PhienThuDoAo t JOIN BienTheSanPham b ON b.MaBienThe=t.MaBienThe
        JOIN SanPham p ON p.MaSanPham=b.MaSanPham
      WHERE t.MaNguoiDung=?
      """;

  private static final String LINKED_SESSION_SELECT = """
      SELECT t.MaPhienThuDo AS sessionId,t.MaBienThe AS variantId,p.MaSanPham AS productId,
        p.TenSanPham AS productName,p.PhanLoaiVTON AS vtonCategory,
        b.MaQuanLyHangHoa AS sku,b.KichCo AS size,b.TenMau AS colorName,b.MaMauSac AS colorHex,
        COALESCE(
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham AND h.MaMauSac=b.MaMauSac
             AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
           ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham
             AND h.DuongDanAnhBocNen IS NOT NULL AND h.DuongDanAnhGoc NOT LIKE '%--ref%'
           ORDER BY h.ThuTuHienThi,h.MaHinhAnh LIMIT 1),
          (SELECT CONCAT('/api/v1/catalog/images/', h.MaHinhAnh, '/content?kind=original')
           FROM HinhAnhSanPham h WHERE h.MaSanPham=p.MaSanPham
           ORDER BY h.LaAnhDaiDien DESC,h.ThuTuHienThi,h.MaHinhAnh LIMIT 1)
        ) AS productImageUrl
      FROM PhienThuDoAo t JOIN BienTheSanPham b ON b.MaBienThe=t.MaBienThe
        JOIN SanPham p ON p.MaSanPham=b.MaSanPham
      WHERE t.MaNguoiDung=? AND t.MaPhienThuDo=?
      """;

  private String resolveGender(String categoryId) {
    String cur = categoryId;
    var visited = new HashSet<String>();
    while (cur != null && !cur.isBlank() && visited.add(cur)) {
      if ("DM000001".equals(cur)) return "NAM";
      if ("DM000002".equals(cur)) return "NU";
      var rows = db.rows("SELECT MaDanhMucCha FROM DanhMuc WHERE MaDanhMuc=?", cur);
      if (rows.isEmpty()) break;
      cur = Db.text(rows.getFirst(), "MaDanhMucCha");
    }
    return null;
  }

  public Map<String, Object> session(String id, String owner) {
    return sessionView(db.one(SESSION_SELECT + " AND t.MaPhienThuDo=?", owner, id), owner, true);
  }

  public List<Map<String, Object>> history(String owner, int page, int size) {
    return db.rows(SESSION_SELECT + " ORDER BY t.ThoiGianKhoiTao DESC,t.MaPhienThuDo LIMIT ? OFFSET ?",
        owner, size, Db.offset(page, size)).stream().map(row -> sessionView(row, owner, false)).toList();
  }

  private Map<String, Object> sessionView(Map<String, Object> source, String owner, boolean detail) {
    var row = new LinkedHashMap<>(source);
    String id = Db.text(row, "sessionId");
    String gender = resolveGender(Db.text(row, "categoryId"));
    if (gender != null) row.put("productGender", gender);
    String personId = media.readableAsset(Db.text(row, "personAssetId"), owner);
    row.put("personAssetId", personId);
    row.put("resultAssetId", media.readableAsset(Db.text(row, "resultAssetId"), owner));
    row.put("shopModelId", personId == null ? null : media.shopModelId(personId));
    var op = db.runtime().findById("operations", id);
    if (op != null && owner.equals(op.get("OwnerId"))) {
      row.put("operationId", id);
      row.put("resourceId", id);
      row.put("kind", "TRY_ON");
      row.put("status", op.get("StateName"));
      row.put("progress", op.get("Progress") instanceof Number n ? n.intValue() : ("COMPLETED".equals(op.get("StateName")) ? 100 : 0));
      if (op.get("Stage") != null) row.put("stage", op.get("Stage"));
      if (op.get("StageMessage") != null) row.put("stageMessage", op.get("StageMessage"));
      row.put("jobId", op.get("JobId"));
      row.put("retryable", Boolean.TRUE.equals(op.get("Retryable")));
      row.put("simulated", "COMPLETED".equals(op.get("StateName")) && Boolean.TRUE.equals(op.get("Simulated")));
      if (op.get("LastError") != null) row.put("error", op.get("LastError"));
      if (detail) row.put("result", operation(id, owner).get("result"));
    } else if ("THANH_CONG".equals(row.get("status")) || "COMPLETED".equals(row.get("status"))) {
      row.put("progress", 100);
      row.put("stage", "COMPLETED");
    }
    String sourceSessionId = op != null ? Db.text(op, "SourceSessionId") : "";
    if (sourceSessionId.isBlank() && personId != null) {
      sourceSessionId = media.sourceSessionId(personId);
    }
    if (!sourceSessionId.isBlank() && !sourceSessionId.equals(id)) {
      var linked = db.rows(LINKED_SESSION_SELECT, owner, sourceSessionId);
      if (!linked.isEmpty()) {
        row.put("sourceSessionId", sourceSessionId);
        row.put("pairedItem", linked.getFirst());
      }
    }
    if (detail && scoring != null) {
      try {
        if (row.get("score") != null) {
          row.putAll(scoring.enrichSavedSession(Db.text(row, "tier"), Db.text(row, "proportionRule")));
        } else if ("THANH_CONG".equals(source.get("status")) || "COMPLETED".equals(row.get("status"))) {
          row.put("singleItemPreview", scoring.singleItemPreview(Db.text(row, "variantId")));
        }
      } catch (Exception ignored) {}
    }
    return row;
  }

  public Map<String, Object> latestAnalysis(String product, String owner) {
    db.one("SELECT MaSanPham FROM SanPham WHERE MaSanPham=?", product);
    return db.runtime().find("operations", op -> product.equals(op.get("ResourceId"))
            && owner.equals(op.get("OwnerId")) && "PRODUCT_ANALYSIS".equals(op.get("KindName")))
        .stream().max(Comparator.comparingLong(op -> ((Number) op.get("CreatedAt")).longValue()))
        .map(op -> operation(Db.text(op, "OperationId"), owner)).orElseGet(Map::of);
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
  @Scheduled(fixedDelayString = "${app.ai.poll-ms:1000}")
  public void tick() {
    if (enabled) processDue();
  }

  /** Một nhịp xử lý có khóa trong JVM, dùng chung scheduler và test điều khiển thời gian. */
  public synchronized void processDue() {
    for (var op :
        db.runtime().find("operations", r -> Set.of("PENDING_SUBMIT","QUEUED","PROCESSING","RECONCILING").contains(r.get("StateName")) && ((Number)r.get("NextAttemptAt")).longValue() <= System.currentTimeMillis()).stream().limit(5).toList()) {
      try {
        if ("TRY_ON".equals(op.get("KindName")) && db.count("SELECT COUNT(*) FROM PhienThuDoAo WHERE MaPhienThuDo=?",op.get("ResourceId"))==0) continue;
        if ("PRODUCT_ANALYSIS".equals(op.get("KindName")) && Boolean.TRUE.equals(op.get("AwaitingCommit"))) continue;
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
        String aiSessionId = Objects.toString(payload.get("sessionId"), id);
        try {
          remote = ai.getVton("/internal/v1/try-on/jobs/" + AiClient.id(aiSessionId));
        } catch (Problem e) {
          if (e.status != 404) throw e;
          payload.put("personAssetId", media.remote(payload.get("personAssetId").toString()));
          payload.put("garmentAssetId", remoteGarment(payload.get("garmentAssetId").toString()));
          // Cloud VTON (FASHN v1.6) chỉ cần ảnh người + ảnh cutout sản phẩm, không cần upload ảnh --ref.
          payload.remove("referenceKey");
          payload.remove("referenceKeys");
          remote = ai.postVton("/internal/v1/try-on/jobs", payload);
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
    } else {
      String path = "/internal/v1/jobs/" + AiClient.id(job);
      remote = kind.equals("TRY_ON") ? ai.getVton(path) : ai.get(path);
    }
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
        if (simulated) {
          db.exec(
              "UPDATE PhienThuDoAo SET"
                  + " TrangThaiPhien='THAT_BAI',DuongDanAnhKetQuaVTON=?,ThongBaoLoi='MOCK_RESULT_ONLY',ThoiGianHoanTat=CURRENT_TIMESTAMP,ThoiGianXuLy=?"
                  + " WHERE MaPhienThuDo=?",
              local,
              duration,
              id);
        } else {
          String personAssetId = Db.text(db.one("SELECT DuongDanAnhNguoiDung FROM PhienThuDoAo WHERE MaPhienThuDo=?", id), "DuongDanAnhNguoiDung");
          String personKey = "tryon/" + id + "/" + UUID.randomUUID().toString() + "--person.png";
          String resultKey = "tryon/" + id + "/" + UUID.randomUUID().toString() + "--result.png";
          try {
            byte[] personBytes = media.bytes(personAssetId);
            byte[] resultBytes = media.bytes(local);
            if (media.isR2Configured()) {
              media.uploadR2(personKey, personBytes);
              media.uploadR2(resultKey, resultBytes);
            }
            var personMedia = db.runtime().get("media", personAssetId);
            String personLocal = personMedia != null && !Db.text(personMedia, "LocalFile").isBlank()
                ? Db.text(personMedia, "LocalFile")
                : personAssetId + ".png";
            media.registerR2Asset(owner, personKey, "person", personLocal);
            media.registerR2Asset(owner, resultKey, "result", local + ".png");
            db.exec(
                "UPDATE PhienThuDoAo SET"
                    + " TrangThaiPhien='THANH_CONG',DuongDanAnhNguoiDung=?,DuongDanAnhKetQuaVTON=?,ThongBaoLoi=NULL,ThoiGianHoanTat=CURRENT_TIMESTAMP,ThoiGianXuLy=?"
                    + " WHERE MaPhienThuDo=?",
                personKey,
                resultKey,
                duration,
                id);
          } catch (Exception e) {
            db.exec(
                "UPDATE PhienThuDoAo SET"
                    + " TrangThaiPhien='THANH_CONG',DuongDanAnhKetQuaVTON=?,ThongBaoLoi=NULL,ThoiGianHoanTat=CURRENT_TIMESTAMP,ThoiGianXuLy=?"
                    + " WHERE MaPhienThuDo=?",
                local,
                duration,
                id);
          }
          try {
            if (scoring != null) {
              var sessionRow = db.one("SELECT MaBienThe,DiemTongThe FROM PhienThuDoAo WHERE MaPhienThuDo=?", id);
              String sku = Db.text(sessionRow, "MaBienThe");
              String vtonCat = Objects.toString(payload.get("vtonCategory"), "");
              String sourceSessionId = Db.text(op, "SourceSessionId");
              if ("dress".equals(vtonCat) && sessionRow.get("DiemTongThe") == null) {
                var eval = scoring.evaluate(sku, null, false);
                db.exec(
                    "UPDATE PhienThuDoAo SET"
                        + " DiemTongThe=?,DiemTiLeVocDang=?,DiemHoaHopMauSac=?,DiemCanBangHoaTiet=?,DiemTruPhat=?,QuyTacTiLeApDung=?,XepHangThamMy=?,LoiNhanXet=?"
                        + " WHERE MaPhienThuDo=?",
                    eval.get("total"),
                    eval.get("proportion"),
                    eval.get("color"),
                    eval.get("pattern"),
                    eval.get("penalty"),
                    eval.get("proportionRule"),
                    eval.get("tier"),
                    eval.get("stylistComment") != null ? eval.get("stylistComment") : eval.get("advice"),
                    id);
              } else if (!"dress".equals(vtonCat)) {
                if (!sourceSessionId.isBlank() && sessionRow.get("DiemTongThe") == null) {
                  var prevRows = db.rows(
                      "SELECT t.MaBienThe,p.PhanLoaiVTON FROM PhienThuDoAo t JOIN BienTheSanPham b ON b.MaBienThe=t.MaBienThe"
                          + " JOIN SanPham p ON p.MaSanPham=b.MaSanPham WHERE t.MaPhienThuDo=? AND t.MaNguoiDung=?",
                      sourceSessionId, owner);
                  if (!prevRows.isEmpty()) {
                    String prevCat = Db.text(prevRows.getFirst(), "PhanLoaiVTON");
                    String prevSku = Db.text(prevRows.getFirst(), "MaBienThe");
                    if (("upper_body".equals(vtonCat) && "lower_body".equals(prevCat))
                        || ("lower_body".equals(vtonCat) && "upper_body".equals(prevCat))) {
                      var eval = scoring.evaluate(sku, prevSku, false);
                      db.exec(
                          "UPDATE PhienThuDoAo SET"
                              + " DiemTongThe=?,DiemTiLeVocDang=?,DiemHoaHopMauSac=?,DiemCanBangHoaTiet=?,DiemTruPhat=?,QuyTacTiLeApDung=?,XepHangThamMy=?,LoiNhanXet=?"
                              + " WHERE MaPhienThuDo=?",
                          eval.get("total"),
                          eval.get("proportion"),
                          eval.get("color"),
                          eval.get("pattern"),
                          eval.get("penalty"),
                          eval.get("proportionRule"),
                          eval.get("tier"),
                          eval.get("stylistComment") != null ? eval.get("stylistComment") : eval.get("advice"),
                          id);
                    }
                  }
                }
                for (var suggestion : scoring.recommend(sku, false)) {
                  String recSku = suggestion.get("variantId").toString();
                  if (db.rows("SELECT MaGoiY FROM GoiYPhoiDo WHERE MaPhienThuDo=? AND MaBienTheGoiY=?", id, recSku).isEmpty()) {
                    db.exec(
                        "INSERT INTO GoiYPhoiDo(MaGoiY,MaPhienThuDo,MaBienTheGoiY,DiemSauKhiPhoiKem,LyDoGoiY) VALUES(?,?,?,?,?)",
                        Db.id("GY"), id, recSku, suggestion.get("projectedScore"), suggestion.get("reason"));
                  }
                }
              }
            }
          } catch (Exception ignored) {}
        }
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
          String storageCode = media.catalogStorageCode(op.get("ResourceId").toString());
          for (int index=0;index<analyzed.size();index++) {
            var item=analyzed.get(index);
            String sourceKey = Db.text(db.runtime().get("media", item.get("sourceAssetId").toString()), "CatalogKey");
            var sourcePosition = java.util.regex.Pattern.compile("--img([0-9]+)--").matcher(sourceKey);
            int position = sourcePosition.find() ? Integer.parseInt(sourcePosition.group(1)) : index + 1;
            item.put("sourceKey", sourceKey);
            item.put("originalKey",media.publishCatalog(storageCode,position,"original",item.get("sourceAssetId").toString()));
            item.put("cutoutKey",media.publishCatalog(storageCode,position,"cutout",item.get("cutoutAssetId").toString()));
            item.put("maskKey",media.publishCatalog(storageCode,position,"mask",item.get("maskAssetId").toString()));
          }
          try {
            var payloadMap = (Map<String, Object>) json.readValue(op.get("Payload").toString(), Map.class);
            var refIds = (List<?>) payloadMap.get("referenceAssetIds");
            if (refIds != null && !refIds.isEmpty()) {
              var refKeys = new ArrayList<String>();
              for (int rIdx = 0; rIdx < refIds.size(); rIdx++) {
                refKeys.add(media.publishCatalog(storageCode, rIdx + 1, "reference", refIds.get(rIdx).toString()));
              }
              converted.put("referenceKeys", refKeys);
            }
          } catch (Exception ignored) {}
        }
        finishProductAnalysis(op);
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
      finishProductAnalysis(op);
    if (kind.equals("TRY_ON") && Set.of("FAILED","CANCELLED").contains(status))
      db.exec("UPDATE PhienThuDoAo SET ThongBaoLoi=?,ThoiGianHoanTat=CURRENT_TIMESTAMP WHERE MaPhienThuDo=?",status,id);
    Object errorCode =
        remote.get("error") instanceof Map<?, ?> error ? error.get("errorCode") : null;
    boolean retryable = remote.get("error") instanceof Map<?, ?> e && Boolean.TRUE.equals(e.get("retryable"));
    int progress = remote.get("progress") instanceof Number n ? n.intValue() : ("COMPLETED".equals(status) ? 100 : 0);
    Object stage = remote.get("stage");
    Object stageMessage = remote.get("stageMessage");
    long nextPoll = Instant.now().plusSeconds(status.equals("PROCESSING") ? 1 : 3).toEpochMilli();
    db.runtime().patch("operations", id, "StateName", status, "Progress", progress, "Stage", stage, "StageMessage", stageMessage, "Simulated", simulated, "ResultJson", result == null ? null : json.writeValueAsString(result), "LastError", remote.get("error") == null ? null : Objects.toString(errorCode, "AI_JOB_FAILED"), "Retryable", retryable, "Attempts", 0, "NextAttemptAt", nextPoll);
  }

  /** Ảnh cutout cũng có thể dùng làm input garment nhưng role gửi AI luôn đúng; tự upload lại nếu cache 404. */
  private String remoteGarment(String asset) {
    var rows = db.runtime().find("media", r -> asset.equals(r.get("AssetId")));
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      String cached = Db.text(row, "AiAssetId");
      if (!cached.isEmpty() && "garment".equals(row.get("RoleName"))) {
        try {
          ai.getVton("/internal/v1/assets/" + AiClient.id(cached));
          return cached;
        } catch (Problem e) {
          if (e.status != 404) throw e;
        }
      }
    }
    String remote =
        AiClient.id(ai.upload(media.garmentInput(asset), "garment").get("assetId").toString());
    if (!rows.isEmpty() && "garment".equals(rows.getFirst().get("RoleName"))) {
      db.runtime().patch("media", asset, "AiAssetId", remote);
    }
    return remote;
  }

  private void finishProductAnalysis(Map<String, Object> operation) {
    db.exec("UPDATE SanPham SET TrangThaiSanPham='BAN_NHAP' WHERE MaSanPham=? AND TrangThaiSanPham='DANG_XU_LY_AI'",
        operation.get("ResourceId"));
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
      else finishProductAnalysis(op);
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
    if ("PRODUCT_ANALYSIS".equals(op.get("KindName")) && "CANCELLED".equals(result.get("status")))
      finishProductAnalysis(op);
    return operation(id, owner);
  }

  /** Đổi ảnh người ngay trên phiên thử đồ đã thất bại mà không tạo phiên PhienThuDoAo mới. */
  @Transactional
  @SuppressWarnings("unchecked")
  public synchronized Map<String, Object> replacePerson(
      String sessionId, String owner, AiController.ReplacePerson b) {
    Problem.require(b.consent(), 422, "CONSENT_REQUIRED", "Cần đồng ý xử lý ảnh.");
    Problem.require((b.personAssetId() == null) != (b.shopModelId() == null),
        422, "PERSON_SOURCE_REQUIRED", "Chọn đúng một ảnh cá nhân hoặc mẫu shop.");
    var op = ownedOperation(sessionId, owner);
    Problem.require("TRY_ON".equals(op.get("KindName")), 404, "NOT_FOUND", "Không tìm thấy phiên thử đồ.");
    String curState = Db.text(op, "StateName");
    Problem.require(
        Set.of("FAILED", "SYNC_FAILED", "CANCELLED").contains(curState)
            || ("COMPLETED".equals(curState) && Boolean.TRUE.equals(op.get("Simulated"))),
        409,
        "SESSION_NOT_REPLACEABLE",
        "Chỉ có thể đổi ảnh khi phiên thử đồ đã thất bại hoặc bị hủy.");
    String nextSourceSessionId = "";
    if (b.personAssetId() != null) {
      var ownedPerson = media.owned(b.personAssetId(), owner);
      Problem.require("person".equals(ownedPerson.get("RoleName")),
          422, "ASSET_ROLE_INVALID", "Cần ảnh tải lên với role person.");
      nextSourceSessionId = Db.text(ownedPerson, "SourceSessionId");
    }
    String personId = b.shopModelId() == null ? b.personAssetId() : media.shopPerson(owner, b.shopModelId());
    var payload = new LinkedHashMap<String, Object>(json.readValue(Db.text(op, "Payload"), Map.class));
    int rev = (op.get("Revision") instanceof Number n ? n.intValue() : 1) + 1;
    payload.put("personAssetId", personId);
    payload.put("sessionId", sessionId + "-r" + rev);
    db.exec(
        "UPDATE PhienThuDoAo SET DuongDanAnhNguoiDung=?,LoaiKhungAnhNguoi='KHONG_HOP_LE',"
            + "TrangThaiPhien='CHO_XU_LY',ThongBaoLoi=NULL,ThoiGianHoanTat=NULL,ThoiGianXuLy=NULL"
            + " WHERE MaPhienThuDo=? AND MaNguoiDung=?",
        personId,
        sessionId,
        owner);
    db.runtime().patch(
        "operations",
        sessionId,
        "Payload", json.writeValueAsString(payload),
        "SourceSessionId", nextSourceSessionId.isBlank() ? null : nextSourceSessionId,
        "Revision", rev,
        "JobId", null,
        "StateName", "PENDING_SUBMIT",
        "Progress", 0,
        "Stage", "QUEUED",
        "StageMessage", "Đang xếp hàng chờ xử lý...",
        "LastError", null,
        "Retryable", false,
        "ResultJson", null,
        "Attempts", 0,
        "NextAttemptAt", 0L);
    return session(sessionId, owner);
  }

  /** Sao chép ảnh kết quả VTON thành asset role=person để thử tiếp món đối ứng (không vượt quota). */
  @Transactional
  public Map<String, Object> promoteResultToPerson(String sessionId, String owner) {
    var s = db.one(
        "SELECT t.MaPhienThuDo AS sessionId,t.TrangThaiPhien AS status,"
            + "t.DuongDanAnhKetQuaVTON AS resultAssetId,t.LoaiKhungAnhNguoi AS personFrame,"
            + "p.PhanLoaiVTON AS vtonCategory,p.MaDanhMuc AS categoryId "
            + "FROM PhienThuDoAo t JOIN BienTheSanPham b ON b.MaBienThe=t.MaBienThe "
            + "JOIN SanPham p ON p.MaSanPham=b.MaSanPham "
            + "WHERE t.MaPhienThuDo=? AND t.MaNguoiDung=?",
        sessionId,
        owner);
    Problem.require("THANH_CONG".equals(s.get("status")), 409, "SESSION_NOT_COMPLETED",
        "Chỉ có thể lấy ảnh kết quả từ phiên thử đồ đã hoàn tất.");
    String resultAsset = Db.text(s, "resultAssetId");
    Problem.require(!resultAsset.isBlank(), 404, "RESULT_NOT_FOUND", "Phiên chưa có ảnh kết quả VTON.");
    String vtonCat = Db.text(s, "vtonCategory");
    Problem.require(!"dress".equals(vtonCat), 422, "CHAIN_DRESS_FORBIDDEN",
        "Không được dùng ảnh kết quả Đầm liền để thử chồng thêm Áo hoặc Quần/Chân váy.");
    byte[] bytes = media.bytes(resultAsset);
    var saved = media.save(owner, "person", bytes, false, false);
    String promotedId = saved.get("assetId").toString();
    db.runtime().patch("media", promotedId, "SourceSessionId", sessionId);
    var out = new LinkedHashMap<String, Object>();
    out.put("personAssetId", promotedId);
    out.put("sourceSessionId", sessionId);
    out.put("sourceCategory", vtonCat);
    out.put("personFrame", Db.text(s, "personFrame"));
    String gender = resolveGender(Db.text(s, "categoryId"));
    if (gender != null) out.put("productGender", gender);
    return out;
  }

  /** Map độ dài catalog sang enum AI, không hardcode mọi sản phẩm là áo. */
  static String length(String value) {
    return switch (value) {
      case "NGAN_TREN_GOI", "AO_CROPTOP" -> "SHORT";
      case "LUNG_NGANG_GOI", "MIDI_QUA_GOI" -> "MIDI";
      case "DAI_CHAM_GOT", "AO_DANG_DAI" -> "LONG";
      default -> "UNKNOWN";
    };
  }

}
