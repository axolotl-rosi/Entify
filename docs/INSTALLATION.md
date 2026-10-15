# APK 설치 및 진위 확인

Entify는 현재 Google Play에 등록되어 있지 않아 APK로 배포합니다. Android의 “알 수 없는 앱” 경고는 Play 스토어 밖의 APK에 공통으로 표시되며, 그 자체가 악성 판정을 뜻하지는 않습니다.

1. https://entify-download.vercel.app/ 에서 APK를 받습니다.
2. 브라우저 또는 파일 앱 한 곳에만 “이 출처의 앱 설치”를 임시 허용합니다.
3. 설치 후 해당 허용을 다시 끕니다.
4. 앱 내부 주소가 https://playentry.org 인지 확인한 뒤 로그인합니다.

기기 전체 보안 검사나 Play Protect를 끄지 마세요. Play Protect가 파일을 악성으로 차단하면 억지로 우회하지 말고 설치를 중단해 주세요.

## SHA-256 확인

Windows에서는 Get-FileHash .\Entify.apk -Algorithm SHA256, macOS/Linux에서는 shasum -a 256 Entify.apk 명령을 사용합니다. 출력값을 다운로드 페이지의 SHA-256과 한 글자씩 비교합니다. 값이 다르면 설치하지 말고 파일을 삭제한 뒤 공식 링크에서 다시 받으세요.

현재 공개 빌드:

- 버전: `1.2.0` (`versionCode 4`)
- APK SHA-256: `B52ECDBE60A7D8124672808FCF4483F11A3FDD3ADF0C8D4D264991C29F6AB703`
- 서명 인증서 SHA-256: `8905D03BAE430503CE1811C9078C96DC5AD2493BD6999688291A4FE209CE776D`

현재 APK는 기존 설치본과의 업데이트 호환성을 위해 초기 배포 인증서 계보를 유지하며 APK Signature Scheme v2로 서명됩니다. 전용 운영 키 전환 계획과 제약은 [릴리스 서명 운영 정책](RELEASE_SIGNING.md)을 확인하세요.

