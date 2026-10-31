-- Chay thu cong bang tai khoan quan tri MySQL, doi password truoc khi chay.
-- Khong DROP DATABASE, khong cham database ERD cu neu da co du lieu.
CREATE DATABASE IF NOT EXISTS yofasoshop_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'yofaso_app'@'localhost' IDENTIFIED BY 'CHANGE_TO_YOUR_OWN_PASSWORD';
GRANT SELECT, INSERT, UPDATE, DELETE ON yofasoshop_db.* TO 'yofaso_app'@'localhost';
