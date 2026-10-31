"""Export OpenAPI snapshot/Postman + phu luc endpoint tu route thuc, khong chua secret."""
from pathlib import Path
import json,re,argparse
from openapi_spec_validator import validate
be=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--accept-breaking',action='store_true');args=parser.parse_args()
spec=json.loads((be/'target/openapi-actual.json').read_text(encoding='utf-8'));validate(spec)
baseline=be/'contracts/openapi.json'
if baseline.exists():
 old=json.loads(baseline.read_text(encoding='utf-8'));breaking=[]
 for path,methods in old['paths'].items():
  for method,op in methods.items():
   if method not in spec['paths'].get(path,{}):breaking.append(f'Removed {method} {path}')
 for name,schema in old['components']['schemas'].items():
  new=spec['components']['schemas'].get(name,{})
  if set(schema.get('properties',{}))-set(new.get('properties',{})):breaking.append('Removed properties: '+name)
  if set(new.get('required',[]))-set(schema.get('required',[])):breaking.append('New required: '+name)
 if breaking and not args.accept_breaking:raise SystemExit('Review breaking changes before accepting: '+repr(breaking))
baseline.write_text(json.dumps(spec,ensure_ascii=False,indent=2),encoding='utf-8')
# Comment Viet nam trong Java duoc dung lam mo ta tung thao tac.
comments={}
for p in (be/'src/main/java').rglob('*Controller.java'):
 s=p.read_text(encoding='utf-8')
 for m in re.finditer(r'/\*\*(.*?)\*/\s*((?:(?!/\*\*).)*?)public\s+[^;{}]+?\s+(\w+)\(',s,re.S):
  comments[p.stem+'_'+m[3]]=re.sub(r'\s*\n\s*\*?\s*',' ',m[1]).strip()
values={'username':'{{customerUsername}}','password':'{{customerPassword}}','fullName':'Khách Test','phone':'0905000001','email':'customer01@example.test','recipientName':'Khách Test','street':'12 Đường Test','ward':'Phường Hải Châu','city':'Thành phố Đà Nẵng','provinceCode':'48','wardCode':'20242','defaultAddress':True,'categoryId':'{{categoryId}}','name':'Áo sơ mi test','position':0,'fit':'SUONG_VUA','waist':'KHONG_AP_DUNG','length':'AO_CROPTOP','pattern':'VAI_TRON','vtonCategory':'upper_body','subtype':'short_sleeved_shirt','sku':'TEST-WHITE-M','size':'M','colorName':'Trắng','colors':'#FFFFFF','price':100000,'status':'DANG_BAN','variantId':'{{variantId}}','quantity':1,'addressId':'{{addressId}}','voucher':'TEST10','amount':100000,'reason':'Nhập hàng thử nghiệm','delta':10,'currentPassword':'{{customerPassword}}','newPassword':'{{newPassword}}','refreshToken':'{{refreshToken}}','roleIds':['{{cashierRoleId}}'],'stars':5,'comment':'Sản phẩm đúng mô tả','orderLineId':'{{orderLineId}}','personAssetId':'{{personAssetId}}','assetId':'{{personAssetId}}','seed':42,'steps':20,'samples':1,'consent':True,'firstVariantId':'{{variantId}}','secondVariantId':'{{bottomVariantId}}','tucked':True,'personFrame':'full_body','garmentLength':'SHORT','garmentSubtype':'short_sleeved_shirt','productAssetIds':['{{garmentAssetId}}'],'referenceAssetIds':[],'sourceAssetId':'{{garmentAssetId}}','shipperId':'{{shipperId}}','code':'TEST10','type':'PHAN_TRAM','value':10,'minimumTotal':0,'maxDiscount':50000,'startsAt':'2026-01-01T00:00:00Z','endsAt':'2030-01-01T00:00:00Z'}
values.update(requestId='{{resetRequestId}}',otp='{{resetOtp}}')
schemas=spec['components']['schemas']
def example(s,field=''):
 if field in values:return values[field]
 if '$ref' in s:
  name=s['$ref'].split('/')[-1]
  if name=='Conversion':return {'provinceCode':'{{oldProvinceCode}}','districtCode':'{{oldDistrictCode}}','wardCode':'{{oldWardCode}}','street':'12 Đường Test'}
  return example(schemas[name])
 if s.get('type')=='object':return {k:example(v,k) for k,v in s.get('properties',{}).items() if k in s.get('required',[]) or k in ['addressId','secondVariantId','provinceCode','wardCode']}
 if s.get('type')=='array':return [example(s.get('items',{}))]
 if s.get('type')=='boolean':return True
 if s.get('type') in ['integer','number']:return max(1,s.get('minimum',0))
 return 'test'
