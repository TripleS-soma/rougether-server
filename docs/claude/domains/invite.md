# 친구 초대 · 초대 링크(딥링크)

이 문서는 친구 초대 보상과 초대 링크/deferred deep link의 서버 구현 기준입니다. 집 초대코드 자체의 규칙(두 종류 코드, 승인 흐름)은 [room-house.md](room-house.md)를 참고합니다.

## 친구 초대 보상 API

- 개인 초대코드는 사용자당 1개, 만료 없음이며 `GET /api/v1/invites/me` 최초 조회 시 발급(lazy)됩니다. 응답에는 공유용 `shareUrl`(랜딩 `/i/{code}`)이 함께 내려갑니다. 링크 base URL(`invite.link.share-base-url`) 미설정 환경에서는 `shareUrl`이 null이고 앱은 코드만 공유로 폴백합니다.
- `POST /api/v1/invites/redeem`은 초대자·초대받은 사람 양쪽에 코인을 지급합니다. 한 계정은 평생 1회만 사용할 수 있고(`uq_invite_rewards_invitee`), 초대자 보상은 건수 상한이 있습니다. 락 순서·동시성 규칙은 `InviteService` 클래스 주석이 정본입니다.
- `GET /api/v1/invites/by-code/{code}`는 redeem 전 미리보기입니다. 딥링크·클립보드로 자동 입력된 코드를 "OO님의 초대" 확인 화면으로 검증하는 용도이며, **확인 없이 자동으로 redeem을 호출하지 않는 것이 계약**입니다 — redeem은 평생 1회라 오탐(잘못 매칭된 코드)의 비용이 큽니다. 검증 순서·에러코드는 redeem과 동일하고, `alreadyRedeemed=true`면 앱은 확인 화면 자체를 건너뜁니다.

## 초대 링크 랜딩과 deferred deep link

앱 미설치 사용자가 초대 링크를 눌렀을 때의 "설치 후 첫 실행에 초대코드 자동 입력" 흐름입니다. 서버는 링크·랜딩·검증 파일·로그를 담당하고, 코드 복원(Install Referrer·클립보드 읽기)과 자동 입력 UX는 모바일 앱이 담당합니다(rougether-mobile 이슈 참고).

- 랜딩은 `GET /i/{code}`(친구 초대)·`GET /h/{code}`(집 초대) 두 개이며 비인증 공개 HTML 페이지입니다(SecurityConfig permitAll). 친구 코드와 집 코드는 네임스페이스가 겹칠 수 있어 path로 종류를 구분합니다. 무효·만료 코드도 200으로 렌더하고 안내만 바꿉니다(스토어 이동은 막지 않음).
- 랜딩에 노출하는 표시 이름은 친구 초대면 마스킹된 초대자 닉네임(첫 글자 + `*`), 집 초대면 집 이름입니다. 닉네임 원문은 공개 페이지에 노출하지 않습니다.
- 코드 전달 경로는 플랫폼별로 다릅니다.
  - 설치된 기기: 랜딩 URL 자체가 universal link(iOS)/app link(Android)로 앱을 직접 엽니다(아래 well-known 검증 전제). 랜딩의 "앱에서 열기"는 커스텀 스킴 폴백으로, 배포된 앱의 기존 라우트 계약을 따릅니다 — 친구 `{app-scheme}://invite?code={CODE}`, 집 `{app-scheme}://join?code={CODE}` (rougether-mobile `src/app/invite.tsx`·`join.tsx`).
  - 미설치 Android: Play 스토어 URL에 `referrer=invite_type%3D{friend|house}%26invite_code%3D{CODE}`를 실어 보내고, 앱이 설치 후 Install Referrer API로 읽습니다(결정적 경로).
  - 미설치 iOS: 스토어 버튼 클릭 시 클립보드에 봉투 문자열 `rougether-invite:{friend|house}:{CODE}`를 복사해 두고, 앱이 첫 실행에서 패턴 감지 후 사용자 확인을 거쳐 읽습니다. 이 봉투 형식은 모바일과의 파싱 계약입니다.
