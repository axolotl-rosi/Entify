# Entify 보안 강화 및 배포 인수인계서

작성 기준일: 2026-10-15  
기준 커밋: `b3edebe`  
계획 기준 앱 버전: `1.1.1` (`versionCode 3`)  
공식 배포 주소: <https://entify-download.vercel.app/>  
현재 배포 APK SHA-256: `52499E981DD0AC24C24ED3E567DA82F1BD489BDAB86B0A413384A6488B8A7439`

> 실행 상태(2026-10-15): v1.2.0 보안 강화 구현이 진행되었으며, 실제 릴리스 해시와 배포 주소는 최종 빌드 검증 후 README·SECURITY·설치 사이트에 반영한다. 이 문서의 기존 값은 작업 착수 시점의 기준선이다.

## 1. 문서 목적

이 문서는 Entify의 다음 보안 강화 작업을 다른 개발자 또는 보안 검토자가 그대로 이어서 수행할 수 있도록 현재 상태, 확인된 문제, 수정 순서, 테스트 기준, APK 빌드·배포 절차, 다운로드 사이트 및 GitHub 문서 정정 범위를 한곳에 정리한 실행 문서다.

Entify는 엔트리와 독립적으로 개발된 비공식 Source-Available Android 앱이다. 이 프로젝트의 보안 목표는 엔트리 웹 페이지 자체, 페이지 안의 사용자 생성 콘텐츠, 네트워크 응답, 알림 payload를 모두 잠재적으로 신뢰할 수 없는 입력으로 간주하면서도 사용자의 세션 쿠키와 토큰을 필요한 최소 범위에서만 취급하는 것이다.

이 문서는 보안 인증서가 아니다. 작업 완료 뒤에도 “금융 서비스 수준 인증”, “절대 안전”, “개인정보 탈취가 불가능”과 같은 표현은 사용하지 않는다. 대신 코드로 검증 가능한 제한, 남은 위험, 재현 가능한 빌드 및 무결성 정보를 공개한다.

## 2. 위협 모델과 신뢰 경계

### 2.1 반드시 방어할 위협

1. 엔트리 페이지 또는 사용자 게시물에 포함된 스크립트가 WebView 안에서 실행되는 상황
2. 엔트리와 같은 출처에서 실행된 악성 스크립트가 Android 네이티브 브리지를 호출하는 상황
3. 알림·프로필·작품 URL 또는 API 응답에 조작된 URL과 과도하게 큰 이미지가 포함되는 상황
4. 로그아웃 뒤 쿠키, Web Storage, 토큰, 숨김 WebView가 남는 상황
5. APK 다운로드 사이트 또는 배포 파일이 변조되거나 서로 다른 버전이 제공되는 상황
6. Logcat, 백업, 일반 SharedPreferences, Room DB를 통한 개인정보 노출
7. 외부 앱이 exported 컴포넌트나 변조된 딥 링크를 이용하는 상황

### 2.2 현실적으로 완전히 방어할 수 없는 위협

루팅된 단말에서 공격자가 앱 프로세스에 Frida/Xposed를 주입하거나 화면·키보드·메모리를 통제하는 경우, 앱 내부 암호화만으로 세션 평문 사용 순간까지 완전히 숨길 수 없다. Keystore는 키 추출 난도를 높이지만 이미 장악된 앱 프로세스가 정상 암호화 API를 호출하는 것까지 막는 장치가 아니다.

따라서 루팅·후킹 탐지는 보조 신호로 사용하고, 탐지 우회가 불가능하다고 홍보하지 않는다. 기본 정책은 경고와 민감 기능 제한이며, 탐지 하나만으로 일반 사용자의 로그인을 무조건 차단하는 정책은 별도의 제품 결정 없이 도입하지 않는다.

### 2.3 프로젝트 제약

- 엔트리 GraphQL/API는 URL, 헤더, body 직렬화가 조금만 달라져도 실패할 수 있다. 네트워크 코드를 옮기기 전에 현재 정상 요청의 구조를 비밀값을 제거한 골든 테스트로 고정한다.
- 실제 쿠키, CSRF 토큰, X-Token, 계정 정보는 테스트 fixture, 로그, Git 기록, CI artifact에 넣지 않는다.
- “알림 영구 수집”은 토글로 켜는 부가 기능이 아니라 기본 동작이다. 원본 보존 요구는 유지하되 저장 데이터의 기밀성·삭제 정책·보존 범위를 명시한다.
- 기존 설치자의 업데이트 가능성과 새 전용 릴리스 키 도입은 서로 충돌할 수 있다. 서명 전환은 별도 의사 결정 없이 강행하지 않는다.