def variable(path,name):
 if name=='code':
  if '/new-provinces/' in path:return 'provinceCode'
  if '/new-wards/' in path:return 'wardCode'
  if '/districts/' in path or '/merge-history/district/' in path:return 'oldDistrictCode'
  if '/wards/' in path or '/merge-history/ward/' in path:return 'oldWardCode'
  return 'oldProvinceCode'
 if '/catalog/images/' in path and name=='id':return 'imageId'
 if name!='id':return name
 if '/scoring/recommendations/' in path:return 'recommendationId'
 for fragment,var in [('/analyses/','analysisId'),('/categories/','categoryId'),('/products/','productId'),('/orders/','orderId'),('/users/','targetUserId'),('/shifts/','shiftId'),('/assets/','assetId'),('/notifications/','notificationId'),('/reviews/','reviewId'),('/sessions/','sessionId'),('/promotions/','promotionId')]:
  if fragment in path:return var
 return 'id'
def credential(path):
 if path.startswith('/api/v1/admin/'):return 'adminToken'
 if path.startswith('/api/v1/pos/'):return 'cashierToken'
 if path.endswith('/deliver') or path.endswith('/delivery-failed'):return 'shipperToken'
 if path.startswith('/api/v1/staff/'):return 'warehouseToken'
 return 'accessToken'
