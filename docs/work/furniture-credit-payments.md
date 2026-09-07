# 사진 가구 생성권 묶음 결제

2026-09-07. 사용자가 선택한 판매 방식은 **가구 생성권 묶음 판매**다. 이 변경은 서버의 결제 검증·생성권 정산을 구현한다. 실제 상품 ID, 묶음별 수량·가격, 스토어 등록 및 모바일 결제 화면은 아직 연결하지 않았다. 기본 설정으로는 판매하거나 생성권을 요구하지 않는다.

기존 spec의 초기 MVP 제외 항목에 대한 후속 구현이다. 상품 조건 확정 후 `rougether-spec`의 상품·API·ERD에 동기화해야 한다. 이 문서의 예시 SKU나 수량은 판매 확정값이 아니다.

## 구현 범위

- Apple 소모성 인앱 구매와 Google Play 일회성 상품을 서버에서 검증하고 생성권을 지급한다.
- 기존 COIN/DIAMOND와 별도 잔액을 사용한다. 구매한 생성권은 만료시키지 않는다.
- 사진 가구 작업을 접수할 때 1개를 예약한다. 가구를 보관함에 지급하는 트랜잭션에서 사용을 확정하며, 처음 가구를 얻기 전에 작업이 실패하면 예약을 반환한다.
- 내부 이미지 재생성·검수에는 추가 생성권을 쓰지 않는다. 기존 호출 한도는 유지한다. 완성된 가구에 대한 피드백도 기존 작업의 한도 안에서 처리하며 추가 차감·실패 반환을 하지 않는다.
- 동일한 구매를 다시 제출하거나, 결제 확인과 스토어 알림이 동시에 도착해도 한 번만 지급한다.
- 실제 스토어의 구매 취소·환불을 반영한다. Apple은 서명 시각으로 순서를 판별해 환불 취소도 반영하며, Google은 환불된 수량을 누적 반영한다.

기존 사진 가구의 사용자당 동시 작업 1개·일일 접수 제한도 유지한다. 현재 기본 일일 제한은 2개이며 실패한 접수도 포함한다. 묶음 구매가 일일 제한을 해제하지는 않는다. 실제 판매 전에는 한도와 사용 안내를 함께 확정하고, 사진 가구 기능·모델 접근·워커가 활성화된 환경에서 판매를 켜야 한다.

## 앱과 서버의 호출 순서

1. 로그인 후 `GET /api/v1/me/furniture-credits`로 잔액과 `accountToken`을 받는다. 이 값은 사용자의 생성권 계정을 식별하는 고정 UUID다.
2. `GET /api/v1/me/furniture-credits/products?store=APPLE` 또는 `GOOGLE`로 판매 가능한 SKU와 생성권 수량을 받는다. `available=false`이면 구매를 시작하지 않는다.
3. 앱이 해당 SKU를 스토어 SDK로 조회한다. 가격·통화·할인 표시는 스토어가 반환하는 현지화된 값을 사용한다. 서버에 클라이언트 가격이나 지급 수량을 제출하지 않는다.
4. 구매를 시작할 때 iOS는 StoreKit `appAccountToken`, Android는 Billing의 `obfuscatedAccountId`에 1번의 UUID를 넣는다. 로그인 사용자의 숫자 ID나 임의의 새 UUID로 바꾸지 않는다.
5. 스토어 결제가 완료되면 `POST /api/v1/me/furniture-credits/purchases`에 거래 식별자를 제출한다. 결제가 대기 중이면 지급하지 않는다.
6. 서버의 지급 확인 후 iOS는 StoreKit 거래를 `finish`한다. Android의 `consume`은 서버가 지급 기록을 커밋한 뒤 수행한다. 앱이 먼저 소비하면 안 된다.
7. 통신 실패·앱 종료 후에는 같은 거래 식별자로 재시도한다. 결제 성공 UI는 서버의 지급 결과를 확인한 뒤 표시한다. 스토어의 구매 취소는 서버 확인을 호출하지 않고 정상 취소 UI로 처리한다.

`accountToken`이 없는 예전 거래나 다른 Rougether 계정에 연결된 거래를 요청자의 소유라고 추측해 지급하지 않는다. 앱에서 로그아웃하거나 다른 계정으로 바뀐 경우에도 구매 당시 계정으로 돌아와 완료해야 한다. 서버의 소비성 생성권 잔액은 같은 Rougether 계정으로 다시 로그인해 조회한다.