## 3. 현재 확인된 보호 조치

- 세션 쿠키, CSRF 토큰, X-Token은 Android Keystore 기반 `EncryptedSharedPreferences`에 저장되며 Keystore 초기화 실패 시 평문으로 폴백하지 않는다.
- 레거시 평문 세션 값은 암호화 저장소로 옮긴 뒤 일반 SharedPreferences에서 제거한다.
- `allowBackup=false`, 백업 및 기기 이전 제외 규칙, cleartext HTTP 차단, 시스템 CA만 신뢰하는 Network Security Config가 적용되어 있다.
- 서비스와 알림 제거 리시버는 외부에 노출되지 않는다. 부팅 리시버만 시스템 브로드캐스트 수신을 위해 노출되어 있다.
- WebView의 혼합 콘텐츠, 파일 접근, 콘텐츠 접근, 제3자 쿠키 및 원격 디버깅을 제한하는 코드가 일부 적용되어 있다.
- 릴리스 빌드는 디버깅 불가, R8 축소·난독화, Android `Log` 호출 제거가 설정되어 있다.
- 계정·알림·프로필 흐름 대부분은 디버그 빌드에서도 출력하지 않는 `PrivacyLog`를 사용한다.
- 다운로드 페이지는 APK SHA-256과 현재 서명 인증서 지문을 안내하며 프로젝트를 “오픈소스”가 아닌 “Source-Available”로 표기한다.

이 보호 조치는 유효하지만 아래 문제 때문에 현재 상태를 “WebView 브리지 제거 완료” 또는 “모든 사용자 데이터 암호화 완료”라고 설명해서는 안 된다.

## 4. 확인된 문제와 우선순위

| 우선순위 | 문제 | 현재 증거 | 위험 | 완료 조건 |
|---|---|---|---|---|
| P0 | 레거시 JavaScript 브리지 잔존 | `MainActivity.kt`에 `addJavascriptInterface()` 8곳 존재 | 동일 출처 XSS 또는 하위 프레임의 스크립트가 네이티브 메서드를 호출할 수 있음 | 프로덕션 코드에서 `addJavascriptInterface` 검색 결과 0건. 가능하면 지속형 WebMessage 브리지도 0건 |
| P0 | 보안 문서와 실제 구현 불일치 | `SECURITY.md`는 전역 브리지를 교체했다고 서술 | 사용자가 실제보다 강한 보호를 기대하고 외부 감사 신뢰도 저하 | 수정 완료 전에는 제한을 공개하고, 완료 뒤 코드 검색·테스트 결과와 일치하게 갱신 |
| P0 | URL 검증 로직 분산 및 문자열 prefix 검사 | `startsWith("https://playentry.org")`, `contains("playentry.org")` 형태가 여러 곳에 존재 | `playentry.org.evil.example` 같은 유사 호스트 또는 예상하지 않은 경로 수용 가능 | 모든 네트워크·WebView·딥 링크 검증이 중앙 URI 정책을 사용하고 유사 호스트 테스트 통과 |
| P0 | 로그아웃이 원자적이지 않음 | `removeAllCookies(null)` 완료를 기다리지 않고 즉시 flush/load 수행 | 쿠키 삭제 전 재접속, Web Storage·숨김 WebView에 세션 상태 잔존 | 서비스 중지, 작업 취소, WebView 파기, WebStorage 삭제, 쿠키 삭제 callback 확인 후 새 로그인 화면 생성 |
| P1 | 기존 공개 APK가 Android Debug 인증서로 서명됨 | 공개 인증서 지문이 Debug 인증서 계보 | 장기 키 관리·접근 통제·배포 신뢰에 부적합 | 업데이트 호환성 결정을 문서화하고 보호된 운영 키 또는 명시적 레거시 유지 정책 확정 |
| P1 | 알림 DB와 원본 payload가 평문 | Room entity에 제목·본문·사용자·URL·`rawPayload` 저장 | 루팅·오프라인 추출 시 알림 기록 전체 노출 | 원본 보존 요구를 지키는 애플리케이션 계층 암호화 또는 검토된 DB 암호화 적용, 마이그레이션 테스트 통과 |
| P1 | 잠금 화면 알림 개인정보 정책 부재 | 알림 builder에서 공개용 대체 내용 또는 visibility 정책이 확인되지 않음 | 잠금 화면에서 댓글·닉네임·작품 정보 노출 | 기본 private 표시와 잠금 화면용 축약 문구 적용, 사용자 설정 및 Android 채널 동작 테스트 |
| P1 | 이미지 다운로드 자원 제한 부족 | 일부 경로에서 응답 전체를 `body.bytes()`로 읽음 | 큰 파일·압축 폭탄·비정상 이미지로 메모리 고갈 가능 | host/MIME/magic 검사와 함께 Content-Length, 실제 읽기 바이트, 픽셀 수, timeout 제한 적용 |
| P1 | 공급망 검증이 동일 사이트 해시에 의존 | APK와 SHA-256이 같은 Vercel 배포에서 제공됨 | 사이트가 함께 변조되면 해시도 교체 가능 | GitHub Release 등 독립 채널의 해시·서명자 지문, CI artifact provenance/SBOM 제공 |
| P1 | 보안 관련 자동화 테스트 부족 | 브리지 부재, 로그아웃, 리다이렉트, URL 우회 테스트가 충분하지 않음 | 회귀로 취약점 재도입 | 정적 grep gate, JVM 정책 테스트, 기기 WebView 테스트, 릴리스 APK 검사 CI 구성 |
| P2 | `security-crypto` alpha 의존성 | `1.1.0-alpha06` 사용 | 장기 유지보수와 향후 폐기 API 대응 부담 | 데이터 마이그레이션을 포함한 교체 계획과 실패 복구 테스트 수립 |
| P2 | 루팅·후킹·변조 신호 없음 | Root/Frida/Xposed/자체 서명 확인 미구현 | 공격 단말에서 탐지·경고 불가 | 우회 가능한 보조 신호임을 명시하고 경고/진단 모드로 도입 |