folders={}; sections=[];count=0
for path,methods in spec['paths'].items():
 for method,op in methods.items():
  count+=1; oid=op['operationId'];description=comments.get(oid,op['summary']);status=int(next(k for k in op['responses'] if k.isdigit()));url=path
  headers=[];query=[]
  for p in op.get('parameters',[]):
   if p['in']=='path':url=url.replace('{'+p['name']+'}','{{'+variable(path,p['name'])+'}}')
   elif p['in']=='header':headers.append({'key':p['name'],'value':'{{idempotencyKey}}' if p['name']=='Idempotency-Key' else '{{'+p['name']+'}}'})
   elif p['in']=='query':
    val=p['schema'].get('default','{{'+p['name']+'}}')
    if path=='/api/v1/full-address' and p['name'] in ['provinceCode','districtCode','wardCode']:val='{{'+{'provinceCode':'oldProvinceCode','districtCode':'oldDistrictCode','wardCode':'oldWardCode'}[p['name']]+'}}'
    query.append({'key':p['name'],'value':str(val).lower() if isinstance(val,bool) else str(val),'disabled':not p['required']})
  request={'method':method.upper(),'header':headers,'url':{'raw':'{{backendBaseUrl}}'+url,'host':['{{backendBaseUrl}}'],'path':url.lstrip('/').split('/')},'description':description+'\nQuyền: '+op.get('x-authorization', 'JWT' if op.get('security') else 'Public')}
  if query:request['url']['query']=query
  request['auth']={'type':'bearer','bearer':[{'key':'token','value':'{{'+credential(path)+'}}','type':'string'}]} if op.get('security') else {'type':'noauth'}
  body=None
  if 'requestBody' in op:
   content=op['requestBody']['content']
   if 'multipart/form-data' in content:
    request['body']={'mode':'formdata','formdata':[{'key':'file','type':'file','src':[]},{'key':'role','type':'text','value':'person'},{'key':'consent','type':'text','value':'true'}]}
   else:
    body=example(content['application/json']['schema'])
    if path=='/api/v1/auth/forgot-password':body={'email':'{{resetEmail}}'}
    if path=='/api/v1/auth/reset-password':body={'requestId':'{{resetRequestId}}','email':'{{resetEmail}}','otp':'{{resetOtp}}','newPassword':'{{newPassword}}'}
    if path=='/api/v1/pos/orders':body.pop('addressId',None);body['cashReceived']=200000
    if path.endswith('/quote'):body.pop('addressId',None);body['voucher']='TEST10'
    if path=='/api/v1/admin/users':body['account']=example(schemas['Register']);body['account'].update(username='warehouse01',phone='0905000002',email='warehouse01@example.test')
    if path.endswith('/status'):body={'status':'KHOA_TAM_THOI'}
    if path.endswith('/interaction'):body={'status':'DA_THU_THEM'}
    if path.startswith('/api/v1/admin/reviews/') and method=='patch':body={'status':'DA_HIEN_THI','reply':'Cảm ơn bạn'}
    request['body']={'mode':'raw','raw':json.dumps(body,ensure_ascii=False,indent=2),'options':{'raw':{'language':'json'}}};headers.append({'key':'Content-Type','value':'application/json'})
  script=[f'pm.test("HTTP {status}", () => pm.response.to.have.status({status}));']
  script+=['if (pm.response.code < 300 && pm.response.headers.get("Content-Type")?.includes("json")) {','  const d = pm.response.json();']
  if path in ['/api/v1/auth/register','/api/v1/auth/login','/api/v1/auth/refresh']:
   script+=['  if(d.accessToken) pm.environment.set("accessToken", d.accessToken);','  if(d.refreshToken) pm.environment.set("refreshToken", d.refreshToken);','  if(d.user?.userId) pm.environment.set("userId",d.user.userId);']
  script+=['  if(d.requestId && pm.request.url.toString().includes("forgot-password"))pm.environment.set("resetRequestId",d.requestId);']
  targets={'categoryId':'categoryId','productId':'productId','variantId':'variantId','addressId':'addressId','orderId':'orderId','shiftId':'shiftId','reviewId':'reviewId','promotionId':'promotionId'}
  for field,target in targets.items():script.append(f'  if(d.{field}) pm.environment.set("{target}",d.{field});')
  script+=['  if(Array.isArray(d)&&d[0]?.recommendationId)pm.environment.set("recommendationId",d[0].recommendationId);', '  if(d.operationId) pm.environment.set(d.kind === "PRODUCT_ANALYSIS" ? "analysisId" : "sessionId", d.operationId);','  if(d.assetId){ pm.environment.set("assetId",d.assetId); pm.environment.set(d.role === "person" ? "personAssetId" : "garmentAssetId",d.assetId); }','  if(d.userId && !d.user) pm.environment.set("targetUserId",d.userId);','  if(d.items?.[0]?.orderLineId) pm.environment.set("orderLineId",d.items[0].orderLineId);','}']
  if path=='/api/v1/admin/roles':script.append('if(pm.response.code===200){const names={ROLE_CUSTOMER:"customerRoleId",ROLE_SHOP_ADMIN:"adminRoleId",ROLE_STAFF_CASHIER:"cashierRoleId",ROLE_STAFF_WAREHOUSE:"warehouseRoleId",ROLE_STAFF_SHIPPER:"shipperRoleId"};for(const r of pm.response.json())if(names[r.name])pm.environment.set(names[r.name],r.roleId);}')
  group=path.split('/')[3] if len(path.split('/'))>3 else 'system';folders.setdefault(group,[]).append({'name':method.upper()+' '+path,'request':request,'event':[{'listen':'test','script':{'type':'text/javascript','exec':script}}]})
  params=', '.join(p['name']+(' (bắt buộc)' if p.get('required') else ' (tùy chọn)') for p in op.get('parameters',[]) if p['in']!='path') or 'Không có query/header riêng.'
  section=f'#### BE-{count:02d}. `{method.upper()} {path}`\n\n{description}\n\n- Quyền: `{op.get("x-authorization", "JWT của người dùng" if op.get("security") else "Public")}`. Thành công: **{status}**.\n- Tham số: {params}. Biến đường dẫn: '+(', '.join('`'+p['name']+'` → `'+variable(path,p['name'])+'`' for p in op.get('parameters',[]) if p['in']=='path') or 'không có')+'.\n'
  if body is not None:section+='\nBody → raw → JSON (đã có sẵn trong collection):\n\n```json\n'+json.dumps(body,ensure_ascii=False,indent=2)+'\n```\n'
  if request.get('body',{}).get('mode')=='formdata':section+='\nBody → form-data: `file` chọn **File** và ảnh PNG/JPEG; `role` là Text (`person`, `garment`, `reference`); `consent` là Text `true`. Để Postman tự đặt Content-Type/boundary.\n'
  sections.append(section)
# Helper login nhân sự không làm ghi đè token khách.
helpers=[]
for role,var in [('admin','adminToken'),('cashier','cashierToken'),('warehouse','warehouseToken'),('shipper','shipperToken')]:
 helpers.append({'name':'Login '+role,'request':{'method':'POST','url':'{{backendBaseUrl}}/api/v1/auth/login','auth':{'type':'noauth'},'header':[{'key':'Content-Type','value':'application/json'}],'body':{'mode':'raw','raw':json.dumps({'username':'{{'+role+'Username}}','password':'{{'+role+'Password}}'})}},'event':[{'listen':'test','script':{'type':'text/javascript','exec':['pm.test("HTTP 200",()=>pm.response.to.have.status(200));',f'if(pm.response.code===200) pm.environment.set("{var}",pm.response.json().accessToken);']}}]})
