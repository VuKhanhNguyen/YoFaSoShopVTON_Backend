package com.yofaso.shop.catalog;

import com.yofaso.shop.common.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalog theo ERD: sản phẩm chung, SKU riêng size/màu và ảnh gắn đúng SKU. */
@Service
public class CatalogService {
  public static final String PRODUCT =
      "SELECT MaSanPham AS productId,MaDanhMuc AS categoryId,TenSanPham AS name,MoTaChiTiet AS"
          + " description,ChatLieu AS material,KieuDang AS fit,LoaiCapQuan AS"
          + " waist,ChieuDaiTrangPhuc AS length,LoaiHoaTiet AS pattern,PhanLoaiVTON AS"
          + " vtonCategory,TieuLoaiTrangPhuc AS subtype,TrangThaiSanPham AS status FROM SanPham";
  public static final String VARIANT =
      "SELECT MaBienThe AS variantId,MaSanPham AS productId,MaQuanLyHangHoa AS sku,KichCo AS"
          + " size,TenMau AS colorName,MaMauSac AS colors,GiaNiemYet AS price,GiaKhuyenMai AS"
          + " salePrice,SoLuongTonKho AS stock,ViTriGiaKe AS shelf,TrangThai AS status FROM"
          + " BienTheSanPham";
  private final Db db;

  /** Nhận repository SQL dùng schema có sẵn. */
  public CatalogService(Db db) {
    this.db = db;
  }

  /** Danh sách công khai chỉ gồm sản phẩm đang bán, lọc SKU bằng EXISTS tránh trùng hàng. */
  public Map<String, Object> browse(
      String q,
      String category,
      String size,
      String color,
      BigDecimal min,
      BigDecimal max,
      int page,
      int limit) {
    return browse(q,category,size,color,min,max,page,limit,"","","","",false);
  }

  /** Bộ lọc dùng chung cho public/staff; SQL luôn bind giá trị, staff mới được thấy draft. */
  public Map<String,Object> browse(String q,String category,String size,String color,BigDecimal min,BigDecimal max,
      int page,int limit,String material,String fit,String pattern,String status,boolean staff) {
    return browse(q,category,size,color,min,max,page,limit,material,fit,pattern,status,staff,"","","newest");
  }