## API

아래 사용자 API는 Rougether JWT가 필수이며 응답에 `Cache-Control: no-store`를 설정한다.

| 요청 | 의미 |
| --- | --- |
| `GET /api/v1/me/furniture-credits` | 사용 가능 잔액, 예약 수량, 환불 조정 상태, 스토어 계정 연결 UUID |
| `GET /api/v1/me/furniture-credits/products?store=APPLE` | 해당 스토어의 판매 가능 여부와 SKU별 생성권 수량 |
| `POST /api/v1/me/furniture-credits/purchases` | 스토어의 현재 거래 조회·검증 후 지급 또는 기존 영수증 반환 |

잔액 예시:

```json
{
  "available": 2,
  "reserved": 1,
  "purchaseAdjustmentPending": false,
  "accountToken": "00000000-0000-4000-8000-000000000001"
}
```

구매 확인 요청은 `{ "store": "APPLE", "reference": "스토어 transactionId" }` 또는 `{ "store": "GOOGLE", "reference": "스토어 purchaseToken" }`이다. Apple JWS나 Google `orderId`를 `reference`로 보내지 않는다.

구매 확인 응답의 `id`는 서버 구매 UUID, `credits`는 원래 지급 총량, `revokedCredits`는 현재 회수량이다. `balance.available`이 지금 사용할 수 있는 잔액이다. `storeConsumed`는 Google의 서버 소비 완료 여부이며, Apple에서는 서버 검증·지급 처리를 마쳤다는 의미다. 이 값으로 StoreKit `finish`를 생략하면 안 된다.

| HTTP / code | 앱 처리 |
| --- | --- |
| `402 FURNITURE_CREDITS_REQUIRED` | 가구 생성 전에 생성권 구매 안내 |
| `409 BILLING_PURCHASE_PENDING` | 스토어 결제 완료를 기다린 뒤 같은 거래 재확인 |
| `409 BILLING_PURCHASE_OWNER_MISMATCH` | 구매 당시 Rougether 계정 확인 |
| `400 BILLING_PURCHASE_INVALID` | 잘못된 거래·앱·환경·유형으로 지급하지 않음 |
| `400 BILLING_PRODUCT_UNAVAILABLE` | 서버에 등록되지 않은 SKU로 지급하지 않음 |
| `429 BILLING_VERIFICATION_LIMIT` | 2초 이상 기다린 뒤 재시도. 새 거래 검증만 제한 |
| `503 BILLING_UNAVAILABLE` | 구매 시작을 중단하거나 미완료 거래를 보존하고 재시도 |

스토어 콜백은 아래 정확한 POST 경로만 Rougether JWT를 면제한다. 서명 검증을 통과해야 처리하며, 처리 완료 또는 무관한 알림은 204를 반환한다.

- `POST /api/v1/billing/notifications/apple`: App Store Server Notifications V2의 `signedPayload`. 공식 SDK로 알림과 중첩 거래의 서명·앱·환경을 검증하고, App Store Server API에서 현재 거래를 다시 조회한다.
- `POST /api/v1/billing/notifications/google`: Pub/Sub의 `message.data`와 `Authorization: Bearer ...`. Google 서명·만료와 전용 audience·서비스 계정 이메일·`email_verified`를 확인한다. 본문의 packageName도 확인하고 현재 구매 상태를 Play Developer API로 조회한다. 일반 Google 로그인 토큰은 콜백 인증이 아니다.

## 정합성과 보안

사용자 행 → 생성권 계정/구매/가구 작업 순서로 잠근다. 같은 사용자의 서로 다른 구매와 가구 작업도 잔액을 덮어쓰지 않는다. 스토어 HTTP는 DB 트랜잭션 밖에서 수행한다.

구매 유일키는 `(store, environment, SHA-256(reference))`이다. 계정은 서버가 발급한 UUID를 기준으로 스토어 검증 결과와 매칭한다. 상품 ID·수량은 스토어 응답을 사용하고, 상품당 생성권 수량은 서버 설정에서 가져와 최초 거래에 저장한다. 출시한 SKU의 생성권 수량을 바꾸지 말고 새 SKU를 등록해야 한다.

