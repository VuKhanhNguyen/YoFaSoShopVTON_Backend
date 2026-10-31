# YoFaSoShop Backend

> **30/10/2026 — ERD cố định 19 bảng:** Backend ghi MySQL, AI không kết nối SQL. Runtime dùng manifest file bền vững trên volume riêng, không SQLite/Flyway/history table. Xem [setupGuide](../YoFaSoShop_Docs/extra-info/setupGuide.md) và [migrationAndLocationGuide](../YoFaSoShop_Docs/extra-info/migrationAndLocationGuide.md).

Spring Boot 4.1.2, Java target 21, Maven Wrapper, MySQL 8.0+, Spring Security JWT.
Source sử dụng **JdbcTemplate và SQL tham số hóa** để bám schema ERD; chưa có Entity/repository JPA. Dependency JPA của khung vẫn giữ, `ddl-auto=none` và sql.init.mode=never; schema được import tường minh, SchemaGuard chỉ kiểm tra. Không dùng `ddl-auto=create/update` để sửa lỗi schema.

## Chạy local

1. Tạo **database dev mới** và user bằng `scripts/create_dev_database.sql` sau khi đổi mật khẩu mẫu. Script không được tự chạy trong lượt coding. Nếu user đã tồn tại thì `CREATE USER IF NOT EXISTS` không đổi mật khẩu.
2. Sau khi tạo/chọn schema trống, import `src/main/resources/db/schema.sql` bằng tài khoản quản trị để có đúng 19 bảng + 5 role (không import lại lên DB đã có bảng). Chạy `scripts/init_env.ps1` một lần. Script giữ `.env` nếu đã có, tạo JWT key ngẫu nhiên và có thể chép key AI từ `.env` private của AI Service. Không in key. File `.env` đã được tạo trong lượt hiện thực; điền `DB_PASSWORD` và kiểm tra `DB_URL`/`DB_USERNAME` thực tế.
3. Để có chủ shop lần đầu, điền `BOOTSTRAP_ADMIN_USERNAME`, `BOOTSTRAP_ADMIN_PASSWORD` (ít nhất 10 ký tự, có chữ/số), `BOOTSTRAP_ADMIN_PHONE` trong `.env`. Khởi động thành công và đăng nhập được thì xóa ba giá trị bootstrap khỏi `.env`; user đã được lưu MySQL. Không có admin/password mặc định.
4. Từ thư mục Backend:

```powershell
.\mvnw.cmd -v
.\mvnw.cmd spring-boot:run
```

`http://localhost:8080/actuator/health` kiểm tra DB/ứng dụng, không chứng minh GPU sẵn sàng. `GET /api/v1/contract` trả OpenAPI; không cài Swagger UI trong bản này. Xem [hướng dẫn Postman](../YoFaSoShop_Docs/extra-info/apiTestingGuide.md).

`.env` dùng cú pháp Java properties (`KEY=value`, không quote/export), được import rõ trong `application.yaml`. Khóa JWT và AI là hai khóa khác nhau. Đổi JWT key làm access token cũ vô hiệu; không tự đổi mỗi lần chạy app. AI local dùng port 8000, SSH tunnel dùng 18000. Không đưa key AI/JWT vào FE.

Để tự test COD/POS/catalog bằng Postman khi chưa có GPU: xem `scripts/demo_catalog.sql`, chỉ chạy thủ công trên DB dev mới sau migration. Hai mặt hàng có nhãn DEMO, không có ảnh VTON, không được coi là sản phẩm AI đã nghiệm thu. Không tự seed khi app chạy. Xem phần B.3.3 trong cẩm nang.

## Các phần đã có

- Auth: BCrypt, đăng ký customer, access 15 phút, refresh 7 ngày xoay một lần, logout, đổi mật khẩu, khóa tài khoản/role có hiệu lực với token cũ.
- Catalog: danh mục, SKU/giá/màu/size, nháp/công bố/ngừng bán; ảnh/cutout/mask theo SKU; phân tích nhiều ảnh và duyệt trước khi publish. Mock không được publish.
- Giỏ, COD, voucher, tồn single pool có ledger/transaction/row lock, snapshot giá, idempotency tạo đơn/điều chỉnh kho; luồng kho/shipper, nhận hoàn tồn đúng một lần.
- POS tiền mặt, mở/kết/duyệt ca, tra SKU/khách hàng, dữ liệu hóa đơn JSON; review verified buyer, thông báo, dashboard và audit.
- Ảnh riêng PNG/JPEG tối đa 10MB/20 triệu pixel, cạnh >=128px, sửa EXIF orientation rồi bỏ metadata; consent trước upload/thử đồ. WebP chưa được hỗ trợ ở Backend dù FastAPI hỗ trợ.
- AI manifest/polling bền trên volume, tìm lại theo sessionId sau timeout, retry/backoff hữu hạn, kết quả tải về local storage và giữ simulated. **Chạy một instance Backend** cho scheduler bản này.
- Điểm 40/40/20 theo catalog/workbook và gợi ý SKU thật còn tồn đạt >=90; đây là **ước tính quy tắc catalog**, chưa phải đo vóc dáng chính xác trên ảnh. API riêng lưu điểm vào phiên thật đã hoàn tất; từ chối phiên mock.