  /** Mở rộng bộ lọc hỗ trợ VTON category, giới tính (Nam/Nữ) và sắp xếp. */
  public Map<String,Object> browse(String q,String category,String size,String color,BigDecimal min,BigDecimal max,
      int page,int limit,String material,String fit,String pattern,String status,boolean staff,
      String vtonCategory,String gender,String sort) {
    int offset=Db.offset(page,limit);
    Problem.require(q.length()<=100 && category.length()<=20 && size.length()<=10 && color.length()<=100 && material.length()<=100
        && min.signum()>=0 && max.compareTo(min)>=0,400,"FILTER_INVALID","Bộ lọc hoặc khoảng giá không hợp lệ.");
    Problem.require(fit.isEmpty() || Set.of("OM_SAT","SUONG_VUA","RONG_OVERSIZE").contains(fit),400,"FILTER_INVALID","Kiểu dáng không hợp lệ.");
    Problem.require(pattern.isEmpty() || Set.of("VAI_TRON","HOA_VAN_NHO","HOA_TO_BAN","KE_SOC_TAM","KE_CARO","IN_HINH").contains(pattern),400,"FILTER_INVALID","Họa tiết không hợp lệ.");
    Problem.require(status.isEmpty() || Set.of("BAN_NHAP","DANG_XU_LY_AI","DANG_KINH_DOANH","NGUNG_KINH_DOANH").contains(status),400,"FILTER_INVALID","Trạng thái không hợp lệ.");
    Problem.require(vtonCategory == null || vtonCategory.isEmpty() || Set.of("upper_body","lower_body","dress").contains(vtonCategory),400,"FILTER_INVALID","Phân loại VTON không hợp lệ.");
    Problem.require(gender == null || gender.isEmpty() || Set.of("NAM","NU").contains(gender),400,"FILTER_INVALID","Giới tính không hợp lệ.");

    String vton = vtonCategory == null ? "" : vtonCategory;
    String gen = gender == null ? "" : gender;

    String where=" WHERE (?='' OR TenSanPham LIKE ?)"
      +" AND (?='' OR MaDanhMuc=? OR MaDanhMuc IN (SELECT d.MaDanhMuc FROM DanhMuc d WHERE d.MaDanhMucCha=? OR d.MaDanhMucCha IN (SELECT sub.MaDanhMuc FROM DanhMuc sub WHERE sub.MaDanhMucCha=?)))"
      +" AND (?='' OR ChatLieu=?)"
      +" AND (?='' OR KieuDang=?)"
      +" AND (?='' OR LoaiHoaTiet=?)"
      +" AND (?='' OR TrangThaiSanPham=?)"
      +" AND (?='' OR PhanLoaiVTON=?)"
      +" AND (?='' OR (?='NAM' AND MaDanhMuc IN (SELECT d1.MaDanhMuc FROM DanhMuc d1 WHERE d1.MaDanhMuc='DM000001' OR d1.MaDanhMucCha='DM000001' OR d1.MaDanhMucCha IN (SELECT s1.MaDanhMuc FROM DanhMuc s1 WHERE s1.MaDanhMucCha='DM000001')))"
      +"       OR (?='NU' AND MaDanhMuc IN (SELECT d2.MaDanhMuc FROM DanhMuc d2 WHERE d2.MaDanhMuc='DM000002' OR d2.MaDanhMucCha='DM000002' OR d2.MaDanhMucCha IN (SELECT s2.MaDanhMuc FROM DanhMuc s2 WHERE s2.MaDanhMucCha='DM000002'))))"
      +" AND (? OR EXISTS(SELECT 1 FROM BienTheSanPham b WHERE b.MaSanPham=SanPham.MaSanPham"
      + (staff ? "" : " AND b.TrangThai<>'NGUNG_BAN'")
      +" AND (?='' OR b.KichCo=?) AND (?='' OR b.TenMau LIKE ? OR b.MaMauSac=? OR FIND_IN_SET(?,b.MaMauSac)>0)"
      +" AND COALESCE(b.GiaKhuyenMai,b.GiaNiemYet)>=? AND COALESCE(b.GiaKhuyenMai,b.GiaNiemYet)<=?))";
    String visibleStatus=staff ? status : "DANG_KINH_DOANH";
    boolean noSkuFilter=staff && size.isEmpty() && color.isEmpty() && min.signum()==0 && max.compareTo(new BigDecimal("9999999999"))==0;
    Object[] args={q,"%"+q+"%",category,category,category,category,material,material,fit,fit,pattern,pattern,visibleStatus,visibleStatus,
      vton,vton,gen,gen,gen,noSkuFilter,size,size,color,"%"+color+"%",color,color,min,max};
    var all = new ArrayList<>(Arrays.asList(args));
    all.add(limit);
    all.add(offset);

    String orderBy = " ORDER BY NgayTao DESC,MaSanPham";
    if ("price_asc".equals(sort)) {
      orderBy = " ORDER BY (SELECT MIN(COALESCE(GiaKhuyenMai,GiaNiemYet)) FROM BienTheSanPham WHERE MaSanPham=SanPham.MaSanPham) ASC, MaSanPham";
    } else if ("price_desc".equals(sort)) {
      orderBy = " ORDER BY (SELECT MIN(COALESCE(GiaKhuyenMai,GiaNiemYet)) FROM BienTheSanPham WHERE MaSanPham=SanPham.MaSanPham) DESC, MaSanPham";
    } else if ("best_selling".equals(sort)) {
      orderBy = " ORDER BY (SELECT COALESCE(SUM(c.SoLuong), 0) FROM BienTheSanPham b JOIN ChiTietDonHang c ON c.MaBienThe=b.MaBienThe JOIN DonHang d ON d.MaDonHang=c.MaDonHang WHERE b.MaSanPham=SanPham.MaSanPham AND d.TrangThaiDonHang IN ('HOAN_TAT','DANG_GIAO_HANG','DANG_DONG_GOI','CHO_XAC_NHAN')) DESC, MaSanPham";
    } else if ("rating".equals(sort) || "top_rated".equals(sort)) {
      orderBy = " ORDER BY (SELECT COALESCE(AVG(SoSao), 0) FROM DanhGiaSanPham WHERE MaSanPham=SanPham.MaSanPham AND TrangThaiDuyet='DA_HIEN_THI') DESC, MaSanPham";
    }

    var items = db.rows(PRODUCT + where + orderBy + " LIMIT ? OFFSET ?", all.toArray());
    enrichCards(items, staff);
    return Map.of(
        "items", items,
        "total",
        db.count("SELECT COUNT(*) FROM SanPham" + where, args),
        "page",
        page,
        "size",
        limit);
  }

