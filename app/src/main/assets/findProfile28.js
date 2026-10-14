(() => {
  'use strict';

  const PROJECT_PREFIX = 'https://playentry.org/project/';
  const MAX_ATTEMPTS = 20;
  const INTERVAL_MS = 150;

  const toAbs = (path) => {
    if (!path) return '';
    if (/^https?:\/\//i.test(path)) return path;
    const normalized = path.startsWith('/') ? path : '/' + path.replace(/^\/+/g, '');
    return location.origin.replace(/\/$/, '') + normalized;
  };

  const extractProfile = () => {
    const followBox = [...document.querySelectorAll('div')]
      .find(div =>
        div?.innerText?.includes('팔로잉') &&
        div?.innerText?.includes('팔로워')
      );

    if (!followBox) {
      console.warn('팔로잉/팔로워 영역 못 찾음');
      return null;
    }

    const userCard = followBox.parentElement;
    if (!userCard) {
      console.warn('유저 카드 못 찾음');
      return null;
    }

    const avatarEl = [...userCard.querySelectorAll('a[style]')]
      .find(a => {
        const bg = a?.style?.backgroundImage || '';
        if (!bg.includes('/uploads/')) return false;
        if (a?.getAttribute?.('href')?.includes('/profile/')) return false;
        return true;
      });

    if (!avatarEl) {
      console.warn('아바타 요소 없음');
      return null;
    }

    let avatar = null;
    const styleText = avatarEl.style?.backgroundImage || '';
    const match = styleText.match(/\/uploads\/[^")]+/);
    if (match) {
      avatar = toAbs(match[0]);
    }

    if (!avatar) {
      console.warn('아바타 추출 실패');
      return null;
    }

    const nickname = avatarEl.querySelector('span')?.textContent?.trim() || null;

    const result = { avatar, nickname };
    console.log('유저 프로필 추출 성공 ✅');
    console.log(result);
    return result;
  };

  window.findProfile28 = () => {
    return new Promise((resolve) => {
      try {
        if (!location.href.startsWith(PROJECT_PREFIX)) {
          console.warn('[findProfile28] ❌ Not a project page:', location.href);
          resolve({ success: false, error: 'wrong_page', avatar: null, nickname: null });
          return;
        }

        let attempts = 0;
        const poll = () => {
          attempts += 1;
          const payload = extractProfile();
          if (payload?.avatar) {
            resolve({ success: true, ...payload });
            return;
          }

          if (attempts < MAX_ATTEMPTS) {
            setTimeout(poll, INTERVAL_MS);
            return;
          }

          console.error('[findProfile28] ❌ Timeout after', attempts, 'attempts');
          resolve({ success: false, error: 'dom_timeout', avatar: null, nickname: null });
        };

        poll();
      } catch (err) {
        console.error('[findProfile28] ❌ Exception:', err?.message || err);
        resolve({ success: false, error: 'exception', avatar: null, nickname: null });
      }
    });
  };

  if (window.FIND_PROFILE_28_AUTO_RUN) {
    window.findProfile28().then((result) => {
      try {
        if (window.ProfileBridge?.postMessage) {
          window.ProfileBridge.postMessage(JSON.stringify({
            type: 'PROFILE_RESULT_28',
            success: result.success,
            avatar: result.avatar || '',
            nickname: result.nickname || '',
            error: result.error || ''
          }));
        }
      } catch (bridgeErr) {
        console.error('[findProfile28] ❌ Bridge error:', bridgeErr?.message || bridgeErr);
      }
    });
  }
})();
