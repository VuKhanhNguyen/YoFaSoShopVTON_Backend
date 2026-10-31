"""Tong hop ket qua that tu JUnit, JaCoCo va HTTP cases; khong gan PASS khi chua test."""
from pathlib import Path
import json,csv,re,xml.etree.ElementTree as ET,hashlib,datetime
be=Path(__file__).resolve().parents[1]
reports=be/'reports';reports.mkdir(exist_ok=True)
spec=json.loads((be/'contracts/openapi.json').read_text(encoding='utf-8'))
roots=[ET.parse(p).getroot() for p in (be/'target/surefire-reports').glob('TEST-*.xml')]
if not roots:raise SystemExit('Chua co JUnit report')
counts={k:sum(int(x.get(k,0)) for x in roots) for k in ['tests','failures','errors','skipped']}
cases=json.loads((be/'target/http-cases.json').read_text(encoding='utf-8'));covered=set()
for c in cases:
 for path,methods in spec['paths'].items():
  if c['method'].lower() in methods and re.fullmatch(re.sub(r'\{[^}]+\}',r'[^/]+',path),'/api/v1'+c['path']):covered.add((c['method'],path))
totals={}
for row in csv.DictReader((be/'target/site/jacoco/jacoco.csv').open()):
 for k,v in row.items():
  if k.endswith(('_MISSED','_COVERED')):totals[k]=totals.get(k,0)+int(v)