  /** Bổ sung ảnh bìa, giá niêm yết/khuyến mãi, SKU, sao đánh giá và lượt bán theo lô. */
  private void enrichCards(List<Map<String, Object>> items, boolean staff) {
    if (items.isEmpty()) return;
    Object[] ids = items.stream().map(p -> p.get("productId")).toArray();
    String marks = String.join(",", Collections.nCopies(ids.length, "?"));

    Map<String, Object> minPrices = new HashMap<>();
    Map<String, Object> origPrices = new HashMap<>();
    for (var row : db.rows("SELECT MaSanPham AS productId,MIN(COALESCE(GiaKhuyenMai,GiaNiemYet)) AS minPrice,MAX(GiaNiemYet) AS originalPrice FROM BienTheSanPham WHERE MaSanPham IN (" + marks + ")" + (staff ? "" : " AND TrangThai<>'NGUNG_BAN'") + " GROUP BY MaSanPham", ids)) {
      minPrices.put(row.get("productId").toString(), row.get("minPrice"));
      origPrices.put(row.get("productId").toString(), row.get("originalPrice"));
    }

    Map<String, String> covers = new HashMap<>();
    for (var row : db.rows("SELECT MaSanPham AS productId,MaHinhAnh AS imageId FROM HinhAnhSanPham WHERE MaSanPham IN (" + marks + ") ORDER BY LaAnhDaiDien DESC,ThuTuHienThi,MaHinhAnh", ids))
      covers.putIfAbsent(row.get("productId").toString(), "/api/v1/catalog/images/" + row.get("imageId") + "/content?kind=original");

    Map<String, String> skus = new HashMap<>();
    for (var row : db.rows("SELECT MaSanPham AS productId,MaQuanLyHangHoa AS sku FROM BienTheSanPham WHERE MaSanPham IN (" + marks + ") ORDER BY MaBienThe", ids))
      skus.putIfAbsent(row.get("productId").toString(), String.valueOf(row.get("sku")));

    Map<String, Object> ratings = new HashMap<>();
    Map<String, Object> reviewCounts = new HashMap<>();
    for (var row : db.rows("SELECT MaSanPham AS productId,ROUND(AVG(SoSao), 1) AS avgRating,COUNT(MaDanhGia) AS reviewCount FROM DanhGiaSanPham WHERE MaSanPham IN (" + marks + ") AND TrangThaiDuyet='DA_HIEN_THI' GROUP BY MaSanPham", ids)) {
      ratings.put(row.get("productId").toString(), row.get("avgRating"));
      reviewCounts.put(row.get("productId").toString(), row.get("reviewCount"));
    }

    Map<String, Object> soldCounts = new HashMap<>();
    for (var row : db.rows("SELECT b.MaSanPham AS productId,COALESCE(SUM(c.SoLuong), 0) AS soldCount FROM BienTheSanPham b JOIN ChiTietDonHang c ON c.MaBienThe=b.MaBienThe JOIN DonHang d ON d.MaDonHang=c.MaDonHang WHERE b.MaSanPham IN (" + marks + ") AND d.TrangThaiDonHang IN ('HOAN_TAT','DANG_GIAO_HANG','DANG_DONG_GOI','CHO_XAC_NHAN') GROUP BY b.MaSanPham", ids)) {
      soldCounts.put(row.get("productId").toString(), row.get("soldCount"));
    }

    Map<String, List<Map<String, String>>> colorSwatches = new HashMap<>();
    for (var row : db.rows("SELECT DISTINCT b.MaSanPham AS productId,b.MaMauSac AS hex,b.TenMau AS name FROM BienTheSanPham b WHERE b.MaSanPham IN (" + marks + ") AND b.MaMauSac IS NOT NULL AND b.MaMauSac<>'' ORDER BY b.MaSanPham, b.TenMau", ids)) {
      String pid = row.get("productId").toString();
      colorSwatches.computeIfAbsent(pid, k -> new ArrayList<>()).add(Map.of(
          "hex", String.valueOf(row.get("hex")),
          "name", String.valueOf(row.get("name"))
      ));
    }

    for (var item : items) {
      String id = item.get("productId").toString();
      BigDecimal minP = minPrices.get(id) != null ? new BigDecimal(minPrices.get(id).toString()) : null;
      BigDecimal origP = origPrices.get(id) != null ? new BigDecimal(origPrices.get(id).toString()) : null;
      item.put("minPrice", minP);
      item.put("originalPrice", origP);
      boolean hasDiscount = origP != null && minP != null && origP.compareTo(minP) > 0;
      item.put("hasDiscount", hasDiscount);
      int discountPercent = (hasDiscount && origP != null && minP != null)
          ? (int) Math.round((1.0 - minP.doubleValue() / origP.doubleValue()) * 100)
          : 0;
      item.put("discountPercent", discountPercent);
      item.put("coverUrl", covers.get(id));
      item.put("sku", skus.getOrDefault(id, id));
      Number avgR = (Number) ratings.get(id);
      item.put("avgRating", avgR != null ? avgR.doubleValue() : 0.0);
      Number rCount = (Number) reviewCounts.get(id);
      item.put("reviewCount", rCount != null ? rCount.intValue() : 0);
      Number sCount = (Number) soldCounts.get(id);
      item.put("soldCount", sCount != null ? sCount.intValue() : 0);
      item.put("colorSwatches", colorSwatches.getOrDefault(id, List.of()));
    }
  }