## 5. 목표 아키텍처

### 5.1 최우선 원칙: 웹 콘텐츠에 지속형 네이티브 권한을 주지 않는다

도메인 허용 목록은 타 출처 공격에는 유효하지만, 엔트리 자체에 동일 출처 XSS가 발생하면 충분하지 않다. 같은 출처에서 실행된 악성 스크립트도 허용된 메시지 채널을 호출할 수 있기 때문이다.

목표 구조는 다음과 같다.

1. 로그인 UI만 인증 전용 WebView에서 표시한다.
2. 앱이 필요한 순간에만 `evaluateJavascript(script, callback)`으로 값을 요청한다.
3. 결과는 Android가 시작한 단발성 callback으로만 받고 페이지가 임의 시점에 호출할 수 있는 영구 객체를 만들지 않는다.
4. 알림 폴링은 가능한 범위에서 네이티브 OkHttp로 실행한다.
5. 작품·프로필 DOM 보조 추출이 반드시 필요하면 제한된 페이지에서 단발 실행하고 결과 스키마, 길이, URL을 네이티브에서 재검증한다.
6. 알림 링크는 인증 WebView에 그대로 열지 않는다. 검증된 ID로 앱이 URL을 재구성하거나, 세션을 공유하지 않는 외부 브라우저로 연다.

nonce, 메시지 타입 이름 변경, 난독화는 동일 출처 XSS에 대한 주 방어가 아니다. 페이지 안의 공격 스크립트가 값을 관찰할 수 있기 때문이다.

### 5.2 WebView 구성 단일화

`HardenedEntryWebViewFactory` 또는 동등한 단일 구성 지점을 만든다.

- JavaScript는 필요한 WebView에서만 활성화한다.
- `allowFileAccess=false`, `allowContentAccess=false`, mixed content 차단, 제3자 쿠키 차단을 기본값으로 강제한다.
- Safe Browsing 사용 가능 여부와 오류 처리를 확인한다.
- 모든 최상위 탐색은 `Uri`로 파싱해 HTTPS와 정확한 host를 검사한다.
- 허용 host는 기능별 최소 집합으로 나눈다. `playentry.org` 전체 하위 도메인을 무조건 허용하지 않는다.
- `onReceivedSslError`에서 우회 진행을 금지한다.
- renderer 종료 시 해당 인스턴스를 폐기하고 새로 만든다.
- JavaScript alert, 파일 선택, 위치, 카메라, 마이크 권한 요청은 기본 거부한다.
- 다운로드와 새 창 생성은 기본 차단하고 필요한 외부 URL만 검증 후 브라우저로 넘긴다.

### 5.3 URL·입력 정책 단일화

현재 `EntryOriginPolicy`를 확장하거나 새로운 `EntryUrlPolicy`를 만든다.