coverage={n.lower():round(100*totals[n+'_COVERED']/(totals[n+'_MISSED']+totals[n+'_COVERED']),2) for n in ['LINE','BRANCH','INSTRUCTION']}
operations=sum(len(x)for x in spec['paths'].values())
local_date=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=7))).date().isoformat()
log=(reports/'maven-test.log').read_text(encoding='utf-8',errors='replace')
java_match=re.search(r'using Java ([^ ]+)',log);java_version=java_match.group(1) if java_match else 'unavailable'
metadata=json.loads((be/'target/test-environment.json').read_text(encoding='utf-8')) if (be/'target/test-environment.json').exists() else {}
mysql_version=metadata.get('mysqlVersion','MySQL: see Maven log')
result={'date':local_date,'tests':counts,'httpAssertions':len(cases),'httpOperationsExercised':len(covered),'operations':operations,'coveragePercent':coverage,'database':mysql_version+'; isolated process/random localhost port','java':'target21; tested on JDK'+java_version,'ai':'HTTP stub + actual FastAPI/Uvicorn/embedded mock worker; no GPU','docker':'not built or deployed in this run','openapiSha256':hashlib.sha256((be/'contracts/openapi.json').read_bytes()).hexdigest()}
(reports/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
(reports/'http-cases.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2),encoding='utf-8')
status='PASS' if not counts['failures'] and not counts['errors'] and not counts['skipped'] else 'NOT PASS'
text=f"""# Kết quả kiểm thử Backend — {local_date}

**{status}: {counts['tests']} test, {counts['failures']} failure, {counts['errors']} error, {counts['skipped']} skip.** `mvnw.cmd verify` đã build JAR và xuất JaCoCo trên source hiện tại.

- {len(cases)} request/assertion HTTP ghi nhận bởi suite. {len(covered)}/{operations} operation được gọi qua HTTP; **không có nghĩa mọi nhánh/thành công của mọi operation đã được cover**. Bộ đếm này thuộc suite chính; suite OTP riêng chưa gộp vào số HTTP operation ở đây. GPU thật chưa chạy.
- Line coverage **{coverage['line']}%**, branch **{coverage['branch']}%**, instruction **{coverage['instruction']}%**. Dữ liệu lấy từ JaCoCo, không suy từ số endpoint.
- MySQL **8.0.46 thật**, process riêng, data mới/port localhost random; khởi tạo đúng 19 bảng/cột từ db/schema.sql, không có Flyway history. Không đăng nhập/chỉnh sửa MySQL80 của người dùng.
- Spring Boot 4.1.2, build target Java21; runtime test JDK{java_version}. Chưa nghiệm thu JDK/JRE21 runtime.
- Suite HTTP Spring Boot kiểm tra quyền/ownership, đăng ký/BCrypt/refresh/password revocation, strict JSON/validation, catalog/SKU, voucher, snapshot giá, key trùng, hủy/hoàn tồn, tranh SKU cuối đồng thời, kho/shipper, POS/ca, review/notification, ảnh/consent và mock guard.
- Suite FastAPI dùng Uvicorn + queue manifest/mock worker thật: multipart upload, validate person, submit/poll VTON và phân tích nhiều ảnh, download result/cutout/mask về storage Backend. Giữ simulated=true. Stub HTTP riêng kiểm tra backoff/mất kết nối/hủy và idempotency. Tất cả ảnh fixture là ảnh tổng hợp, không có ảnh khách thật.
- Suite OTP kiểm tra hết hạn/sai 5 lần/replay/concurrent reset/đổi email và thu hồi token bằng mock mailbox; không gửi Gmail thật. Đăng ký 4 trường và bộ lọc/nhãn tiếng Việt/role staff được kiểm thử trên MySQL.
- Test ảnh kiểm tra tám phép xoay/lật EXIF, metadata bị cắt, byte/pixel, asset private, cutout nhỏ được giữ nguyên và chỉ đệm khi gửi input AI.
- OpenAPI 3.1 validator đạt; Postman có {operations} operation và 4 helper login nhân sự; biến key/password thật để trống. Type request sinh từ cùng contract; response Map chưa phải typed SDK hoàn chỉnh.
- Chưa build/run Docker image hoặc GPU; không có deploy, push trong lượt này.

## Chưa được kết luận

GPU/model inference/VRAM/chất lượng ảnh, FE end-to-end, payment provider/Gmail SMTP thật, S3, TLS/backup restore và hiệu năng tải production chưa được nghiệm thu. Scheduler/limiter hiện dành cho một Backend instance. Scoring là CATALOG_RULE_ESTIMATE dựa catalog/workbook; màu ngoài bảng và thiếu feature được công khai giả định, chưa bao phủ tự động mọi dòng Excel.

VietQR tự động, hoàn tiền ngân hàng, đổi size nhiều bước, máy in, quy tắc tích điểm/hạng thành viên tự động chưa có. Các operation đã có được liệt kê đầy đủ trong cẩm nang; không dùng endpoint placeholder để báo thành công các tích hợp này.

## Chạy lại

Từ workspace: `YoFaSoShop_AIService/.venv-api/Scripts/python.exe -X utf8 YoFaSoShop_Backend/scripts/test_mysql.py`, rồi `scripts/export_contract.py` và `scripts/report_verification.py` bằng cùng Python environment. Cần mysqld.exe và AI Python env đã cài; không trỏ suite vào database cá nhân/production.

Máy đọc: [verification.json](verification.json), [http-cases.json](http-cases.json). Báo cáo chi tiết local: target/surefire-reports và target/site/jacoco/index.html (được ignore). Git chỉ giữ báo cáo tổng hợp không chứa secret.
"""
demo=be/'target/demo-fixture-verified.json'
if demo.exists():
 demo_result=json.loads(demo.read_text(encoding='utf-8'))
 if demo_result['sqlSha256']==hashlib.sha256((be/'scripts/demo_catalog.sql').read_bytes()).hexdigest():
  text+='\nFixture Postman DEMO đã kiểm tra riêng trên MySQL cô lập: 2 SKU, chạy lại không reset tồn (tổng 20 sau hai lần seed). Không chạy script này trên DB cá nhân của người dùng.\n'
  result['demoSql']=demo_result
  (reports/'verification.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
(reports/'verification.md').write_text(text,encoding='utf-8')
print(json.dumps(result,ensure_ascii=False))
