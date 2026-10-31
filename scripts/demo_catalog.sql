-- DU LIEU GIA LAP CHO POSTMAN, KHONG PHAI KET QUA MODEL.
-- Chi chay thu cong tren database DEV moi, sau khi import db/schema.sql (19 bang).
-- Chon schema dev trong Workbench va xem SELECT DATABASE() truoc khi chay.
-- Khong tu chay khi app khoi dong, khong DROP/UPDATE du lieu da co.
SELECT DATABASE() AS KiemTraDatabaseDevTruocKhiChay;
START TRANSACTION;
INSERT IGNORE INTO DanhMuc(MaDanhMuc,TenDanhMuc,MoTa) VALUES
('DMDEMO01','DEMO - Ao','Du lieu tong hop cho test nghiep vu'),
('DMDEMO02','DEMO - Quan','Du lieu tong hop cho test nghiep vu');
INSERT IGNORE INTO SanPham(MaSanPham,MaDanhMuc,TenSanPham,MoTaChiTiet,KieuDang,LoaiCapQuan,ChieuDaiTrangPhuc,LoaiHoaTiet,PhanLoaiVTON,TieuLoaiTrangPhuc,TrangThaiSanPham) VALUES
('SPDEMO01','DMDEMO01','DEMO - Ao trang test','GIA LAP: chi test catalog/cart/COD/POS/scoring, khong phai san pham duoc AI nghiem thu.','SUONG_VUA','KHONG_AP_DUNG','AO_CROPTOP','VAI_TRON','upper_body','short_sleeved_shirt','DANG_KINH_DOANH'),
('SPDEMO02','DMDEMO02','DEMO - Quan den test','GIA LAP: khong co anh/mask de VTON.','SUONG_VUA','CAP_CAO','DAI_CHAM_GOT','VAI_TRON','lower_body','trousers','DANG_KINH_DOANH');
INSERT IGNORE INTO BienTheSanPham(MaBienThe,MaSanPham,MaQuanLyHangHoa,KichCo,TenMau,MaMauSac,GiaNiemYet,SoLuongTonKho) VALUES
('BTDEMO01','SPDEMO01','SKU-DEMO-WHITE-M','M','Trang','#FFFFFF',100000,10),
('BTDEMO02','SPDEMO02','SKU-DEMO-BLACK-M','M','Den','#1A1A1A',200000,10);
COMMIT;
