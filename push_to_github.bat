@echo off
chcp 65001 >nul
title Entify GitHub 배포 스크립트

:: 스크립트가 위치한 프로젝트 루트 경로로 정확히 이동
cd /d "%~dp0"

echo ========================================================
echo       Entify 깃허브 오픈소스 자동 등록 및 푸시 매니저
echo ========================================================
echo.

where gh >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [1/3] GitHub CLI(gh)가 설치되어 있습니다.
    echo 자동으로 GitHub에 'Entify' Public 저장소를 만들고 푸시를 진행합니다...
    echo.
    git init
    git branch -M main
    git config user.name >nul 2>nul
    if %ERRORLEVEL% NEQ 0 (
        git config user.name "Entify-Developer"
        git config user.email "entify-developer@users.noreply.github.com"
    )
    git add .
    git commit -m "feat: Entify notification client with DND, swipe-dismiss, per-user filter, and web installer"
    gh repo create Entify --public --source=. --remote=origin --push
    if %ERRORLEVEL% EQU 0 (
        echo.
        echo ========================================================
        echo  🎉 성공! GitHub에 Entify 저장소가 성공적으로 등록되었습니다!
        echo ========================================================
        echo.
        pause
        exit /b 0
    )
    echo.
    echo [안내] gh 명령어 실행 중 오류가 발생했거나 로그인이 필요합니다.
    echo 일반 Git 푸시 방식으로 자동 전환합니다.
    echo.
)

echo [안내] GitHub 사용자 아이디(계정명)를 입력해주세요.
set /p GH_USER="GitHub 계정 아이디: "

if "%GH_USER%"=="" (
    echo 아이디가 입력되지 않았습니다. 취소되었습니다.
    pause
    exit /b 1
)

echo.
echo [2/3] 로컬 Git 커밋 생성 중...
git init
git branch -M main

:: Git 사용자 정보가 없을 경우 자동 보완 (커밋 오류 방지)
git config user.name >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    git config user.name "%GH_USER%"
    git config user.email "%GH_USER%@users.noreply.github.com"
)

git add .
git commit -m "feat: Entify notification client with DND, swipe-dismiss, per-user filter, and web installer"

git remote remove origin >nul 2>nul
git remote add origin https://github.com/%GH_USER%/Entify.git

echo.
echo [3/3] GitHub로 푸시 진행 중...
echo (브라우저 로그인 팝업이 뜨면 'Sign in with your browser'를 눌러 승인하시면 됩니다)
echo.
git push -u origin main

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================================
    echo  🎉 축하합니다! GitHub에 Entify 저장소가 오픈소스로 배포되었습니다!
    echo  주소: https://github.com/%GH_USER%/Entify
    echo ========================================================
) else (
    echo.
    echo ========================================================
    echo [안내] 만약 'Repository not found' 에러가 뜬다면:
    echo 1. https://github.com/new 에 접속하여
    echo 2. Repository name에 'Entify'를 입력하고 'Create repository' 클릭!
    echo 3. 다시 이 스크립트(push_to_github.bat)를 더블클릭하면 즉시 등록 완료됩니다!
    echo ========================================================
)

echo.
pause