- 문자열 prefix/substring 비교를 금지한다.
- scheme은 `https`, host는 정확한 allowlist, port는 기본 443만 허용한다.
- 사용자 정보(`user:pass@host`), 역슬래시, 제어 문자, 혼합 인코딩, IDN 혼동, 비정상 port를 거부한다.
- 딥 링크는 서버가 준 완성 URL을 신뢰하지 않고 가능한 경우 검증된 project/user/topic ID로 재구성한다.
- 이미지 host는 HTML 탐색 host와 별도 allowlist를 사용한다.
- 네트워크 리다이렉트는 기본 비활성화하고, 필요 시 매 hop마다 scheme/host를 재검증하며 인증 헤더를 다른 host로 전달하지 않는다.

필수 회귀 사례에는 `https://playentry.org.evil.example`, `https://evil.example/?playentry.org`, `http://playentry.org`, `https://user@playentry.org`, 허용되지 않은 port 및 인코딩된 우회 문자열을 포함한다.

### 5.4 세션 생명주기

세션은 다음 상태 머신으로 관리한다.

`LoggedOut -> LoginWebViewActive -> TokenCaptured -> PollingActive -> LogoutInProgress -> LoggedOut`

- 각 상태 전환은 한 소유자만 수행한다.
- 토큰과 쿠키 평문은 가능한 짧게 메모리에 유지한다.
- 백그라운드 서비스에 필요한 값만 전달하고 Intent extra, SavedState, 예외 메시지에 넣지 않는다.
- 로그아웃 시작 즉시 신규 요청을 차단하고 in-flight 요청을 취소한다.
- 모든 폴러·coroutine·handler callback·숨김 WebView를 종료한다.
- `WebStorage.getInstance().deleteAllData()`를 수행한다.
- `removeAllCookies(callback)`의 성공 결과를 기다린 뒤 `flush()`한다.
- 관련 WebView에서 `stopLoading()`, `loadUrl("about:blank")`, history/cache/form data 정리 후 `destroy()`한다.
- 암호화 세션 저장소와 레거시 키를 모두 지우고 삭제 완료 뒤 새 로그인 WebView를 만든다.
- 실패 시 로그인 상태로 복구하지 말고 fail-closed 상태와 재시도 안내를 표시한다.

### 5.5 알림 영구 보관 데이터

알림 영구 보관은 기본 동작으로 유지한다. 보관본은 제목, 본문, 원본 시각, 실제 사용자 프로필, 실제 작품 썸네일 URL, 원본 payload를 가능한 한 원형으로 보존하되 다음을 적용한다.

- `rawPayload`를 무제한 문자열로 저장하지 않는다. 최대 크기와 JSON 스키마를 정하고 필요 없는 세션·헤더 필드를 제거한다.
- 원본 내용 필드는 Keystore 보호 데이터 키를 이용한 AES-GCM 애플리케이션 계층 암호화를 우선 검토한다.
- 검색·분류에 필요한 최소 비민감 인덱스만 평문으로 남긴다. 닉네임도 개인정보로 취급한다.
- 암호문에는 schema version, nonce/IV, key version, authentication tag를 함께 관리한다.
- 키 무효화·앱 재설치·손상된 행을 처리하는 복구 정책을 만든다. 복호화 실패 시 평문 폴백하지 않는다.
- 기존 평문 DB의 마이그레이션은 transaction, 중단 재개, 롤백 및 완료 후 잔여 평문 검사까지 테스트한다.
- 사용자가 앱 데이터 삭제 또는 로그아웃 시 알림 기록을 유지할지 삭제할지는 제품 정책으로 명시한다. 세션 삭제와 기록 삭제를 암묵적으로 같은 동작으로 만들지 않는다.

### 5.6 알림 표시 개인정보

- 알림 채널과 builder에 `VISIBILITY_PRIVATE`를 기본 적용한다.
- 잠금 화면용 `publicVersion`에는 “새 Entify 알림”처럼 계정·본문·작품명이 없는 문구를 사용한다.
- 민감 내용 미리보기 설정을 제공하더라도 안전한 값이 기본이어야 한다.
- PendingIntent는 immutable을 유지하고 명시적 컴포넌트만 사용한다.
- 알림 분류 결과와 표시 아이콘이 일치하는 단위 테스트를 추가한다. 작품 좋아요는 좌측 하트 아이콘, 우측 실제 작품 URL 썸네일이라는 제품 규칙도 회귀 테스트로 고정한다.

### 5.7 네트워크와 이미지 처리