- 클릭 로그는 `invite_link_clicks`에 남습니다 — 초대 퍼널의 분모(클릭 수)이며 분자는 `invite_rewards`(친구)·`house_members`(집)입니다. 발급 문자 집합 형식에 맞지 않는 입력(스캐너·오타)은 로그를 남기지 않고, IP·User-Agent 원문은 저장하지 않습니다(OS 추정값만).
- `/.well-known/apple-app-site-association`·`/.well-known/assetlinks.json`은 설정값으로 생성해 서빙하며, 연결값 미설정이면 404입니다 — 자리표시자 검증 파일을 배포하는 것보다 명시적 부재가 낫습니다.
- IP 기반 확률적 매칭(자체 fingerprint deferred match)은 구현하지 않았습니다. 한국 통신사 NAT 환경의 오탐 대비 효용이 낮아 Install Referrer + 클립보드 조합으로 시작하고, 부족하면 서드파티 어트리뷰션 도입과 함께 별도 설계합니다.

## 설정 (`invite.link.*`)

전부 미설정이어도 서버는 뜨고 기능이 단계적으로 줄어듭니다. 실제 값은 앱 스토어 등록 후 env로 주입합니다.

- `share-base-url`(`INVITE_SHARE_BASE_URL`): 초대 링크 도메인. 미설정이면 `shareUrl` null.
- `app-scheme`(`INVITE_APP_SCHEME`, 기본 `rougether`): 앱 커스텀 스킴.
- `android-package`(`INVITE_ANDROID_PACKAGE`) · `appstore-id`(`INVITE_APPSTORE_ID`): 스토어 버튼. 미설정이면 해당 버튼 숨김.
- `apple-app-id`(`INVITE_APPLE_APP_ID`, `TEAMID.bundleId` 형식) · `android-cert-fingerprints`(`INVITE_ANDROID_CERT_FINGERPRINTS`, 쉼표 구분 SHA256): well-known 검증 파일. 미설정이면 404.

## 기존 정적 랜딩(rougether.com)과의 관계

앱이 공유하는 링크는 현재 `https://rougether.com/invite.html?code=`·`/join.html?code=`(TripleS-soma/rougether-landing, GitHub Pages)이며, 설치된 기기에서 스킴 딥링크로 앱을 여는 것까지만 합니다(미설치면 코드 유실 — deferred 없음). 서버 랜딩(`/i`, `/h`)은 그 위에 클릭 로그·마스킹 표시 이름·Play referrer·클립보드 복사를 더한 것입니다. 어느 쪽을 정본 랜딩으로 갈지(정적 랜딩에 referrer/클립보드 JS를 추가 vs 링크 도메인을 user-api로 라우팅)는 미정이며, 서버는 양쪽 모두를 막지 않게 설계돼 있습니다(`share-base-url`만 바꾸면 됨).

## 미정값 (spec open questions)

- 초대 링크 정본 랜딩 결정: 기존 rougether.com 정적 랜딩 유지(+referrer·클립보드 JS 추가) vs 링크 도메인 → user-api(`/i`, `/h`, `/.well-known`) 라우팅(CloudFront). 후자면 `INVITE_SHARE_BASE_URL` 설정으로 `shareUrl` 이 내려가기 시작합니다.
- 스토어 등록값 4종(위 env).
- 집 초대 응답(`InviteCodeResponse` 등)에 `shareUrl`을 추가할지 — 현재는 친구 초대(`/api/v1/invites/me`)에만 있고, `/h/{code}` 랜딩은 서버에 준비돼 있습니다.
- 랜딩은 비인증 DB 쓰기(클릭 로그)라 rate limit이 없습니다. 남용이 관측되면 제한·집계 방식(배치 집계 등)을 정합니다. 클릭 분모를 부풀리는 요인(HEAD 프리페치, 메신저 링크 프리뷰 봇 UA)의 제외 규칙도 이때 함께 정합니다.
- 노출 정책 비대칭 재확인: 랜딩에서 친구 닉네임은 마스킹하지만 집 이름은 원문 노출합니다. 링크 프리뷰 성격상 의도한 설계지만, 집 이름도 사용자 입력 문자열이므로 기획 확정이 필요합니다.
