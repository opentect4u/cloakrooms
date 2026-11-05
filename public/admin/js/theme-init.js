// Runs before first paint (loaded in <head>): apply the saved or system theme, so the page
// never flashes the wrong colours. External file because the CSP allows no inline script.
(function () {
  var theme;
  try {
    theme = localStorage.getItem('crb-theme');
  } catch {
    theme = null;
  }
  if (theme !== 'light' && theme !== 'dark') {
    theme =
      window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  document.documentElement.setAttribute('data-bs-theme', theme);
  // Lets CSS hide controls that only matter without JavaScript (e.g. "Apply" on auto-submit forms).
  document.documentElement.classList.add('js');
})();
