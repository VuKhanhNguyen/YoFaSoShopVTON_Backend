# Danh mục Backend sinh từ route và DTO

#### BE-01. `GET /api/v1/admin/analyses/{id}`

Kết quả từng ảnh giữ quan hệ N ảnh nguồn/N cutout/N mask.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `analysisId`.

#### BE-02. `POST /api/v1/admin/analyses/{id}/approve`

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

#### BE-03. `POST /api/v1/admin/categories`

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

#### BE-04. `DELETE /api/v1/admin/categories/{id}`

Ẩn danh mục thay vì xóa sản phẩm còn liên kết.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `categoryId`.

#### BE-05. `POST /api/v1/admin/orders/{id}/cancel`

Admin hủy đơn chưa giao; không tự hoàn tiền khi chưa đối soát.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-06. `GET /api/v1/admin/products`

CatalogController: adminProducts

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn), q (tùy chọn), categoryId (tùy chọn), color (tùy chọn), material (tùy chọn), fit (tùy chọn), pattern (tùy chọn), status (tùy chọn). Biến đường dẫn: không có.

#### BE-07. `POST /api/v1/admin/products`

Tạo sản phẩm nháp, chưa công bố trước khi có ảnh sạch.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

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

#### BE-08. `DELETE /api/v1/admin/products/{id}`

Ngừng kinh doanh, không xóa lịch sử mua hàng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-09. `GET /api/v1/admin/products/{id}`

CatalogController: adminProduct

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-10. `PUT /api/v1/admin/products/{id}`

Sửa metadata sẽ đưa sản phẩm về nháp để duyệt lại.

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

#### BE-11. `POST /api/v1/admin/products/{id}/analysis`

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

#### BE-12. `POST /api/v1/admin/products/{id}/publish`

Công bố sản phẩm có đủ ảnh/metadata thật cho mọi SKU.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-13. `POST /api/v1/admin/products/{id}/variants`

Thêm SKU, tồn ban đầu bằng 0.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

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

#### BE-14. `PUT /api/v1/admin/products/{id}/variants/{variantId}`

Sửa giá/màu/size SKU, không cho sửa trực tiếp tồn kho.

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

#### BE-15. `POST /api/v1/admin/promotions`

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

#### BE-16. `DELETE /api/v1/admin/promotions/{id}`

Dừng voucher mà vẫn giữ lịch sử đơn đã áp dụng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `promotionId`.

#### BE-17. `GET /api/v1/admin/reports/summary`

Dashboard doanh thu snapshot của các đơn hoàn tất; chưa coi là báo cáo kế toán.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-18. `GET /api/v1/admin/reviews`

Chủ shop đọc danh sách đánh giá chờ duyệt.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-19. `PATCH /api/v1/admin/reviews/{id}`

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

#### BE-20. `GET /api/v1/admin/roles`