collection={'info':{'name':'YoFaSoShop Backend v1','schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json','description':'Chọn từng request theo hướng dẫn, không Runner toàn bộ vì có thao tác ghi/hủy/khóa user.'},'item':[{'name':'00 - Login nhân sự','item':helpers}]+[{'name':k,'item':v} for k,v in folders.items()]}
post=be/'postman';post.mkdir(exist_ok=True);(post/'YoFaSoShop_Backend.postman_collection.json').write_text(json.dumps(collection,ensure_ascii=False,indent=2),encoding='utf-8')
env={'provinceCode':'48','wardCode':'20242','oldProvinceCode':'01','oldDistrictCode':'001','oldWardCode':'00004','imageId':'HA00000003','cashierRoleId':'','warehouseRoleId':'','shipperRoleId':'','adminRoleId':'','customerRoleId':'','districtCode':'001','q':'ba','backendBaseUrl':'http://localhost:8080','customerUsername':'customer01','customerPassword':'','newPassword':'','idempotencyKey':'CHANGE_FOR_EACH_NEW_ACTION'}
env.update(resetEmail='',resetRequestId='',resetOtp='',newPassword='',seedProductId='SP00000001',seedVariantId='BT00000001',seedCategoryId='DM000001',adminUsername='admin_yofaso',customerUsername='maianh_tran',cashierUsername='cashier_lanphuong',warehouseUsername='wh_manhhung',shipperUsername='ship_quocbao')
for v in ['accessToken','refreshToken','adminToken','cashierToken','warehouseToken','shipperToken','userId','targetUserId','categoryId','productId','variantId','bottomVariantId','addressId','orderId','orderLineId','shiftId','personAssetId','garmentAssetId','assetId','analysisId','sessionId','recommendationId','shipperId','notificationId','reviewId','promotionId']+ [r+k for r in ['admin','cashier','warehouse','shipper'] for k in ['Username','Password']]:env.setdefault(v,'')
(post/'YoFaSoShop_Backend.local.postman_environment.json').write_text(json.dumps({'name':'YoFaSoShop Backend local','values':[{'key':k,'value':v,'enabled':True,'type':'secret' if 'Token' in k or 'Password' in k or k=='resetOtp' else 'default'} for k,v in env.items()]},ensure_ascii=False,indent=2),encoding='utf-8')
(be/'contracts/endpoint-reference.md').write_text('# Danh mục Backend sinh từ route và DTO\n\n'+ '\n'.join(sections),encoding='utf-8')
# TypeScript request DTO nhẹ, không giả vờ response map là type đã chốt.
def ts(s):
 if '$ref' in s:return s['$ref'].split('/')[-1]
 if s.get('type')=='array':return 'Array<'+ts(s.get('items',{}))+'>'
 return {'integer':'number','number':'number','boolean':'boolean','string':'string','object':'Record<string, unknown>'}.get(s.get('type'),'unknown')
types=[]
for name,s in schemas.items():types.append('export interface '+name+' {\n'+'\n'.join(f'  {k}{"" if k in s.get("required",[]) else "?"}: {ts(v)};' for k,v in s.get('properties',{}).items())+'\n}')
(be/'contracts/requests.d.ts').write_text('// Generated from OpenAPI. Response maps stay Record<string, unknown>.\n'+'\n\n'.join(types),encoding='utf-8')
print(f'Validated OpenAPI 3.1: {count} operations; exported Postman {count}+4 login helpers, DTO types and endpoint reference.')

# Dong bo phu luc trong cam nang khi phan B da duoc tao.
guide=be.parent/'YoFaSoShop_Docs/extra-info/apiTestingGuide.md'
text=guide.read_text(encoding='utf-8')
begin='<!-- BACKEND_REFERENCE_BEGIN -->';end='<!-- BACKEND_REFERENCE_END -->'
if begin in text and end in text:
 before=text.split(begin,1)[0];after=text.split(end,1)[1]
 guide.write_text(before+begin+'\n\n'+'\n'.join(sections)+'\n'+end+after,encoding='utf-8')
