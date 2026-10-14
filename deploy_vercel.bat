@echo off
chcp 65001 >nul
title Entify Vercel 배포 매니저

:: 스크립트가 위치한 프로젝트 루트 경로로 정확히 이동
cd /d "%~dp0"

echo ========================================================
echo         Entify Vercel 웹 설치 매니저 배포 스크립트
echo   현재 작업 경로: %CD%
echo ========================================================
echo.

:: 1. Node.js & npm 확인
where npm >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [오류] Node.js 및 npm이 설치되어 있지 않습니다.
    echo https://nodejs.org/ 에서 Node.js를 먼저 설치해주세요.
    echo.
    pause
    exit /b 1
)

:: 2. Vercel 명령어 결정 (글로벌 vercel 우선, 없으면 npx vercel 사용)
set VERCEL_CMD=vercel
where vercel >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo [1/4] Vercel CLI 전역 설치 시도 중 (npm install -g vercel)...
    call npm install -g vercel 2>nul
    where vercel >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        echo [안내] 글로벌 설치 대신 npx vercel(무설치 즉시 실행)을 사용합니다.
        set VERCEL_CMD=npx vercel
    ) else (
        echo [1/4] Vercel CLI 글로벌 설치 완료!
    )
) else (
    echo [1/4] Vercel CLI가 준비되어 있습니다.
)

:: 3. APK 빌드 및 web 폴더로 복사
echo [2/4] 배포용 APK (app-release.apk) 빌드 중...
echo (처음 빌드 시 잠시 시간이 걸릴 수 있습니다...)
echo.
call gradlew.bat assembleRelease
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [안내] 릴리즈 빌드 실패 시 디버그 빌드를 확인합니다...
    call gradlew.bat assembleDebug
)

if exist "app\build\outputs\apk\release\app-release.apk" (
    echo.
    echo [3/4] 빌드된 릴리즈 APK를 web 폴더로 복사 중...
    copy /y "app\build\outputs\apk\release\app-release.apk" "web\app-release.apk" >nul
    copy /y "app\build\outputs\apk\release\app-release.apk" "web\Entify.apk" >nul
    echo APK 준비 완료!
) else if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo.
    echo [3/4] 디버그 APK를 web 폴더로 복사 중...
    copy /y "app\build\outputs\apk\debug\app-debug.apk" "web\app-release.apk" >nul
    copy /y "app\build\outputs\apk\debug\app-debug.apk" "web\Entify.apk" >nul
    echo APK 준비 완료!
) else (
    echo.
    echo [안내] APK 빌드 파일이 없어 웹페이지만 우선 배포합니다.
)

:: 4. Vercel 로그인 및 배포
echo.
echo [4/4] Vercel 배포를 시작합니다...
cd web

echo.
echo Vercel 계정 로그인 여부를 확인합니다...
call %VERCEL_CMD% whoami >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [안내] 로그인이 필요합니다. 브라우저가 열리면 Vercel 로그인을 진행해주세요!
    call %VERCEL_CMD% login
)

echo.
echo Vercel 프로덕션 배포 진행 중 (%VERCEL_CMD% --prod)...
echo.
call %VERCEL_CMD% --prod --yes

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo  🎉 축하합니다! Vercel에 Entify 웹사이트 배포가 완료되었습니다!
    echo  위 터미널에 나온 Production 도메인 링크(https://...vercel.app)를
    echo  모바일 기기나 브라우저에서 열어 다운로드를 확인해보세요!
    echo ========================================================
) else (
    echo.
    echo [안내] 배포가 완료되었거나 추가 설정이 필요합니다. 위 터미널 메시지를 확인해주세요.
)

cd ..
echo.
pause
