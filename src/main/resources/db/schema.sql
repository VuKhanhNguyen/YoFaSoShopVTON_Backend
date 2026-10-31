-- Schema tu ERD_optimize.md; khong DROP/CREATE DATABASE, khong tai khoan mau.
CREATE TABLE VaiTro (
    MaVaiTro VARCHAR(10) NOT NULL COMMENT 'Mã định danh vai trò (VD: VT001, VT002)',
    TenVaiTro VARCHAR(50) NOT NULL COMMENT 'Tên mã vai trò Spring Security (VD: ROLE_CUSTOMER, ROLE_STAFF_CASHIER)',
    TenHienThi VARCHAR(100) NOT NULL COMMENT 'Tên hiển thị tiếng Việt (VD: Khách hàng, Thu ngân, Thủ kho, Shipper, Quản trị)',
    MoTa VARCHAR(255) NULL COMMENT 'Mô tả chi tiết thẩm quyền của vai trò',
    NgayTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm khởi tạo vai trò',
    CONSTRAINT PK_VaiTro PRIMARY KEY (MaVaiTro),
    CONSTRAINT UQ_VaiTro_TenVaiTro UNIQUE (TenVaiTro)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng danh mục vai trò phân quyền hệ thống (RBAC động)';

-- Bảng 2: NguoiDung (Tài khoản người dùng toàn hệ thống & tích hợp hồ sơ thành viên)
CREATE TABLE NguoiDung (
    MaNguoiDung VARCHAR(20) NOT NULL COMMENT 'Mã người dùng (VD: ND00000001)',
    TenDangNhap VARCHAR(50) NOT NULL COMMENT 'Tên đăng nhập hoặc SĐT xác thực',
    MatKhau VARCHAR(255) NOT NULL COMMENT 'Mật khẩu mã hóa BCrypt',
    HoVaTen VARCHAR(100) NOT NULL COMMENT 'Họ và tên đầy đủ',
    SoDienThoai VARCHAR(15) NOT NULL COMMENT 'Số điện thoại liên hệ chính thức',
    Email VARCHAR(100) NULL COMMENT 'Địa chỉ thư điện tử',
    AnhDaiDien VARCHAR(255) NULL COMMENT 'Đường dẫn ảnh đại diện',
    DiemTichLuy INT NOT NULL DEFAULT 0 COMMENT 'Điểm thưởng thành viên mua sắm',
    HangThanhVien ENUM('DONG', 'BAC', 'VANG', 'KIM_CUONG') NOT NULL DEFAULT 'DONG' COMMENT 'Hạng thẻ ưu đãi',
    NgaySinh DATE NULL COMMENT 'Ngày sinh nhật để áp mã giảm giá tại quầy POS',
    GioiTinh ENUM('NAM', 'NU', 'KHAC') NOT NULL DEFAULT 'NU' COMMENT 'Giới tính',
    TrangThaiHoatDong ENUM('HOAT_DONG', 'KHOA_TAM_THOI') NOT NULL DEFAULT 'HOAT_DONG' COMMENT 'Trạng thái tài khoản',
    NgayTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm đăng ký',
    NgayCapNhat DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Thời điểm cập nhật gần nhất',
    CONSTRAINT PK_NguoiDung PRIMARY KEY (MaNguoiDung),
    CONSTRAINT UQ_NguoiDung_TenDangNhap UNIQUE (TenDangNhap),
    CONSTRAINT UQ_NguoiDung_SoDienThoai UNIQUE (SoDienThoai),
    CONSTRAINT CHK_NguoiDung_Diem CHECK (DiemTichLuy >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng tài khoản người dùng, nhân viên và tích điểm thành viên';

-- Bảng 3: NguoiDung_VaiTro (Liên kết phân quyền n-n giữa Người dùng và Vai trò)
CREATE TABLE NguoiDung_VaiTro (
    MaNguoiDung VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung',
    MaVaiTro VARCHAR(10) NOT NULL COMMENT 'Khóa ngoại tham chiếu VaiTro',
    NgayGan DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm gán vai trò',
    CONSTRAINT PK_NguoiDung_VaiTro PRIMARY KEY (MaNguoiDung, MaVaiTro),
    CONSTRAINT FK_NDVT_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT FK_NDVT_VaiTro FOREIGN KEY (MaVaiTro) REFERENCES VaiTro(MaVaiTro) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng phân quyền người dùng theo vai trò động';

-- Bảng 4: SoDiaChi (Sổ địa chỉ nhận hàng giao tận nhà)
CREATE TABLE SoDiaChi (
    MaDiaChi VARCHAR(20) NOT NULL COMMENT 'Mã địa chỉ (VD: DC00000001)',
    MaNguoiDung VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung',
    TenNguoiNhan VARCHAR(100) NOT NULL COMMENT 'Họ tên người nhận kiện hàng',
    SoDienThoaiNhan VARCHAR(15) NOT NULL COMMENT 'Số điện thoại liên hệ giao hàng',
    SoNhaTenDuong VARCHAR(255) NOT NULL COMMENT 'Số nhà, ngõ, tên đường',
    PhuongXa VARCHAR(100) NOT NULL COMMENT 'Phường / Xã',
    TinhThanhPho VARCHAR(100) NOT NULL COMMENT 'Tỉnh / Thành phố',
    LaMacDinh TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1: Địa chỉ mặc định, 0: Phụ',
    CONSTRAINT PK_SoDiaChi PRIMARY KEY (MaDiaChi),
    CONSTRAINT FK_SoDiaChi_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng sổ địa chỉ nhận hàng của khách hàng';

-- -------------------------------------------------------------------------------------
-- CỤM 2: DANH MỤC, SẢN PHẨM & KHO HÀNG HỢP NHẤT (4 BẢNG)
-- -------------------------------------------------------------------------------------

-- Bảng 5: DanhMuc (Cây phân cấp danh mục thời trang)
CREATE TABLE DanhMuc (
    MaDanhMuc VARCHAR(20) NOT NULL COMMENT 'Mã danh mục (VD: DM000001)',
    TenDanhMuc VARCHAR(100) NOT NULL COMMENT 'Tên danh mục (Áo sơ mi, Quần tây, Chân váy, Đầm liền)',
    MaDanhMucCha VARCHAR(20) NULL COMMENT 'Mã danh mục cha (đệ quy cấp 2)',
    MoTa VARCHAR(255) NULL COMMENT 'Mô tả tóm tắt phân loại',
    ThuTuHienThi INT NOT NULL DEFAULT 0 COMMENT 'Thứ tự hiển thị trên Menu/App',
    TrangThai ENUM('HIEN_THI', 'AN') NOT NULL DEFAULT 'HIEN_THI' COMMENT 'Trạng thái hiển thị',
    CONSTRAINT PK_DanhMuc PRIMARY KEY (MaDanhMuc),
    CONSTRAINT FK_DanhMuc_DanhMucCha FOREIGN KEY (MaDanhMucCha) REFERENCES DanhMuc(MaDanhMuc) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng danh mục thời trang';

-- Bảng 6: SanPham (Hợp nhất Thông tin sản phẩm, Thuộc tính thẩm mỹ & Metadata bóc tách AI)
CREATE TABLE SanPham (
    MaSanPham VARCHAR(20) NOT NULL COMMENT 'Mã sản phẩm (VD: SP00000001)',
    MaDanhMuc VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu DanhMuc',
    TenSanPham VARCHAR(150) NOT NULL COMMENT 'Tên thương mại của sản phẩm',
    MoTaChiTiet TEXT NULL COMMENT 'Mô tả chi tiết chất liệu, cách phối đồ',
    ChatLieu VARCHAR(100) NULL COMMENT 'Chất liệu vải (Cotton, Lụa, Denim, Kaki)',
    KieuDang ENUM('OM_SAT', 'SUONG_VUA', 'RONG_OVERSIZE') NOT NULL DEFAULT 'SUONG_VUA' COMMENT 'Form dáng phục vụ tính tỉ lệ',
    LoaiCapQuan ENUM('CAP_CAO', 'CAP_VUA', 'CAP_TRE', 'KHONG_AP_DUNG') NOT NULL DEFAULT 'KHONG_AP_DUNG' COMMENT 'Độ cao cạp quần/váy phục vụ hack dáng',
    ChieuDaiTrangPhuc ENUM('NGAN_TREN_GOI', 'LUNG_NGANG_GOI', 'MIDI_QUA_GOI', 'DAI_CHAM_GOT', 'AO_CROPTOP', 'AO_TIEU_CHUAN', 'AO_DANG_DAI') NOT NULL DEFAULT 'AO_TIEU_CHUAN' COMMENT 'Chiều dài phục vụ tỉ lệ 1/3-2/3',
    LoaiHoaTiet ENUM('VAI_TRON', 'HOA_VAN_NHO', 'HOA_TO_BAN', 'KE_SOC_TAM', 'KE_CARO', 'IN_HINH') NOT NULL DEFAULT 'VAI_TRON' COMMENT 'Họa tiết vải phục vụ tính điểm hoa văn',
  
    -- Siêu dữ liệu AI bóc tách tự động (Triệt tiêu bảng SieuDuLieuAI riêng):
    PhanLoaiVTON ENUM('upper_body', 'lower_body', 'dress') NOT NULL DEFAULT 'upper_body' COMMENT 'Vùng cơ thể điều phối router mô hình VTON',
    TieuLoaiTrangPhuc VARCHAR(50) NOT NULL DEFAULT 'short_sleeved_shirt' COMMENT 'Subtype nhận diện từ DeepFashion2',
    BangMauTrichXuat JSON NULL COMMENT 'Danh sách mã màu HEX và tỉ lệ trích xuất qua K-Means',
    DuongDanMatNaVungThayDo VARCHAR(255) NULL COMMENT 'Đường dẫn file mặt nạ mask lưu trữ trên storage',
  
    TrangThaiSanPham ENUM('BAN_NHAP', 'DANG_XU_LY_AI', 'DANG_KINH_DOANH', 'TAM_NGUNG') NOT NULL DEFAULT 'BAN_NHAP' COMMENT 'Vòng đời sản phẩm',
    NgayTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm đăng sản phẩm',
    NgayCapNhat DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Thời điểm cập nhật',
    CONSTRAINT PK_SanPham PRIMARY KEY (MaSanPham),
    CONSTRAINT FK_SanPham_DanhMuc FOREIGN KEY (MaDanhMuc) REFERENCES DanhMuc(MaDanhMuc) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng thông tin sản phẩm và siêu dữ liệu bóc tách AI';

-- Bảng 7: BienTheSanPham (Biến thể SKU & Tồn kho khả dụng Single Pool dùng chung Online/POS)
CREATE TABLE BienTheSanPham (
    MaBienThe VARCHAR(20) NOT NULL COMMENT 'Mã biến thể SKU (VD: BT00000001)',
    MaSanPham VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu SanPham',
    MaQuanLyHangHoa VARCHAR(50) NOT NULL COMMENT 'Mã SKU quét Barcode (VD: SKU-SM01-TRANG-M)',
    KichCo ENUM('XS', 'S', 'M', 'L', 'XL', 'XXL', 'FREESIZE') NOT NULL DEFAULT 'M' COMMENT 'Kích cỡ size',
    TenMau VARCHAR(100) NOT NULL COMMENT 'Tên màu sắc hiển thị (Trắng, Đen, Be phối Xanh mint & Xám)',
    MaMauSac VARCHAR(100) NOT NULL COMMENT 'Mã màu HEX đơn sắc (#FFFFFF) hoặc chuỗi mã màu phối đa sắc (#BFBEC2,#A5A5A9,#8A898E) để tính bánh xe màu và lọc ảnh',
    GiaNiemYet DECIMAL(12, 2) NOT NULL COMMENT 'Giá niêm yết bán lẻ',
    GiaKhuyenMai DECIMAL(12, 2) NULL COMMENT 'Giá khuyến mãi áp dụng (nếu có)',
    SoLuongTonKho INT NOT NULL DEFAULT 0 COMMENT 'Số lượng tồn kho duy nhất cho cả Online & POS (Single Pool)',
    ViTriGiaKe VARCHAR(50) NULL COMMENT 'Vị trí giá kệ trong kho/cửa hàng',
    TrangThai ENUM('DANG_BAN', 'HET_HANG', 'NGUNG_BAN') NOT NULL DEFAULT 'DANG_BAN' COMMENT 'Trạng thái SKU',
    CONSTRAINT PK_BienTheSanPham PRIMARY KEY (MaBienThe),
    CONSTRAINT UQ_BienTheSanPham_MaQuanLyHangHoa UNIQUE (MaQuanLyHangHoa),
    CONSTRAINT UQ_BienThe_SanPham_KichCo_Mau UNIQUE (MaSanPham, KichCo, TenMau),
    CONSTRAINT FK_BienTheSanPham_SanPham FOREIGN KEY (MaSanPham) REFERENCES SanPham(MaSanPham) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT CHK_BienThe_Gia CHECK (GiaNiemYet >= 0),
    CONSTRAINT CHK_BienThe_TonKho CHECK (SoLuongTonKho >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng biến thể kích cỡ, màu sắc và tồn kho hợp nhất';

-- Bảng 8: HinhAnhSanPham (Bộ sưu tập ảnh sản phẩm và ảnh bóc nền sạch phông)
CREATE TABLE HinhAnhSanPham (
    MaHinhAnh VARCHAR(20) NOT NULL COMMENT 'Mã hình ảnh (VD: HA00000001)',
    MaSanPham VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu SanPham',
    DuongDanAnhGoc VARCHAR(255) NOT NULL COMMENT 'Ảnh chụp trên móc treo hoặc ma-nơ-canh',
    DuongDanAnhBocNen VARCHAR(255) NULL COMMENT 'Ảnh PNG đã bóc nền trong suốt phục vụ VTON',
    MaMauSac VARCHAR(100) NULL COMMENT 'Mã màu HEX đơn sắc (#FFFFFF) hoặc chuỗi màu phối (#BFBEC2,#A5A5A9,#8A898E) gắn với ảnh',
    LaAnhDaiDien TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1: Ảnh đại diện chính, 0: Ảnh phụ',
    ThuTuHienThi INT NOT NULL DEFAULT 0 COMMENT 'Thứ tự sắp xếp trình chiếu',
    CONSTRAINT PK_HinhAnhSanPham PRIMARY KEY (MaHinhAnh),
    CONSTRAINT FK_HinhAnhSanPham_SanPham FOREIGN KEY (MaSanPham) REFERENCES SanPham(MaSanPham) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng kho hình ảnh sản phẩm phục vụ hiển thị và thử đồ ảo';

-- -------------------------------------------------------------------------------------
-- CỤM 3: THỬ ĐỒ ẢO AI, ĐÁNH GIÁ THẨM MỸ & GỢI Ý PHỐI ĐỒ (2 BẢNG)
-- -------------------------------------------------------------------------------------

-- Bảng 9: PhienThuDoAo (Vòng đời phiên VTON & Điểm thẩm mỹ 3 tiêu chí khoa học)
CREATE TABLE PhienThuDoAo (
    MaPhienThuDo VARCHAR(30) NOT NULL COMMENT 'Mã phiên thử đồ duy nhất (VD: TD00000001) phục vụ quét QR tại quầy O2O',
    MaNguoiDung VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung (NULL nếu khách vãng lai thử tại quầy)',
    MaBienThe VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu BienTheSanPham được thử',
    LoaiKhungAnhNguoi ENUM('FULL_BODY', 'HALF_BODY', 'THREE_QUARTER', 'KHONG_HOP_LE') NOT NULL COMMENT 'Khung ảnh người từ YOLO Pose',
    DuongDanAnhNguoiDung VARCHAR(255) NOT NULL COMMENT 'Đường dẫn ảnh chân dung gốc của khách',
    DuongDanAnhKetQuaVTON VARCHAR(255) NULL COMMENT 'Đường dẫn ảnh kết quả sau khi qua OOTDiffusion',
    TrangThaiPhien ENUM('CHO_XU_LY', 'DANG_RENDER_GPU', 'THANH_CONG', 'THAT_BAI') NOT NULL DEFAULT 'CHO_XU_LY' COMMENT 'Tiến trình AI',
    ThoiGianXuLy DECIMAL(6, 2) NULL COMMENT 'Thời gian thực thi của GPU Worker (giây)',
    ThongBaoLoi VARCHAR(255) NULL COMMENT 'Thông báo lỗi khi vi phạm Gate kiểm định',
  
    -- Các trường chấm điểm thẩm mỹ tích hợp trực tiếp (Triệt tiêu bảng DiemThamMy riêng):
    DiemTongThe INT NULL COMMENT 'Điểm thẩm mỹ tổng hợp (0 - 100 điểm)',
    DiemTiLeVocDang INT NULL COMMENT 'Điểm cân đối vóc dáng 1/3-2/3 (40%)',
    DiemHoaHopMauSac INT NULL COMMENT 'Điểm hòa hợp bánh xe màu sắc (40%)',
    DiemCanBangHoaTiet INT NULL COMMENT 'Điểm cân bằng hoa văn bề mặt (20%)',
    DiemTruPhat INT NOT NULL DEFAULT 0 COMMENT 'Điểm phạt vi phạm lỗi thẩm mỹ',
    QuyTacTiLeApDung VARCHAR(50) NULL COMMENT 'Tên quy tắc áp dụng (1/3-2/3, 2/3-1/3, Lỗi 1/1)',
    XepHangThamMy ENUM('RECOMMENDED', 'ACCEPTABLE', 'NEEDS_ADJUSTMENT', 'NOT_RECOMMENDED') NULL COMMENT 'Xếp loại hiển thị UI',
    LoiNhanXet TEXT NULL COMMENT 'Lời nhận xét ấm áp, thanh lịch của Stylist',
  
    ThoiGianKhoiTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm bấm thử đồ',
    ThoiGianHoanTat DATETIME NULL COMMENT 'Thời điểm hoàn thành phiên',
    CONSTRAINT PK_PhienThuDoAo PRIMARY KEY (MaPhienThuDo),
    CONSTRAINT FK_PhienThuDoAo_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT FK_PhienThuDoAo_BienThe FOREIGN KEY (MaBienThe) REFERENCES BienTheSanPham(MaBienThe) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT CHK_PhienThuDoAo_Diem CHECK (DiemTongThe IS NULL OR (DiemTongThe BETWEEN 0 AND 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng phiên thử đồ ảo và bảng điểm thẩm mỹ phân rã';

-- Bảng 10: GoiYPhoiDo (Gợi ý bán kèm thông minh quét trực tiếp từ kho shop - Hỗ trợ gợi ý cả set nhiều món)
CREATE TABLE GoiYPhoiDo (
    MaGoiY VARCHAR(30) NOT NULL COMMENT 'Mã gợi ý (VD: GY00000001)',
    MaPhienThuDo VARCHAR(30) NOT NULL COMMENT 'Khóa ngoại tham chiếu PhienThuDoAo',
    MaBienTheGoiY VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu BienTheSanPham được hệ thống đề xuất mua kèm',
    DiemSauKhiPhoiKem INT NOT NULL COMMENT 'Điểm thẩm mỹ dự kiến sau khi phối món đồ mới (luôn >= 90)',
    LyDoGoiY VARCHAR(255) NOT NULL COMMENT 'Giải thích vì sao món này giúp nâng điểm',
    TrangThaiTuongTac ENUM('CHUA_XEM', 'DA_THU_THEM', 'DA_THEM_GIO_HANG', 'DA_MUA') NOT NULL DEFAULT 'CHUA_XEM' COMMENT 'Đo lường tỉ lệ chuyển đổi Conversion Rate',
    CONSTRAINT PK_GoiYPhoiDo PRIMARY KEY (MaGoiY),
    CONSTRAINT FK_GoiYPhoiDo_PhienThuDo FOREIGN KEY (MaPhienThuDo) REFERENCES PhienThuDoAo(MaPhienThuDo) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT FK_GoiYPhoiDo_BienThe FOREIGN KEY (MaBienTheGoiY) REFERENCES BienTheSanPham(MaBienThe) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT CHK_GoiYPhoiDo_Diem CHECK (DiemSauKhiPhoiKem BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng sản phẩm gợi ý bán kèm thông minh (Smart Cross-selling)';

-- -------------------------------------------------------------------------------------
-- CỤM 4: KHUYẾN MÃI & GIỎ HÀNG ONLINE (3 BẢNG)
-- -------------------------------------------------------------------------------------

-- Bảng 11: KhuyenMai (Voucher giảm giá & Ưu đãi thành viên/sinh nhật)
CREATE TABLE KhuyenMai (
    MaKhuyenMai VARCHAR(20) NOT NULL COMMENT 'Mã khuyến mãi (VD: KM000001)',
    MaPhieuGiamGia VARCHAR(50) NOT NULL COMMENT 'Mã voucher in hoa khách nhập (VD: YOFASO10)',
    TenChuongTrinh VARCHAR(150) NOT NULL COMMENT 'Tên chương trình ưu đãi',
    LoaiGiamGia ENUM('PHAN_TRAM', 'TIEN_MAT_TRUC_TIEP', 'MIEN_PHI_VAN_CHUYEN') NOT NULL COMMENT 'Hình thức giảm giá',
    GiaTriGiam DECIMAL(12, 2) NOT NULL COMMENT 'Tỉ lệ % hoặc số tiền mặt giảm',
    GiaTriDonHangToiThieu DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Điều kiện giá trị đơn tối thiểu',
    GiamToiDa DECIMAL(12, 2) NULL COMMENT 'Số tiền giảm tối đa (với voucher %)',
    SoLuongPhatHanh INT NOT NULL DEFAULT 100 COMMENT 'Tổng số lượt phát hành',
    SoLuongDaDung INT NOT NULL DEFAULT 0 COMMENT 'Số lượt đã sử dụng',
    NgayBatDau DATETIME NOT NULL COMMENT 'Thời điểm kích hoạt',
    NgayKetThuc DATETIME NOT NULL COMMENT 'Thời điểm hết hạn',
    TrangThai ENUM('DANG_DIEN_RA', 'TAM_DUNG', 'KET_THUC') NOT NULL DEFAULT 'DANG_DIEN_RA' COMMENT 'Trạng thái voucher',
    CONSTRAINT PK_KhuyenMai PRIMARY KEY (MaKhuyenMai),
    CONSTRAINT UQ_KhuyenMai_MaPhieuGiamGia UNIQUE (MaPhieuGiamGia),
    CONSTRAINT CHK_KhuyenMai_SoLuong CHECK (SoLuongDaDung <= SoLuongPhatHanh)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng quản lý mã voucher khuyến mãi';

-- Bảng 12: GioHang (Giỏ hàng mua sắm online trên Mobile App)
CREATE TABLE GioHang (
    MaGioHang VARCHAR(20) NOT NULL COMMENT 'Mã giỏ hàng (VD: GH00000001)',
    MaNguoiDung VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu duy nhất 1-1 NguoiDung',
    NgayTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm tạo giỏ',
    NgayCapNhat DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Thời điểm sửa giỏ',
    CONSTRAINT PK_GioHang PRIMARY KEY (MaGioHang),
    CONSTRAINT UQ_GioHang_NguoiDung UNIQUE (MaNguoiDung),
    CONSTRAINT FK_GioHang_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng giỏ hàng mua sắm trực tuyến của khách';

-- Bảng 13: ChiTietGioHang (Các dòng sản phẩm nằm trong giỏ)
CREATE TABLE ChiTietGioHang (
    MaChiTietGioHang VARCHAR(20) NOT NULL COMMENT 'Mã dòng giỏ hàng (VD: CTGH000001)',
    MaGioHang VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu GioHang',
    MaBienThe VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu BienTheSanPham',
    SoLuong INT NOT NULL DEFAULT 1 COMMENT 'Số lượng sản phẩm thêm vào giỏ',
    NgayThem DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm thêm món hàng',
    CONSTRAINT PK_ChiTietGioHang PRIMARY KEY (MaChiTietGioHang),
    CONSTRAINT UQ_ChiTietGioHang UNIQUE (MaGioHang, MaBienThe),
    CONSTRAINT FK_ChiTietGioHang_GioHang FOREIGN KEY (MaGioHang) REFERENCES GioHang(MaGioHang) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT FK_ChiTietGioHang_BienThe FOREIGN KEY (MaBienThe) REFERENCES BienTheSanPham(MaBienThe) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT CHK_ChiTietGioHang_SoLuong CHECK (SoLuong > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng chi tiết các mặt hàng trong giỏ';

-- -------------------------------------------------------------------------------------
-- CỤM 5: ĐƠN HÀNG ĐA KÊNH & GIAO VẬN HỢP NHẤT (2 BẢNG)
-- -------------------------------------------------------------------------------------

-- Bảng 14: DonHang (Đơn hàng đa kênh hợp nhất: Online App & Bán lẻ POS tại quầy)
-- Lưu ý tối ưu: Loại bỏ các trường tính toán thừa TongTienHang và TongThanhToan (tính động bằng SUM chi tiết và trừ giảm giá)
CREATE TABLE DonHang (
    MaDonHang VARCHAR(20) NOT NULL COMMENT 'Mã đơn hàng duy nhất toàn hệ thống (VD: DH00000001)',
    MaNguoiDung VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung (NULL nếu khách vãng lai mua tại quầy)',
    KenhBanHang ENUM('ONLINE_APP', 'TAI_QUAY_POS') NOT NULL COMMENT 'Kênh bán hàng xuất phát đơn',
    MaThuNgan VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung là Thu ngân lập đơn (NULL nếu online)',
    MaNguoiGiaoHang VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung là Shipper nội bộ giao hàng',
    MaDiaChiGiaoHang VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu SoDiaChi (NULL nếu mua tại quầy)',
    TenNguoiNhan VARCHAR(100) NOT NULL COMMENT 'Họ tên người nhận hàng (hoặc tên khách tại quầy)',
    SoDienThoaiNhan VARCHAR(15) NOT NULL COMMENT 'Số điện thoại nhận hàng/tích điểm',
    DiaChiGiaoChiTiet VARCHAR(255) NULL COMMENT 'Địa chỉ giao hàng hoàn chỉnh',
  
    -- Chi phí & Giảm trừ thực tế:
    TienGiamGia DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Số tiền được giảm trừ từ voucher hoặc chiết khấu',
    PhiVanChuyen DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Cước phí vận chuyển giao hàng tận nhà',
  
    -- Thanh toán tích hợp (Triệt tiêu bảng GiaoDichThanhToan riêng):
    PhuongThucThanhToan ENUM('COD_TIEN_MAT_KHI_NHAN', 'VIETQR_CHUYEN_KHOAN_ONLINE', 'TIEN_MAT_TAI_QUAY', 'VIETQR_TAI_QUAY') NOT NULL COMMENT 'Phương thức thanh toán',
    TrangThaiThanhToan ENUM('CHUA_THANH_TOAN', 'DA_THANH_TOAN', 'DA_HOAN_TIEN') NOT NULL DEFAULT 'CHUA_THANH_TOAN' COMMENT 'Trạng thái thanh toán',
    MaGiaoDichNgoai VARCHAR(100) NULL COMMENT 'Mã tham chiếu chuyển khoản ngân hàng (VietQR FT code)',
    ThoiGianThanhToan DATETIME NULL COMMENT 'Thời điểm ghi nhận tiền về',
  
    -- Vòng đời đơn hàng & Điều phối shipper (Triệt tiêu bảng PhieuGiaoHang riêng):
    TrangThaiDonHang ENUM('CHO_XAC_NHAN', 'DANG_DONG_GOI', 'DANG_GIAO_HANG', 'HOAN_TAT', 'DA_HUY', 'TRA_HANG') NOT NULL DEFAULT 'CHO_XAC_NHAN' COMMENT 'Vòng đời xử lý đơn hàng',
    SoTienThuHo DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Số tiền mặt Shipper cần thu hộ',
    SoLanGiao INT NOT NULL DEFAULT 0 COMMENT 'Số lần shipper đã gọi giao hàng',
    GhiChuDonHang VARCHAR(255) NULL COMMENT 'Ghi chú của khách hàng hoặc shipper',
  
    MaKhuyenMai VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu KhuyenMai đã dùng',
    NgayTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm lập đơn hàng',
    NgayCapNhat DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Thời điểm cập nhật đơn',
    CONSTRAINT PK_DonHang PRIMARY KEY (MaDonHang),
    CONSTRAINT FK_DonHang_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT FK_DonHang_ThuNgan FOREIGN KEY (MaThuNgan) REFERENCES NguoiDung(MaNguoiDung) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT FK_DonHang_NguoiGiaoHang FOREIGN KEY (MaNguoiGiaoHang) REFERENCES NguoiDung(MaNguoiDung) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT FK_DonHang_DiaChi FOREIGN KEY (MaDiaChiGiaoHang) REFERENCES SoDiaChi(MaDiaChi) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT FK_DonHang_KhuyenMai FOREIGN KEY (MaKhuyenMai) REFERENCES KhuyenMai(MaKhuyenMai) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT CHK_DonHang_TienGiam CHECK (TienGiamGia >= 0),
    CONSTRAINT CHK_DonHang_PhiShip CHECK (PhiVanChuyen >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng đơn hàng đa kênh hợp nhất toàn hệ thống';

-- Bảng 15: ChiTietDonHang (Snapshot dòng sản phẩm hóa đơn - Loại bỏ trường tính toán thừa ThanhTien)
CREATE TABLE ChiTietDonHang (
    MaChiTietDonHang VARCHAR(20) NOT NULL COMMENT 'Mã dòng chi tiết đơn hàng (VD: CTDH000001)',
    MaDonHang VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu DonHang',
    MaBienThe VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu BienTheSanPham',
    TenSanPhamLucDatHang VARCHAR(150) NOT NULL COMMENT 'Snapshot tên sản phẩm tại thời điểm mua',
    TenMauLucDatHang VARCHAR(100) NOT NULL COMMENT 'Snapshot màu sắc sản phẩm tại thời điểm mua',
    KichCoLucDatHang VARCHAR(20) NOT NULL COMMENT 'Snapshot kích cỡ sản phẩm tại thời điểm mua',
    SoLuong INT NOT NULL COMMENT 'Số lượng sản phẩm đã mua',
    DonGiaLucDatHang DECIMAL(12, 2) NOT NULL COMMENT 'Snapshot đơn giá bán tại thời điểm mua',
    MaPhienThuDo VARCHAR(30) NULL COMMENT 'Khóa ngoại tham chiếu PhienThuDoAo nếu sản phẩm này được mua qua thử đồ ảo (truy vết O2O)',
    -- Lưu ý: Không lưu trường ThanhTien vì ThanhTien = SoLuong * DonGiaLucDatHang (tính toán tức thì qua SELECT hoặc Backend DTO)
    CONSTRAINT PK_ChiTietDonHang PRIMARY KEY (MaChiTietDonHang),
    CONSTRAINT FK_ChiTietDonHang_DonHang FOREIGN KEY (MaDonHang) REFERENCES DonHang(MaDonHang) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT FK_ChiTietDonHang_BienThe FOREIGN KEY (MaBienThe) REFERENCES BienTheSanPham(MaBienThe) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT FK_ChiTietDonHang_PhienThuDo FOREIGN KEY (MaPhienThuDo) REFERENCES PhienThuDoAo(MaPhienThuDo) ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT CHK_ChiTietDonHang_SoLuong CHECK (SoLuong > 0),
    CONSTRAINT CHK_ChiTietDonHang_DonGia CHECK (DonGiaLucDatHang >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng chi tiết các sản phẩm trong đơn hàng (Lưu snapshot lịch sử & liên kết VTON O2O)';

-- -------------------------------------------------------------------------------------
-- CỤM 6: SỔ CÁI KHO HÀNG, CA LÀM VIỆC POS & ĐÁNH GIÁ SẢN PHẨM (3 BẢNG)
-- -------------------------------------------------------------------------------------

-- Bảng 16: BienDongKho (Sổ cái kho hàng hợp nhất Single Pool & Tích hợp Kiểm kê cân kho)
CREATE TABLE BienDongKho (
    MaBienDong VARCHAR(20) NOT NULL COMMENT 'Mã biến động kho (VD: BDK00000001)',
    MaBienThe VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu BienTheSanPham',
    LoaiBienDong ENUM('BAN_TAI_QUAY', 'GIU_HANG_ONLINE', 'XUAT_KHO_GIAO_HANG', 'NHAP_KHO_HANG_HOAN', 'KIEM_KE_CAN_KHO', 'NHAP_KHO_BO_SUNG') NOT NULL COMMENT 'Loại nghiệp vụ kho',
    SoLuongThayDoi INT NOT NULL COMMENT 'Số lượng thay đổi (Số âm: giảm tồn, Số dương: tăng tồn)',
    SoLuongTonTruoc INT NOT NULL COMMENT 'Tồn kho khả dụng trước khi biến động',
    SoLuongTonSau INT NOT NULL COMMENT 'Tồn kho khả dụng sau khi biến động',
    MaThamChieu VARCHAR(30) NULL COMMENT 'Mã đơn hàng hoặc mã phiên kiểm kê phát sinh',
    MaNhanVienThucHien VARCHAR(20) NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung thực hiện',
    GhiChu VARCHAR(255) NULL COMMENT 'Lý do xuất/nhập/điều chỉnh chênh lệch kiểm kê',
    NgayThucHien DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm ghi sổ biến động',
    CONSTRAINT PK_BienDongKho PRIMARY KEY (MaBienDong),
    CONSTRAINT FK_BienDongKho_BienThe FOREIGN KEY (MaBienThe) REFERENCES BienTheSanPham(MaBienThe) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT FK_BienDongKho_NhanVien FOREIGN KEY (MaNhanVienThucHien) REFERENCES NguoiDung(MaNguoiDung) ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng sổ cái biến động kho hàng hợp nhất chống bán lố và cân kho';

-- Bảng 17: CaLamViecThuNgan (Quản lý ca làm việc Web POS - Loại bỏ hoàn toàn các trường tính toán doanh thu)
-- Lưu ý tối ưu: Doanh thu tiền mặt và VietQR được tính toán bằng câu lệnh SUM(DonHang) trong khoảng thời gian ca làm việc
CREATE TABLE CaLamViecThuNgan (
    MaCaLamViec VARCHAR(20) NOT NULL COMMENT 'Mã ca làm việc (VD: CA000001)',
    MaThuNgan VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung thu ngân mở ca',
    ThoiGianMoCa DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm mở ca bán lẻ',
    ThoiGianDongCa DATETIME NULL COMMENT 'Thời điểm kết ca',
    TienMatDauCa DECIMAL(12, 2) NOT NULL DEFAULT 0.00 COMMENT 'Số tiền mặt có sẵn trong két để trả lại khách',
    TienMatKiemDemThucTe DECIMAL(12, 2) NULL COMMENT 'Tiền mặt thực tế đếm được trong két khi kết ca',
    -- Ghi chú: Không lưu TongDoanhThuTienMat, TongDoanhThuVietQR và TienMatChenhLech vì được tính toán động qua Backend DTO / UI
    TrangThaiCa ENUM('DANG_MO', 'DA_DONG_CHO_DUYET', 'DA_PHE_DUYET_BAN_GIAO') NOT NULL DEFAULT 'DANG_MO' COMMENT 'Trạng thái ca bán hàng',
    GhiChuKetCa TEXT NULL COMMENT 'Báo cáo bàn giao ca của thu ngân',
    CONSTRAINT PK_CaLamViecThuNgan PRIMARY KEY (MaCaLamViec),
    CONSTRAINT FK_CaLamViec_ThuNgan FOREIGN KEY (MaThuNgan) REFERENCES NguoiDung(MaNguoiDung) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT CHK_CaLamViec_TienDauCa CHECK (TienMatDauCa >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng quản lý ca làm việc thu ngân và bàn giao két tiền mặt POS';

-- Bảng 18: DanhGiaSanPham (Khách hàng nhận xét, chấm sao - Ràng buộc 1-1 với ChiTietDonHang)
CREATE TABLE DanhGiaSanPham (
    MaDanhGia VARCHAR(20) NOT NULL COMMENT 'Mã đánh giá (VD: DG00000001)',
    MaSanPham VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu SanPham',
    MaNguoiDung VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung viết đánh giá',
    MaChiTietDonHang VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại duy nhất 1-1 tham chiếu ChiTietDonHang (Chỉ khách đã mua và nhận hàng thành công khi DonHang có TrangThaiDonHang = HOAN_TAT mới được đánh giá 1 lần duy nhất)',
    SoSao INT NOT NULL COMMENT 'Điểm đánh giá từ 1 đến 5 sao',
    NoiDungBinhLuan TEXT NOT NULL COMMENT 'Nội dung nhận xét về độ vừa vặn, form dáng, chất vải',
    HinhAnhThucTe VARCHAR(255) NULL COMMENT 'Ảnh chụp thực tế sản phẩm do khách tải lên',
    PhanHoiTuShop TEXT NULL COMMENT 'Câu trả lời giải đáp chính thức từ Shop',
    TrangThaiDuyet ENUM('CHO_DUYET', 'DA_HIEN_THI', 'DA_AN_VI_PHAM') NOT NULL DEFAULT 'DA_HIEN_THI' COMMENT 'Trạng thái kiểm duyệt',
    NgayDanhGia DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm gửi đánh giá',
    CONSTRAINT PK_DanhGiaSanPham PRIMARY KEY (MaDanhGia),
    CONSTRAINT UQ_DanhGiaSanPham_ChiTietDonHang UNIQUE (MaChiTietDonHang),
    CONSTRAINT FK_DanhGia_SanPham FOREIGN KEY (MaSanPham) REFERENCES SanPham(MaSanPham) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT FK_DanhGia_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT FK_DanhGia_ChiTietDonHang FOREIGN KEY (MaChiTietDonHang) REFERENCES ChiTietDonHang(MaChiTietDonHang) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT CHK_DanhGia_SoSao CHECK (SoSao BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng đánh giá sản phẩm Verified Buyer (Ràng buộc 1-1 với dòng hàng đã mua và kiểm duyệt nội dung)';

-- -------------------------------------------------------------------------------------
-- CỤM 7: HỘP THƯ THÔNG BÁO ĐA KÊNH & CẢNH BÁO HỆ THỐNG (1 BẢNG)
-- -------------------------------------------------------------------------------------

-- Bảng 19: ThongBao (Hệ thống thông báo đa kênh: Cảnh báo tồn kho, Đơn hàng, VTON, Khuyến mãi)
CREATE TABLE ThongBao (
    MaThongBao VARCHAR(20) NOT NULL COMMENT 'Mã thông báo (VD: TB00000001)',
    MaNguoiDung VARCHAR(20) NOT NULL COMMENT 'Khóa ngoại tham chiếu NguoiDung nhận thông báo',
    TieuDe VARCHAR(150) NOT NULL COMMENT 'Tiêu đề ngắn gọn của thông báo',
    NoiDung TEXT NOT NULL COMMENT 'Nội dung chi tiết thông báo',
    LoaiThongBao ENUM('DON_HANG', 'THU_DO_VTON', 'CANH_BAO_KHO', 'KHUYEN_MAI', 'HE_THONG') NOT NULL DEFAULT 'HE_THONG' COMMENT 'Phân loại thông báo để hiển thị icon và routing',
    LienKetDieuHuong VARCHAR(255) NULL COMMENT 'Deep link hoặc URL điều hướng khi click (VD: /orders/DH00000001, /vton/TD00000001, /admin/inventory)',
    DaDoc TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0: Chưa đọc (hiển thị chấm đỏ), 1: Đã đọc',
    NgayTao DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Thời điểm phát sinh thông báo',
    CONSTRAINT PK_ThongBao PRIMARY KEY (MaThongBao),
    CONSTRAINT FK_ThongBao_NguoiDung FOREIGN KEY (MaNguoiDung) REFERENCES NguoiDung(MaNguoiDung) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Bảng thông báo hệ thống đa kênh cho khách hàng và nhân sự';

-- -------------------------------------------------------------------------------------
-- TỐI ƯU HÓA CHỈ MỤC TÌM KIẾM (INDEX CHO HIỆU NĂNG TRUY VẤN CAO)
-- -------------------------------------------------------------------------------------

CREATE INDEX IDX_NguoiDung_SoDienThoai ON NguoiDung(SoDienThoai);
CREATE INDEX IDX_SanPham_DanhMuc ON SanPham(MaDanhMuc);
CREATE INDEX IDX_SanPham_TrangThai ON SanPham(TrangThaiSanPham);
CREATE INDEX IDX_BienTheSanPham_MaQuanLyHangHoa ON BienTheSanPham(MaQuanLyHangHoa);
CREATE INDEX IDX_BienTheSanPham_TonKho ON BienTheSanPham(SoLuongTonKho);
CREATE INDEX IDX_HinhAnhSanPham_SanPham_Mau ON HinhAnhSanPham(MaSanPham, MaMauSac);
CREATE INDEX IDX_PhienThuDoAo_NguoiDung ON PhienThuDoAo(MaNguoiDung);
CREATE INDEX IDX_PhienThuDoAo_TrangThai ON PhienThuDoAo(TrangThaiPhien);
CREATE INDEX IDX_GoiYPhoiDo_PhienThuDo ON GoiYPhoiDo(MaPhienThuDo);
CREATE INDEX IDX_DonHang_NguoiDung ON DonHang(MaNguoiDung);
CREATE INDEX IDX_DonHang_TrangThaiDonHang ON DonHang(TrangThaiDonHang);
CREATE INDEX IDX_DonHang_NgayTao ON DonHang(NgayTao);
CREATE INDEX IDX_ChiTietDonHang_PhienThuDo ON ChiTietDonHang(MaPhienThuDo);
CREATE INDEX IDX_BienDongKho_BienThe ON BienDongKho(MaBienThe);
CREATE INDEX IDX_BienDongKho_NgayThucHien ON BienDongKho(NgayThucHien);
CREATE INDEX IDX_CaLamViec_ThuNgan ON CaLamViecThuNgan(MaThuNgan);
CREATE INDEX IDX_ThongBao_NguoiDung_DaDoc ON ThongBao(MaNguoiDung, DaDoc, NgayTao DESC);

-- =====================================================================================

INSERT INTO VaiTro (MaVaiTro, TenVaiTro, TenHienThi, MoTa, NgayTao) VALUES
('VT001', 'ROLE_SHOP_ADMIN', 'Quản trị viên', 'Toàn quyền quản trị hệ thống, danh mục, đơn hàng và cấu hình shop', '2026-01-01 00:00:00'),
('VT002', 'ROLE_CUSTOMER', 'Khách hàng', 'Khách hàng mua sắm online, thử đồ ảo AI và đánh giá sản phẩm', '2026-01-01 00:00:00'),
('VT003', 'ROLE_STAFF_CASHIER', 'Nhân viên thu ngân', 'Bán lẻ tại quầy Web POS, tra cứu tích điểm và chốt kết ca', '2026-01-01 00:00:00'),
('VT004', 'ROLE_STAFF_WAREHOUSE', 'Nhân viên kho', 'Quản lý xuất nhập kho, nhặt hàng đóng gói và kiểm kê hàng hóa', '2026-01-01 00:00:00'),
('VT005', 'ROLE_STAFF_SHIPPER', 'Nhân viên giao hàng', 'Tiếp nhận kiện hàng xuất kho, giao hàng, thu tiền COD và bàn giao hoàn', '2026-01-01 00:00:00');
