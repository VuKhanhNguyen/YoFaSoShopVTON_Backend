"""Chay MySQL rieng tren port ngau nhien de test, khong cham MySQL80 cua nguoi dung."""
from pathlib import Path
import os,sys,socket,subprocess,tempfile,time,json,shutil,hashlib
be=Path(__file__).resolve().parents[1]
server=Path(os.environ.get('MYSQLD_PATH',r'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe'))
if not server.is_file():raise SystemExit('Dat MYSQLD_PATH den mysqld hoac dung MySQL test rieng. Khong tu dung DB ca nhan.')
(be/'target').mkdir(exist_ok=True)
(be/'target/test-environment.json').write_text(json.dumps({'mysqlVersion':subprocess.check_output([str(server),'--version'],text=True).strip()}),encoding='utf-8')
data=be/'data'/'test-mysql';data.mkdir(parents=True,exist_ok=True)
run=Path(tempfile.mkdtemp(prefix='run-',dir=data)); database=run/'db';database.mkdir()
subprocess.run([str(server),'--no-defaults','--initialize-insecure',f'--datadir={database}'],check=True,stdout=subprocess.DEVNULL,stderr=open(run/'init.log','w'))
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
log=open(run/'server.log','w')
proc=subprocess.Popen([str(server),'--no-defaults',f'--datadir={database}',f'--port={port}','--bind-address=127.0.0.1','--mysqlx=0','--skip-log-bin','--default-time-zone=+00:00','--console'],stdout=log,stderr=log)
try:
 for _ in range(120):
  if proc.poll() is not None:raise RuntimeError((run/'server.log').read_text(errors='replace'))
  try:
   with socket.create_connection(('127.0.0.1',port),timeout=.3):break
  except OSError:time.sleep(.5)
 else:raise RuntimeError('Test MySQL startup timeout')
 schema_sql='CREATE DATABASE yofaso_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; USE yofaso_test;\n'+(be/'src/main/resources/db/schema.sql').read_text(encoding='utf-8')
 cli=server.with_name('mysql.exe');cli_env=os.environ.copy();cli_env['MYSQL_PWD']=''
 subprocess.run([str(cli),'--protocol=tcp','-h127.0.0.1',f'-P{port}','-uroot','--default-character-set=utf8mb4'],input=schema_sql,text=True,encoding='utf-8',env=cli_env,check=True,stdout=subprocess.DEVNULL)
 if '--seed-only' in sys.argv:
  from prepare_erd_bootstrap import statements
  import re
  inserts=[s for s in statements((be.parent/'yofaso_insert_data.sql').read_text(encoding='utf-8-sig')) if re.match(r'INSERT INTO ',s,re.I)]
  assert len(inserts)==19
  seed_sql='USE yofaso_test; DELETE FROM VaiTro; START TRANSACTION;\n'+'\n'.join(inserts)+'\nCOMMIT;\nSELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE=\'BASE TABLE\';'
  check=subprocess.run([str(cli),'--protocol=tcp','-h127.0.0.1',f'-P{port}','-uroot','--default-character-set=utf8mb4','--batch','--skip-column-names'],input=seed_sql,text=True,encoding='utf-8',capture_output=True,env=cli_env,check=True)
  assert check.stdout.strip().splitlines()[-1]=='19',check.stdout
  (be/'reports/erd-seed-verification.json').write_text(json.dumps({'tables':19,'seedInsertGroups':19,'foreignKeysEnabled':True,'isolatedMySQL':True,'schemaSourceSha256':hashlib.sha256((be.parent/'yofasoshop_db.sql').read_bytes()).hexdigest(),'seedSourceSha256':hashlib.sha256((be.parent/'yofaso_insert_data.sql').read_bytes()).hexdigest()},indent=2),encoding='utf-8')
  print('PASS: original 19 seed groups import with foreign keys enabled; exactly 19 tables.')
  sys.exit(0)
 if '--demo-only' in sys.argv:
  sql='USE yofaso_test;\n'
 
  fixture=(be/'scripts/demo_catalog.sql').read_text(encoding='utf-8')
  sql+=fixture+'\n'+fixture+"\nSELECT COUNT(*), SUM(SoLuongTonKho) FROM BienTheSanPham WHERE MaBienThe IN ('BTDEMO01','BTDEMO02');"
  cli=server.with_name('mysql.exe');cli_env=os.environ.copy();cli_env['MYSQL_PWD']=''
  check=subprocess.run([str(cli),'--protocol=tcp','-h127.0.0.1',f'-P{port}','-uroot','--default-character-set=utf8mb4','--batch','--skip-column-names'],input=sql,text=True,encoding='utf-8',capture_output=True,env=cli_env,check=True)
  assert check.stdout.strip().splitlines()[-1]=='2\t20',check.stdout
  (be/'target/demo-fixture-verified.json').write_text(json.dumps({'sqlSha256':hashlib.sha256(fixture.encode()).hexdigest(),'rows':2,'stockSumAfterTwoRuns':20,'database':'isolated MySQL 8.0 test process'}),encoding='utf-8')
  print('PASS: demo SQL creates 2 SKUs; second run preserves stock, using isolated database.')
  sys.exit(0)
 env=os.environ.copy();env.update(DB_URL=f'jdbc:mysql://127.0.0.1:{port}/yofaso_test?createDatabaseIfNotExist=true&connectionTimeZone=UTC',DB_USERNAME='root',DB_PASSWORD='',JWT_SECRET='test-only-random-key-32-bytes-never-production-8b738272',AI_POLL_ENABLED='false',BOOTSTRAP_ADMIN_USERNAME='',MAIL_ENABLED='false',MAIL_USERNAME='',MAIL_APP_PASSWORD='',MAIL_FROM='',BACKEND_RUNTIME_ROOT=str(run/'backend-runtime'),R2_ENDPOINT='',R2_ACCESS_KEY_ID='',R2_SECRET_ACCESS_KEY='')
 with open(be/'reports'/'maven-test.log','w',encoding='utf-8') as testlog:
  result=subprocess.run(['cmd','/c','mvnw.cmd','-q','clean','verify'],cwd=be,env=env,stdout=testlog,stderr=subprocess.STDOUT)
 (be/'target/test-environment.json').write_text(json.dumps({'mysqlVersion':subprocess.check_output([str(server),'--version'],text=True).strip()}),encoding='utf-8')
 print('Test-only MySQL port:',port,'exit:',result.returncode,flush=True)
 if result.returncode==0:
  subprocess.run([sys.executable,'-X','utf8',str(be/'scripts/export_contract.py')],check=True)
  subprocess.run([sys.executable,'-X','utf8',str(be/'scripts/report_verification.py')],check=True)
 sys.exit(result.returncode)
finally:
 admin=server.with_name('mysqladmin.exe')
 try:subprocess.run([str(admin),'--protocol=tcp','-h127.0.0.1',f'-P{port}','-uroot','shutdown'],timeout=15,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 except Exception:proc.terminate()
 try:proc.wait(timeout=20)
 except subprocess.TimeoutExpired:proc.kill()
 log.close()
 # Chi xoa datadir tu tao cua lan test nay, sau khi process da dung.
 resolved_run=run.resolve();resolved_root=data.resolve()
 if proc.poll() is not None and resolved_run.is_relative_to(resolved_root) and resolved_run.name.startswith('run-'):
  shutil.rmtree(resolved_run)