  /** Chi tiết công khai hoặc quản trị; tuyệt đối không lộ bản nháp qua public. */
  public Map<String, Object> detail(String id, boolean admin) {
    var p =
        db.one(
            PRODUCT
                + " WHERE MaSanPham=?"
                + (admin ? "" : " AND TrangThaiSanPham='DANG_KINH_DOANH'"),
            id);
    var variants = db.rows(VARIANT + " WHERE MaSanPham=?" + (admin ? "" : " AND TrangThai<>'NGUNG_BAN'"), id);
    p.put("variants", variants);
    p.put("images", images(id));

    var ratingRows = db.rows("SELECT ROUND(AVG(SoSao), 1) AS avgRating, COUNT(MaDanhGia) AS reviewCount FROM DanhGiaSanPham WHERE MaSanPham=? AND TrangThaiDuyet='DA_HIEN_THI'", id);
    if (!ratingRows.isEmpty() && ratingRows.getFirst().get("avgRating") != null) {
      var r = ratingRows.getFirst();
      Number avg = (Number) r.get("avgRating");
      p.put("avgRating", avg != null ? avg.doubleValue() : 0.0);
      p.put("reviewCount", r.get("reviewCount") != null ? ((Number) r.get("reviewCount")).intValue() : 0);
    } else {
      p.put("avgRating", 0.0);
      p.put("reviewCount", 0);
    }
    var soldRows = db.rows("SELECT COALESCE(SUM(c.SoLuong), 0) AS soldCount FROM BienTheSanPham b JOIN ChiTietDonHang c ON c.MaBienThe=b.MaBienThe JOIN DonHang d ON d.MaDonHang=c.MaDonHang WHERE b.MaSanPham=? AND d.TrangThaiDonHang IN ('HOAN_TAT','DANG_GIAO_HANG','DANG_DONG_GOI','CHO_XAC_NHAN')", id);
    p.put("soldCount", !soldRows.isEmpty() && soldRows.getFirst().get("soldCount") != null ? soldRows.getFirst().get("soldCount") : 0);

    BigDecimal minPrice = null;
    BigDecimal origPrice = null;
    for (var v : variants) {
      BigDecimal price = v.get("price") != null ? new BigDecimal(v.get("price").toString()) : null;
      BigDecimal salePrice = v.get("salePrice") != null ? new BigDecimal(v.get("salePrice").toString()) : null;
      BigDecimal effective = (salePrice != null && salePrice.compareTo(BigDecimal.ZERO) > 0) ? salePrice : price;
      if (effective != null && (minPrice == null || effective.compareTo(minPrice) < 0)) minPrice = effective;
      if (price != null && (origPrice == null || price.compareTo(origPrice) > 0)) origPrice = price;
    }
    p.put("minPrice", minPrice);
    p.put("originalPrice", origPrice);
    boolean hasDiscount = origPrice != null && minPrice != null && origPrice.compareTo(minPrice) > 0;
    p.put("hasDiscount", hasDiscount);
    int detailDiscountPercent = (hasDiscount && origPrice != null && minPrice != null)
        ? (int) Math.round((1.0 - minPrice.doubleValue() / origPrice.doubleValue()) * 100)
        : 0;
    p.put("discountPercent", detailDiscountPercent);

    return p;
  }

  /** Ảnh công khai không bao gồm metadata thô/path trên filesystem. */
  public List<Map<String, Object>> images(String id) {
    var rows = db.rows(
        "SELECT MaHinhAnh AS imageId,DuongDanAnhGoc AS"
            + " originalAssetId,DuongDanAnhBocNen AS cutoutAssetId,MaMauSac AS colors,LaAnhDaiDien AS cover,ThuTuHienThi AS position FROM"
            + " HinhAnhSanPham WHERE MaSanPham=? ORDER BY ThuTuHienThi,MaHinhAnh",
        id);
    for (var image : rows) {
      String base = "/api/v1/catalog/images/" + image.get("imageId") + "/content?kind=";
      image.put("originalUrl", base + "original");
      image.put("cutoutUrl", image.get("cutoutAssetId") == null ? null : base + "cutout");
    }
    return rows;
  }

