# 아멘모아 백엔드 API 명세서

- 버전: 0.1 / 작성일: 2026-11-16
- 상태: **구현 전 제안 계약**. 아래 경로는 현재 동작하는 API를 의미하지 않는다.
- 대상: Flutter 앱 ↔ Spring Boot API / MySQL
- 기준: 프런트 README, `lib/features/auth`, `lib/features/experience/experience.dart`의 현재 화면·모델. 백엔드는 앱 진입점과 환경 설정만 있는 상태를 기준으로 한다.
- 범위: P0·P1은 구현할 요청·응답 계약, P2는 후속 설계용 API 목록이다.

## 1. 현재 상태와 설계 방향

| 영역 | 현재 앱 | 백엔드 구현 방향 |
| --- | --- | --- |
| 이메일 가입·인증·로그인·세션 | Supabase SDK 사용 | Supabase 유지. Spring에서 비밀번호나 refresh token을 받지 않음 |
| 이메일 중복 확인 | 인터페이스만 존재, 실제 가입 차단 | 공개 중복 확인 API 구현 |
| 닉네임 | Supabase user_metadata 저장, 중복 API 미연결 | MySQL 고유 닉네임을 기준으로 조회·저장 통합 |
| 기도 기록·제목·예약·선호 | 계정별 로컬 저장 | 사용자별 영구 저장과 조회 |
| 그룹·공동 목표·라이브·친구 | 로컬 데이터 또는 예시 | P2에서 권한·실시간 정책 확정 후 구현 |

**설계 가정:** 인증 제공자는 기존 Supabase를 유지한다. 자체 Spring 인증으로 교체하는 요구가 생기면 회원가입·메일 발송·코드 검증·토큰 발급/갱신·로그아웃 계약을 별도로 설계해야 한다.

### 구현 우선순위

1. **P0 / 가입·온보딩 연결:** 이메일 중복, 내 프로필 조회, 닉네임 중복·저장, JWT 검증.
2. **P1 / 개인 기능 서버화:** 기도제목, 기록, 월별 통계, 홈 요약, 예약, 사용자 설정.
3. **P2 / 공동체 기능:** 그룹·초대·멤버, 댓글, 공동 목표, 라이브 기도방, 친구 약속·톡톡, 푸시.

둘러보기 모드는 로컬 전용이다. 익명 요청을 임의의 공용 사용자로 저장하지 않는다.

## 2. 공통 계약

### 2.1 URL·헤더·인증

- 기본 경로: `/api/v1`. 호스트는 local/dev/staging/prod 배포별로 설정한다. 실제 도메인은 미정이다.
- 로컬 예시: `http://localhost:8080/api/v1`
- JSON 요청·응답: `Content-Type: application/json`, UTF-8, 필드명 camelCase.
- 아래에서 `공개`로 표기한 API 외에는 `Authorization: Bearer <Supabase access token>` 필수.
- 서버는 서명·허용 알고리즘·issuer·audience·만료를 검증한다. 단순 JWT decode 결과나 클라이언트의 userId를 신뢰하지 않는다.
- JWT `sub`를 인증 사용자 식별자로 사용한다. Supabase의 서버 측 사용자 조회로 이메일 인증 완료 여부를 확인한다. 검증 실패는 401, 미인증 이메일은 403 `EMAIL_NOT_VERIFIED`.
- 닉네임 설정 전에도 P0 인증 API를 사용할 수 있다. P1은 프로필 온보딩 완료가 필요하며 미완료면 403 `PROFILE_INCOMPLETE`.
- 사용자 ID·작성자·소유자는 서버가 결정한다. 개인 리소스는 본인만 접근한다. 다른 사용자의 ID와 존재하지 않는 ID 모두 404로 응답한다.
- 모든 응답에 `X-Request-Id`를 부여한다. 오류 본문 requestId와 동일하다.
- 운영 CORS는 허용한 프런트 origin만 허용한다. 인증 토큰·메일·기도 본문·비밀번호를 로그에 남기지 않는다.

