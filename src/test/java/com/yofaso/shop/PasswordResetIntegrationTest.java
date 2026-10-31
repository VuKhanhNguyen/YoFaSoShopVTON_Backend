package com.yofaso.shop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.yofaso.shop.auth.*;
import com.yofaso.shop.common.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** HTTP + MySQL 19 bảng thật; mailbox Mockito, tuyệt đối không gửi SMTP ra ngoài. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class PasswordResetIntegrationTest {
  @LocalServerPort int port;
  @Autowired AuthService auth;
  @Autowired Db db;
  @Autowired JsonMapper json;
  @MockitoBean OtpMailSender mail;
  String owner,username,email,access,refresh;
  final HttpClient client=HttpClient.newHttpClient();
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
    var root=Files.createTempDirectory(Path.of("target"),"otp-test-");
    r.add("app.runtime-root",root::toString);r.add("app.storage-root",()->root.resolve("media").toString());
    r.add("app.rate-limit",()->100000);r.add("app.ai.key",()->"test-only-internal-key-at-least-32-characters");
  }
  @BeforeEach @SuppressWarnings("unchecked") void user() {
    String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
    username="otp"+suffix;email="mailbox+"+suffix+"@gmail.com";
    String phone="099"+String.format("%012d",Math.abs(UUID.randomUUID().getLeastSignificantBits()%1000000000000L));
    var tokens=auth.register(new AuthController.Register(username,"BeforeReset123",null,phone,email));
    owner=((Map<String,Object>)tokens.get("user")).get("userId").toString();
    access=tokens.get("accessToken").toString();refresh=tokens.get("refreshToken").toString();
  }
  Map<String,Object> call(String method,String path,String token,Object body,int status) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path));
    if(token!=null)request.header("Authorization","Bearer "+token);
    request.header("Content-Type","application/json").method(method,body==null ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    assertEquals(status,response.statusCode(),path+" "+response.body());
    return json.readValue(response.body(),Map.class);
  }
  String[] issue() throws Exception {
    var response=call("POST","/auth/forgot-password",null,Map.of("email",email),202);
    var capture=ArgumentCaptor.forClass(String.class);
    verify(mail,timeout(2000)).send(eq(email),capture.capture(),eq(10));
    String otp=capture.getValue();
    assertTrue(otp.matches("[0-9]{6}"));assertFalse(json.writeValueAsString(response).contains(otp));
    return new String[]{response.get("requestId").toString(),otp};
  }
  Map<String,Object> reset(String[] issued,String code) {return Map.of("requestId",issued[0],"email",email,"otp",code,"newPassword","AfterReset123");}
  @Test void resetConsumesOtpAndRevokesOldTokens() throws Exception {
    var issued=issue();
    String persisted=db.runtime().get("passwordreset",issued[0]).toString();
    assertFalse(persisted.contains(issued[1]));
    call("POST","/auth/reset-password",null,reset(issued,issued[1]),200);
    call("POST","/auth/reset-password",null,reset(issued,issued[1]),400);
    call("GET","/me",access,null,401);
    call("POST","/auth/refresh",null,Map.of("refreshToken",refresh),401);
    call("POST","/auth/login",null,Map.of("username",username,"password","AfterReset123"),200);
    assertEquals(19,db.count("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'"));
  }
  @Test @SuppressWarnings("unchecked") void registrationNeedsOnlyTheFourRequestedFields() throws Exception {
    String name="four"+UUID.randomUUID().toString().replace("-","").substring(0,12);
    var response=call("POST","/auth/register",null,Map.of("username",name,"password","FourFields123", "phone","087"+System.nanoTime()%1000000000000L,"email",name+"+1@gmail.com"),201);
    var user=(Map<String,Object>)response.get("user");
    assertEquals(name,user.get("fullName"));
    assertEquals(List.of("ROLE_CUSTOMER"),user.get("roles"));
  }
  @Test void fiveWrongCodesInvalidateChallenge() throws Exception {
    var issued=issue();String wrong=issued[1].equals("000000") ? "111111" : "000000";
    for(int i=0;i<5;i++)call("POST","/auth/reset-password",null,reset(issued,wrong),400);
    call("POST","/auth/reset-password",null,reset(issued,issued[1]),400);
  }
  @Test void changingEmailNeedsPasswordAndInvalidatesOldOtp() throws Exception {
    var issued=issue();
    var body=Map.of("fullName",username,"email","new+"+username+"@gmail.com");
    call("PATCH","/me",access,body,400);
    var confirmed=new HashMap<String,Object>(body);confirmed.put("currentPassword","BeforeReset123");
    call("PATCH","/me",access,confirmed,200);
    call("POST","/auth/reset-password",null,reset(issued,issued[1]),400);
  }
  @Test void expiredOtpCannotReset() throws Exception {
    var issued=issue();db.runtime().patch("passwordreset",issued[0],"expires",0L);
    call("POST","/auth/reset-password",null,reset(issued,issued[1]),400);
  }
  @Test void duplicateAndUnknownEmailsDoNotSendOrRevealAccount() throws Exception {
    var unknown=call("POST","/auth/forgot-password",null,Map.of("email","unknown+"+username+"@gmail.com"),202);
    db.exec("UPDATE NguoiDung SET Email=? WHERE MaNguoiDung=?",email,owner);
    String duplicate=Db.id("ND");
    db.exec("INSERT INTO NguoiDung(MaNguoiDung,TenDangNhap,MatKhau,HoVaTen,SoDienThoai,Email) SELECT ?,?,MatKhau,'Duplicate',?,Email FROM NguoiDung WHERE MaNguoiDung=?",duplicate,"duplicate_"+username,"088"+System.nanoTime()%1000000000000L,owner);
    var ambiguous=call("POST","/auth/forgot-password",null,Map.of("email",email),202);
    assertEquals(unknown.get("message"),ambiguous.get("message"));
    verify(mail,never()).send(anyString(),anyString(),anyInt());
  }
  @Test void repeatedUnknownAddressAlsoHasCooldown() throws Exception {
    call("POST","/auth/forgot-password",null,Map.of("email","unknown+"+username+"@gmail.com"),202);
    call("POST","/auth/forgot-password",null,Map.of("email","UNKNOWN+"+username+"@gmail.com"),429);
  }
  @Test void mailDisabledFailsExplicitly() throws Exception {
    doThrow(new Problem(503,"MAIL_NOT_CONFIGURED","Chưa cấu hình SMTP.")).when(mail).requireConfigured();
    call("POST","/auth/forgot-password",null,Map.of("email",email),503);
  }
  @Test void concurrentResetOnlyOneSucceeds() throws Exception {
    var issued=issue();
    try(var pool=Executors.newFixedThreadPool(2)) {
      var futures=new ArrayList<Future<Integer>>();
      for(int i=0;i<2;i++)futures.add(pool.submit(()->{
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/reset-password")).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(reset(issued,issued[1])))).build();
        return client.send(req,HttpResponse.BodyHandlers.ofString()).statusCode();
      }));
      var statuses=new ArrayList<Integer>();for(var future:futures)statuses.add(future.get());
      Collections.sort(statuses);assertEquals(List.of(200,400),statuses);
    }
  }
}
