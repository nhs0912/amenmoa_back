-- 이미 저장된 계정과 V1 마이그레이션 이력을 유지하면서 테이블 이름을 변경한다.
RENAME TABLE auth_sample_users TO accounts;
