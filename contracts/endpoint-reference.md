# Danh mục Backend sinh từ route và DTO

#### BE-01. `POST /api/v1/admin/ai/segment-garment`

Bóc tách nền và tạo Agnostic Mask realtime từ GPU cho một ảnh trang phục.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "garmentAssetId": "test"
}
```

#### BE-02. `GET /api/v1/admin/analyses/{id}`

Kết quả từng ảnh giữ quan hệ N ảnh nguồn/N cutout/N mask.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `analysisId`.

#### BE-03. `POST /api/v1/admin/analyses/{id}/approve`

Duyệt mapping ảnh→SKU từ kết quả thật; không nhận path hay metadata tùy ý từ client.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `analysisId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "bindings": [
    {
      "sourceAssetId": "{{garmentAssetId}}",
      "variantId": "{{variantId}}"
    }
  ]
}
```

#### BE-04. `GET /api/v1/admin/audit-logs`

Nhật ký kiểm toán hành vi trọng yếu toàn hệ thống từ các bảng CSDL MySQL (BienDongKho, CaLamViecThuNgan, DonHang).

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-05. `POST /api/v1/admin/categories`

Thêm danh mục; chỉ cho phép hai cấp để tránh chu kỳ cây.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "name": "Áo sơ mi test",
  "position": 0
}
```

#### BE-06. `DELETE /api/v1/admin/categories/{id}`

Ẩn danh mục thay vì xóa sản phẩm còn liên kết.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `categoryId`.

#### BE-07. `GET /api/v1/admin/inventory/movements`

Danh sách biến động kho toàn hệ thống cho chủ shop kiểm tra và đối soát.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-08. `PATCH /api/v1/admin/orders/{id}`

Cập nhật thông tin giao hàng/người nhận/ghi chú đơn hàng cho admin.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{}
```

#### BE-09. `POST /api/v1/admin/orders/{id}/cancel`

Admin hủy đơn chưa giao; không tự hoàn tiền khi chưa đối soát.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-10. `POST /api/v1/admin/orders/{id}/confirm-payment`

StaffController: confirmPayment

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "transactionRef": "test"
}
```

#### BE-11. `GET /api/v1/admin/products`

CatalogController: adminProducts

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn), q (tùy chọn), categoryId (tùy chọn), color (tùy chọn), material (tùy chọn), fit (tùy chọn), pattern (tùy chọn), status (tùy chọn). Biến đường dẫn: không có.

#### BE-12. `POST /api/v1/admin/products`

Tạo sản phẩm nháp, chưa công bố trước khi có ảnh sạch.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Idempotency-Key (tùy chọn). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "categoryId": "{{categoryId}}",
  "fit": "SUONG_VUA",
  "length": "AO_CROPTOP",
  "name": "Áo sơ mi test",
  "pattern": "VAI_TRON",
  "subtype": "short_sleeved_shirt",
  "vtonCategory": "upper_body",
  "waist": "KHONG_AP_DUNG"
}
```

#### BE-13. `DELETE /api/v1/admin/products/{id}`

Ngừng kinh doanh, không xóa lịch sử mua hàng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-14. `GET /api/v1/admin/products/{id}`

CatalogController: adminProduct

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-15. `PUT /api/v1/admin/products/{id}`

Sửa metadata và giữ trạng thái bán hiện tại.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "categoryId": "{{categoryId}}",
  "fit": "SUONG_VUA",
  "length": "AO_CROPTOP",
  "name": "Áo sơ mi test",
  "pattern": "VAI_TRON",
  "subtype": "short_sleeved_shirt",
  "vtonCategory": "upper_body",
  "waist": "KHONG_AP_DUNG"
}
```

#### BE-16. `GET /api/v1/admin/products/{id}/analysis`

AiController: latestAnalysis

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-17. `POST /api/v1/admin/products/{id}/analysis`

Submit phân tích catalog, không công bố sản phẩm tự động.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **202**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: `id` → `productId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "productAssetIds": [
    "{{garmentAssetId}}"
  ],
  "referenceAssetIds": []
}
```

#### BE-18. `POST /api/v1/admin/products/{id}/images`

