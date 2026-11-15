# amenmoa-api 환경 설정

공통 설정은 `application.yaml`, 환경별 설정은 `application-{profile}.yaml`에 둡니다.
프로필을 지정하지 않으면 `local`로 실행합니다.

| 환경 | SPRING_PROFILES_ACTIVE |
| --- | --- |
| 로컬 | local (기본값) |
| 개발 | dev |
| 스테이징 | staging |
| 운영 | prod |

## 로컬 실행

MySQL에 `amenmoa` 데이터베이스와 접근 가능한 계정을 먼저 준비하세요.
기본 URL은 `jdbc:mysql://localhost:3306/amenmoa`, 기본 계정/비밀번호는 `amenmoa`입니다.
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 기본값을 덮어쓸 수 있습니다.

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

IntelliJ 실행 설정의 Environment variables에도 `SPRING_PROFILES_ACTIVE=local`을 지정할 수 있습니다.

## 서버 실행

배포 환경마다 다음 환경변수를 별도로 설정하세요.

```text
SPRING_PROFILES_ACTIVE=dev
DB_URL=jdbc:mysql://<db-host>:3306/amenmoa
DB_USERNAME=<db-user>
DB_PASSWORD=<db-password>
PORT=8080
```

스테이징은 `staging`, 운영은 `prod`로 지정합니다.
서버 프로필은 DB 접속 정보의 기본값을 제공하지 않습니다.
실제 비밀번호는 YAML이나 Git에 저장하지 마세요. `.env` 파일은 Spring Boot가 자동으로 읽지 않습니다.

개발은 애플리케이션 DEBUG 로그, 스테이징과 운영은 INFO 로그를 사용합니다.
Flyway와 JPA 동작은 Spring Boot 기본값을 유지합니다.
테스트의 MySQL Testcontainers 설정은 Docker 실행이 필요합니다.
