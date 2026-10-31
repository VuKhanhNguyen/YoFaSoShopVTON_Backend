package com.yofaso.shop.catalog;

import com.yofaso.shop.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Endpoint catalog công khai và thao tác quản trị có RBAC. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
  public record Category(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 20) String parentId,
      @Size(max = 255) String description,
      @Min(0) int position) {}

  public record Product(
      @NotBlank @Size(max = 20) String categoryId,
      @NotBlank @Size(max = 150) String name,
      @Size(max = 10000) String description,
      @Size(max = 100) String material,
      @NotNull @Pattern(regexp = "OM_SAT|SUONG_VUA|RONG_OVERSIZE") String fit,
      @NotNull @Pattern(regexp = "CAP_CAO|CAP_VUA|CAP_TRE|KHONG_AP_DUNG") String waist,
      @NotNull
          @Pattern(
              regexp =
                  "NGAN_TREN_GOI|LUNG_NGANG_GOI|MIDI_QUA_GOI|DAI_CHAM_GOT|AO_CROPTOP|AO_TIEU_CHUAN|AO_DANG_DAI")
          String length,
      @NotNull @Pattern(regexp = "VAI_TRON|HOA_VAN_NHO|HOA_TO_BAN|KE_SOC_TAM|KE_CARO|IN_HINH")
          String pattern,
      @NotNull @Pattern(regexp = "upper_body|lower_body|dress") String vtonCategory,
      @NotBlank @Size(max = 50) String subtype) {}

  public record Variant(
      @NotBlank @Size(max = 50) String sku,
      @NotNull @Pattern(regexp = "XS|S|M|L|XL|XXL|FREESIZE") String size,
      @NotBlank @Size(max = 100) String colorName,
      @NotNull @Pattern(regexp = "#[0-9A-Fa-f]{6}(,#[0-9A-Fa-f]{6}){0,8}") String colors,
      @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal price,
      @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal salePrice,
      @Size(max = 50) String shelf,
      @NotNull @Pattern(regexp = "DANG_BAN|HET_HANG|NGUNG_BAN") String status) {}

  private final Db db;
  private final CatalogService service;

  /** Nhận service catalog và DB danh mục. */
  public CatalogController(Db db, CatalogService service) {
    this.db = db;
    this.service = service;
  }

  /** Danh mục hiển thị cho khách vãng lai. */
  @GetMapping("/catalog/categories")
  public List<Map<String, Object>> categories() {
    return db.rows(
        "SELECT MaDanhMuc AS categoryId,TenDanhMuc AS name,MaDanhMucCha AS parentId,MoTa AS"
            + " description FROM DanhMuc WHERE TrangThai='HIEN_THI' ORDER BY"
            + " ThuTuHienThi,MaDanhMuc");
  }

  /** Tìm kiếm và phân trang catalog, giá được tính theo SKU thực. */
  @GetMapping("/catalog/products")
  public Map<String, Object> products(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String categoryId,
      @RequestParam(defaultValue = "") String size,
      @RequestParam(defaultValue = "") String color,
      @RequestParam(defaultValue = "0") BigDecimal minPrice,
      @RequestParam(defaultValue = "9999999999") BigDecimal maxPrice,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "") String material,
      @RequestParam(defaultValue = "") String fit,
      @RequestParam(defaultValue = "") String pattern) {
    return service.browse(q, categoryId, size, color, minPrice, maxPrice, page, limit,material,fit,pattern,"",false);
  }

  /** Chi tiết một sản phẩm đang kinh doanh cùng các SKU và ảnh. */
  @GetMapping("/catalog/products/{id}")
  public Map<String, Object> product(@PathVariable String id) {
    return service.detail(id, false);
  }

  /** Danh sách nội bộ có bộ lọc trạng thái; giữ response list cho consumer hiện có. */
  @GetMapping({"/admin/products","/staff/products"})
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')")
  public List<Map<String,Object>> adminProducts(
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
      @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String categoryId,
      @RequestParam(defaultValue="") String color,@RequestParam(defaultValue="") String material,
      @RequestParam(defaultValue="") String fit,@RequestParam(defaultValue="") String pattern,
      @RequestParam(defaultValue="") String status) {
    @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)service.browse(q,categoryId,"",color,BigDecimal.ZERO,
      new BigDecimal("9999999999"),page,size,material,fit,pattern,status,true).get("items");
    return items;
  }

  /** Các dropdown public: code gửi khi lọc, label hiển thị tiếng Việt; không lộ draft. */
  @GetMapping("/catalog/filters")
  public Map<String,Object> filters() {
    return filterOptions(false);
  }
  /** Dropdown nội bộ bổ sung trạng thái sản phẩm. */
  @GetMapping("/staff/product-filters")
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')")
  public Map<String,Object> staffFilters() { return filterOptions(true); }
  private Map<String,Object> filterOptions(boolean staff) {
    var response=new LinkedHashMap<String,Object>();
    String active=staff ? "" : " AND p.TrangThaiSanPham='DANG_KINH_DOANH'";
    response.put("materials",db.rows("SELECT DISTINCT p.ChatLieu AS value,p.ChatLieu AS label FROM SanPham p WHERE p.ChatLieu IS NOT NULL AND p.ChatLieu<>''"+active+" ORDER BY p.ChatLieu"));
    response.put("colors",db.rows("SELECT DISTINCT b.MaMauSac AS value,b.TenMau AS label FROM BienTheSanPham b JOIN SanPham p ON p.MaSanPham=b.MaSanPham WHERE 1=1"+active+(staff ? "" : " AND b.TrangThai<>'NGUNG_BAN'")+" ORDER BY b.TenMau"));
    response.put("fit",VietnameseLabels.options("OM_SAT","SUONG_VUA","RONG_OVERSIZE"));
    response.put("pattern",VietnameseLabels.options("VAI_TRON","HOA_VAN_NHO","HOA_TO_BAN","KE_SOC_TAM","KE_CARO","IN_HINH"));
    response.put("categories",db.rows("SELECT MaDanhMuc AS value,TenDanhMuc AS label FROM DanhMuc"+(staff ? "" : " WHERE TrangThai='HIEN_THI'")+" ORDER BY ThuTuHienThi"));
    response.put("sizes",List.of("XS","S","M","L","XL","XXL","FREESIZE"));
    if(staff) response.put("status",VietnameseLabels.options("BAN_NHAP","DANG_XU_LY_AI","DANG_KINH_DOANH","NGUNG_KINH_DOANH"));
    return response;
  }

  /** Chi tiết bản nháp cho chủ shop. */
  @GetMapping({"/admin/products/{id}","/staff/products/{id}"})
  @PreAuthorize("hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')")
  public Map<String, Object> adminProduct(@PathVariable String id) {
    return service.detail(id, true);
  }

  /** Thêm danh mục; chỉ cho phép hai cấp để tránh chu kỳ cây. */
  @PostMapping("/admin/categories")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> category(@Valid @RequestBody Category b) {
    if (b.parentId() != null) {
      var p = db.one("SELECT MaDanhMucCha FROM DanhMuc WHERE MaDanhMuc=?", b.parentId());
      Problem.require(
          p.get("MaDanhMucCha") == null, 422, "CATEGORY_DEPTH", "Danh mục chỉ hỗ trợ hai cấp.");
    }
    String id = Db.id("DM");
    db.exec(
        "INSERT INTO DanhMuc(MaDanhMuc,TenDanhMuc,MaDanhMucCha,MoTa,ThuTuHienThi)"
            + " VALUES(?,?,?,?,?)",
        id,
        b.name(),
        b.parentId(),
        b.description(),
        b.position());
    return Map.of("categoryId", id);
  }

  /** Ẩn danh mục thay vì xóa sản phẩm còn liên kết. */
  @DeleteMapping("/admin/categories/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> hideCategory(@PathVariable String id) {
    Problem.require(
        db.exec("UPDATE DanhMuc SET TrangThai='AN' WHERE MaDanhMuc=?", id) == 1,
        404,
        "NOT_FOUND",
        "Không có danh mục.");
    return Map.of("hidden", true);
  }

  /** Tạo sản phẩm nháp, chưa công bố trước khi có ảnh sạch. */
  @PostMapping("/admin/products")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> create(@Valid @RequestBody Product b, Authentication a) {
    String id = service.save(null, b, a.getName());
    return service.detail(id, true);
  }

  /** Sửa metadata sẽ đưa sản phẩm về nháp để duyệt lại. */
  @PutMapping("/admin/products/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> update(
      @PathVariable String id, @Valid @RequestBody Product b, Authentication a) {
    service.save(id, b, a.getName());
    return service.detail(id, true);
  }

  /** Công bố sản phẩm có đủ ảnh/metadata thật cho mọi SKU. */
  @PostMapping("/admin/products/{id}/publish")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> publish(@PathVariable String id, Authentication a) {
    return service.publish(id, a.getName());
  }

  /** Ngừng kinh doanh, không xóa lịch sử mua hàng. */
  @DeleteMapping("/admin/products/{id}")
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> archive(@PathVariable String id) {
    Problem.require(
        db.exec("UPDATE SanPham SET TrangThaiSanPham='TAM_NGUNG' WHERE MaSanPham=?", id) == 1,
        404,
        "NOT_FOUND",
        "Không có sản phẩm.");
    return Map.of("archived", true);
  }

  /** Thêm SKU, tồn ban đầu bằng 0. */
  @PostMapping("/admin/products/{id}/variants")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public Map<String, Object> variant(
      @PathVariable String id, @Valid @RequestBody Variant b, Authentication a) {
    return Map.of("variantId", service.variant(id, null, b, a.getName()));
  }

  /** Sửa giá/màu/size SKU, không cho sửa trực tiếp tồn kho. */
  @PutMapping("/admin/products/{id}/variants/{variantId}")
  @Transactional
  @PreAuthorize("hasRole('SHOP_ADMIN')")
  public Map<String, Object> updateVariant(
      @PathVariable String id,
      @PathVariable String variantId,
      @Valid @RequestBody Variant b,
      Authentication a) {
    return Map.of("variantId", service.variant(id, variantId, b, a.getName()));
  }
}