- 엔트리 API의 URL·헤더·body 형식은 골든 fixture 테스트가 통과하는 범위 밖에서 변경하지 않는다.
- 토큰 포함 요청은 자동 cross-host redirect를 허용하지 않는다.
- connect/read/call timeout과 동시 요청 상한을 설정한다.
- 응답 `Content-Type`, 선언 길이, 실제 읽은 길이, 이미지 magic byte, 디코딩 전 bounds를 모두 검사한다.
- 큰 이미지는 전체 byte array로 읽기 전에 상한을 적용하고 샘플링 디코딩한다.
- 인증 헤더를 이미지 CDN 등 불필요한 host에 보내지 않는다.
- 인증서 고정은 현재 권장 기본안이 아니다. Entify가 엔트리 인증서 수명 주기를 통제하지 못해 정상 인증서 교체 때 앱 전체가 중단될 수 있고, 루팅·후킹 공격에 대한 절대 방어도 아니다. 도입하려면 백업 핀, 만료, 원격 복구, 장애 대응 계획을 먼저 마련한다.

### 5.8 루팅·후킹·변조 대응

P0/P1 작업이 끝난 뒤 다음을 P2로 적용한다.

- 설치된 APK의 서명 인증서 지문 자체 확인
- `ApplicationInfo.FLAG_DEBUGGABLE`, debugger 연결, 흔한 root artifact, 의심스러운 hooking class·mount 상태 등 복수 신호 수집
- 단일 신호로 계정을 차단하지 않고 위험 안내와 진단 코드 제공
- 민감 기능을 수행할 때 신호를 다시 확인하되 개인정보가 포함된 원격 telemetry는 기본 수집하지 않음
- 탐지 로직이 우회 가능하며 정상 커스텀 ROM을 오탐할 수 있음을 문서에 공개

Play Integrity는 Play 배포 및 Google 서비스 의존성, 서버 검증 구조가 필요하므로 직접 APK 배포만 유지하는 현재 구조에서는 즉시 적용 대상으로 보지 않는다.

## 6. 단계별 실행 계획

### Phase 0 — 기준선 고정

- [ ] 현재 정상 로그인, 토큰 추출, SELECT_TOPICS, 댓글 조회, 팔로우·공유·좋아요 분류 흐름을 기록한다.
- [ ] 실제 비밀값을 `<COOKIE>`, `<CSRF>`, `<X_TOKEN>`으로 치환한 API 요청 골든 fixture를 만든다.
- [ ] `testDebugUnitTest`, `lintDebug`, `assembleRelease` 결과를 기준선으로 저장한다.
- [ ] 현재 공개 APK 해시와 서명 인증서 지문을 별도 기록한다.
- [ ] bridge 호출 종류와 payload 스키마를 전수 목록화한다.

### Phase 1 — WebView 신뢰 경계 축소

- [ ] `MainActivity.kt`의 8개 `addJavascriptInterface()` 사용을 기능별로 제거한다.
- [ ] 토큰·DOM 결과 수신을 단발 `evaluateJavascript` callback으로 전환한다.
- [ ] 장기 알림 폴러를 WebView 주입 스크립트에서 네이티브 요청으로 이전한다.
- [ ] 기존 `EntryWebViewManager`의 지속형 message listener도 필요성을 재평가하고 제거를 목표로 한다.
- [ ] 사용되지 않는 `login(username, password)` 자동 입력 경로는 삭제한다. 유지 시 자격 증명을 WebView JavaScript에 전달하지 않는 구조로 다시 설계한다.
- [ ] 모든 WebView를 중앙 hardened factory로 생성한다.
- [ ] 인증 WebView에서 알림 링크와 사용자 콘텐츠를 직접 열지 않는다.
- [ ] `rg "addJavascriptInterface" app/src/main` 결과 0건을 CI gate로 만든다.

### Phase 2 — URL, 로그아웃, 네트워크 보강

- [ ] 모든 `startsWith/contains("playentry.org")` 검사를 중앙 URI 정책으로 교체한다.
- [ ] 기능별 host/path allowlist와 악성 URL 테스트를 추가한다.
- [ ] 원자적 로그아웃 coordinator와 기기 테스트를 구현한다.
- [ ] cross-host redirect 차단 및 인증 헤더 전달 테스트를 추가한다.
- [ ] 이미지 크기·픽셀·MIME·magic byte 제한을 적용한다.
- [ ] 잠금 화면 알림 redaction을 적용한다.

### Phase 3 — 저장 데이터와 로그 보강

- [ ] 알림 DB 민감 필드 및 원본 payload 암호화 방식을 결정한다.
- [x] 평문 DB에서 암호화 형식으로의 재시도 가능한 점진 이전을 구현한다. 트랜잭션 중단·실기기 업데이트 검증은 별도 계측 테스트 대상으로 남긴다.
- [ ] `rawPayload` 허용 키와 최대 길이를 정의한다.
- [ ] `android.util.Log`, `println`, exception message 전수 검사를 CI에 추가한다.
- [ ] Debug/Release APK 양쪽에서 토큰·쿠키·댓글 원문이 Logcat에 나오지 않는지 기기 테스트한다.

