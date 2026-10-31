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
    int offset=Db.offset(page,limit);
    Problem.require(q.length()<=100 && category.length()<=20 && size.length()<=10 && color.length()<=100 && material.length()<=100
        && min.signum()>=0 && max.compareTo(min)>=0,400,"FILTER_INVALID","Bộ lọc hoặc khoảng giá không hợp lệ.");
    Problem.require(fit.isEmpty() || Set.of("OM_SAT","SUONG_VUA","RONG_OVERSIZE").contains(fit),400,"FILTER_INVALID","Kiểu dáng không hợp lệ.");
    Problem.require(pattern.isEmpty() || Set.of("VAI_TRON","HOA_VAN_NHO","HOA_TO_BAN","KE_SOC_TAM","KE_CARO","IN_HINH").contains(pattern),400,"FILTER_INVALID","Họa tiết không hợp lệ.");
    Problem.require(status.isEmpty() || Set.of("BAN_NHAP","DANG_XU_LY_AI","DANG_KINH_DOANH","NGUNG_KINH_DOANH").contains(status),400,"FILTER_INVALID","Trạng thái không hợp lệ.");
    String where=" WHERE (?='' OR TenSanPham LIKE ?) AND (?='' OR MaDanhMuc=?) AND (?='' OR ChatLieu=?)"
      +" AND (?='' OR KieuDang=?) AND (?='' OR LoaiHoaTiet=?) AND (?='' OR TrangThaiSanPham=?)"
      +" AND (? OR EXISTS(SELECT 1 FROM BienTheSanPham b WHERE b.MaSanPham=SanPham.MaSanPham"
      + (staff ? "" : " AND b.TrangThai<>'NGUNG_BAN'")
      +" AND (?='' OR b.KichCo=?) AND (?='' OR b.TenMau LIKE ? OR b.MaMauSac=? OR FIND_IN_SET(?,b.MaMauSac)>0)"
      +" AND COALESCE(b.GiaKhuyenMai,b.GiaNiemYet)>=? AND COALESCE(b.GiaKhuyenMai,b.GiaNiemYet)<=?))";
    String visibleStatus=staff ? status : "DANG_KINH_DOANH";
    boolean noSkuFilter=staff && size.isEmpty() && color.isEmpty() && min.signum()==0 && max.compareTo(new BigDecimal("9999999999"))==0;
    Object[] args={q,"%"+q+"%",category,category,material,material,fit,fit,pattern,pattern,visibleStatus,visibleStatus,
      noSkuFilter,size,size,color,"%"+color+"%",color,color,min,max};
    var all = new ArrayList<>(Arrays.asList(args));
    all.add(limit);
    all.add(offset);
    var items = db.rows(PRODUCT + where + " ORDER BY NgayTao DESC,MaSanPham LIMIT ? OFFSET ?", all.toArray());
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

  /** Bổ sung ảnh bìa/giá tối thiểu theo lô, tránh FE gọi chi tiết N lần cho N thẻ. */
  private void enrichCards(List<Map<String, Object>> items, boolean staff) {
    if (items.isEmpty()) return;
    Object[] ids = items.stream().map(p -> p.get("productId")).toArray();
    String marks = String.join(",", Collections.nCopies(ids.length, "?"));
    Map<String, Object> prices = new HashMap<>();
    for (var row : db.rows("SELECT MaSanPham AS productId,MIN(COALESCE(GiaKhuyenMai,GiaNiemYet)) AS minPrice FROM BienTheSanPham WHERE MaSanPham IN (" + marks + ")" + (staff ? "" : " AND TrangThai<>'NGUNG_BAN'") + " GROUP BY MaSanPham", ids))
      prices.put(row.get("productId").toString(), row.get("minPrice"));
    Map<String, String> covers = new HashMap<>();
    for (var row : db.rows("SELECT MaSanPham AS productId,MaHinhAnh AS imageId FROM HinhAnhSanPham WHERE MaSanPham IN (" + marks + ") ORDER BY LaAnhDaiDien DESC,ThuTuHienThi,MaHinhAnh", ids))
      covers.putIfAbsent(row.get("productId").toString(), "/api/v1/catalog/images/" + row.get("imageId") + "/content?kind=original");
    for (var item : items) {
      String id = item.get("productId").toString();
      item.put("minPrice", prices.get(id));
      item.put("coverUrl", covers.get(id));
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
    p.put(
        "variants",
        db.rows(VARIANT + " WHERE MaSanPham=?" + (admin ? "" : " AND TrangThai<>'NGUNG_BAN'"), id));
    p.put("images", images(id));
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
