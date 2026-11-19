# amenmoa-api 환경 설정

공통 설정은 `application.yaml`, 환경별 설정은 `application-{profile}.yaml`에 둡니다. 프로필을 지정하지 않으면 `local`로 실행합니다.

| 환경 | SPRING_PROFILES_ACTIVE |
| --- | --- |
| 로컬 | local (기본값) |
| 개발 | dev |
| 스테이징 | staging |
| 운영 | prod |

## 로컬 실행

Rancher Desktop을 실행한 뒤 백엔드 프로젝트 루트에서 MySQL과 Redis를 시작합니다. Compose가 MySQL 데이터베이스와 접속 계정을 준비하고, 앱이 시작되면 Flyway가 회원 테이블을 준비합니다.

```sh
docker --context rancher-desktop compose up -d mysql redis
export AUTH_JWT_SECRET="$(openssl rand -hex 32)"
./gradlew bootRun --args='--spring.profiles.active=local'
```

IntelliJ의 실행 설정에는 활성 프로필 `local`과 환경 변수 `AUTH_JWT_SECRET`을 지정합니다. Redis 기본 주소는 `redis://127.0.0.1:6379`이며 `AUTH_REDIS_URL`로 변경할 수 있습니다.

## 서버 실행

배포 환경마다 다음 환경 변수를 지정합니다.

```text
SPRING_PROFILES_ACTIVE=dev
DB_URL=jdbc:mysql://<db-host>:3306/amenmoa
DB_USERNAME=<db-user>
DB_PASSWORD=<db-password>
AUTH_REDIS_URL=rediss://<redis-host>:6379
AUTH_JWT_SECRET=<secret>
PORT=8080
```

스테이징은 `staging`, 운영은 `prod`로 지정합니다. 세 환경의 DB와 Redis 주소 및 JWT 서명 키는 각 배포 환경에 주입합니다. 실제 비밀번호와 키는 Git 밖에 보관합니다. `.env` 파일은 Spring Boot가 자동으로 읽지 않습니다.

개발은 애플리케이션 DEBUG 로그, 스테이징과 운영은 INFO 로그를 사용합니다. 앱이 시작되면 Flyway가 마이그레이션을 적용하고 JPA가 스키마를 검증합니다. MySQL·Redis Testcontainers 기반 통합 테스트를 실행하려면 Docker가 필요합니다.

## API 명세

[이메일 인증 안내](docs/AUTH.md)에서 현재 인증 API, 코드 읽는 순서, 배포 전 남은 작업을 확인할 수 있습니다. [백엔드 API 명세서](docs/API_SPEC.md)에서 후속 회원·온보딩, 개인 기능, 공동체 기능의 설계를 확인할 수 있습니다.
