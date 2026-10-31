"""Sinh SQL cho database MỚI từ hai file người dùng cung cấp, không kết nối MySQL.

Không phát sinh DROP và không tắt foreign key. CREATE DATABASE không có IF EXISTS:
chạy file bằng mysql batch không --force để dừng ngay nếu database đã tồn tại.
Sau import, Backend chỉ kiểm tra 19 bảng/cột; không chạy Flyway hay ALTER.
"""
import argparse
import re
from pathlib import Path


def statements(text):
    """Tách SQL theo dấu ; ngoài literal/comment, không eval nội dung SQL."""
    result, current = [], []
    quoted = False
    index = 0
    while index < len(text):
        char = text[index]
        if not quoted and text.startswith("--", index):
            end = text.find("\n", index)
            index = len(text) if end == -1 else end + 1
            current.append("\n")
            continue
        current.append(char)
        if quoted and char == "\\" and index + 1 < len(text):
            index += 1
            current.append(text[index])
        elif char == "'":
            if quoted and index + 1 < len(text) and text[index + 1] == "'":
                index += 1
                current.append("'")
            else:
                quoted = not quoted
        elif char == ";" and not quoted:
            result.append("".join(current).strip())
            current = []
        index += 1
    if quoted or "".join(current).strip():
        raise ValueError("SQL có literal chưa đóng hoặc statement thiếu dấu ;")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,63}", args.database):
        raise ValueError("Tên database không hợp lệ")
    root = Path(__file__).resolve().parents[2]
    schema = statements((root / "yofasoshop_db.sql").read_text(encoding="utf-8-sig"))
    seed = statements((root / "yofaso_insert_data.sql").read_text(encoding="utf-8-sig"))
    ddl = [s for s in schema if re.match(r"CREATE TABLE |ALTER TABLE |CREATE (?:UNIQUE )?INDEX ", s, re.I)]
    inserts = [s for s in seed if re.match(r"INSERT INTO ", s, re.I)]
    if len([s for s in ddl if s.upper().startswith("CREATE TABLE ")]) != 19 or len(inserts) != 19:
        raise ValueError("Nguồn không còn đúng 19 bảng/19 nhóm INSERT; cần đối chiếu trước")
    output = f"-- Generated for NEW database only. Do not use mysql --force.\nCREATE DATABASE `{args.database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\nUSE `{args.database}`;\n"
    output += "\n".join(ddl) + "\nSTART TRANSACTION;\n" + "\n".join(inserts) + "\nCOMMIT;\n"
    # Không overwrite artifact đã có để người dùng còn giữ bản đối chiếu trước.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as file:
        file.write(output)
    print("Generated new-database SQL; no database connection, no DROP, no disabled foreign keys.")


if __name__ == "__main__":
    main()