### 2.2 데이터 규칙

| 종류 | 규칙 |
| --- | --- |
| ID | 서버 UUID 문자열. userId는 인증 제공자의 sub UUID |
| 타임스탬프 | RFC 3339 UTC, 예: `2026-11-16T00:00:00Z` |
| 날짜 | `YYYY-MM-DD`. 날짜 경계는 사용자의 저장된 IANA timeZone 기준 |
| timeZone | 기본 `Asia/Seoul`, 유효한 IANA 이름만 허용 |
| 시간량 | 정수 초, 클라이언트는 분 입력을 초로 변환 |
| 문자 길이 | 사용자에게 보이는 grapheme cluster 기준. UTF-16 길이로 대체하지 않음 |
| 알 수 없는 필드·enum | 요청 오류 400. 숫자 범위/형식 불일치도 400 |
| PATCH | 명시한 필드만 변경. 필수 필드에 null은 오류. 선택 필드의 null은 해당 절의 규칙 적용 |

성공은 자원을 직접 반환한다(별도 data 래퍼 없음). 생성 201 + `Location`, 조회·수정 200, 삭제 204(본문 없음). 명시된 PUT upsert는 생성 201/재조회 200이다.

### 2.3 목록·오류

목록 쿼리: `limit` 기본 20, 1~100 / `cursor` 선택. 서버 발급 불투명 cursor만 허용한다. 기본 정렬은 `(createdAt DESC, id DESC)`이며 기록은 `(prayedOn DESC, createdAt DESC, id DESC)`이다. cursor는 사용자·필터·정렬에 바인딩하며 변조/다른 조건 재사용은 400이다.

