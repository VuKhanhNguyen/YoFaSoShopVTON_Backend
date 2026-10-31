package com.yofaso.shop.common;

import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Giữ code cho request/SQL, thêm field Label tiếng Việt cho UI, không đổi contract cũ. */
@RestControllerAdvice
public class VietnameseLabels implements ResponseBodyAdvice<Object> {
  public static final Map<String,String> LABELS;
  static {
    var labels=new LinkedHashMap<String,String>();
    String[][] pairs={
      {"OM_SAT","Ôm sát"},{"SUONG_VUA","Suông vừa"},{"RONG_OVERSIZE","Rộng oversize"},
      {"CAP_CAO","Cạp cao"},{"CAP_VUA","Cạp vừa"},{"CAP_TRE","Cạp trễ"},{"KHONG_AP_DUNG","Không áp dụng"},
      {"NGAN_TREN_GOI","Ngắn trên gối"},{"LUNG_NGANG_GOI","Lửng ngang gối"},{"MIDI_QUA_GOI","Midi qua gối"},{"DAI_CHAM_GOT","Dài chạm gót"},
      {"AO_CROPTOP","Áo croptop"},{"AO_TIEU_CHUAN","Áo tiêu chuẩn"},{"AO_DANG_DAI","Áo dáng dài"},
      {"VAI_TRON","Vải trơn"},{"HOA_VAN_NHO","Hoa văn nhỏ"},{"HOA_TO_BAN","Hoa to bản"},{"KE_SOC_TAM","Kẻ sọc tăm"},{"KE_CARO","Kẻ ca rô"},{"IN_HINH","In hình"},
      {"BAN_NHAP","Bản nháp"},{"DANG_XU_LY_AI","Đang xử lý AI"},{"DANG_KINH_DOANH","Đang kinh doanh"},{"NGUNG_KINH_DOANH","Ngừng kinh doanh"},
      {"DANG_BAN","Đang bán"},{"HET_HANG","Hết hàng"},{"NGUNG_BAN","Ngừng bán"},{"HOAT_DONG","Hoạt động"},{"KHOA_TAM_THOI","Khóa tạm thời"},
      {"upper_body","Áo"},{"lower_body","Quần / chân váy"},{"dress","Đầm liền"},
      {"CHO_XAC_NHAN","Chờ xác nhận"},{"DA_XAC_NHAN","Đã xác nhận"},{"DANG_CHUAN_BI_HANG","Đang chuẩn bị hàng"},{"CHO_GIAO_HANG","Chờ giao hàng"},
      {"DANG_GIAO_HANG","Đang giao hàng"},{"HOAN_TAT","Hoàn tất"},{"DA_HUY","Đã hủy"},{"GIAO_HANG_THAT_BAI","Giao hàng thất bại"},{"HOAN_VE_KHO","Hoàn về kho"},
      {"CHUA_THANH_TOAN","Chưa thanh toán"},{"DA_THANH_TOAN","Đã thanh toán"},{"DA_HOAN_TIEN","Đã hoàn tiền"},
      {"COD_TIEN_MAT_KHI_NHAN","COD - tiền mặt khi nhận"},{"VIETQR_CHUYEN_KHOAN_ONLINE","VietQR chuyển khoản online"},{"TIEN_MAT_TAI_QUAY","Tiền mặt tại quầy"},{"VIETQR_TAI_QUAY","VietQR tại quầy"},
      {"ONLINE_APP","Online"},{"TAI_QUAY_POS","Tại quầy POS"},{"CHO_XU_LY","Chờ xử lý"},{"DANG_RENDER_GPU","Đang tạo ảnh thử đồ"},{"THANH_CONG","Thành công"},{"THAT_BAI","Thất bại"},
      {"CHUA_XEM","Chưa xem"},{"DA_THU_THEM","Đã thử thêm"},{"DA_THEM_GIO_HANG","Đã thêm giỏ hàng"},{"DA_MUA","Đã mua"},
      {"DANG_MO","Đang mở ca"},{"DA_DONG_CHO_DUYET","Đã đóng ca, chờ duyệt"},{"DA_DUYET","Đã duyệt"},
      {"CHO_DUYET","Chờ duyệt"},{"DA_HIEN_THI","Đã hiển thị"},{"DA_AN_VI_PHAM","Đã ẩn do vi phạm"},
      {"DONG","Đồng"},{"BAC","Bạc"},{"VANG","Vàng"},{"KIM_CUONG","Kim cương"},
      {"RECOMMENDED","Nên phối"},{"ACCEPTABLE","Phù hợp"},{"NEEDS_ADJUSTMENT","Cần điều chỉnh"},{"NOT_RECOMMENDED","Không nên phối"}
    };
    for(var pair:pairs) labels.put(pair[0],pair[1]);
    String[][] other={
      {"DANG_DONG_GOI","Đang đóng gói"},{"DA_PHE_DUYET_BAN_GIAO","Đã phê duyệt bàn giao"},
      {"HIEN_THI","Hiển thị"},{"AN","Ẩn"},{"TAM_NGUNG","Tạm ngừng"},{"TAM_DUNG","Tạm dừng"},{"DANG_DIEN_RA","Đang diễn ra"},{"KET_THUC","Kết thúc"},
      {"NAM","Nam"},{"NU","Nữ"},{"KHAC","Khác"},{"FULL_BODY","Toàn thân"},{"HALF_BODY","Nửa thân"},{"THREE_QUARTER","Ba phần tư thân"},{"KHONG_HOP_LE","Không hợp lệ"},
      {"PHAN_TRAM","Phần trăm"},{"TIEN_MAT_TRUC_TIEP","Giảm tiền trực tiếp"},{"MIEN_PHI_VAN_CHUYEN","Miễn phí vận chuyển"},
      {"NHAP_KHO_BO_SUNG","Nhập kho bổ sung"},{"NHAP_KHO_HANG_HOAN","Nhập kho hàng hoàn"},{"XUAT_KHO_GIAO_HANG","Xuất kho giao hàng"},{"KIEM_KE_CAN_KHO","Kiểm kê cân kho"},{"GIU_HANG_ONLINE","Giữ hàng online"},{"BAN_TAI_QUAY","Bán tại quầy"},{"TRA_HANG","Trả hàng"},
      {"CANH_BAO_KHO","Cảnh báo kho"},{"DON_HANG","Đơn hàng"},{"HE_THONG","Hệ thống"},{"KHUYEN_MAI","Khuyến mãi"},{"THU_DO_VTON","Thử đồ ảo"},
      {"XS","Cỡ XS"},{"S","Cỡ S"},{"M","Cỡ M"},{"L","Cỡ L"},{"XL","Cỡ XL"},{"XXL","Cỡ XXL"},{"FREESIZE","Freesize"},
      {"QUEUED","Đang chờ"},{"PROCESSING","Đang xử lý"},{"COMPLETED","Hoàn tất"},{"FAILED","Thất bại"},{"CANCELLED","Đã hủy"},{"PENDING_SUBMIT","Chờ gửi AI"},{"RECONCILING","Đang đối soát"},{"SYNC_FAILED","Đồng bộ thất bại"},
      {"short_sleeved_shirt","Áo tay ngắn"},{"long_sleeved_shirt","Áo tay dài"},{"shirt","Áo"},{"short_sleeved_outwear","Áo khoác tay ngắn"},{"long_sleeved_outwear","Áo khoác tay dài"},
      {"vest","Áo không tay"},{"sling","Áo hai dây"},{"shorts","Quần ngắn"},{"trousers","Quần dài"},{"skirt","Chân váy"},
      {"short_sleeved_dress","Đầm tay ngắn"},{"long_sleeved_dress","Đầm tay dài"},{"vest_dress","Đầm không tay"},{"sling_dress","Đầm hai dây"}
    };
    for(var pair:other) labels.put(pair[0],pair[1]);
    LABELS=Collections.unmodifiableMap(labels);
  }
  public static List<Map<String,String>> options(String... codes) {
    return Arrays.stream(codes).map(code -> Map.of("value",code,"label",LABELS.getOrDefault(code,code))).toList();
  }
  private Object decorate(Object value) {
    if(value instanceof List<?> list) return list.stream().map(this::decorate).toList();
    if(!(value instanceof Map<?,?> map)) return value;
    var result=new LinkedHashMap<String,Object>();
    map.forEach((key,item) -> result.put(key.toString(),decorate(item)));
    for(String key:List.of("fit","waist","length","pattern","vtonCategory","subtype","status","paymentStatus","paymentMethod","channel","membership","tier","gender","frame","size","type")) {
      Object code=map.get(key);
      if(code instanceof String && LABELS.containsKey(code)) result.put(key+"Label",LABELS.get(code));
    }
    return result;
  }
  @Override public boolean supports(MethodParameter parameter,Class<? extends HttpMessageConverter<?>> converter) {return true;}
  @Override public Object beforeBodyWrite(Object body,MethodParameter parameter,MediaType type,
      Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response) {
    return MediaType.APPLICATION_JSON.isCompatibleWith(type) ? decorate(body) : body;
  }
}
