# 릴리스 서명 운영 정책

Entify의 릴리스 빌드는 두 가지 서명 모드를 명시적으로 구분합니다.

- `legacy`(기본값): 기존 공개 APK와 같은 디버그 인증서를 사용합니다. 이미 설치한 사용자가 v1.x 업데이트를 계속 받을 수 있도록 한시적으로 유지하는 호환 모드입니다.
- `production`: 별도로 보관한 Entify 전용 릴리스 키를 사용합니다. 네 가지 필수 값 중 하나라도 없으면 빌드가 즉시 실패하며 디버그 키로 자동 대체되지 않습니다.

운영 키는 저장소, APK, CI 로그에 포함하지 않습니다. 키 저장소와 비밀번호는 서로 다른 보안 저장소에 보관하고, 오프라인 복구 사본과 접근 기록을 유지합니다.

## 운영 키로 빌드

다음 값을 환경 변수 또는 로컬 Gradle 속성으로 설정합니다.

```text
ENTIFY_SIGNING_MODE=production
ENTIFY_RELEASE_STORE_FILE=<keystore path>
ENTIFY_RELEASE_STORE_PASSWORD=<store password>
ENTIFY_RELEASE_KEY_ALIAS=<key alias>
ENTIFY_RELEASE_KEY_PASSWORD=<key password>
```

그 후 `gradlew.bat assembleRelease`를 실행합니다. 릴리스 빌드는 APK Signature Scheme v1, v2, v3, v4를 활성화합니다.

## 전환 원칙

Android는 서명자가 다른 APK를 기존 앱 위에 설치할 수 없습니다. 따라서 현재 사용자에게 인플레이스 업데이트를 제공하는 동안에는 `legacy`가 필요합니다. 전용 키로 전환할 때는 다음 중 하나를 공개적으로 선택하고 사전 고지합니다.

1. 새 애플리케이션 ID로 병행 배포하고 설정 이전 절차를 제공한다.
2. 기존 앱 삭제 후 재설치가 필요한 메이저 버전으로 전환한다.

전환 전후 APK의 인증서 지문과 SHA-256을 릴리스 문서 및 다운로드 사이트에 함께 게시합니다. 운영 키를 생성하기 전에는 `production` 모드로 공개 빌드를 만들지 않습니다.