Nhận các service dùng lại giữa POS, kho và admin. */ public StaffController( Db db, OrderService orders, InventoryService stock, PasswordEncoder passwords, Idempotency keys) { this.db = db; this.orders = orders; this.stock = stock; this.keys = keys; this.passwords = passwords; } /** Danh sách quyền có sẵn; quyền mới cần có chính sách endpoint tương ứng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-21. `POST /api/v1/admin/shifts/{id}/approve`

Duyệt bàn giao ca sau khi đối soát.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

#### BE-22. `GET /api/v1/admin/users`

Danh sách user không có hash mật khẩu hoặc token.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-23. `POST /api/v1/admin/users`

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

#### BE-24. `GET /api/v1/admin/users/{id}`

Các màn hình nội bộ chỉ đọc; vẫn dùng bảng/cột ERD gốc và giới hạn theo vai trò. */ @RestController @RequestMapping("/api/v1") public class StaffLookupController { private final Db db; private final StaffController staff; public StaffLookupController(Db db,StaffController staff) {this.db=db;this.staff=staff;} /** Hồ sơ user cho admin quản lý; không trả password hash/token.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

#### BE-25. `PATCH /api/v1/admin/users/{id}`

Admin sửa thông tin cơ bản; email/số điện thoại không truyền giữ nguyên, role có API riêng.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "fullName": "Khách Test"
}
```

#### BE-26. `PUT /api/v1/admin/users/{id}/roles`

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

#### BE-27. `PATCH /api/v1/admin/users/{id}/status`

Khóa tài khoản có hiệu lực ngay vì JWT kiểm tra lại DB mỗi request.

- Quyền: `hasRole('SHOP_ADMIN')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `targetUserId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "status": "KHOA_TAM_THOI"
}
```

#### BE-28. `POST /api/v1/ai/persons/validate`

Kiểm tra ảnh người thật qua AI; mode mock của AI vẫn được giữ nguyên cờ simulated.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "assetId": "{{personAssetId}}"
}
```

#### BE-29. `POST /api/v1/ai/routing/preview`

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

#### BE-30. `POST /api/v1/assets`

Nhận orchestration, storage và client nội bộ; FE không có key AI. */ public AiController( Db db, AiClient client, AiService service, MediaService media, JsonMapper json) { this.db = db; this.client = client; this.service = service; this.media = media; this.json = json; } /** Upload ảnh cá nhân; ảnh sản phẩm/ảnh mẫu chỉ dành admin.

- Quyền: `JWT của người dùng`. Thành công: **201**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → form-data: `file` chọn **File** và ảnh PNG/JPEG; `role` là Text (`person`, `garment`, `reference`); `consent` là Text `true`. Để Postman tự đặt Content-Type/boundary.

#### BE-31. `DELETE /api/v1/assets/{id}`

Xóa ảnh riêng chưa dùng trong lịch sử/catalog/job, tránh phá ảnh của phiên đang chạy.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `assetId`.

#### BE-32. `GET /api/v1/assets/{id}/content`

Tải ảnh cá nhân/kết quả riêng, không trả filesystem path.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `assetId`.

#### BE-33. `POST /api/v1/auth/forgot-password`

Gửi mã 6 số qua SMTP, response không chứa OTP và không xác nhận email tồn tại.

- Quyền: `Public`. Thành công: **202**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "email": "{{resetEmail}}"
}
```

#### BE-34. `POST /api/v1/auth/login`

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

#### BE-35. `POST /api/v1/auth/logout`

Thu hồi refresh; access hiện tại còn hiệu lực tối đa 15 phút.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "refreshToken": "{{refreshToken}}"
}
```

#### BE-36. `POST /api/v1/auth/refresh`

Đổi refresh token còn hạn lấy cặp token mới.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "refreshToken": "{{refreshToken}}"
}
```

#### BE-37. `POST /api/v1/auth/register`

Nhận service tài khoản và kho dữ liệu địa chỉ. */ public AuthController(AuthService auth, Db db, LocationService locations) { this.auth = auth; this.db = db; this.locations = locations; } /** Tạo tài khoản khách hàng, trả token để dùng ngay.

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

#### BE-38. `POST /api/v1/auth/reset-password`

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

#### BE-39. `GET /api/v1/cart`

Nhận service đặt hàng và repository giỏ. */ public OrderController(Db db, OrderService orders) { this.db = db; this.orders = orders; } /** Giỏ cá nhân luôn dùng giá hiện tại, không cam kết giá đến khi checkout.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-40. `PUT /api/v1/cart/items`

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

#### BE-41. `DELETE /api/v1/cart/items/{id}`

Xóa SKU khỏi giỏ của chính mình.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

#### BE-42. `GET /api/v1/catalog/assets/{id}/content`

Chỉ các asset catalog của sản phẩm đang bán mới được công khai.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `assetId`.

#### BE-43. `GET /api/v1/catalog/categories`

Nhận service catalog và DB danh mục. */ public CatalogController(Db db, CatalogService service) { this.db = db; this.service = service; } /** Danh mục hiển thị cho khách vãng lai.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-44. `GET /api/v1/catalog/filters`

Các dropdown public: code gửi khi lọc, label hiển thị tiếng Việt; không lộ draft.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-45. `GET /api/v1/catalog/images/{id}/content`

Ảnh seed SQL được định danh bằng MaHinhAnh, không đưa object key tùy ý vào URL proxy.

- Quyền: `Public`. Thành công: **200**.
- Tham số: kind (tùy chọn). Biến đường dẫn: `id` → `imageId`.

#### BE-46. `GET /api/v1/catalog/products`

Tìm kiếm và phân trang catalog, giá được tính theo SKU thực.

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (tùy chọn), categoryId (tùy chọn), size (tùy chọn), color (tùy chọn), minPrice (tùy chọn), maxPrice (tùy chọn), page (tùy chọn), limit (tùy chọn), material (tùy chọn), fit (tùy chọn), pattern (tùy chọn). Biến đường dẫn: không có.

#### BE-47. `GET /api/v1/catalog/products/{id}`

Chi tiết một sản phẩm đang kinh doanh cùng các SKU và ảnh.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-48. `GET /api/v1/catalog/products/{id}/reviews`

Nhận DB để kiểm tra giao dịch mua trước khi nhận đánh giá. */ public InteractionController(Db db) { this.db = db; } /** Chỉ hiển thị đánh giá đã duyệt.

- Quyền: `Public`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: `id` → `productId`.

#### BE-49. `GET /api/v1/contract`

OpenAPI sinh từ route và record DTO thật, gồm request schema để Postman/SDK không lệch code. */ @RestController public class ContractController { private final ApplicationContext context; /** Tra mapping sau khi context khởi động để không tạo vòng lặp bean. */ public ContractController(ApplicationContext context) { this.context = context; } /** Hợp đồng máy đọc được cho API v1, không có token/config secret.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-50. `POST /api/v1/convert/address`

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

#### BE-51. `POST /api/v1/convert/batch`

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

#### BE-52. `GET /api/v1/districts/{code}`

LocationController: district

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldDistrictCode`.

#### BE-53. `GET /api/v1/districts/{code}/wards`

Dropdown xã cũ theo huyện cũ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldDistrictCode`.

#### BE-54. `GET /api/v1/full-address`

Preview text đầy đủ trước khi gửi sổ địa chỉ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: provinceCode (bắt buộc), districtCode (bắt buộc), wardCode (tùy chọn), street (tùy chọn). Biến đường dẫn: không có.

#### BE-55. `GET /api/v1/me`

Hồ sơ an toàn và quyền hiện hành.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-56. `PATCH /api/v1/me`

Cập nhật tên/email của mình; không có field đổi role.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "fullName": "Khách Test"
}
```

#### BE-57. `GET /api/v1/me/addresses`

Sổ địa chỉ riêng; không nhận userId từ client.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-58. `POST /api/v1/me/addresses`

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

#### BE-59. `DELETE /api/v1/me/addresses/{id}`

Snapshot giao hàng của ERD chỉ có 255 ký tự, cần kiểm tra cả chuỗi đã ghép. */ private LocationService.AddressText addressText(Address body) { var value = locations.validateAddress(body.provinceCode(), body.wardCode(), body.city(), body.ward()); Problem.require((body.street().strip() + ", " + value.ward() + ", " + value.city()).length() <= 255, 422, "ADDRESS_TOO_LONG", "Địa chỉ đầy đủ vượt 255 ký tự; rút gọn số nhà/tên đường."); return value; } /** Xóa địa chỉ riêng; FK bảo vệ địa chỉ còn liên kết với đơn.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `id`.

#### BE-60. `PUT /api/v1/me/addresses/{id}`

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

#### BE-61. `POST /api/v1/me/password`

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

#### BE-62. `GET /api/v1/merge-history/district/{code}`

LocationController: districtHistory

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldDistrictCode`.

#### BE-63. `GET /api/v1/merge-history/province/{code}`

LocationController: provinceHistory

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldProvinceCode`.

#### BE-64. `GET /api/v1/merge-history/ward/{code}`

LocationController: wardHistory

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldWardCode`.

#### BE-65. `GET /api/v1/new-full-address`

LocationController: newFull

- Quyền: `Public`. Thành công: **200**.
- Tham số: provinceCode (bắt buộc), wardCode (bắt buộc), street (tùy chọn). Biến đường dẫn: không có.

#### BE-66. `GET /api/v1/new-provinces`

Địa chỉ hiện hành chỉ có tỉnh -> phường/xã/đặc khu.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-67. `GET /api/v1/new-provinces/{code}`

LocationController: newProvince

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `provinceCode`.

#### BE-68. `GET /api/v1/new-provinces/{code}/wards`

LocationController: newWards

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `provinceCode`.

#### BE-69. `GET /api/v1/new-wards/{code}`

LocationController: newWard

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `wardCode`.

#### BE-70. `GET /api/v1/notifications`

Thông báo của người đăng nhập, hỗ trợ lọc chưa đọc.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: unread (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-71. `POST /api/v1/notifications/{id}/read`

Đánh dấu đã đọc có ownership, không sửa nội dung thông báo.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `notificationId`.

#### BE-72. `GET /api/v1/orders`

Lịch sử đơn riêng, không nhận userId để xem người khác.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-73. `POST /api/v1/orders`

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

#### BE-74. `GET /api/v1/orders/{id}`

Chi tiết đơn được kiểm tra ownership.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-75. `POST /api/v1/orders/{id}/cancel`

Khách chỉ hủy trước khi đóng gói, hoàn tồn nguyên tử.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-76. `GET /api/v1/pos/customers`

Khách tại quầy chỉ tra bằng số điện thoại chính xác, không liệt kê cả database.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: phone (bắt buộc). Biến đường dẫn: không có.

#### BE-77. `POST /api/v1/pos/orders`

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

#### BE-78. `GET /api/v1/pos/orders/{id}/receipt`

Dữ liệu hóa đơn từ snapshot đơn; UI/browser in, API không giả lập máy in vật lý.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-79. `GET /api/v1/pos/shifts`

Danh sách ca của thu ngân hiện tại; admin xem tất cả, lọc trạng thái và phân trang.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: status (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-80. `POST /api/v1/pos/shifts`

Mở ca; khóa cashier để không có hai ca mở đồng thời.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "amount": 100000
}
```

#### BE-81. `GET /api/v1/pos/shifts/{id}`

Đọc tổng doanh thu ca từ các đơn liên kết, không tin tổng client gửi lên.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

#### BE-82. `POST /api/v1/pos/shifts/{id}/close`

Kết ca đúng người mở, lưu tiền kiểm đếm và chờ chủ shop duyệt.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `shiftId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "amount": 100000
}
```

#### BE-83. `GET /api/v1/pos/try-on/sessions/{id}`

O2O: khách phải cung cấp cả mã phiên và số điện thoại; không mở ảnh người riêng cho staff.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER')`. Thành công: **200**.
- Tham số: phone (bắt buộc). Biến đường dẫn: `id` → `sessionId`.

#### BE-84. `GET /api/v1/profile`

Nguồn, license, phiên bản và số dòng; không phải hồ sơ người dùng.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-85. `GET /api/v1/promotions`

Voucher đang hiệu lực, không lộ dữ liệu tài chính nội bộ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-86. `POST /api/v1/promotions/quote`

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

#### BE-87. `GET /api/v1/provinces`

63 tỉnh/thành của snapshot cũ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-88. `GET /api/v1/provinces/{code}`

LocationController: province

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldProvinceCode`.

#### BE-89. `GET /api/v1/provinces/{code}/districts`

Dropdown huyện theo tỉnh cũ.

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldProvinceCode`.

#### BE-90. `POST /api/v1/reviews`

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

#### BE-91. `POST /api/v1/scoring/evaluate`

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

#### BE-92. `GET /api/v1/scoring/recommendations`

Gợi ý SKU thật còn hàng có điểm dự kiến ít nhất 90.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: variantId (bắt buộc), tucked (tùy chọn). Biến đường dẫn: không có.

#### BE-93. `PATCH /api/v1/scoring/recommendations/{id}/interaction`

Tương tác chỉ tiến lên; thêm giỏ/mua phải có dữ liệu thực của chủ phiên.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `recommendationId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "status": "DA_THU_THEM"
}
```

#### BE-94. `GET /api/v1/scoring/rules`

Nhận bộ chấm điểm dùng workbook. */ public ScoringController(ScoringService scoring, com.yofaso.shop.common.Db db) { this.scoring = scoring; this.db = db; } /** Trả bảng gốc đã trích để đối chiếu rule, không nhận điểm client gửi.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-95. `POST /api/v1/scoring/sessions/{id}`

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

#### BE-96. `GET /api/v1/scoring/sessions/{id}/recommendations`

Lịch sử riêng của chủ phiên; không coi tồn kho lúc gợi ý là cam kết giữ hàng.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-97. `POST /api/v1/scoring/sessions/{id}/recommendations`

Quét hàng còn bán, chỉ ghi điểm >=90; gọi lại không xóa lịch sử tương tác.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: tucked (tùy chọn). Biến đường dẫn: `id` → `sessionId`.

#### BE-98. `GET /api/v1/search-address`

Search có giới hạn, hỗ trợ không dấu.

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (bắt buộc), provinceCode (tùy chọn), limit (tùy chọn), offset (tùy chọn). Biến đường dẫn: không có.

#### BE-99. `GET /api/v1/search-new-address`

LocationController: searchNew

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (bắt buộc), provinceCode (tùy chọn), limit (tùy chọn), offset (tùy chọn). Biến đường dẫn: không có.

#### BE-100. `POST /api/v1/staff/inventory/adjustments`

Nhập hàng/cân kho ghi dấu vết và chặn tồn âm.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "delta": 10,
  "reason": "Nhập hàng thử nghiệm",
  "variantId": "{{variantId}}"
}
```

#### BE-101. `GET /api/v1/staff/inventory/low-stock`

Cảnh báo tồn thấp dùng chung POS/kho, phân trang có giới hạn.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: threshold (tùy chọn), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-102. `GET /api/v1/staff/inventory/movements`

Sổ cái kho theo SKU.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: variantId (bắt buộc), page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-103. `GET /api/v1/staff/orders`

Đơn staff: shipper chỉ thấy đơn được phân, cashier chỉ thấy đơn mình lập.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-104. `GET /api/v1/staff/orders/{id}`

Chi tiết để in pick list/receipt; staff không được lấy đơn ngoài phạm vi.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE','STAFF_CASHIER','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-105. `POST /api/v1/staff/orders/{id}/assign`

Kho phân công đúng nhân viên có role shipper.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "shipperId": "{{shipperId}}"
}
```

#### BE-106. `POST /api/v1/staff/orders/{id}/deliver`

Shipper xác nhận đã giao và thu COD; không dùng cho thanh toán VietQR.

- Quyền: `hasRole('STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-107. `POST /api/v1/staff/orders/{id}/delivery-failed`

Giao thất bại chuyển về kho chờ phân lại, chưa hoàn tồn khi hàng chưa về.

- Quyền: `hasRole('STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-108. `POST /api/v1/staff/orders/{id}/pack`

Kho nhận đơn và chuyển sang đóng gói.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-109. `GET /api/v1/staff/orders/{id}/pick-list`

Pick list gồm SKU, số lượng và vị trí kệ; không sửa tồn kho khi đọc.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-110. `POST /api/v1/staff/orders/{id}/receive-return`

Kho xác nhận nhận lại hàng vật lý và hoàn tồn một lần.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `orderId`.

#### BE-111. `GET /api/v1/staff/product-filters`

Dropdown nội bộ bổ sung trạng thái sản phẩm.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: không có.

#### BE-112. `GET /api/v1/staff/products`

CatalogController: adminProducts

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn), q (tùy chọn), categoryId (tùy chọn), color (tùy chọn), material (tùy chọn), fit (tùy chọn), pattern (tùy chọn), status (tùy chọn). Biến đường dẫn: không có.

#### BE-113. `GET /api/v1/staff/products/{id}`

CatalogController: adminProduct

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE','STAFF_SHIPPER')`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `productId`.

#### BE-114. `GET /api/v1/staff/variants/by-sku`

Tra barcode nội bộ có vị trí kệ, không mở cho khách vãng lai.

- Quyền: `hasAnyRole('SHOP_ADMIN','STAFF_CASHIER','STAFF_WAREHOUSE')`. Thành công: **200**.
- Tham số: sku (bắt buộc). Biến đường dẫn: không có.

#### BE-115. `GET /api/v1/suggest`

LocationController: searchNew

- Quyền: `Public`. Thành công: **200**.
- Tham số: q (bắt buộc), provinceCode (tùy chọn), limit (tùy chọn), offset (tùy chọn). Biến đường dẫn: không có.

#### BE-116. `GET /api/v1/try-on/sessions`

Lịch sử cá nhân không chứa input ảnh của người khác.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: page (tùy chọn), size (tùy chọn). Biến đường dẫn: không có.

#### BE-117. `POST /api/v1/try-on/sessions`

Tạo phiên bền trong MySQL, tác vụ nền sẽ submit sang FastAPI.

- Quyền: `JWT của người dùng`. Thành công: **202**.
- Tham số: Idempotency-Key (bắt buộc). Biến đường dẫn: không có.

Body → raw → JSON (đã có sẵn trong collection):

```json
{
  "consent": true,
  "personAssetId": "{{personAssetId}}",
  "samples": 1,
  "seed": 42,
  "steps": 20,
  "variantId": "{{variantId}}"
}
```

#### BE-118. `GET /api/v1/try-on/sessions/{id}`

Poll chỉ trả phiên do người dùng hiện tại tạo.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-119. `POST /api/v1/try-on/sessions/{id}/cancel`

Chỉ hủy job AI còn đang xếp hàng; đang render có thể trả 409.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-120. `POST /api/v1/try-on/sessions/{id}/retry`

Thử đối soát lại hoặc gọi retry theo policy lỗi tạm của AI.

- Quyền: `JWT của người dùng`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `id` → `sessionId`.

#### BE-121. `GET /api/v1/wards/{code}`

LocationController: oldWard

- Quyền: `Public`. Thành công: **200**.
- Tham số: Không có query/header riêng.. Biến đường dẫn: `code` → `oldWardCode`.