Google 지급 후 소비 요청 실패는 구매 행에 다음 시각을 기록해 120초 이후 재시도한다. 스케줄러는 30초마다 최대 20건을 조회한다. 소비 성공 직후 서버가 종료돼도 다음 조회에서 스토어 소비 상태를 확인하여 중복 지급 없이 완료한다. 소비가 오래 지연되는 거래는 운영에서 확인해야 한다.

Apple 거래는 검증된 `signedDate`가 더 최신일 때만 회수량을 갱신한다. 이전 구매·환불 알림이 뒤늦게 도착해도 최신 잔액을 되돌리지 않는다. Google은 `refundableQuantity` 감소분만 회수하여 오래된 구매 상태가 생성권을 다시 만들지 않도록 한다.

구매 참조 원문은 별도 32바이트 키로 AES-256-GCM 암호화해 저장한다. API 응답이나 로그에 원문·JWS·암호문·서비스 키를 내보내지 않는다. 결제 JSON 파싱 오류도 본문이 포함된 예외 메시지를 기록하지 않는다. 외부 HTTP 대상은 스토어의 고정 API이며 클라이언트 URL을 받지 않는다.

사진 업로드·검수·보관함 지급·작업 실패와 생성권 예약 정산은 기존 트랜잭션 경계에 연결한다. 워커 중단·원본 만료·탈퇴로 실패하는 작업도 예약을 한 번만 반환한다. 구매 생성권의 유효기간과 사진 원본의 24시간 보관기간은 별개다.

## 환불 처리의 기본 동작

스토어의 현금 환불은 해당 구매에서 지급한 생성권을 회수한다. 생성 실패 시 예약 반환은 생성권 반환이며 현금 환불이 아니다.

이미 사용·예약한 수량이 환불되면 내부 잔액은 음수가 될 수 있다. API는 사용 가능 잔액을 0, `purchaseAdjustmentPending=true`로 반환하고 새로운 작업을 막는다. 예약 작업이 실패하거나 추가 구매로 잔액이 보충되면 그 차이를 정산한다. 카드에 추가 결제를 발생시키지 않는다. 이미 받은 가구는 자동 삭제하지 않는다. 이 운영 정책은 실제 판매 안내에 반영해야 한다.

Apple 환불 취소는 더 최신인 스토어 거래 상태에 따라 회수분을 복원한다. Google 다중 수량 구매의 수량 단위 부분 환불은 남은 미환불 수량으로 처리한다. Apple의 금액 비율 부분 환불은 현재 `revocationDate`가 있는 거래 전체의 생성권 회수로 처리한다. 비율별 생성권 정책은 미정이며, 이 경우 운영 조정이 필요하다.

자동 현금 환불 요청, Apple `CONSUMPTION_REQUEST`에 대한 사용 증빙 제출, Google 환불 심사 답변, 운영자 수동 잔액 조정 화면, 콜백 누락 전체 대사는 구현 범위에 포함하지 않는다. 스토어 알림 재전송을 모두 놓친 거래는 운영에서 별도 확인해야 한다.

## 배포 설정

`BillingProperties`의 기본값으로 서버가 기동하므로 결제 키가 없다고 기존 앱이 중단되지는 않는다. 설정은 환경 변수 또는 외부 Spring YAML/`SPRING_APPLICATION_JSON`로 넣는다. 점이 포함된 SKU는 외부 YAML/JSON의 map key로 전달한다.

```yaml
billing:
  enabled: false                   # 신규 판매·사용자 구매 확인 허용
  require-credits: false           # 사진 가구 접수 시 1개 예약
  worker-enabled: true             # 기존 Google 지급 거래 소비 완료
  environment: PRODUCTION          # SANDBOX와 DB·배포 환경 분리
  encryption-key: ${BILLING_ENCRYPTION_KEY:}
  apple:
    enabled: false
    bundle-id: ${BILLING_APPLE_BUNDLE_ID:}
    app-apple-id: 1234567890        # 예시. App Store의 실제 숫자 앱 ID로 교체
    key-id: ${BILLING_APPLE_KEY_ID:}
    issuer-id: ${BILLING_APPLE_ISSUER_ID:}
    private-key-path: /run/secrets/app-store-server.p8
    root-certificates:
      - /run/certificates/AppleRootCA-G3.cer
    products:
      replace_with_registered_product_id: 3  # 예시. 실제 SKU와 수량으로 교체
  google:
    enabled: false
    package-name: ${BILLING_GOOGLE_PACKAGE_NAME:}
    credentials-path: /run/secrets/play-publisher.json
    notification-audience: ${BILLING_GOOGLE_NOTIFICATION_AUDIENCE:}
    notification-service-account: ${BILLING_GOOGLE_NOTIFICATION_SERVICE_ACCOUNT:}
    products:
      replace_with_registered_product_id: 3  # 예시. 실제 SKU와 수량으로 교체
```