Lưu đồng bộ danh sách ảnh theo màu và bảng màu trích xuất K-Means.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "images": [
    {
      "assetId": "{{personAssetId}}",
      "colors": "#FFFFFF",
      "cover": true,
      "position": 0
    }
  ]
}
```

#### BE-19. `POST /api/v1/admin/products/{id}/publish`

Công bố sản phẩm có đủ ảnh/metadata thật cho mọi SKU.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-20. `POST /api/v1/admin/products/{id}/variants`

Thêm SKU, tồn ban đầu bằng 0.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Idempotency-Key (tùy chọn). Biến đường dẫn: `id` → `productId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "colorName": "Trắng",
  "colors": "#FFFFFF",
  "price": 100000,
  "size": "M",
  "sku": "TEST-WHITE-M",
  "status": "DANG_BAN"
}
```

#### BE-21. `DELETE /api/v1/admin/products/{id}/variants/{variantId}`

Xóa SKU chưa có lịch sử giao dịch hoặc chuyển NGUNG_BAN.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`, `variantId` → `variantId`.

#### BE-22. `PUT /api/v1/admin/products/{id}/variants/{variantId}`

Sửa giá/màu/size/tồn SKU.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`, `variantId` → `variantId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "colorName": "Trắng",
  "colors": "#FFFFFF",
  "price": 100000,
  "size": "M",
  "sku": "TEST-WHITE-M",
  "status": "DANG_BAN"
}
```

#### BE-23. `PATCH /api/v1/admin/products/{id}/vton`

Cập nhật nhanh phân loại VTON và tiểu loại DeepFashion2 từ AI Inspector.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "vtonCategory": "upper_body"
}
```

#### BE-24. `GET /api/v1/admin/products/{productId}/images/{imageId}/content`

Ảnh sản phẩm ở mọi trạng thái (kể cả bản nháp) dành cho Shop Admin xem & kiểm duyệt.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: kind (tùy chọn). Biến đường dẫn: `productId` → `productId`, `imageId` → `imageId`.

#### BE-25. `GET /api/v1/admin/products/{productId}/mask/content`

Ảnh Agnostic Mask của sản phẩm dành cho Shop Admin kiểm tra qua AI Inspector.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `productId` → `productId`.

#### BE-26. `GET /api/v1/admin/promotions`

Danh sách toàn bộ chương trình khuyến mãi cho chủ shop quản lý đa kênh.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-27. `POST /api/v1/admin/promotions`

Tạo voucher, chặn phần trăm lớn hơn 100 và thời gian đảo ngược.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "code": "TEST10",
  "endsAt": "2030-01-01T00:00:00Z",
  "minimumTotal": 0,
  "name": "Áo sơ mi test",
  "quantity": 1,
  "startsAt": "2026-01-01T00:00:00Z",
  "type": "PHAN_TRAM",
  "value": 10
}
```

#### BE-28. `DELETE /api/v1/admin/promotions/{id}`

Dừng voucher mà vẫn giữ lịch sử đơn đã áp dụng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `promotionId`.

#### BE-29. `GET /api/v1/admin/reports/finance`

Thống kê doanh thu theo ngày và kênh bán hàng từ bảng DonHang.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-30. `GET /api/v1/admin/reports/summary`

Dashboard doanh thu snapshot của các đơn hoàn tất; chưa coi là báo cáo kế toán.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-31. `GET /api/v1/admin/reviews`

Chủ shop đọc danh sách đánh giá từ khách hàng kèm tên sản phẩm và tên người dùng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-32. `PATCH /api/v1/admin/reviews/{id}`

Duyệt/ẩn và phản hồi từ shop.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `reviewId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "status": "DA_HIEN_THI",
  "reply": "Cảm ơn bạn"
}
```

#### BE-33. `GET /api/v1/admin/roles`

Nhận các service dùng lại giữa POS, kho và admin. */ public StaffController( Db db, OrderService orders, InventoryService stock, PasswordEncoder passwords, Idempotency keys) { this.db = db; this.orders = orders; this.stock = stock; this.keys = keys; this.passwords = passwords; } /** Danh sách quyền có sẵn; quyền mới cần có chính sách endpoint tương ứng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-34. `GET /api/v1/admin/shifts`

Danh sách ca làm việc thu ngân POS cho chủ shop theo dõi và duyệt bàn giao két.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-35. `POST /api/v1/admin/shifts/{id}/approve`

Duyệt bàn giao ca sau khi đối soát.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

#### BE-36. `POST /api/v1/admin/shifts/{id}/reject`

Yêu cầu đếm lại / từ chối duyệt ca.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

#### BE-37. `GET /api/v1/admin/users`

Danh sách user cho quản trị: hỗ trợ lọc role (CUSTOMER / STAFF / ALL), kèm địa chỉ mặc định và phân quyền; không lộ password.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: role (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-38. `POST /api/v1/admin/users`

Tài khoản nhân sự do chủ shop tạo; không dùng endpoint đăng ký để tự nhận role.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "account": {
    "email": "warehouse01@example.test",
    "password": "{{customerPassword}}",
    "phone": "0905000002",
    "username": "warehouse01"
  },
  "roleIds": [
    "{{cashierRoleId}}"
  ]
}
```