```json
{
  "items": [],
  "nextCursor": null,
  "hasNext": false
}
```

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값을 확인해 주세요.",
  "requestId": "7e30a9a9-5e36-4aef-b3ca-53edddfc2ab3",
  "fieldErrors": [{"field": "nickname", "reason": "SIZE", "message": "2~20자로 입력해 주세요."}]
}
```

fieldErrors는 검증 오류에서만 배열로 반환하며 다른 오류에서는 빈 배열이다. 클라이언트 분기는 message 대신 code를 사용한다.

| 상태 | code | 의미 |
| --- | --- | --- |
| 400 | VALIDATION_FAILED / INVALID_CURSOR | 입력 또는 cursor 오류 |
| 401 | UNAUTHENTICATED | 토큰 없음·위조·만료 |
| 403 | EMAIL_NOT_VERIFIED / PROFILE_INCOMPLETE / FORBIDDEN | 이메일 미인증·온보딩 미완료·권한 부족 |
| 404 | RESOURCE_NOT_FOUND | 리소스 없음 또는 접근할 수 없는 개인 리소스 |
| 409 | NICKNAME_TAKEN / RECORD_ID_CONFLICT / SCHEDULE_ALREADY_STARTED | 중복 이름·기록 ID 충돌·이미 시작한 예약 |
| 429 | RATE_LIMITED | 제한 초과. `Retry-After`에 재시도 대기 초 |
| 503 | AUTH_PROVIDER_UNAVAILABLE | 인증 제공자 조회 실패·타임아웃. 사용 가능 판정으로 대체하지 않음 |
| 500 | INTERNAL_ERROR | 내부 오류. SQL·스택·개인정보 미노출 |

제안 초기 제한: 공개 이메일 중복 확인 IP당 분당 10회, 닉네임 조회 사용자당 분당 60회, 그 외 사용자당 분당 120회. 고정 60초 구간 카운터를 다중 인스턴스가 공유하며 운영 수치는 설정으로 조절한다. 프런트 1초 debounce는 서버 제한을 대신하지 않는다.

## 3. P0 — 회원·온보딩

### 3.1 이메일 중복 확인

`POST /auth/email-availability` · 공개 · 200

```json
{"email": "member@example.com"}
```

```json
{"available": true}
```

- email 필수, trim 후 최대 254자, 이메일 형식 검증. 도메인은 소문자화하고 로컬 파트는 보존한다. 최종 비교는 인증 제공자의 계정 동일성 기준을 따르며 별도 MySQL 회원 테이블만으로 판정하지 않는다.
- 인증 제공자에 미인증 가입 계정이 있어도 `available:false`. 중복은 오류가 아니라 200/false.
- 계정 생성·가입 API 호출·메일 발송을 부작용으로 수행하지 않는다. 제공자의 관리 권한 조회는 서버 전용 비밀키로만 실행한다.
- 구현 선행조건: 인증 제공자의 정규화 규칙과 기존 계정의 정확한 조회 방식을 확인한다. 신규 가입·계정 삭제 이벤트 및 누락 보정을 포함한 이메일 인덱스 또는 정확한 서버 조회가 준비되지 않으면 503으로 실패시킨다. 전체 사용자 목록을 요청마다 순회하지 않는다.
- 명시적 중복 응답은 계정 존재 유출 가능성이 있다. 출시 전 공개 여부·봇 방지 정책을 확정한다. 이 문서는 현재 앱의 명시적 중복 확인 UX를 기준으로 한다.
- 최종 가입 중복은 Supabase 가입 처리에서 다시 보장한다. 조회는 이메일 예약이 아니다.
- 프런트 연결: `EmailAvailabilityChecker.check`에서 true→available, false→taken, 네트워크/429/5xx→unavailable. 오류를 사용 가능으로 표시하지 않는다.

### 3.2 내 프로필 조회·초기 생성

`GET /me` · 인증 · 200

```json
{
  "userId": "7d05d54b-b5e9-499b-a349-20a0ef81b5db",
  "email": "member@example.com",
  "emailVerified": true,
  "nickname": null,
  "onboardingRequired": true,
  "createdAt": "2026-11-16T00:00:00Z",
  "updatedAt": "2026-11-16T00:00:00Z"
}
```

- 검증된 인증 계정의 최초 요청에서 MySQL 프로필·기본 설정을 원자적으로 생성한다. userId unique로 동시 첫 요청을 중복 생성하지 않는다.
- email/emailVerified는 인증 제공자의 검증된 정보만 사용한다. 요청 본문으로 변경할 수 없다.
- nickname이 없으면 null, onboardingRequired=true. 프런트는 닉네임 입력으로 이동한다.
- 이전 metadata.nickname은 자동으로 확정하지 않는다. 중복 가능한 기존 이름은 닉네임 마이그레이션 절차를 거쳐야 한다.

### 3.3 닉네임 중복 확인

`POST /users/nickname-availability` · 인증 · 200

```json
{"nickname": "기도하는모아"}
```

```json
{"available": true, "normalizedNickname": "기도하는모아"}
```

- trim → Unicode NFC 정규화 → 2~20 grapheme 검증. 프런트 `validateNickname`과 동일한 제어·숨김 문자 범위(U+0000~001F, 007F~009F, 200B, 200E~200F, 202A~202E, 2060~206F, FEFF)를 거부한다.
- 표시명은 위 정규화 값을 저장한다. 중복 비교 키는 NFC 표시명에 Unicode 기본 full case folding을 적용한다. Unicode 버전을 고정하고 MySQL의 기본 collation에 판정을 맡기지 않는다.
- 내부 공백은 보존한다. `모아`/` 모아 `는 동일, `Moa`/`moa`는 동일, `모 아`/`모아`는 서로 다르다.
- 본인의 현재 닉네임은 available=true. 중복 확인은 예약이 아니며 저장 시 다시 판정한다.
- 프런트 연결: `NicknameAvailabilityChecker.check`. 미온보딩 사용자도 호출 가능하다.

### 3.4 닉네임 설정·변경

`PATCH /me` · 인증 · 200

```json
{"nickname": "기도하는모아"}
```

응답: 3.2의 전체 프로필 객체. nickname은 저장된 정규화 값, onboardingRequired=false.

- nickname 필수·null 금지. 3.3과 같은 규칙 적용. 동일한 현재 이름 재저장은 200.
- 최종 닉네임 비교 키에 DB UNIQUE 제약을 두어 조회-저장 사이 경합을 차단한다. 중복 저장은 409 `NICKNAME_TAKEN`.
- 성공 응답을 받기 전에 앱을 홈으로 보내지 않는다. 새 프로필 값을 메모리·저장 세션에 반영한다.
- **전환 후 MySQL 프로필을 단일 기준으로 사용한다.** Supabase metadata와 동시 쓰기를 필수로 하지 않는다. 기존 `saveNickname`의 Supabase updateUser 호출을 이 API로 교체하고, 자동 로그인에서도 GET /me로 이름을 조회해야 한다.

### 3.5 인증 처리 경계

Spring에 `/login`, `/signup`, `/refresh`를 중복 구현하지 않는다. 이메일 인증 링크/8자리 코드, 가입·로그인, 재전송 대기 60초, 세션 복원·갱신은 기존 Supabase 경로를 사용한다. 로그아웃은 현재 기기의 저장 세션을 제거하는 기존 동작을 유지하며 모든 기기 로그아웃으로 확대하지 않는다.

SNS 로그인·비밀번호 재설정은 아직 제품 연결 전이다. 공급자 설정과 리다이렉트 계약을 확정한 다음 별도 문서로 추가한다.

## 4. P1 — 개인 기도제목

### 모델 Topic

```json
{
  "id": "ef4b054a-51b9-44e0-a853-c7690d1d0b45",
  "title": "가족의 평안",
  "content": "서로를 돌볼 수 있도록",
  "createdAt": "2026-11-16T00:00:00Z",
  "updatedAt": "2026-11-16T00:00:00Z"
}
```

| API | 요청 | 성공 |
| --- | --- | --- |
| POST /me/prayer-topics | `{title, content?}` | 201 Topic |
| GET /me/prayer-topics | limit, cursor | 200 목록<Topic> |
| GET /me/prayer-topics/{topicId} | 없음 | 200 Topic |
| PATCH /me/prayer-topics/{topicId} | title 또는 content 중 최소 하나 | 200 Topic |
| DELETE /me/prayer-topics/{topicId} | 없음 | 204 |

- title trim 후 1~100자. content 기본 빈 문자열, 최대 5,000자. PATCH content=null은 빈 문자열로 초기화한다. title=null은 400.
- 개인 제목은 본인만 접근. 동일 제목의 서로 다른 리소스를 허용한다.
- 삭제된 제목은 조회할 수 없으나 과거 기록의 title 스냅샷은 보존한다. 레코드 topicId는 null로 변경한다.
- 목록·단건 조회도 동일한 소유권 검사를 적용한다.

## 5. P1 — 기도 기록·동기화

### 5.1 기록 생성: 클라이언트 ID 기반 멱등 저장

`PUT /me/prayer-records/{recordId}` · 201 생성 / 200 같은 내용 재시도

recordId는 클라이언트가 기도 시작 또는 직접 기록 작성 시 한 번 생성한 UUID다. 네트워크 재시도 시 바꾸지 않는다.

```json
{
  "title": "가족의 평안",
  "topicId": "ef4b054a-51b9-44e0-a853-c7690d1d0b45",
  "durationSeconds": 600,
  "prayedOn": "2026-11-16",
  "timeZone": "Asia/Seoul",
  "source": "TIMER",
  "kind": "PERSONAL",
  "startedAt": "2026-11-16T00:00:00Z",
  "endedAt": "2026-11-16T00:12:00Z",
  "note": "감사하는 마음으로 기도했어요."
}
```

응답 Record: 요청 필드 + `id`, `createdAt`, `updatedAt`. 선택 필드 topicId/startedAt/endedAt은 미지정 시 null, note 기본 빈 문자열. 요청에는 id/userId/createdAt을 넣지 않는다.

| 필드 | 검증 |
| --- | --- |
| title | 필수, trim 후 1~100자. 제목 삭제 후에도 표시할 스냅샷 |
| topicId | 선택, 본인 소유의 존재하는 제목만 허용, 아니면 404 |
| durationSeconds | 필수 정수 1~86,400. TIMER는 실제 기도 시간만, 일시정지 제외 |
| prayedOn | 필수, 2020-01-01 이후이며 요청 timeZone의 오늘 이하 |
| timeZone | 필수 IANA 이름. 기록 생성 시점 값을 보존 |
| source | TIMER / MANUAL / IMPORT |
| kind | P1에서는 PERSONAL만 허용. 표시 문구 `개인 기도`를 enum으로 전송하지 않음 |
| startedAt / endedAt | TIMER는 둘 다 필수, endedAt >= startedAt, 미래 5분 초과 금지, durationSeconds <= 경과 초. MANUAL/IMPORT는 둘 다 null |
| note | 선택, 최대 5,000자 |

- TIMER prayedOn은 시작 시각을 요청 timeZone으로 변환한 날짜여야 한다. 자정을 넘어도 한 기록은 시작일에 귀속한다. MANUAL/IMPORT는 지정한 날짜에 귀속한다.
- 자연인별 기록 정확성 검증을 보장하는 API는 아니다. 클라이언트 시간이므로 순위·보상·공동 목표 인증에 자동 재사용하지 않는다.
- `(userId, recordId)` UNIQUE. 정규화된 필드/기본값이 같은 재요청은 기존 Record 반환(200), 다른 내용이면 409 `RECORD_ID_CONFLICT`. 재시도로 시간·통계를 중복 합산하지 않는다.
- 제목/메모는 trim 후 저장. 동일성 비교에는 모든 저장 필드와 topicId를 포함한다.
- 생성 후 수정은 P1에서 지원하지 않는다. 삭제 후 새 recordId로 다시 작성한다.

### 5.2 조회·삭제

| API | 쿼리·요청 | 응답 |
| --- | --- | --- |
| GET /me/prayer-records | from, to, limit, cursor | 200 목록<Record> |
| GET /me/prayer-records/{recordId} | 없음 | 200 Record |
| DELETE /me/prayer-records/{recordId} | 없음 | 204 |

from/to는 함께 지정하는 날짜(양끝 포함), 최대 366일. 생략하면 전체 기록에 페이지네이션. from > to는 400.

삭제는 서버 집계에서 즉시 제외한다. 본인에게 존재했던 동일 ID 재삭제는 204. 삭제된 ID를 다시 PUT하면 409로 거부해 오래된 오프라인 요청이 기록을 부활시키지 않도록 한다. 이를 위해 최소한의 삭제 tombstone을 유지한다. 다른 사용자 ID는 404.

### 5.3 달력 통계

`GET /me/prayer-statistics?month=2026-11` · 200

month 필수 `YYYY-MM`, 2020-01 이상이며 미래 달은 400.

```json
{
  "month": "2026-11",
  "totalSeconds": 600,
  "recordCount": 1,
  "prayedDays": 1,
  "daily": [{"date": "2026-11-16", "totalSeconds": 600, "recordCount": 1}]
}
```

daily는 해당 월의 모든 날짜를 오름차순으로 포함하고 기록 없는 날도 0으로 반환한다. 위 예시는 한 날짜만 발췌했다. 집계는 record.prayedOn 기준이며 사용자의 timeZone 설정 변경이 과거 기록의 날짜를 바꾸지 않는다.

### 5.4 기존 로컬 기록 가져오기

별도 bulk API 없이 레코드별 PUT을 사용한다. 기존 앱 ID는 마이크로초 문자열이므로 그대로 보내지 않는다. 프런트는 각 로컬 항목에 UUID를 한 번 부여하고 변환 매핑을 영구 보관한다. source=IMPORT, kind=PERSONAL, 날짜는 기존 화면에 표시하던 로컬 날짜를 보존한다. 1~86,400초 범위를 벗어난 데이터는 가져오기 오류로 표시한다.

자동 동기화 전에 계정 소유 기록인지 확인하고 사용자에게 가져오기 선택을 제공한다. 둘러보기 데이터는 계정에 자동 병합하지 않는다. 성공 응답을 받은 항목만 동기화 완료로 표시한다. 한 항목 실패 시 다른 항목 성공을 되돌리지 않는다. 타이머 start/pause/resume는 계속 클라이언트에서 처리하며 종료 저장만 이 API를 호출한다.

## 6. P1 — 개인 예약

P1 예약은 개인 단발 예약이다. 반복·그룹 초대·알림 발송 보장을 포함하지 않는다.

```json
{
  "id": "53f47d2f-8044-405c-803c-11ec586a513e",
  "title": "아침 기도",
  "scheduledAt": "2026-11-17T00:00:00Z",
  "timeZone": "Asia/Seoul",
  "durationSeconds": 1800,
  "createdAt": "2026-11-16T00:00:00Z",
  "updatedAt": "2026-11-16T00:00:00Z"
}
```

| API | 요청 | 성공 |
| --- | --- | --- |
| POST /me/prayer-schedules | title, scheduledAt, timeZone, durationSeconds | 201 Schedule |
| GET /me/prayer-schedules | from, to, limit, cursor | 200 목록<Schedule> |
| GET /me/prayer-schedules/{scheduleId} | 없음 | 200 Schedule |
| PATCH /me/prayer-schedules/{scheduleId} | 생성 필드 중 최소 하나 | 200 Schedule |
| DELETE /me/prayer-schedules/{scheduleId} | 없음 | 204 |

- title 1~100자, durationSeconds 60~86,400, timeZone 필수 IANA. 생성 시 scheduledAt은 미래이며 366일 이내여야 한다.
- PATCH null은 허용하지 않는다. 시작 전 예약만 수정 가능하고 병합한 scheduledAt에도 생성과 동일한 범위를 검증한다. 이미 시작한 예약 수정은 409 `SCHEDULE_ALREADY_STARTED`.
- 목록은 scheduledAt ASC, id ASC. from/to는 함께 지정하는 RFC 3339 시각, 범위는 `[from, to)`이며 최대 366일. 생략하면 현재 이후 31일.
- P1에서는 알림 전송 기능을 제공한다고 표시하지 않는다. 모임 수신 선호 저장과 실제 푸시 발송을 분리한다.

## 7. P1 — 설정·홈

### 7.1 사용자 설정

`GET /me/settings` → 200 Settings

```json
{"textScale": 1.0, "meetingRemindersEnabled": true, "timeZone": "Asia/Seoul"}
```

`PATCH /me/settings` → 200 전체 Settings. 위 필드 중 최소 하나 전달하며 모두 null 금지.

- textScale: 1.0 / 1.2 / 1.4만 허용. meetingRemindersEnabled: boolean. timeZone: 유효한 IANA 이름.
- 기본값은 위 예시와 같다. 계정별로 저장한다.
- meetingRemindersEnabled는 알림 수신 선호이며 OS 알림 권한이나 푸시 토큰 등록을 대신하지 않는다.
- 현재 앱의 목표 분량은 화면 예시 상수다. 개인 목표 설정 API는 목표 기간·집계 정책 확정 후 P2에 추가한다.

### 7.2 홈 요약

`GET /me/home` → 200

```json
{
  "nickname": "기도하는모아",
  "date": "2026-11-16",
  "timeZone": "Asia/Seoul",
  "todaySeconds": 600,
  "totalSeconds": 3600,
  "recordCount": 6,
  "nextSchedule": null
}
```

- date는 서버 현재 시각을 사용자 설정 timeZone으로 변환한 오늘. todaySeconds는 prayedOn=date인 기록의 합, totalSeconds/recordCount는 삭제 제외 전체 합/개수.
- nextSchedule은 현재 이후 가장 가까운 Schedule 전체 객체 또는 null. 모든 값은 본인 데이터만 집계한다.
- 아직 구현되지 않은 공동 참여자 수·친구 진행률을 임의 숫자로 반환하지 않는다.

## 8. P2 — 후속 API 설계 목록 (상세 계약 미확정)

아래 목록은 개발 범위 누락 방지용이다. 요청/응답 DTO·상태·제한을 확정하기 전 프런트에서 호출하거나 구현 완료로 취급하지 않는다.

| 기능 | 후보 API | 구현 전 확정할 항목 |
| --- | --- | --- |
| 그룹 | POST/GET /groups, GET/PATCH/DELETE /groups/{groupId} | 생성자 OWNER, 비공개 기본, 이름·소개·탈퇴/삭제 정책 |
| 멤버 | GET /groups/{groupId}/members, DELETE /groups/{groupId}/members/me | 멤버만 조회, 마지막 OWNER 탈퇴 전 소유권 이전 |
| QR 초대 | POST /groups/{groupId}/invitations, POST /group-invitations/accept | OWNER 발급·폐기, 만료·사용 횟수, QR에는 불투명 토큰만 포함 |
| 그룹 기도제목 | POST/GET /groups/{groupId}/prayer-topics | MEMBER만 열람·작성, 작성자 수정·관리자 삭제 범위 |
| 댓글 | GET/POST /groups/{groupId}/prayer-topics/{topicId}/comments | 작성자/관리자 삭제, 페이지네이션, 신고·차단 |
| 공동 목표 | POST/GET /groups/{groupId}/goals, GET /groups/{groupId}/goals/{goalId} | 기간·목표 초·참여 동의·기록 귀속·삭제 재집계·중복 기여 방지 |
| 그룹 예약 | POST/GET /groups/{groupId}/schedules | 초대 대상·참석 응답·취소 통지·반복 일정 |
| 라이브 기도방 | POST/GET /prayer-rooms, POST/DELETE /prayer-rooms/{roomId}/participants/me | HTTP 참여와 실시간 이벤트의 일관성, heartbeat·TTL·중복 접속 |
| 친구 기도 약속 | POST/GET /prayer-promises, POST /prayer-promises/{promiseId}/accept | 친구 관계/초대 수락, 날짜 경계·기간·인당 목표·개인정보 공유 동의 |
| 톡톡 | POST /prayer-promises/{promiseId}/nudges | 수신 동의·대상 멤버·일일 횟수·차단·알림 중복 방지 |
| 알림함 | GET /me/notifications, PATCH /me/notifications/{notificationId} | readAt, 커서·보관기간·딥링크 접근 권한 |
| 푸시 토큰 | PUT/DELETE /me/devices/{installationId} | 플랫폼·FCM/APNs 토큰 교체·로그아웃 해제·실패 재시도 |
| 회원 탈퇴 | DELETE /me | 인증 계정/개인 기록/그룹 소유권 삭제·유예·재시도 정책 |

라이브 연결 경로·프로토콜(WebSocket/SSE)은 미정이다. 이벤트에는 eventId, roomId, type, occurredAt, sequence를 포함하는 방향으로 설계하며 재연결 시 스냅샷 재조회와 접근 권한 재검증이 필요하다. 오디오·영상·채팅은 현재 범위에 포함하지 않는다.

## 9. 프런트 연결·DB 구현 체크리스트

### 연결 순서

1. 환경별 API base URL 주입. Supabase 키와 API URL을 혼동하지 않는다.
2. 이메일 중복 checker 연결 → 가입 차단 해제.
3. 로그인/세션 복원 후 GET /me → 닉네임 여부 판정.
4. 닉네임 checker와 PATCH /me 연결 → Supabase metadata 기반 저장/복원 교체.
5. 계정별 기록·제목·예약·설정 저장소 구현. 로그인 계정 전환 시 캐시를 userId별로 분리.
6. 기존 기록 가져오기는 사용자 동의 및 UUID 매핑 후 수행. 오프라인 쓰기는 실패 상태와 재시도를 표시.
7. 401은 기존 인증 저장소로 갱신을 단일 실행하고 1회 재시도. 503/네트워크 실패는 로그아웃시키거나 성공으로 대체하지 않음.

### 최소 DB 구조

| 테이블 | 핵심 제약 |
| --- | --- |
| users | id=인증 sub PK, nickname nullable, nickname_key UNIQUE, created_at/updated_at |
| user_settings | user_id PK/FK, text_scale, meeting_reminders_enabled, time_zone |
| prayer_topics | id PK, user_id FK, title/content, timestamps, (user_id, created_at, id) 인덱스 |
| prayer_records | 내부 PK 또는 복합 PK, UNIQUE(user_id, client_record_id), topic_id FK nullable, title 스냅샷, 날짜·초·source·kind·시간대·메모·타임스탬프·삭제 표시 |
| prayer_schedules | id PK, user_id FK, title, scheduled_at, time_zone, duration_seconds, timestamps |

Flyway migration으로 생성한다. 기록 인덱스는 `(user_id, prayed_on, created_at, id)`, 예약은 `(user_id, scheduled_at, id)`를 기본으로 검토한다. 닉네임 unique 위반은 HTTP 409로 변환한다. 이메일 중복 인덱스를 도입할 경우 Auth 이벤트 누락·재처리·계정 삭제 보정을 설계한 후 추가한다.

### 구현 완료 판정 시나리오

- [ ] 토큰 없음·위조·만료·잘못된 issuer/audience 차단, 이메일 미인증 차단.
- [ ] 최초 /me 동시 요청 시 사용자 한 명만 생성, 닉네임 없는 계정은 온보딩으로 이동.
- [ ] 이메일 중복 조회에서 메일 발송·계정 생성 없음, 인증 서비스 장애를 available=true로 반환하지 않음.
- [ ] 한글·조합 이모지 닉네임 길이, NFC·대소문자 중복, 본인 이름 유지, 동시 저장 한 요청만 성공.
- [ ] A 계정으로 B의 제목·기록·예약을 조회·삭제할 수 없음. 목록도 누출 없음.
- [ ] 기록 PUT 재시도 한 번만 합산, 다른 payload 409, 삭제 후 오래된 PUT으로 부활하지 않음.
- [ ] 직접 기록 분→초 변환, 일시정지 제외, 자정 경계·날짜 필터·월 전체 0일 집계·삭제 통계 반영.
- [ ] 커서 끝·변조·필터 변경, 예약 시작 후 수정 차단, 글씨 크기 잘못된 값 400.
- [ ] 계정 전환 시 데이터 분리, 로컬 가져오기 재실행 시 중복 없음, 둘러보기 자동 병합 없음.
- [ ] 문서와 실제 OpenAPI 계약 일치, P0/P1 계약 테스트 및 MySQL Testcontainers 통합 테스트 통과.

## 10. 확정이 필요한 항목

이 문서의 길이 제한·rate limit·이메일/닉네임 정규화·기록 날짜 귀속·오프라인 가져오기 정책은 제안값이다. 구현 시작 전 팀 기준으로 확정한다. 운영 도메인, 인증 issuer/audience, 이메일 조회 연동 방식, 기존 metadata 닉네임 이관, 개인정보 보관·탈퇴 정책은 별도 확정이 필요하다.

P0·P1 구현 후 `docs/openapi.yaml` 또는 생성된 OpenAPI를 이 명세와 함께 관리한다. 이 문서 작성만으로 Swagger·컨트롤러·DB·인증 연동이 구현되는 것은 아니다.