## Cấu trúc

`common/`: lỗi/SQL/ID/idempotency/OpenAPI; `config/`: JWT/CORS/limiter/bootstrap; `auth/`: tài khoản; `catalog/`: sản phẩm; `order/`: cart/COD/POS/kho/staff/review; `ai/`: storage/client/outbox; `scoring/`: bảng điểm. Mỗi endpoint và hàm có comment/docstring tiếng Việt. Hợp đồng và Postman nằm ở `contracts/`, `postman/`.

## Test

```powershell
# Từ workspace gốc; cần mysqld.exe đã cài và Python environment AI Service có sẵn.
.\YoFaSoShop_AIService\.venv-api\Scripts\python.exe -X utf8 YoFaSoShop_Backend/scripts/test_mysql.py
.\YoFaSoShop_AIService\.venv-api\Scripts\python.exe -X utf8 YoFaSoShop_Backend/scripts/export_contract.py
```

Script test khởi động **MySQL process riêng, cổng random, database test mới**, tắt khi xong; không đăng nhập/chỉnh sửa MySQL80 của bạn. File test trong `data/` được ignore. Nếu MySQL cài ở nơi khác, đặt `MYSQLD_PATH` đến `mysqld.exe`. Một suite gọi AI stub; một suite chạy FastAPI/Uvicorn + mock worker thật bằng Python, không tải weights. Đường dẫn Python Windows hiện ở `.venv-api/Scripts/python.exe` trong suite đó. Test đầy đủ cần môi trường AI đã setup theo phần A.

Nếu dùng MySQL test do bạn cấp riêng: đặt `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` test và chạy `mvnw.cmd verify`. **Không trỏ vào DB dev/production**, vì fixture sẽ tạo dữ liệu test. Test không dùng H2 để thay thế kiểm tra SQL MySQL. Xem `reports/verification.md` cho kết quả và giới hạn thực đo.

`scripts/export_contract.py` kiểm tra OpenAPI 3.1 và phát hiện một số breaking changes trước khi cập nhật snapshot/collection; đây chưa phải bộ kiểm tra tương thích đầy đủ cho mọi biến đổi JSON Schema. Rà soát contract trong code review, không dùng `--accept-breaking` để bỏ qua lỗi thiếu suy xét. JSON response Map vẫn linh hoạt; `contracts/requests.d.ts` chỉ cung cấp type request, chưa phải SDK response hoàn chỉnh.

## Docker (chưa chạy nghiệm thu)

`Dockerfile` build bằng JDK21 rồi chạy JRE21 non-root. `.dockerignore` loại secret và ảnh. Compose tạo MySQL/storage volume riêng, không công khai port MySQL ra ngoài. Điền thêm `MYSQL_ROOT_PASSWORD` trong `.env`. Nếu AI chạy trên laptop Docker Desktop, đặt `AI_SERVICE_BASE_URL=http://host.docker.internal:8000` trong `.env` cho container Backend.

```powershell
docker compose config --quiet
docker compose up --build -d
```

Image build bỏ qua test vì cần database riêng; phải chạy suite test trước. Không dùng `docker compose down -v` để sửa lỗi (xóa volume). Sao lưu **MySQL, runtime manifest và storage**; các asset AI có hạn nên Backend đã copy kết quả cần giữ. Deploy thật cần HTTPS, managed secret, tài khoản DB runtime chỉ có quyền DML, backup đã thử restore, limiter dùng chung khi nhiều replica và scheduler có distributed claim/lease.

## Các tích hợp chưa có

VietQR/payment webhook tự động (chưa có provider/secret để xác thực), hoàn tiền ngân hàng, đổi size nhiều bước, in máy in phần cứng, LLM stylist, private R2 hoàn chỉnh, quy tắc tích điểm/hạng thành viên tự động và FE. Không có endpoint giả báo thanh toán thành công. API hiện chỉ nhận COD online và tiền mặt POS; freeship voucher hiện không giảm thêm vì phí ship bản đầu bằng 0. Nhận hàng hoàn không tự đổi trạng thái tiền thành đã hoàn; cần nghiệp vụ đối soát/hoàn tiền sau.

Một số quy tắc workbook cần dữ liệu catalog hiện chưa có (Jacquard, gân, lớp phụ kiện, độ phủ cơ thể thật). Engine chỉ áp dụng các field xác định được và công khai giả định/xấp xỉ màu trong response; không tuyên bố bao phủ đầy đủ mọi kịch bản thời trang trong Excel. Chưa nghiệm thu GPU/model thật hoặc hiệu năng production.

Forgot/reset OTP Gmail, filter có nhãn tiếng Việt và tra cứu staff đã bổ sung; xem setupGuide.md/apiTestingGuide.md và apiCoverageReview.md trong Docs/extra-info. SMTP thật cần điền MAIL_*; suite chỉ dùng mock mailbox.