#### BE-39. `DELETE /api/v1/admin/users/{id}`

Xóa hoặc vô hiệu hóa tài khoản nhân sự; bảo toàn dữ liệu nếu đã có lịch sử đơn/ca.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

#### BE-40. `GET /api/v1/admin/users/{id}`

Các màn hình nội bộ chỉ đọc; vẫn dùng bảng/cột ERD gốc và giới hạn theo vai trò. */ @RestController @RequestMapping("/api/v1") public class StaffLookupController { private final Db db; private final StaffController staff; public StaffLookupController(Db db,StaffController staff) {this.db=db;this.staff=staff;} /** Hồ sơ user cho admin quản lý; không trả password hash/token.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

#### BE-41. `PATCH /api/v1/admin/users/{id}`

Admin sửa thông tin cơ bản; email/số điện thoại không truyền giữ nguyên, role có API riêng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "fullName": "Khách Test"
}
```

#### BE-42. `PUT /api/v1/admin/users/{id}/roles`

Đổi role và vô hiệu token cũ; không cho admin tự tước quyền của mình.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "roleIds": [
    "{{cashierRoleId}}"
  ]
}
```

#### BE-43. `PATCH /api/v1/admin/users/{id}/status`

Khóa tài khoản có hiệu lực ngay vì JWT kiểm tra lại DB mỗi request.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "status": "KHOA_TAM_THOI"
}
```

#### BE-44. `POST /api/v1/ai/persons/validate`

Kiểm tra ảnh người thật qua AI; mode mock của AI vẫn được giữ nguyên cờ simulated.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "assetId": "{{personAssetId}}"
}
```

#### BE-45. `POST /api/v1/ai/routing/preview`

Preview quy tắc chỉ để hướng dẫn UI, không thay kiểm tra ảnh thật.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "garmentLength": "SHORT",
  "personFrame": "full_body",
  "vtonCategory": "upper_body"
}
```

#### BE-46. `POST /api/v1/assets`

Nhận orchestration, storage và client nội bộ; FE không có key AI. */ public AiController( Db db, AiClient client, AiService service, MediaService media, JsonMapper json) { this.db = db; this.client = client; this.service = service; this.media = media; this.json = json; } /** Upload ảnh cá nhân; ảnh sản phẩm/ảnh mẫu chỉ dành admin.

- Quyền: `JWT của người dùng`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → form-data: `file` chọn **File** và ảnh PNG/JPEG; `role` là Text (`person`, `garment`, `reference`); `consent` là Text `true`. Để Postman tự đặt Content-Type/boundary.

#### BE-47. `DELETE /api/v1/assets/{id}`

Xóa ảnh riêng chưa dùng trong lịch sử/catalog/job, tránh phá ảnh của phiên đang chạy.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `assetId`.

#### BE-48. `GET /api/v1/assets/{id}/content`

Tải ảnh cá nhân/kết quả riêng, không trả filesystem path.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: thumbnail (tùy chọn). Biến đường dẫn: `id` → `assetId`.

#### BE-49. `POST /api/v1/assets/{id}/detect-color`

Nhận diện lại màu sắc và phân loại bằng AI cho ảnh catalog đã lưu.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `assetId`.

#### BE-50. `POST /api/v1/auth/forgot-password`

Gửi mã 6 số qua SMTP, response không chứa OTP và không xác nhận email tồn tại.

- Quyền: `Public`. Thành công: **202**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "email": "{{resetEmail}}"
}
```

#### BE-51. `POST /api/v1/auth/login`

Đăng nhập cho cả khách hàng và nhân sự shop.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "password": "{{customerPassword}}",
  "username": "{{customerUsername}}"
}
```

#### BE-52. `POST /api/v1/auth/logout`

Thu hồi refresh; access hiện tại còn hiệu lực tối đa 15 phút.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "refreshToken": "{{refreshToken}}"
}
```

#### BE-53. `POST /api/v1/auth/refresh`

Đổi refresh token còn hạn lấy cặp token mới.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "refreshToken": "{{refreshToken}}"
}
```

#### BE-54. `POST /api/v1/auth/register`

Nhận service tài khoản và kho dữ liệu địa chỉ. */ public AuthController(AuthService auth, Db db, LocationService locations, MediaService media, R2Storage r2) { this.auth = auth; this.db = db; this.locations = locations; this.media = media; this.r2 = r2; } /** Tạo tài khoản khách hàng, trả token để dùng ngay.

- Quyền: `Public`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "email": "customer01@example.test",
  "password": "{{customerPassword}}",
  "phone": "0905000001",
  "username": "{{customerUsername}}"
}
```

#### BE-55. `POST /api/v1/auth/reset-password`

Xác minh mã còn hạn, đổi BCrypt password rồi thu hồi token cũ; không tự đăng nhập.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "requestId": "{{resetRequestId}}",
  "email": "{{resetEmail}}",
  "otp": "{{resetOtp}}",
  "newPassword": "{{newPassword}}"
}
```

#### BE-56. `GET /api/v1/cart`

Nhận service đặt hàng và repository giỏ. */ public OrderController(Db db, OrderService orders) { this.db = db; this.orders = orders; } /** Giỏ cá nhân luôn dùng giá hiện tại, kèm thông tin sản phẩm và ảnh đại diện để hiển thị chính xác.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-57. `PUT /api/v1/cart/items`

Đặt số lượng tuyệt đối để retry PUT không cộng thêm hàng.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "quantity": 1,
  "variantId": "{{variantId}}"
}
```

#### BE-58. `DELETE /api/v1/cart/items/{id}`

Xóa SKU khỏi giỏ của chính mình.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

#### BE-59. `GET /api/v1/catalog/assets/{id}/content`

Chỉ các asset catalog của sản phẩm đang bán mới được công khai.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `assetId`.

#### BE-60. `GET /api/v1/catalog/categories`

Nhận service catalog và DB danh mục. */ public CatalogController(Db db, CatalogService service, Idempotency keys) { this.db = db; this.service = service; this.keys = keys; } /** Danh mục hiển thị cho khách vãng lai.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-61. `GET /api/v1/catalog/filters`

Các dropdown public: code gửi khi lọc, label hiển thị tiếng Việt; không lộ draft.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-62. `GET /api/v1/catalog/images/{id}/content`

Ảnh seed SQL được định danh bằng MaHinhAnh, không đưa object key tùy ý vào URL proxy.

- Quyền: `Public`. Thành công: **200**.
- Tham số: kind (tùy chọn). Biến đường dẫn: `id` → `imageId`.

#### BE-63. `GET /api/v1/catalog/products`

Tìm kiếm và phân trang catalog, giá được tính theo SKU thực.

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (tùy chọn), categoryId (tùy chọn), size (tùy chọn), color (tùy chọn), minPrice (tùy chọn), maxPrice (tùy chọn), page (tùy chọn), limit (tùy chọn), material (tùy chọn), fit (tùy chọn), pattern (tùy chọn), vtonCategory (tùy chọn), gender (tùy chọn), sort (tùy chọn). Biến đường dẫn: không có.

#### BE-64. `GET /api/v1/catalog/products/{id}`

Chi tiết một sản phẩm đang kinh doanh cùng các SKU và ảnh.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-65. `GET /api/v1/catalog/products/{id}/reviews`

Nhận DB để kiểm tra giao dịch mua trước khi nhận đánh giá. */ public InteractionController(Db db) { this.db = db; } /** Chỉ hiển thị đánh giá đã duyệt.

- Quyền: `Public`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: `id` → `productId`.

#### BE-66. `GET /api/v1/contract`

OpenAPI sinh từ route và record DTO thật, gồm request schema để Postman/SDK không lệch code. */ @RestController public class ContractController { private final ApplicationContext context; /** Tra mapping sau khi context khởi động để không tạo vòng lặp bean. */ public ContractController(ApplicationContext context) { this.context = context; } /** Hợp đồng máy đọc được cho API v1, không có token/config secret.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-67. `POST /api/v1/convert/address`

Một địa chỉ cũ có thể ra nhiều đích; client xác nhận trước khi lưu.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "provinceCode": "{{oldProvinceCode}}",
  "districtCode": "{{oldDistrictCode}}",
  "wardCode": "{{oldWardCode}}",
  "street": "12 Đường Test"
}
```

#### BE-68. `POST /api/v1/convert/batch`

LocationController: batch

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "addresses": [
    {
      "provinceCode": "{{oldProvinceCode}}",
      "districtCode": "{{oldDistrictCode}}",
      "wardCode": "{{oldWardCode}}",
      "street": "12 Đường Test"
    }
  ]
}
```

#### BE-69. `GET /api/v1/districts/{code}`

LocationController: district

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldDistrictCode`.

#### BE-70. `GET /api/v1/districts/{code}/wards`

Dropdown xã cũ theo huyện cũ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldDistrictCode`.

#### BE-71. `GET /api/v1/full-address`

Preview text đầy đủ trước khi gửi sổ địa chỉ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: provinceCode (bắt buộc), districtCode (bắt buộc), wardCode (tùy chọn), street (tùy chọn). Biến đường dẫn: không có.

#### BE-72. `GET /api/v1/me`

Hồ sơ an toàn và quyền hiện hành.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-73. `PATCH /api/v1/me`

Cập nhật tên/email của mình; không có field đổi role.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "fullName": "Khách Test"
}
```

#### BE-74. `GET /api/v1/me/addresses`

Sổ địa chỉ riêng; không nhận userId từ client.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-75. `POST /api/v1/me/addresses`

Thêm địa chỉ; khóa user để chỉ giữ một mặc định.

- Quyền: `JWT của người dùng`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "city": "Thành phố Đà Nẵng",
  "defaultAddress": true,
  "phone": "0905000001",
  "provinceCode": "48",
  "recipientName": "Khách Test",
  "street": "12 Đường Test",
  "ward": "Phường Hải Châu",
  "wardCode": "20242"
}
```

#### BE-76. `DELETE /api/v1/me/addresses/{id}`

Snapshot giao hàng của ERD chỉ có 255 ký tự, cần kiểm tra cả chuỗi đã ghép. */ private LocationService.AddressText addressText(Address body) { var value = locations.validateAddress(body.provinceCode(), body.wardCode(), body.city(), body.ward()); Problem.require((body.street().strip() + ", " + value.ward() + ", " + value.city()).length() <= 255, 422, "ADDRESS_TOO_LONG", "Địa chỉ đầy đủ vượt 255 ký tự; rút gọn số nhà/tên đường."); return value; } /** Xóa địa chỉ riêng; FK bảo vệ địa chỉ còn liên kết với đơn.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

#### BE-77. `PUT /api/v1/me/addresses/{id}`

Sửa địa chỉ của chính mình, giữ quan hệ địa chỉ đã dùng bởi đơn.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "city": "Thành phố Đà Nẵng",
  "defaultAddress": true,
  "phone": "0905000001",
  "provinceCode": "48",
  "recipientName": "Khách Test",
  "street": "12 Đường Test",
  "ward": "Phường Hải Châu",
  "wardCode": "20242"
}
```

#### BE-78. `POST /api/v1/me/avatar`

Cập nhật ảnh đại diện của chính mình, upload lên Cloudflare R2 và xóa ảnh cũ trên R2.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → form-data: `file` chọn **File** và ảnh PNG/JPEG; `role` là Text (`person`, `garment`, `reference`); `consent` là Text `true`. Để Postman tự đặt Content-Type/boundary.

#### BE-79. `POST /api/v1/me/password`

Đổi mật khẩu và kết thúc các phiên cũ.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "currentPassword": "{{customerPassword}}",
  "newPassword": "{{newPassword}}"
}
```

#### BE-80. `GET /api/v1/merge-history/district/{code}`

LocationController: districtHistory

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldDistrictCode`.

#### BE-81. `GET /api/v1/merge-history/province/{code}`

LocationController: provinceHistory

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldProvinceCode`.

#### BE-82. `GET /api/v1/merge-history/ward/{code}`

LocationController: wardHistory

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldWardCode`.

#### BE-83. `GET /api/v1/new-full-address`

LocationController: newFull

- Quyền: `Public`. Thành công: **200**.
- Tham số: provinceCode (bắt buộc), wardCode (bắt buộc), street (tùy chọn). Biến đường dẫn: không có.

#### BE-84. `GET /api/v1/new-provinces`

Địa chỉ hiện hành chỉ có tỉnh -> phường/xã/đặc khu.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-85. `GET /api/v1/new-provinces/{code}`

LocationController: newProvince

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `provinceCode`.

#### BE-86. `GET /api/v1/new-provinces/{code}/wards`

LocationController: newWards

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `provinceCode`.

#### BE-87. `GET /api/v1/new-wards/{code}`

LocationController: newWard

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `wardCode`.

#### BE-88. `GET /api/v1/notifications`

Thông báo của người đăng nhập, hỗ trợ lọc chưa đọc.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: unread (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-89. `POST /api/v1/notifications/{id}/read`

Đánh dấu đã đọc có ownership, không sửa nội dung thông báo.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `notificationId`.

#### BE-90. `GET /api/v1/orders`

Lịch sử đơn riêng, không nhận userId để xem người khác; trả kèm preview sản phẩm.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-91. `POST /api/v1/orders`

Checkout COD, key chống tạo hai đơn khi mạng chập chờn.

- Quyền: `JWT của người dùng`. Thành công: **201**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "addressId": "{{addressId}}",
  "items": [
    {
      "quantity": 1,
      "variantId": "{{variantId}}"
    }
  ]
}
```

#### BE-92. `GET /api/v1/orders/{id}`

Chi tiết đơn được kiểm tra ownership.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-93. `POST /api/v1/orders/{id}/cancel`

Khách chỉ hủy trước khi đóng gói, hoàn tồn nguyên tử.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-94. `GET /api/v1/pos/customers`

Khách tại quầy chỉ tra bằng số điện thoại chính xác, không liệt kê cả database.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: phone (bắt buộc). Biến đường dẫn: không có.

#### BE-95. `POST /api/v1/pos/orders`

Bán tiền mặt tại quầy; JSON kết quả cung cấp dữ liệu hóa đơn cho FE in.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **201**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "items": [
    {
      "quantity": 1,
      "variantId": "{{variantId}}"
    }
  ],
  "cashReceived": 200000
}
```

#### BE-96. `GET /api/v1/pos/orders/{id}/receipt`

Dữ liệu hóa đơn từ snapshot đơn; UI/browser in, API không giả lập máy in vật lý.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-97. `GET /api/v1/pos/shifts`

Danh sách ca của thu ngân hiện tại; admin xem tất cả, lọc trạng thái và phân trang.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: status (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-98. `POST /api/v1/pos/shifts`

Mở ca; khóa cashier để không có hai ca mở đồng thời.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "amount": 100000
}
```

#### BE-99. `GET /api/v1/pos/shifts/{id}`

Đọc tổng doanh thu ca từ các đơn liên kết, không tin tổng client gửi lên.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

#### BE-100. `POST /api/v1/pos/shifts/{id}/close`

Kết ca đúng người mở, lưu tiền kiểm đếm và chờ chủ shop duyệt.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "amount": 100000
}
```

#### BE-101. `GET /api/v1/pos/try-on/sessions/{id}`

O2O: khách phải cung cấp cả mã phiên và số điện thoại; không mở ảnh người riêng cho staff.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: phone (bắt buộc). Biến đường dẫn: `id` → `sessionId`.

#### BE-102. `GET /api/v1/profile`

Nguồn, license, phiên bản và số dòng; không phải hồ sơ người dùng.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-103. `GET /api/v1/promotions`

Voucher đang hiệu lực, không lộ dữ liệu tài chính nội bộ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-104. `POST /api/v1/promotions/quote`

Xem giảm giá theo giá server của SKU, chưa tiêu lượt voucher.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "items": [
    {
      "quantity": 1,
      "variantId": "{{variantId}}"
    }
  ],
  "voucher": "TEST10"
}
```

#### BE-105. `GET /api/v1/provinces`

63 tỉnh/thành của snapshot cũ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-106. `GET /api/v1/provinces/{code}`

LocationController: province

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldProvinceCode`.

#### BE-107. `GET /api/v1/provinces/{code}/districts`

Dropdown huyện theo tỉnh cũ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldProvinceCode`.

#### BE-108. `POST /api/v1/reviews`

Mỗi dòng đơn đã giao chỉ được đánh giá một lần nhờ unique constraint.

- Quyền: `JWT của người dùng`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "comment": "Sản phẩm đúng mô tả",
  "orderLineId": "{{orderLineId}}",
  "stars": 5
}
```

#### BE-109. `POST /api/v1/scoring/evaluate`

Ước tính điểm set bằng SKU và giả định sơ vin đã khai báo.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "firstVariantId": "{{variantId}}",
  "secondVariantId": "{{bottomVariantId}}",
  "tucked": true
}
```

#### BE-110. `GET /api/v1/scoring/recommendations`

Gợi ý SKU thật còn hàng có điểm dự kiến ít nhất 90.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: variantId (bắt buộc), tucked (tùy chọn). Biến đường dẫn: không có.

#### BE-111. `PATCH /api/v1/scoring/recommendations/{id}/interaction`

Tương tác chỉ tiến lên; thêm giỏ/mua phải có dữ liệu thực của chủ phiên.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `recommendationId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "status": "DA_THU_THEM"
}
```

#### BE-112. `GET /api/v1/scoring/rules`

Nhận bộ chấm điểm dùng workbook. */ public ScoringController(ScoringService scoring, com.yofaso.shop.common.Db db) { this.scoring = scoring; this.db = db; } /** Trả bảng gốc đã trích để đối chiếu rule, không nhận điểm client gửi.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-113. `POST /api/v1/scoring/sessions/{id}`

Lưu điểm ước tính catalog vào phiên thật đã hoàn tất; không chấm ảnh mock thành điểm thật.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "firstVariantId": "{{variantId}}",
  "secondVariantId": "{{bottomVariantId}}",
  "tucked": true
}
```

#### BE-114. `GET /api/v1/scoring/sessions/{id}/recommendations`

Lịch sử riêng của chủ phiên; không coi tồn kho lúc gợi ý là cam kết giữ hàng.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-115. `POST /api/v1/scoring/sessions/{id}/recommendations`

Quét hàng còn bán, chỉ ghi điểm >=90; gọi lại không xóa lịch sử tương tác.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: tucked (tùy chọn). Biến đường dẫn: `id` → `sessionId`.

#### BE-116. `GET /api/v1/search-address`

Search có giới hạn, hỗ trợ không dấu.

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (bắt buộc), provinceCode (tùy chọn), limit (tùy chọn), offset (tùy chọn). Biến đường dẫn: không có.

#### BE-117. `GET /api/v1/search-new-address`

LocationController: searchNew

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (bắt buộc), provinceCode (tùy chọn), limit (tùy chọn), offset (tùy chọn). Biến đường dẫn: không có.

#### BE-118. `POST /api/v1/staff/inventory/adjustments`

Nhập hàng/cân kho ghi dấu vết và chặn tồn âm.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "delta": 10,
  "reason": "Nhập hàng thử nghiệm",
  "variantId": "{{variantId}}"
}
```

#### BE-119. `GET /api/v1/staff/inventory/audit-zones`

Danh mục kiểm kê theo dãy kệ thực tế trong kho từ bảng BienTheSanPham & SanPham.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-120. `GET /api/v1/staff/inventory/audits`

InventoryAuditController: list

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-121. `POST /api/v1/staff/inventory/audits`

InventoryAuditController: submit

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "lines": [
    {
      "book": 1,
      "counted": 1,
      "revision": 1,
      "variantId": "{{variantId}}"
    }
  ],
  "reason": "Nhập hàng thử nghiệm"
}
```

#### BE-122. `POST /api/v1/staff/inventory/audits/{id}/approve`

InventoryAuditController: approve

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

#### BE-123. `POST /api/v1/staff/inventory/audits/{id}/reject`

InventoryAuditController: reject

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

#### BE-124. `GET /api/v1/staff/inventory/low-stock`

Cảnh báo tồn thấp dùng chung POS/kho, phân trang có giới hạn.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: threshold (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-125. `GET /api/v1/staff/inventory/movements`

Sổ cái kho theo SKU.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: variantId (bắt buộc), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-126. `GET /api/v1/staff/inventory/summary`

Báo cáo Xuất - Nhập - Tồn từ BienTheSanPham và BienDongKho.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-127. `GET /api/v1/staff/orders`

Đơn staff: shipper thấy đơn được phân hoặc nhận thêm, kho thấy toàn bộ đơn kèm chi tiết dòng hàng & kệ.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: status (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-128. `GET /api/v1/staff/orders/{id}`

Chi tiết để in pick list/receipt; staff không được lấy đơn ngoài phạm vi. Cho phép tìm theo mã đơn hoặc mã SKU/Barcode.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-129. `POST /api/v1/staff/orders/{id}/assign`

Kho phân công đúng nhân viên có role shipper.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "shipperId": "{{shipperId}}"
}
```

#### BE-130. `POST /api/v1/staff/orders/{id}/deliver`

Shipper xác nhận đã giao và thu COD; không dùng cho thanh toán VietQR.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{}
```

#### BE-131. `POST /api/v1/staff/orders/{id}/delivery-failed`

Giao thất bại chuyển về kho chờ phân lại hoặc cập nhật số lần giao và lý do.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{}
```

#### BE-132. `POST /api/v1/staff/orders/{id}/pack`

Kho nhận đơn và chuyển sang đóng gói.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-133. `GET /api/v1/staff/orders/{id}/pick-list`

Pick list gồm SKU, số lượng và vị trí kệ; không sửa tồn kho khi đọc.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-134. `POST /api/v1/staff/orders/{id}/pickup`

Shipper quét nhận thêm kiện hàng đóng gói vào lộ trình giao cá nhân.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-135. `POST /api/v1/staff/orders/{id}/receive-return`

Xác nhận đổi trả/nhận lại hàng tại quầy hoặc kho và hoàn tồn một lần hoặc chuyển kho lỗi.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: restock (tùy chọn). Biến đường dẫn: `id` → `orderId`.

#### BE-136. `GET /api/v1/staff/product-filters`

Dropdown nội bộ bổ sung trạng thái sản phẩm.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-137. `GET /api/v1/staff/products`

CatalogController: adminProducts

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn), q (tùy chọn), categoryId (tùy chọn), color (tùy chọn), material (tùy chọn), fit (tùy chọn), pattern (tùy chọn), status (tùy chọn). Biến đường dẫn: không có.

#### BE-138. `GET /api/v1/staff/products/{id}`

CatalogController: adminProduct

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-139. `GET /api/v1/staff/shipper/pending-parcels`

Kiện hàng đã đóng gói đang chờ bàn giao cho shipper tiếp nhận thêm.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-140. `POST /api/v1/staff/shipper/settle`

Quyết toán ca giao hàng cuối ngày của shipper nộp tiền COD cho thu ngân.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-141. `GET /api/v1/staff/shippers`

Danh sách nhân viên giao hàng đang hoạt động để kho bàn giao kiện hàng.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-142. `GET /api/v1/staff/variants/by-sku`

Tra barcode nội bộ có vị trí kệ, không mở cho khách vãng lai.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: sku (bắt buộc). Biến đường dẫn: không có.

#### BE-143. `GET /api/v1/suggest`

LocationController: searchNew

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (bắt buộc), provinceCode (tùy chọn), limit (tùy chọn), offset (tùy chọn). Biến đường dẫn: không có.

#### BE-144. `GET /api/v1/try-on/sessions`

Lịch sử cá nhân không chứa input ảnh của người khác.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-145. `POST /api/v1/try-on/sessions`

Tạo phiên bền trong MySQL, tác vụ nền sẽ submit sang FastAPI.

- Quyền: `JWT của người dùng`. Thành công: **202**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "variantId": "{{variantId}}",
  "personAssetId": "{{personAssetId}}",
  "seed": 42,
  "steps": 20,
  "samples": 1,
  "consent": true
}
```

#### BE-146. `GET /api/v1/try-on/sessions/{id}`

Poll chỉ trả phiên do người dùng hiện tại tạo.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-147. `POST /api/v1/try-on/sessions/{id}/cancel`

Chỉ hủy job AI còn đang xếp hàng; đang render có thể trả 409.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-148. `POST /api/v1/try-on/sessions/{id}/retry`

Thử đối soát lại hoặc gọi retry theo policy lỗi tạm của AI.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-149. `GET /api/v1/users/{id}/avatar`

Xem ảnh đại diện của người dùng theo ID (public cache 7 ngày).

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

#### BE-150. `GET /api/v1/wards/{code}`

LocationController: oldWard

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldWardCode`.
