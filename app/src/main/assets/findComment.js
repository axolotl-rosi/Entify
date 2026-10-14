// == Entry GraphQL 댓글/답글 탐색기 (간소화 버전) ==
// - template_1 (runEntryTimeMatch): 시간 기준으로 부모 댓글만 매칭
// - template_34 (runEntryReplySearch): 내 댓글(UID) 아래 답글만 시간 기준 매칭
// - template_space_1 (runEntrySpaceCommentSearch): 부모/답글 모두 탐색 후 최근접 매칭

(() => {
  "use strict";

  /* ================= GraphQL Query ================= */

  const COMMENT_QUERY = `
    query SELECT_COMMENTS(
      $pageParam: PageParam
      $target: String
      $searchAfter: JSON
    ){
      commentList(
        pageParam: $pageParam
        target: $target
        searchAfter: $searchAfter
      ) {
        searchAfter
        list {
          id
          content
          created
          user {
            id
            nickname
            profileImage {
              filename
              imageType
            }
          }
          image {
            filename
          }
          sticker {
            filename
          }
        }
      }
    }
  `;

  /* ================= Helper Functions ================= */

  // 페이지 정보 추출 함수 (카테고리별 분기)
  function getPageInfo() {
    const currentUrl = window.location.href;

    // 프로젝트 페이지
    const projectMatch = currentUrl.match(/\/project\/([a-f0-9]+)/i);
    if (projectMatch) {
      return {
        id: projectMatch[1],
        subject: "project",
        type: "project"
      };
    }

    // 스페이스 월드 페이지
    const worldMatch = currentUrl.match(/\/world\/([a-f0-9]+)/i);
    if (worldMatch) {
      return {
        id: worldMatch[1],
        subject: "space",
        type: "space"
      };
    }

    // QnA 페이지
    const qnaMatch = currentUrl.match(/\/community\/qna\/([a-f0-9]+)/i);
    if (qnaMatch) {
      return {
        id: qnaMatch[1],
        subject: "discuss",
        type: "qna"
      };
    }

    // Tips 페이지
    const tipsMatch = currentUrl.match(/\/community\/tips\/([a-f0-9]+)/i);
    if (tipsMatch) {
      return {
        id: tipsMatch[1],
        subject: "discuss",
        type: "tips"
      };
    }

    // EntryStory 페이지
    const entrystoryMatch = currentUrl.match(/\/community\/entrystory\/([a-f0-9]+)/i);
    if (entrystoryMatch) {
      return {
        id: entrystoryMatch[1],
        subject: "discuss",
        type: "entrystory"
      };
    }

    // EntryStory 목록 페이지
    const entrystoryListMatch = currentUrl.match(/\/community\/entrystory\/list/i);
    if (entrystoryListMatch) {
      return {
        id: null,
        subject: "entrystory_list",
        type: "entrystory_list"
      };
    }

    // 그룹 커뮤니티 페이지
    const groupCommunityMatch = currentUrl.match(/\/group\/community\/([a-f0-9]+)\/([a-f0-9]+)/i);
    if (groupCommunityMatch) {
      return {
        id: groupCommunityMatch[1],
        groupId: groupCommunityMatch[2],
        subject: "discuss",
        type: "group_community"
      };
    }

    console.warn("[FIND_COMMENT] 지원되지 않는 페이지 타입:", currentUrl);
    return {
      id: null,
      subject: "project",
      type: "unknown"
    };
  }

  function getTargetId(fallback) {
    if (fallback) return fallback;
    const info = getPageInfo();
    if (info && info.id) return info.id;
    const g = location.href.match(/\/group\/community\/([0-9a-f]{24})\/([0-9a-f]{24})/i);
    if (g) return g[1];
    const m = location.href.match(/(?:project|community\/[^/]+)\/([0-9a-f]{24})/i);
    return m ? m[1] : null;
  }

  async function fetchComments(target, searchAfter = null, display = 20, opts = {}) {
    const variables = {
      target,
      searchAfter,
      pageParam: { display, sort: "created", order: -1 }
    };

    let sessionToken = "";
    let sessionCsrf = "";
    let localToken = "";
    let localCsrf = "";
    try {
      sessionToken = sessionStorage.getItem("entry_xToken") || "";
      sessionCsrf = sessionStorage.getItem("entry_csrfToken") || "";
    } catch (_) {}
    try {
      localToken = localStorage.getItem("entry_xToken") || localStorage.getItem("playentry_token") || "";
      localCsrf = localStorage.getItem("entry_csrfToken") || "";
    } catch (_) {}

    const token = opts.token || sessionToken || localToken || "";
    const csrf =
      opts.csrf ||
      (document.querySelector('meta[name="csrf-token"]') || {}).content ||
      sessionCsrf ||
      localCsrf ||
      "";

    try {
      const res = await fetch("https://playentry.org/graphql/SELECT_COMMENTS", {
        method: "POST",
        credentials: "include",
        headers: {
          "content-type": "application/json",
          "x-client-type": "Client",
          ...(token ? { "x-token": token } : {}),
          ...(csrf ? { "csrf-token": csrf } : {})
        },
        body: JSON.stringify({
          operationName: "SELECT_COMMENTS",
          query: COMMENT_QUERY,
          variables
        })
      });

      if (!res.ok) {
        console.error("[FIND_COMMENT] HTTP error:", res.status);
        return null;
      }

      const data = await res.json();
      if (data?.errors) {
        console.error("[FIND_COMMENT] GraphQL errors:", data.errors);
      }

      return data?.data?.commentList || null;
    } catch (e) {
      console.error("[FIND_COMMENT] Fetch error:", e.message);
      return null;
    }
  }

  function toAbsoluteUrl(path) {
    if (!path) return "";
    if (/^https?:\/\//i.test(path)) return path;
    return location.origin + (path.startsWith("/") ? path : "/" + path);
  }

  function normalizeUploadUrl(filename, kind = "image", imageType = "") {
    if (!filename) return "";

    // 이미 절대 URL이면 그대로 반환
    if (/^https?:\/\//i.test(filename)) {
      return filename;
    }

    // 확장자 없고 imageType 있으면 붙이기 (주로 profileImage svg)
    if (imageType && !/\.[a-z0-9]+$/i.test(filename)) {
      filename = filename + "." + imageType;
    }

    // /uploads/로 시작하면 절대 경로로 변환
    if (filename.startsWith("/uploads/")) {
      return toAbsoluteUrl(filename);
    }

    // 그 외에는 /uploads/ 접두어 추가
    return toAbsoluteUrl("/uploads/" + filename);
  }

  function buildResult(comment, matchType = "comment", parentInfo = null) {
    const profileObj = comment.user?.profileImage || {};
    const result = {
      userId: comment.user?.id || "",
      nickname: comment.user?.nickname || "",
      content: comment.content || "",
      created: comment.created || "",
      profileFilename: profileObj.filename || "",
      profileImageType: profileObj.imageType || "",
      profile: normalizeUploadUrl(profileObj.filename || "", "profile", profileObj.imageType || ""),
      sticker: normalizeUploadUrl(comment.sticker?.filename || "", "sticker"),
      image: normalizeUploadUrl(comment.image?.filename || "", "image"),
      matchType
    };

    if (parentInfo) {
      result.parentId = parentInfo.parentId;
      result.parentUserId = parentInfo.parentUserId;
      result.parentNickname = parentInfo.parentNickname;
    }

    return result;
  }

  /* ================= Template_1: 부모 댓글만 찾기 ================= */

  window.runEntryTimeMatch = async function ({
    isoTime,
    toleranceSec = 0.006,
    token,
    csrf,
    targetId
  }) {
    console.log("[FIND_COMMENT] runEntryTimeMatch start", { isoTime, toleranceSec });

    if (!isoTime) {
      console.error("[FIND_COMMENT] ❌ isoTime 필요");
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_no_isotime");
      }
      return null;
    }

    const targetMs = new Date(isoTime).getTime();
    if (isNaN(targetMs)) {
      console.error("[FIND_COMMENT] ❌ 잘못된 ISO 시간:", isoTime);
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_invalid_time");
      }
      return null;
    }

    const resolvedTargetId = getTargetId(targetId);
    if (!resolvedTargetId) {
      console.error("[FIND_COMMENT] ❌ 대상 ID 추출 실패");
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_no_target");
      }
      return null;
    }

    const toleranceMs = toleranceSec * 1000;

    /* -------- 1️⃣ 부모 댓글 순회 -------- */

    let searchAfter = null;
    let best = null;
    let pageCount = 0;
    const maxPages = 50; // 최대 50페이지

    while (pageCount < maxPages) {
      const box = await fetchComments(resolvedTargetId, searchAfter, 20, {
        token,
        csrf
      });
      if (!box || !box.list || box.list.length === 0) break;

      pageCount++;
      let shouldStop = false;

      for (const c of box.list) {
        const cMs = new Date(c.created).getTime();
        if (isNaN(cMs)) continue;
        const diff = cMs - targetMs;
        const absDiff = Math.abs(diff);

        if (!best || absDiff < best.absDiff) {
          best = { comment: c, absDiff, diff };
        }

        if (cMs < targetMs - toleranceMs) {
          shouldStop = true;
          break;
        }
      }

      if (best && best.absDiff <= toleranceMs) break;
      if (shouldStop) break;
      if (!box.searchAfter) break;
      searchAfter = box.searchAfter;
    }

    console.log("[FIND_COMMENT] 🕒 기준 ISO:", isoTime);
    console.log("[FIND_COMMENT] 🎯 허용 오차(ms):", toleranceMs);
    console.log("[FIND_COMMENT] ✅ best:", best && {
      id: best.comment?.id,
      createdISO: best.comment?.created,
      diff_ms: best.diff
    });

    if (best && best.absDiff <= toleranceMs) {
      const result = buildResult(best.comment, "comment");
      result.diff_ms = best.diff;
      return result;
    }

    console.warn("[FIND_COMMENT] ⚠ 매칭된 댓글 없음");
    if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
      AndroidApp.postMessage("COMMENT_RESULT: not_found");
    }
    return null;
  };

  /* ================= Template_34: 내 댓글에 달린 답글 찾기 ================= */

  window.runEntryReplySearch = async function ({
    parentUserId,
    replyIsoTime,
    toleranceSec = 0.006,
    token,
    csrf,
    targetId
  }) {
    console.log("[FIND_COMMENT] runEntryReplySearch start", {
      parentUserId,
      replyIsoTime,
      toleranceSec
    });

    if (!replyIsoTime) {
      console.error("[FIND_COMMENT] ❌ replyIsoTime 필요");
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_missing_params");
      }
      return null;
    }
    const normalizedParentUserId = String(parentUserId || "").trim();
    const useParentUidFilter = normalizedParentUserId.length > 0;

    const baseMs = new Date(replyIsoTime).getTime();
    if (isNaN(baseMs)) {
      console.error("[FIND_COMMENT] ❌ 잘못된 ISO 시간:", replyIsoTime);
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_invalid_time");
      }
      return null;
    }

    const resolvedTargetId = getTargetId(targetId);
    if (!resolvedTargetId) {
      console.error("[FIND_COMMENT] ❌ 대상 ID 추출 실패");
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_no_target");
      }
      return null;
    }

    const toleranceMs = toleranceSec * 1000;
    let matched = [];

    /* ---------- 1️⃣ 부모 댓글 순회 (UID 기준) ---------- */

    let parentAfter = null;
    let parentPage = 0;
    const maxParentPages = 50;

    while (parentPage < maxParentPages) {
      const box = await fetchComments(resolvedTargetId, parentAfter, 20, {
        token,
        csrf
      });
      if (!box || !box.list || box.list.length === 0) break;

      // parentUserId가 있으면 우선 필터링하고, 한 건도 없으면 시간 기반으로 전체 부모를 폴백 탐색
      const strictParents = useParentUidFilter
        ? box.list.filter(c => c.user?.id === normalizedParentUserId)
        : box.list;
      const parents = strictParents.length > 0 ? strictParents : box.list;

      if (useParentUidFilter && strictParents.length === 0) {
        console.log(`[FIND_COMMENT] 페이지 ${parentPage}: UID 일치 부모 없음 -> 시간 기반 폴백`);
      }
      console.log(`[FIND_COMMENT] 페이지 ${parentPage}: 전체 ${box.list.length}개, 후보 부모 ${parents.length}개`);

      /* ---------- 2️⃣ 각 부모의 답글 탐색 ---------- */

      for (const parent of parents) {
        let replyAfter = null;
        let replyPage = 0;
        const maxReplyPages = 10;

        while (replyPage < maxReplyPages) {
          const rbox = await fetchComments(parent.id, replyAfter, 20, {
            token,
            csrf
          });
          if (!rbox || !rbox.list || rbox.list.length === 0) break;

          for (const rp of rbox.list) {
            const rpMs = new Date(rp.created).getTime();
            const diff = rpMs - baseMs;
            const absDiff = Math.abs(diff);

            // 기준 시각과 가까운 답글만
            if (absDiff <= toleranceMs) {
              const result = buildResult(rp, "reply", {
                parentId: parent.id,
                parentUserId: parent.user?.id || "",
                parentNickname: parent.user?.nickname || ""
              });
              result.diff_ms = diff;
              matched.push(result);
              break;
            }
            if (rpMs < baseMs - toleranceMs) break;
          }

          if (!rbox.searchAfter) break;
          replyAfter = rbox.searchAfter;
          replyPage++;
        }
      }

      if (!box.searchAfter) break;
      parentAfter = box.searchAfter;
      parentPage++;
    }

    /* ---------- 결과 ---------- */

    console.log("[FIND_COMMENT] 🎯 매칭된 답글 수:", matched.length);

    if (matched.length > 0) {
      console.table(
        matched.map(r => ({
          parentId: r.parentId,
          parentUID: r.parentUserId,
          replyUID: r.userId,
          replyISO: r.created,
          diff_ms: r.diff_ms,
          nickname: r.nickname,
          content: (r.content || "").slice(0, 50)
        }))
      );

      // 첫 번째 매칭된 답글 전달
      const result = matched[0];

      return result;
    } else {
      console.warn("[FIND_COMMENT] ⚠ 매칭된 답글 없음");
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: not_found");
      }
      return null;
    }
  };

  /* ================= Template_space_1: 부모/답글 동시 탐색 ================= */

  window.runEntrySpaceCommentSearch = async function ({
    parentUserId,
    isoTime,
    toleranceSec = 1.0,
    token,
    csrf,
    targetId
  }) {
    if (!isoTime) {
      if (typeof AndroidApp !== "undefined" && !window.__ENTRY_SUPPRESS_POST__) {
        AndroidApp.postMessage("COMMENT_RESULT: error_no_isotime");
      }
      return null;
    }

    const [commentMatch, replyMatch] = await Promise.all([
      window.runEntryTimeMatch({
        isoTime,
        toleranceSec,
        token,
        csrf,
        targetId
      }).catch(() => null),
      window.runEntryReplySearch({
        parentUserId,
        replyIsoTime: isoTime,
        toleranceSec,
        token,
        csrf,
        targetId
      }).catch(() => null)
    ]);

    const parseDiff = (item) => {
      if (!item || typeof item !== "object") return Number.POSITIVE_INFINITY;
      const raw = Number(item.diff_ms);
      return Number.isFinite(raw) ? Math.abs(raw) : Number.POSITIVE_INFINITY;
    };

    const commentDiff = parseDiff(commentMatch);
    const replyDiff = parseDiff(replyMatch);
    const chosen = replyDiff < commentDiff ? replyMatch : commentMatch;

    if (chosen && typeof chosen === "object") {
      if (!chosen.matchType) {
        chosen.matchType = chosen === replyMatch ? "reply" : "comment";
      }
      return chosen;
    }

    return null;
  };

  /* ================= Legacy findComment (호환성) ================= */

  window.findComment = async function (nickname, createdIso = "", userOpts = {}) {
    console.warn("[FIND_COMMENT] Legacy findComment called - redirecting to runEntryTimeMatch");
    return window.runEntryTimeMatch({
      isoTime: createdIso,
      toleranceSec: userOpts.toleranceSec || 0.1,
      token: userOpts.token,
      csrf: userOpts.csrf,
      targetId: userOpts.targetId
    });
  };

  console.log("✅ Entry 댓글/답글 탐색 엔진 준비 완료 (간소화 버전)");
})();
