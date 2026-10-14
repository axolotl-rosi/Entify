// == template_27 (팔로잉) 프로필 추출 ==
// 사용자 프로필 페이지에서 아바타 추출
// 사용: findProfile27().then(console.log)

(() => {
  'use strict';

  // ---------- 공통 유틸 ----------
  const toAbs = (p) => {
    if (!p) return '';
    if (/^https?:\/\//i.test(p)) return p;
    if (p.startsWith('//')) return location.protocol + p;
    if (!p.startsWith('/')) p = '/' + p.replace(/^\/+/, '');
    return location.origin.replace(/\/$/, '') + p;
  };

  // ---------- 실제 추출 로직 ----------
  const extractProfile = () => {
    /** 1. 상태 메시지 기준점 */
    const statusP = [...document.querySelectorAll('p')]
      .find(p =>
        p.textContent &&
        p.textContent.trim().length > 0 &&
        p.closest('div')?.innerText.includes('팔로잉') &&
        p.closest('div')?.innerText.includes('팔로워')
      );

    if (!statusP) {
      console.warn('[findProfile27] 상태 메시지 p 탐색 실패');
      return null;
    }

    /** 2. 프로필 전체 카드 (한 단계 위) */
    const profileCard = statusP.closest('div')?.parentElement;
    if (!profileCard) {
      console.warn('[findProfile27] 프로필 카드 탐색 실패');
      return null;
    }

    /** 3. 진짜 유저 아바타 추출 */
    const avatar = [...profileCard.querySelectorAll('div[style]')]
      .map(el => el.style.backgroundImage)
      .filter(bg =>
        bg.includes('/uploads/') &&
        !bg.includes('/uploads/bm/') &&
        !bg.includes('/uploads/thumb/')
      )
      .map(bg => {
        const m = bg.match(/\/uploads\/[^")]+/);
        return m ? toAbs(m[0]) : null;
      })
      .find(Boolean) || null;

    /** 4. 상태 메시지 (XSS 강력 차단) */
    let status = statusP.textContent || '';

    if (/<script|onerror\s*=|onload\s*=|javascript:|data:|<img|<svg/i.test(status)) {
      status = '[차단된 상태 메시지]';
    }

    status = status.replace(
      /(https?:\/\/[^\s]+)/gi,
      '[링크 차단됨]'
    );

    status = status.slice(0, 200);

    if (!avatar) {
      console.warn('[findProfile27] Avatar not found in profile card');
      return null;
    }

    console.log('[findProfile27] ✅ 구조 반영 최종:', { avatar, status });
    return { avatar, status };
  };

  // ---------- React 렌더링 대기 + 추출 ----------
  window.findProfile27 = () => {
    return new Promise((resolve) => {
      try {
        // URL 검증
        if (!location.href.startsWith('https://playentry.org/profile/')) {
          console.warn('[findProfile27] ❌ Not a profile page:', location.href);
          resolve({ success: false, error: 'wrong_page', avatar: null, status: null });
          return;
        }

        let attempts = 0;
        const maxAttempts = 20;  // 최대 2초 대기 (20 × 100ms)
        const intervalMs = 100;

        const tryExtract = () => {
          attempts++;

          // DOM에 기본 요소가 충분히 있는지 확인
          const body = document.body || document.documentElement;
          const hasBasicContent = body && body.querySelectorAll('*').length > 10;
          const bodyText = body?.textContent || '';
          const hasFollowStats = bodyText.includes('팔로잉') && bodyText.includes('팔로워');

          if (hasBasicContent && hasFollowStats) {
            // React 렌더링 완료, 추출 시도
            const result = extractProfile();
            if (result) {
              console.log('[findProfile27] ✅ Success after', attempts, 'attempts');
              resolve({ success: true, ...result });
              return;
            }
          }

          // 재시도
          if (attempts < maxAttempts) {
            if (attempts % 5 === 0) {
              console.log('[findProfile27] ⏳ Attempt', attempts + '/' + maxAttempts, '- waiting for React...');
            }
            setTimeout(tryExtract, intervalMs);
          } else {
            console.error('[findProfile27] ❌ Timeout after', attempts, 'attempts');
            resolve({ success: false, error: 'dom_timeout', avatar: null, status: null });
          }
        };

        tryExtract();

      } catch (err) {
        console.error('[findProfile27] ❌ Exception:', err.message);
        resolve({ success: false, error: 'exception', avatar: null, status: null });
      }
    });
  };

  // 즉시 실행 (Kotlin에서 호출 시)
  if (window.FIND_PROFILE_27_AUTO_RUN) {
    window.findProfile27().then((result) => {
      try {
        if (window.ProfileBridge && window.ProfileBridge.postMessage) {
          window.ProfileBridge.postMessage(JSON.stringify({
            type: 'PROFILE_RESULT_27',
            success: result.success,
            avatar: result.avatar || '',
            status: result.status || '',
            error: result.error || ''
          }));
        }
      } catch (bridgeErr) {
        console.error('[findProfile27] ❌ Bridge error:', bridgeErr.message);
      }
    });
  }
})();