### Phase 4 — 서명과 공급망

- [ ] 운영 서명 키를 오프라인에서 생성하고 접근자·백업·복구 절차를 정한다.
- [ ] 기존 Debug 인증서 설치자 업데이트 정책을 결정한다.
- [ ] Android 7/8 사용자를 포함한 키 전환 호환성을 실제 기기에서 검증한다.
- [ ] 호환되지 않으면 기존 package를 유지할지, 새 applicationId로 안전한 재설치를 안내할지 명시적으로 결정한다.
- [ ] CI에서 잠금된 의존성, dependency verification, SBOM, APK 해시, signer 지문, 테스트 결과를 생성한다.
- [ ] GitHub Release에 APK, SHA-256 파일, SBOM, 변경 기록을 함께 게시한다.
- [ ] Vercel 사이트는 GitHub Release와 교차 검증할 수 있게 링크한다.

### Phase 5 — 선택적 런타임 방어와 외부 검토

- [ ] 자체 서명 확인 및 root/hooking 위험 신호를 경고 모드로 적용한다.
- [ ] MobSF 정적 분석과 실제 단말 동적 테스트를 수행한다.
- [ ] 외부 검토자가 재현할 수 있는 빌드 절차와 테스트 결과를 공개한다.
- [ ] 공개 전 보안 회귀 체크리스트에 독립 검토자 1인의 확인을 받는다.

## 7. 테스트 계획과 릴리스 차단 조건

### 7.1 자동 테스트

- URL 정책 단위 테스트: 정상 root/subdomain, 유사 host, HTTP, userinfo, port, encoding, redirect
- 알림 분류 테스트: tag/template/category 불일치, silent 오분류, 작품 좋아요 아이콘·썸네일 매핑
- 저장소 테스트: Keystore 실패 시 fail-closed, 레거시 평문 제거, DB 마이그레이션 중단·재시도
- 로그아웃 기기 테스트: 쿠키, localStorage, sessionStorage, IndexedDB, cache, service, coroutine, 숨김 WebView 잔존 여부
- WebView 테스트: iframe·동일 출처 스크립트가 네이티브 함수를 호출할 수 없는지 확인
- 네트워크 테스트: cross-host redirect에서 인증 헤더 미전달, oversized body 중단
- 빌드 검사: release debuggable=false, backup=false, cleartext=false, 브리지 0건, 민감 문자열 로그 0건

### 7.2 수동 테스트

- Android 7, 8, 13, 14 이상에서 신규 설치·업데이트·로그인·재부팅·로그아웃
- 정상 엔트리 인증서 교체 또는 네트워크 오류 상황에서 안전한 실패 안내
- 잠금 화면, 알림 확장, 알림 클릭, 외부 브라우저 전환
- 배터리 최적화, 서비스 재시작, 네트워크 전환 중 중복 알림과 세션 재사용 여부
- 대형 이미지, 깨진 이미지, HTML 응답을 이미지 URL로 위장한 경우

### 7.3 릴리스 차단 조건

다음 중 하나라도 해당하면 새 공개 APK를 배포하지 않는다.

- 프로덕션 코드에 `addJavascriptInterface`가 남아 있음
- 문서가 실제 코드보다 강한 보안을 주장함
- 로그인·로그아웃·기존 설치 업데이트 회귀 테스트 실패
- APK와 사이트의 versionName, versionCode, SHA-256 불일치
- 서명 인증서가 승인된 지문과 다름
- 실제 계정 정보, 쿠키, 토큰, 알림 원문이 로그 또는 빌드 artifact에 포함됨
- Vercel 다운로드 URL과 GitHub Release 파일의 해시가 다름

## 8. 다운로드 사이트 수정 계획

사이트는 상업용 소프트웨어 문서처럼 간결하고 진지한 표현을 유지하며 이모지와 과장된 안전 문구를 사용하지 않는다.

### 8.1 첫 화면

- 제품명, 비공식 독립 클라이언트, Source-Available 상태를 즉시 표시한다.
- 다크 테마를 기본으로 하고 우측 상단에는 라이트 테마 전환 버튼 하나만 둔다.
- 설치 버튼 바로 아래에 설치 방법을 배치한다.
- 설치 방법 아래에 “보안 및 개인정보 질문” 바로가기를 배치한다.
- 같은 클릭으로 탭이 두 개 열리는 중복 핸들러가 없는지 확인한다.

