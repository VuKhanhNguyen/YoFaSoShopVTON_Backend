"""Sinh SQL gán email test cho 15 user seed; không kết nối DB, không đổi mật khẩu/schema."""

import argparse
import os
import re
from pathlib import Path

from prepare_erd_bootstrap import statements


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    envfile = Path(__file__).resolve().parents[1] / ".env"
    match = (
        re.search(r"^SEED_TEST_GMAIL=(.*)$", envfile.read_text("utf-8-sig"), re.M)
        if envfile.exists()
        else None
    )
    default_gmail = os.environ.get("SEED_TEST_GMAIL", match[1].strip() if match else "")
    parser.add_argument(
        "--gmail", default=default_gmail, help="Gmail gốc do bạn sở hữu, không có +tag"
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    mailbox = args.gmail.strip().lower()
    if not re.fullmatch(r"[a-z0-9.]+@gmail\.com", mailbox):
        raise ValueError("Nhập Gmail gốc không có +tag; chỉ dùng account bạn sở hữu")
    root = Path(__file__).resolve().parents[2]
    insert = next(
        s
        for s in statements((root / "yofaso_insert_data.sql").read_text("utf-8-sig"))
        if re.match(r"INSERT INTO NguoiDung\s*\(", s)
    )
    users = re.findall(r"\('(?P<id>ND[0-9]+)',\s*'(?P<username>[a-z0-9_]+)'", insert)
    if len(users) != 15:
        raise ValueError("Seed không còn đúng 15 người dùng; cần đối chiếu lại")
    local, domain = mailbox.split("@")
    lines = [
        "-- DEV/TEST ONLY. Chọn database test đúng 19 bảng trước khi Execute.",
        "-- 15 alias cùng về một hộp thư; không dùng chung hộp thư cho người dùng production.",
        "SELECT DATABASE();",
        "START TRANSACTION;",
    ]
    for index, (identifier, username) in enumerate(users, 1):
        lines.append(
            f"UPDATE NguoiDung SET Email='{local}+{index}@{domain}' "
            f"WHERE MaNguoiDung='{identifier}' AND TenDangNhap='{username}';"
        )
    lines.extend(
        [
            "COMMIT;",
            "SELECT MaNguoiDung,TenDangNhap,Email FROM NguoiDung ORDER BY MaNguoiDung;",
        ]
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")
    print(
        "Đã sinh SQL 15 alias; chưa thực thi DB hoặc gửi email. Xem file trước khi chạy trong Workbench."
    )


if __name__ == "__main__":
    main()
