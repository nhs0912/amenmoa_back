# 아멘모아 이메일 인증

Spring Boot에서 이메일 가입, 로그인, 토큰 갱신, 로그아웃을 처리합니다. 회원 정보와 비밀번호 해시는 MySQL에 저장하고, 로그인 세션은 Redis에서 관리합니다. Flutter 앱은 현재 Supabase에 연결돼 있으며, 전환할 때까지 기존 로그인 흐름을 사용합니다.

## 로컬에서 실행하기

Rancher Desktop의 Moby 엔진에서, 백엔드 프로젝트 루트에서 실행합니다.

```sh
docker --context rancher-desktop compose up -d mysql redis
export AUTH_JWT_SECRET="$(openssl rand -hex 32)"
./gradlew bootRun --args='--spring.profiles.active=local'
```

IntelliJ로 실행한다면 **Active profiles**에 `local`을 입력하고, **Environment variables**에 `AUTH_JWT_SECRET`을 추가합니다. 모든 환경에 서명 키가 필요합니다. 키를 바꾸면 이전 access token의 서명 검증에 실패하므로 동일한 배포 환경에서는 키를 안전하게 보관해 재사용합니다.

로컬 Redis 주소는 기본적으로 `redis://127.0.0.1:6379`이며 `AUTH_REDIS_URL`로 바꿀 수 있습니다. `dev`, `staging`, `prod`에서는 `AUTH_REDIS_URL`을 지정합니다.

Flyway는 기존 V1 마이그레이션을 유지합니다. V2가 `auth_sample_users`를 `accounts`로 바꾸고 그 안의 회원 행을 보존합니다. 로컬 MySQL 접속 계정과 DB는 Compose가 처음 초기화할 때 준비합니다. 이전 학습용 인증 토큰은 다시 사용할 수 없으므로 전환 후 새 API에서 로그인합니다.

## API 호출 순서

모든 경로 앞에 `/api/v1`이 붙습니다. 요청 본문은 JSON입니다.

| 메서드와 경로 | 요청 | 성공 응답 |
| --- | --- | --- |
| POST `/auth/email-availability` | `{"email":"member@example.com"}` | 200, `{"available":true}` |
| POST `/users/nickname-availability` | `{"nickname":"기도모아"}` | 200, `{"available":true}` |
| POST `/auth/signup` | `email`, `nickname`, `password` | 201, `id`(숫자)·`email`·`nickname` |
| POST `/auth/login` | `email`, `password` | 200, `accessToken`, `refreshToken`, `tokenType`, `expiresIn`(초) |
| GET `/me` | `Authorization: Bearer <accessToken>` | 200, `id`(숫자)·`email`·`nickname` |
| POST `/auth/refresh` | `{"refreshToken":"..."}` | 200, 새 토큰 쌍 |
| POST `/auth/logout` | `Authorization: Bearer <accessToken>` | 204 |

```sh
curl -i http://localhost:8080/api/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"member@example.com","nickname":"기도모아","password":"example-password-123"}'
```

가입 후 로그인 요청으로 토큰을 발급받습니다. 중복 확인 결과는 조회 시점의 상태이며, 최종 가입 시 MySQL의 UNIQUE 제약이 동시 요청에서 중복을 차단합니다. 인증 API의 오류 본문은 `{"code":"..."}` 형식입니다. Bearer 토큰 검증에서 거부된 경우 Spring Security가 HTTP 401과 인증 헤더를 반환합니다.

| 상태 | 주요 오류 코드 |
| --- | --- |
| 400 | `INVALID_REQUEST`, `INVALID_EMAIL`, `INVALID_NICKNAME`, `INVALID_PASSWORD` |
| 401 | `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` |
| 409 | `EMAIL_TAKEN`, `NICKNAME_TAKEN` |
| 503 | `AUTH_TEMPORARILY_UNAVAILABLE` |

- 이메일은 앞뒤 공백을 제거하고 소문자로 저장합니다.
- 닉네임은 앞뒤 공백을 없애고 Unicode NFC로 정규화합니다. 길이는 화면상 문자 단위로 2~20자이며 비교할 때 대소문자를 구분하지 않습니다.
- 비밀번호는 12자 이상(Unicode 코드 포인트 기준), UTF-8 72바이트 이하이고 공백만 입력할 수 없습니다. MySQL에는 BCrypt 해시가 저장됩니다.

## 코드를 읽는 순서

1. [AuthController.kt](../src/main/kotlin/com/moa/amenmoa/api/auth/AuthController.kt)에서 요청을 받고, 응답에 필요한 값만 DTO로 반환합니다.
2. [AccountService.kt](../src/main/kotlin/com/moa/amenmoa/api/auth/AccountService.kt)에서 입력 검증·정규화·중복 검사·비밀번호 비교를 처리하고, [AccountRepository.kt](../src/main/kotlin/com/moa/amenmoa/api/auth/AccountRepository.kt)를 통해 MySQL을 사용합니다.
3. [AuthTokens.kt](../src/main/kotlin/com/moa/amenmoa/api/auth/AuthTokens.kt)는 15분짜리 access JWT를 서명합니다. 최대 7일간 유효한 refresh token을 무작위로 생성하고, Redis에 사용자 ID와 토큰 해시를 저장합니다. 갱신할 때마다 refresh token이 바뀌므로 클라이언트는 새 토큰 쌍을 함께 저장해야 합니다. 동일한 refresh token을 동시에 보내면 한 요청만 성공합니다.
4. [AuthSecurityConfiguration.kt](../src/main/kotlin/com/moa/amenmoa/api/auth/AuthSecurityConfiguration.kt)에서 JWT의 서명·만료·발급자·대상을 검증합니다. 로그아웃하면 Redis 세션을 지워 갱신을 막습니다. 이미 발급된 access JWT는 만료까지 사용할 수 있습니다.

통합 테스트는 별도의 MySQL·Redis 컨테이너로 가입부터 로그아웃까지 확인합니다.

## 공개 서비스 전환 전 남은 작업

- 이메일 소유 확인, 인증 메일 재전송, 미인증 계정의 로그인 차단, 비밀번호 재설정을 연결합니다. 인증 링크나 코드에는 만료 시간과 일회 사용 제약을 두어야 합니다.
- 로그인·가입·중복 확인·인증 메일에 서버 측 요청 제한과 모니터링을 적용합니다. 이메일 중복 확인은 가입 여부를 노출하므로 공개 경로로 계속 제공할지도 결정합니다.
- Flutter에서 실제 백엔드 인증 API와 안전한 토큰 저장을 연결합니다. 현재 프런트가 요구하는 이메일 인증과 가입 후 닉네임 설정 흐름을 백엔드 계약과 맞추고, 기존 Supabase 계정과 회원별 데이터의 이전 방식을 정합니다.
- 배포 환경마다 서명 키, HTTPS와 운영용 Redis 연결을 구성합니다. Redis 세션을 잃으면 사용자가 다시 로그인해야 합니다.
