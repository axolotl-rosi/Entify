# 🔔 Entify

> **엔트리(PlayEntry) 실시간 백그라운드 푸시 알림 및 통계 매니저**

Entify는 엔트리 공식 웹/앱에서 실시간으로 발생하는 알림(댓글, 답글, 좋아요, 팔로우, 리메이크, 공유, 시스템 공지 등)을 백그라운드 포그라운드 서비스 및 GraphQL API를 통해 10초 주기로 감지하고, 풍부한 미디어 썸네일과 함께 시스템 상단 푸시 알림으로 전달해 주는 Android 소스 공개(Source-Available) 애플리케이션입니다.

현재 배포 버전은 **v1.2.0 (versionCode 4)**입니다. Entify는 엔트리와 독립적으로 개발된 비공식 클라이언트입니다.

- [설치 페이지](https://entify-download.vercel.app/)
- [보안 구조와 취약점 신고](SECURITY.md)
- [보안 강화 및 배포 인수인계 계획](docs/SECURITY_HARDENING_HANDOFF.md)
- [위협 모델과 잔여 위험](docs/THREAT_MODEL.md)
- [개인정보·세션 처리 설명](PRIVACY.md)
- [APK 설치와 해시 검증](docs/INSTALLATION.md)
- [릴리스 서명 운영 정책](docs/RELEASE_SIGNING.md)

---

## ✨ 핵심 기능 (Features)

1. **실시간 백그라운드 푸시 알림 (Foreground Data-Sync Service)**
   - 앱을 닫거나 스마트폰 화면이 꺼진 상태에서도 WakeLock과 포그라운드 서비스를 통해 주기적으로 알림을 감지합니다.
   - 프로필 아바타, 작품 썸네일, 감정 뱃지(❤️, 🤖, ⚠️)가 적용된 풍부한 알림을 제공합니다.

2. **알림 감시 마스터 토글 (Master Monitoring Toggle)**
   - 알림 감시 자체를 켜고 끌 수 있는 메인 스위치를 제공합니다. 감시가 꺼져 있을 때는 네트워크 폴링을 즉시 중단합니다.

3. **스와이프 지움 기억 (Swipe Dismiss Tracking)**
   - 사용자가 상단 알림 바에서 직접 스와이프하여 지운 알림은 앱이 다시 시작되거나 백그라운드에서 재감지되어도 다시 울리지 않습니다.
   - 단, 사용자가 직접 밀어서 지운 것이 아닌 비정상 종료나 기기 재부팅 시에는 알림이 안전하게 보존됩니다.

4. **유저별 알림 종류 맞춤 필터링 (Per-User Category Filter)**
   - 특정 사용자의 알림을 공격적으로 "차단"하는 표현 대신, 사용자마다 피드에서 숨길 알림 종류(`댓글`, `좋아요`, `팔로우`, `공유`)를 선택할 수 있습니다.
   - 전체 숨기기 또는 종류별 숨기기를 원클릭으로 설정할 수 있습니다.

5. **방해금지 시간대 설정 (Do Not Disturb - Quiet Hours)**
   - 수면 시간이나 집중 시간(예: 23:00 ~ 07:00)을 설정하면, 해당 시간대 동안은 백그라운드 알림 감시를 일시 정지하여 배터리를 아끼고 방해받지 않습니다.

6. **영구 알림 보관함 & 통계 (Permanent Archive & Stats)**
   - 피드에서 일반 알림을 비우더라도, 수신된 모든 원본 알림은 로컬 Room SQLite DB에 영구 보존됩니다.
   - 보관함 검색, 카테고리별 통계, 즐겨찾기 보관 기능을 지원합니다.

7. **Vercel 원클릭 웹 다운로드 사이트 지원**
   - 구글 플레이 스토어 없이도 Vercel을 통해 모바일 웹 브라우저에서 직접 원클릭으로 최신 APK를 다운로드하고 설치할 수 있는 웹사이트가 내장되어 있습니다.

---

## 🏗️ 아키텍처 및 기술 스택

- **UI**: Jetpack Compose, Material 3, Material Icons Extended
- **아키텍처**: MVVM Pattern, Kotlin Coroutines & Flow
- **로컬 데이터베이스**: Room Persistence Library (SQLite), SharedPreferences, Android Keystore 기반 암호화 세션 저장소
- **네트워크**: OkHttp 4, PlayEntry GraphQL API (`SELECT_TOPICS`, `project`, `userProfile`), Headless WebView Token Extraction
- **이미지 로딩**: Coil Compose, AndroidSVG
- **시스템 연동**: Android Foreground Service (`dataSync`), BroadcastReceiver (`BootReceiver`, `NotificationDismissReceiver`), PowerManager WakeLock

---

## 📁 프로젝트 구조

```
Entify/
├── app/
│   ├── src/main/java/com/example/entify_again/
│   │   ├── MainActivity.kt                      # 메인 진입점, 토큰 추출, 네비게이션
│   │   ├── classifier/                          # 알림 분류 로직
│   │   ├── data/
│   │   │   ├── db/EntifyDatabase.kt             # Room DB 정의
│   │   │   ├── entity/NotificationEntity.kt     # 알림 및 아카이브 엔티티
│   │   │   └── repository/                      # 알림 Repository
│   │   ├── receiver/
│   │   │   ├── BootReceiver.kt                  # 재부팅 복구 리시버
│   │   │   └── NotificationDismissReceiver.kt   # 스와이프 지움 감지 리시버
│   │   ├── service/
│   │   │   └── EntryPollingForegroundService.kt # 10초 주기 백그라운드 감시 서비스
│   │   ├── ui/
│   │   │   ├── screen/                          # Compose 화면 (피드, 보관함, 필터, 설정)
│   │   │   ├── theme/                           # 머티리얼3 테마 및 다크 모드
│   │   │   └── viewmodel/                       # ViewModels
│   │   └── util/
│   │       ├── PreferenceManager.kt             # 설정, DND, 필터, 중복 방지 저장소
│   │       ├── SecureSessionStore.kt             # Keystore 기반 쿠키·토큰 암호화 저장소
│   │       ├── UserProfileFetcher.kt            # 엔트리 프로필 및 프로젝트 메타 GraphQL 추출
│   │       └── EntryTemplateFormatter.kt        # 알림 템플릿 파서
│   └── build.gradle.kts                         # 릴리즈 서명 및 의존성 설정
├── web/
│   ├── index.html                               # Vercel 원클릭 배포 및 설치 매니저 페이지
│   └── vercel.json                              # Vercel MIME 라우팅 설정
├── README.md
└── THIRD_PARTY_LICENSES.md                      # 서드파티 오픈소스 고지사항
```

---

## 🚀 빌드 및 실행 가이드

### 개발 환경 요구 사항
- Android Studio Hedgehog (2023.1.1) 이상
- JDK 11 or 17
- Android SDK 34 (Android 14) / Min SDK 24 (Android 7.0)

### 1. 디버그 빌드
```bash
./gradlew assembleDebug
```
빌드된 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

### 2. 릴리즈 배포용 APK 빌드 (웹 배포용)
기본 `legacy` 모드는 기존 설치본과의 업데이트 호환성을 위해 초기 인증서를 유지합니다. 전용 운영 키를 쓰는 `production` 모드는 필수 환경 변수가 하나라도 없으면 빌드에 실패합니다. 저장소에는 서명 키나 비밀번호를 커밋하지 않습니다:
```bash
./gradlew assembleRelease
```
생성된 APK 경로:
```
app/build/outputs/apk/release/app-release.apk
```

빌드 산출물이 서명되었는지는 Android SDK의 `apksigner verify --print-certs`로 확인하세요. 전환 방법과 키 보관 원칙은 [릴리스 서명 운영 정책](docs/RELEASE_SIGNING.md)을 따릅니다.

---

## 🌐 Vercel 웹 설치 매니저 배포 방법

`web/` 디렉토리에 정적 설치 웹사이트가 마련되어 있습니다.

1. **APK 복사**:
   ```bash
   cp app/build/outputs/apk/release/app-release.apk web/app-release.apk
   ```
2. **Vercel 배포**:
   - [Vercel](https://vercel.com)에 로그인한 뒤, `web/` 디렉토리를 연결하여 배포하거나 CLI를 사용합니다:
   ```bash
   cd web
   vercel --prod
   ```
3. SHA-256 값을 `web/index.html`의 검증 안내와 일치시키고, 배포 뒤 실제 다운로드 파일의 해시와 보안 응답 헤더를 다시 확인합니다.

---

## 🔐 세션·쿠키·토큰 보안

- 비밀번호는 Entify가 저장하지 않으며, 사용자는 `https://playentry.org` 공식 로그인 화면에서 로그인합니다.
- 세션 쿠키와 CSRF/x-token은 Android Keystore로 보호되는 암호화 저장소에만 보관합니다.
- 구버전 평문 세션은 앱 시작 시 암호화 저장소로 이전한 뒤 삭제하며, Keystore 오류 시 평문 저장으로 낮추지 않습니다.
- 앱 설정, 데이터베이스와 파일은 Android 클라우드 백업 및 기기 이전에서 제외됩니다.
- 세션을 다루는 WebView는 원격 디버깅, HTTP 혼합 콘텐츠, 제3자 쿠키, 파일 접근을 차단합니다.
- WebView 메시지는 정확한 HTTPS 출처, 최상위 프레임, 현재 페이지 경로와 크기 제한을 모두 통과해야 합니다.
- 화면 캡처와 최근 앱 미리보기를 제한하고, Android 12 이상에서는 다른 앱의 오버레이를 차단하며 가려진 터치를 거부합니다.
- API와 이미지 응답은 처리 전에 크기 상한을 적용하고, 로그에 토큰·쿠키·알림 원문·전체 요청 헤더를 남기지 않습니다.
- 인증 정보는 Entify 중계 서버가 아니라 엔트리 HTTPS API로 기기에서 직접 전송됩니다.

알림 영구 보관함은 검색과 원본 확인을 위해 Room SQLite에 저장합니다. 제목·본문·원본 payload는 Android Keystore 키를 사용하는 AES-GCM 필드 암호화를 적용하고 기존 평문 행은 실행 후 점진적으로 이전합니다. 검색·분류에 필요한 종류, 시각, 사용자 식별자와 이미지 URL 같은 메타데이터는 평문 인덱스로 남으므로 루팅되거나 잠금이 해제된 손상 단말에서 모든 알림 정보가 보호된다고 표현하지 않습니다. 데이터베이스는 백업·기기 이전에서 제외합니다.

세부 데이터 흐름, 위협 모델, 한계와 신고 절차는 [SECURITY.md](SECURITY.md)를 확인하세요. API 호환성에 민감한 요청 URL·헤더·본문 형식은 이번 보안 강화에서 변경하지 않았습니다.

---

## 📤 GitHub 레포지토리 등록 방법

[https://github.com/dashboard](https://github.com/dashboard)에서 새 저장소를 생성하여 본 프로젝트의 소스 코드를 공개할 수 있습니다:

```bash
# 1. 깃 초기화 및 브랜치 설정
git init
git branch -M main

# 2. 파일 추가 및 커밋
git add .
git commit -m "Initial commit: Entify notification client with filtering, DND, and Vercel web installer"

# 3. 원격 저장소 연결 (예: your-username/Entify)
git remote add origin https://github.com/<YOUR_GITHUB_USERNAME>/Entify.git

# 4. 푸시
git push -u origin main
```

---

## 📄 License & Third-Party Notices

- **프로젝트 라이선스**: 본 프로젝트는 **Entify Source-Available License v1.0**을 따릅니다. 공식 배포본의 개인적 이용 및 콘텐츠 제작은 무료이나 무단 상업적 재배포는 제한됩니다. 상세 내용은 [LICENSE](LICENSE)를 참조하세요.
- **제3자 오픈소스 소프트웨어 규정**: 프로젝트에서 사용된 AndroidX, Jetpack Compose, Room, Coil, OkHttp, AndroidSVG 등의 오픈소스 고지 및 Apache 2.0 라이선스 전문은 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)에 상세히 기술되어 있습니다.