판매를 켜려면 `billing.enabled=true`, `billing.require-credits=true`, 해당 스토어의 enabled 및 설정을 함께 준비해야 한다. 전용 암호화 키가 없거나 생성권 사용 조건을 켜지 않은 채 판매를 켜면 기동을 거부한다. 상품 조회는 로컬 인증 파일의 형식을 확인하지만 실제 스토어 계정 권한까지 보증하지는 않는다.

신규 판매만 중단할 때는 `billing.enabled=false`로 바꾸고 `require-credits`, 스토어 검증 설정·암호화 키·소비 워커를 유지한다. 이미 예약한 생성권은 설정 변경 후에도 정산한다. 아직 확인되지 않은 신규 구매는 판매 중단 기간 동안 사용자 확인 API에서 503을 받으므로 앱은 거래를 보존해야 한다. 검증된 스토어 알림과 기존 소비 워커는 계속 처리한다.

Apple 로그인 키나 OpenAI 서버 키를 결제 인증에 재사용하지 않는다. App Store Server API용 키와 Apple 루트 인증서, Play Developer API 권한이 있는 Google 서비스 계정을 별도로 설정한다. 결제 암호화 키를 바꿀 때는 기존 구매 참조를 계속 복호화할 수 있도록 재암호화 절차를 먼저 준비한다. 키나 JSON 인증 파일을 저장소에 커밋하지 않는다.

## 출시 전에 남은 연결

1. 스토어별 소모성 SKU, 묶음 수량·가격, 판매 국가와 안내 문구를 확정하고 등록한다.
2. App Store API 키·앱 식별자와 Play API 권한·Pub/Sub 인증을 배포 환경에 연결한다. Apple V2 및 Google 일회성 상품/환불 알림을 설정한다.
3. 모바일에 네이티브 IAP SDK, 상품 목록·스토어 가격 표시·구매·미완료 거래 복구를 구현한다. 기존 사진 가구 생성 UI와 잔액 안내를 연결한다.
4. 분리된 Sandbox/라이선스 테스트 환경에서 구매 → 지급 → 가구 생성 → 실패 반환 → 환불 → 중복/재시작 복구를 실제 스토어와 검증한다.
5. 확정한 상품·환불 운영 정책을 spec과 앱 안내에 반영한 뒤 판매를 활성화한다.

현재 자동 테스트는 스토어 호출을 대체하고 실제 Spring/JPA 트랜잭션을 검증한다. 실제 스토어 결제, 정산, 운영 DB migration, 앱 심사 또는 운영 판매 검증은 수행하지 않았다.

2026-09-07 검증: `./gradlew test` 전체 1,825개 중 1,824개 통과, 실제 API 테스트 1개 건너뜀. 이 중 결제 관련 41개는 모두 통과했다. 중복·동시 구매, 계정/환경/SKU 불일치, 예약·지급·실패·만료·피드백 정산, 환불/환불 취소/역순 알림, Google 소비 재시도, 토큰 암호화·변조·로그 비노출, 두 워커의 JPA 기동을 포함한다. `git diff --check`도 통과했다.

## 근거 문서

- [Apple App Review Guidelines 3.1.1](https://developer.apple.com/app-store/review/guidelines/): 앱 내 디지털 상품과 구매한 크레딧의 만료 기준.
- [Apple 공식 Java Server Library](https://github.com/apple/app-store-server-library-java): 거래 조회 및 JWS 검증.
- [Apple 알림 종류](https://developer.apple.com/documentation/appstoreservernotifications/notificationtype): 환불 및 환불 취소.
- [Google Play 결제 보안](https://developer.android.com/google/play/billing/security): 서버 검증, 계정 매칭, 지급 후 소비.
- [Google ProductPurchaseV2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2): 구매 상태, 수량, 소비 상태.
- [Google RTDN](https://developer.android.com/google/play/billing/rtdn-reference): 일회성 상품과 수량 단위 환불 알림.
- [Pub/Sub push 인증](https://cloud.google.com/pubsub/docs/authenticate-push-subscriptions): 서명 및 audience·이메일 검증.
