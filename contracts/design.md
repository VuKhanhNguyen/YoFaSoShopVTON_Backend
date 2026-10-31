# Backend/AI contract — ERD cố định 19 bảng

SQL nghiệp vụ đúng yofasoshop_db.sql; không tự DDL/Flyway/SQLite. Backend owns MySQL; AI nhận bytes/model jobs và không kết nối SQL. Manifest JSON trên volume riêng giữ token/idempotency/assets/operations/queue, khóa OS + ghi nguyên tử; một Backend và một host API/worker AI.

/api/v1: JWT Bearer, camelCase, ownership/RBAC từ role/status hiện hành. Auth BCrypt + refresh rotation/revocation. Catalog/SKU/màu, kho, giỏ, đơn, POS/ca, review, notification dùng bảng gốc. Audit ghi log, bỏ /admin/audit-events.

Phân tích sản phẩm thật: mọi original/cutout/mask lên R2 namespace product/analysis, thứ tự input cố định; mask img01 là đại diện chung SanPham. HinhAnhSanPham dùng MaSanPham+MaMauSac, không thêm MaBienThe. Admin duyệt metadata và dùng CRUD product xác nhận form/eo/length/pattern.

PhienThuDoAo giữ trạng thái nghiệp vụ và cột điểm gốc; operation simulated ở manifest. Mock hoàn tất operation nhưng SQL THAT_BAI/MOCK_RESULT_ONLY; không publish/chấm điểm thật từ mock. processingSeconds do worker đo, không cộng thời gian nằm queue.

Scoring là CATALOG_RULE_ESTIMATE theo workbook. GET recommendations là preview; POST/GET sessions/{id}/recommendations lưu GoiYPhoiDo >=90, PATCH interaction kiểm tra owner và bằng chứng giỏ/đơn. Gọi lại không reset lịch sử.

Địa chỉ mới/cũ/convert dùng JSON local trong RAM, xác nhận mapping mơ hồ; SoDiaChi chỉ lưu các cột text gốc. OpenAPI/Postman/DTO/reference được sinh lại từ route, xem setupGuide.md và apiTestingGuide.md.