### 8.2 보안 및 개인정보 질문

FAQ 대신 “보안 및 개인정보 질문”이라는 제목을 사용한다. 최소한 다음 질문에 답한다.

1. Play 스토어 앱이 아닌데 왜 설치해도 되는가?
2. 앱이 기기의 모든 개인정보를 볼 수 있는가?
3. 왜 엔트리 쿠키와 토큰이 필요한가?
4. 로그인 비밀번호를 저장하는가?
5. 쿠키와 토큰은 어디에 저장되는가?
6. 엔트리 페이지에 XSS가 있으면 Entify도 위험한가?
7. 알림 원본은 무엇을 얼마나 오래 저장하는가?
8. 로그아웃 또는 앱 삭제 시 무엇이 삭제되는가?
9. APK가 변조되지 않았는지 어떻게 확인하는가?
10. Source-Available과 오픈소스의 차이는 무엇인가?

### 8.3 반드시 사용할 정직한 문구

> Entify는 엔트리 페이지를 완전히 신뢰하지 않는 구조를 목표로 합니다. 웹 페이지의 내용과 링크는 신뢰할 수 없는 입력으로 검증하며, 페이지가 Android 네이티브 기능을 지속적으로 호출할 수 있는 JavaScript 브리지를 제거하는 작업을 진행하고 있습니다.

브리지 제거가 끝나기 전에는 마지막 문장을 “현재 일부 레거시 WebView 흐름에는 JavaScript 브리지가 남아 있으며, 다음 보안 릴리스에서 제거할 예정입니다”로 표시한다.

다음 표현은 금지한다.

- 금융권 수준으로 완벽하게 안전합니다.
- 개인정보 탈취는 불가능합니다.
- 엔트리 공식 앱과 동일한 보안입니다.
- 모든 데이터가 암호화되어 있습니다.
- 보안 감사를 통과했습니다. 단, 실제 독립 감사를 통과한 경우는 제외한다.

### 8.4 배포 정보 표시

- 앱 버전과 versionCode
- APK SHA-256
- 서명 인증서 SHA-256과 현재 키가 레거시 Debug 계보인지 운영 키인지
- GitHub Release의 동일 파일 및 checksum 링크
- 보안 변경 내역과 알려진 제한 링크
- APK 다운로드 MIME, Content-Disposition, no-sniff, CSP 및 필요한 보안 헤더

## 9. APK 빌드 및 배포 절차

### 9.1 배포 전

1. 작업 트리가 의도한 변경만 포함하는지 확인한다.
2. 버전을 한 곳에서 올리고 README, 사이트, changelog가 이를 참조하도록 가능하면 자동화한다.
3. 다음을 실행한다.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleRelease
```

4. `apksigner verify --verbose --print-certs`로 signature scheme, debuggable 여부, signer 지문을 검사한다.
5. `Get-FileHash -Algorithm SHA256`으로 APK 해시를 생성한다.
6. 승인된 APK 한 개를 `web/app-release.apk`와 호환 URL이 필요할 때만 `web/Entify.apk`에 동일하게 반영한다.
7. 두 파일의 해시가 완전히 같은지 확인한다.
8. 사이트의 버전, 해시, signer 설명을 자동 또는 한 번의 변경으로 갱신한다.

### 9.2 GitHub Release

- tag와 versionName을 일치시킨다.
- APK, `.sha256`, SBOM, 릴리스 노트를 게시한다.
- 수정된 보안 항목과 남은 제한을 분리해서 적는다.
- 보안 수정 전 APK를 최신으로 오인하지 않도록 prerelease/stable 상태를 정확히 지정한다.

### 9.3 Vercel

1. `web/.vercel/project.json`과 `vercel project inspect`로 대상 프로젝트를 확인한다.
2. preview에 배포해 설치 버튼, 테마, 펼침 모션, 모바일 레이아웃, 단일 탭 열림을 검사한다.
3. preview APK 해시와 로컬 승인 APK 해시를 비교한다.
4. production으로 승격한다.
5. `https://entify-download.vercel.app/`와 실제 APK 응답을 다시 받아 상태 코드, 헤더, 파일 해시를 검증한다.
6. 최신 production alias가 정상임을 확인한 뒤에만 충돌하는 구버전 deployment를 제거한다.
7. 제거 대상 URL을 명시적으로 확인하고 최신 production deployment는 삭제하지 않는다.

### 9.4 배포 후 확인

