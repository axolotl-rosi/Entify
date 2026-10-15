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
      button.textContent = isLight ? "🌙 다크" : "☀️ 라이트";
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
})();
