-- 자체 로그인 샘플이 사용할 계정이다. MySQL 접속 계정은 Compose가 준비한다.
CREATE TABLE auth_sample_users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(254) COLLATE utf8mb4_0900_bin NOT NULL,
    nickname VARCHAR(255) NOT NULL,
    nickname_key VARCHAR(255) COLLATE utf8mb4_0900_bin NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_auth_sample_users_email UNIQUE (email),
    CONSTRAINT uk_auth_sample_users_nickname UNIQUE (nickname_key)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