- [ ] 공식 URL HTTP 200
- [ ] 다운로드는 한 번만 시작되고 새 탭도 한 개만 열림
- [ ] 다운로드 APK SHA-256이 사이트·GitHub Release와 동일
- [ ] APK signer 지문이 공개 값과 동일
- [ ] Android 신규 설치 성공
- [ ] 이전 공개 버전 위 업데이트 성공 또는 비호환 정책 안내 확인
- [ ] 로그인, 알림 수신, 프로필·작품 썸네일, 영구 로그, 로그아웃 정상

## 10. GitHub 문서 정정 계획

| 파일 | 정정 내용 |
|---|---|
| `README.md` | 위협 모델, WebView 브리지 제거 상태, 세션과 알림 DB의 암호화 범위, 빌드·검증 링크, 정확한 최신 버전 반영 |
| `SECURITY.md` | 실제 코드와 일치하는 현재 보호/미완료 항목, 동일 출처 XSS 한계, 루팅 단말 한계, 지원 버전 및 신고 절차 명시 |
| `PRIVACY.md` | 쿠키·CSRF·X-Token의 목적, WebView CookieManager 사용, 알림 원본·프로필·작품 URL 저장, 보존·삭제 기준, 외부 전송 여부 명시 |
| `docs/INSTALLATION.md` | 외부 출처 설치 경고의 의미, SHA-256과 signer 지문을 각각 검증하는 방법, 불일치 시 중단 절차 추가 |
| `docs/RELEASE_SIGNING.md` | Debug 계보 유지 이유, 운영 키 전환 시 구형 Android 업데이트 한계, 키 보관·복구·폐기 정책 추가 |
| `THIRD_PARTY_LICENSES.md` | 실제 dependency와 버전 대조, 누락된 라이선스 확인 |
| `LICENSE` | Source-Available 명칭 유지. README·사이트에서 “오픈소스”로 오기하지 않도록 검토 |
| 신규 `CHANGELOG.md` | 버전별 보안 수정, 호환성 변화, 알려진 제한 기록 |
| 신규 `docs/THREAT_MODEL.md` | 자산, 공격자, 신뢰 경계, 보안 목표·비목표, 데이터 흐름 기록 |

문서 정정은 구현보다 먼저 과장된 표현을 제거하고, 구현 완료 뒤 완료 사실과 테스트 증거를 추가하는 두 단계로 진행한다.

## 11. 서명 키 전환 의사 결정

현재 `legacy` 모드는 기존 설치자와 업데이트 호환성을 위해 Debug 인증서를 사용한다. `production` 모드에 새 키를 넣는 것만으로는 기존 APK 위에 항상 업데이트할 수 있는 것이 아니다.

결정 전에 다음을 검증한다.

1. 현재 공개 APK signer와 보유한 기존 키가 정확히 일치하는가?
2. Android 7/8을 포함한 지원 범위에서 키 회전 또는 lineage가 업데이트를 허용하는가?
3. 허용하지 않는 버전의 사용자를 위해 기존 키 업데이트를 병행할 것인가?
4. 새 applicationId로 재출시할 경우 알림 기록과 세션을 어떻게 이전하거나 포기할 것인가?
5. 운영 키 분실·유출 시 복구 절차는 무엇인가?

이 결정이 끝나기 전에는 사이트에 “전용 운영 키로 서명됨”이라고 표시하지 않는다. 현재는 레거시 Debug 인증서 계보라는 사실과 정확한 지문을 공개한다.

## 12. 완료 정의

이번 강화 작업은 다음 조건을 모두 만족할 때 완료로 본다.

- 레거시 및 지속형 JavaScript-to-native 브리지 제거 또는 불가피한 예외의 독립 보안 검토 완료
- 모든 URL·redirect·이미지 입력의 중앙 정책 적용
- 원자적 로그아웃과 세션 잔존 테스트 통과
- 영구 알림 보관 데이터 보호 및 마이그레이션 테스트 통과
- 잠금 화면 개인정보 축약 표시 적용
- 릴리스 서명 정책과 공급망 검증 절차 확정
- 전체 자동·수동 회귀 테스트 통과
- APK, Vercel 사이트, GitHub Release, README, SECURITY, PRIVACY의 버전·해시·설명이 서로 일치
- 알려진 한계와 루팅 단말의 위험을 숨기지 않고 공개

## 13. 참고 기준

- OWASP MASVS: <https://mas.owasp.org/MASVS/>
- OWASP MASTG: <https://mas.owasp.org/MASTG/>
- Android WebView native bridge 위험: <https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges>
- Android Network Security Configuration: <https://developer.android.com/privacy-and-security/security-config>
- Android 앱 서명: <https://developer.android.com/studio/publish/app-signing>