  /** Tạo/sửa sản phẩm bằng các field đã kiểm tra, không mass assignment. */
  @Transactional
  public String save(String id, CatalogController.Product b, String actor) {
    db.one("SELECT MaDanhMuc FROM DanhMuc WHERE MaDanhMuc=?", b.categoryId());
    boolean create = id == null;
    if (create) id = Db.id("SP");
    else db.one("SELECT MaSanPham FROM SanPham WHERE MaSanPham=? FOR UPDATE", id);
    if (create)
      db.exec(
          "INSERT INTO SanPham(MaSanPham,MaDanhMuc,TenSanPham) VALUES(?,?,?)",
          id,
          b.categoryId(),
          b.name());
    db.exec(
        "UPDATE SanPham SET"
            + " MaDanhMuc=?,TenSanPham=?,MoTaChiTiet=?,ChatLieu=?,KieuDang=?,LoaiCapQuan=?,ChieuDaiTrangPhuc=?,LoaiHoaTiet=?,PhanLoaiVTON=?,TieuLoaiTrangPhuc=?,TrangThaiSanPham='BAN_NHAP'"
            + " WHERE MaSanPham=?",
        b.categoryId(),
        b.name(),
        b.description(),
        b.material(),
        b.fit(),
        b.waist(),
        b.length(),
        b.pattern(),
        b.vtonCategory(),
        b.subtype(),
        id);
    db.audit(actor, create ? "PRODUCT_CREATE" : "PRODUCT_UPDATE", id);
    return id;
  }

  /** Công bố chỉ khi có SKU, ảnh sạch đúng SKU và metadata do nhân viên duyệt. */
  @Transactional
  public Map<String, Object> publish(String id, String actor) {
    db.one("SELECT MaSanPham FROM SanPham WHERE MaSanPham=? FOR UPDATE", id);
    long variants = db.count("SELECT COUNT(*) FROM BienTheSanPham WHERE MaSanPham=?", id);
    long ready =
        db.count(
            "SELECT COUNT(DISTINCT b.MaBienThe) FROM BienTheSanPham b JOIN SanPham p ON p.MaSanPham=b.MaSanPham JOIN HinhAnhSanPham h ON h.MaSanPham=b.MaSanPham AND h.MaMauSac=b.MaMauSac WHERE b.MaSanPham=? AND h.DuongDanAnhBocNen LIKE 'catalog/products/%' AND p.DuongDanMatNaVungThayDo LIKE 'catalog/products/%'",
            id);
    Problem.require(
        variants > 0 && ready == variants,
        409,
        "CATALOG_NOT_READY",
        "Mỗi SKU cần ảnh bóc nền, mask và metadata đã duyệt từ dữ liệu thật.");
    db.exec("UPDATE SanPham SET TrangThaiSanPham='DANG_KINH_DOANH' WHERE MaSanPham=?", id);
    db.audit(actor, "PRODUCT_PUBLISH", id);
    return detail(id, true);
  }

  /** SKU mới tồn bằng 0; thay tồn phải qua ledger kho. */
  public String variant(String product, String id, CatalogController.Variant b, String actor) {
    db.one("SELECT MaSanPham FROM SanPham WHERE MaSanPham=?", product);
    Problem.require(
        b.salePrice() == null || b.salePrice().compareTo(b.price()) <= 0,
        422,
        "PRICE_INVALID",
        "Giá khuyến mãi không vượt giá niêm yết.");
    if (id == null) {
      id = Db.id("BT");
      db.exec(
          "INSERT INTO"
              + " BienTheSanPham(MaBienThe,MaSanPham,MaQuanLyHangHoa,KichCo,TenMau,MaMauSac,GiaNiemYet)"
              + " VALUES(?,?,?,?,?,?,?)",
          id,
          product,
          b.sku(),
          b.size(),
          b.colorName(),
          b.colors(),
          b.price());
    }
    db.one("SELECT MaBienThe FROM BienTheSanPham WHERE MaBienThe=? AND MaSanPham=?", id, product);
    db.exec(
        "UPDATE BienTheSanPham SET"
            + " MaQuanLyHangHoa=?,KichCo=?,TenMau=?,MaMauSac=?,GiaNiemYet=?,GiaKhuyenMai=?,ViTriGiaKe=?,TrangThai=?"
            + " WHERE MaBienThe=?",
        b.sku(),
        b.size(),
        b.colorName(),
        b.colors(),
        b.price(),
        b.salePrice(),
        b.shelf(),
        b.status(),
        id);
    db.audit(actor, "VARIANT_SAVE", id);
    return id;
  }
}
