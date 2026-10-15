(function () {
  "use strict";

  const root = document.documentElement;
  const button = document.getElementById("theme-toggle");
  const themeColor = document.querySelector('meta[name="theme-color"]');
  let theme = "dark";

  try {
    if (localStorage.getItem("entify-theme") === "light") theme = "light";
  } catch (_) {}

  function applyTheme(nextTheme) {
    theme = nextTheme === "light" ? "light" : "dark";
    if (theme === "light") root.setAttribute("data-theme", "light");
    else root.removeAttribute("data-theme");

    if (button) {
      const isLight = theme === "light";
      button.textContent = isLight ? "다크 모드" : "라이트 모드";
      button.setAttribute("aria-label", isLight ? "다크 테마로 전환" : "라이트 테마로 전환");
      button.setAttribute("aria-pressed", String(isLight));
    }
    if (themeColor) themeColor.setAttribute("content", theme === "light" ? "#fbf9ff" : "#121016");
  }

  applyTheme(theme);
  button?.addEventListener("click", function () {
    const nextTheme = theme === "dark" ? "light" : "dark";
    applyTheme(nextTheme);
    try { localStorage.setItem("entify-theme", nextTheme); } catch (_) {}
  });

  document.querySelectorAll("details").forEach(function (details) {
    const summary = details.querySelector("summary");
    if (!summary) return;
    let animation = null;

    summary.addEventListener("click", function (event) {
      event.preventDefault();
      if (animation) {
        animation.cancel();
        animation = null;
      }

      const startHeight = details.offsetHeight;
      const opening = !details.open;
      if (opening) details.open = true;
      const endHeight = opening ? details.scrollHeight : summary.offsetHeight;

      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        details.open = opening;
        details.style.height = "";
        return;
      }

      const currentAnimation = details.animate(
        { height: [startHeight + "px", endHeight + "px"] },
        { duration: 380, easing: "cubic-bezier(0.4, 0, 0.2, 1)" }
      );
      animation = currentAnimation;
      currentAnimation.onfinish = function () {
        details.open = opening;
        details.style.height = "";
        if (animation === currentAnimation) animation = null;
      };
      currentAnimation.oncancel = function () {
        details.style.height = "";
        if (animation === currentAnimation) animation = null;
      };
    });
  });
})();
