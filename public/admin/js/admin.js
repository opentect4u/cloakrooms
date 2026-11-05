// Cloak Room admin panel: small progressive enhancements. Every page works without this file.
(function () {
  'use strict';

  var root = document.documentElement;

  // ---- Theme toggle (persisted per browser) ----
  document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var next = root.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark';
      root.setAttribute('data-bs-theme', next);
      try {
        localStorage.setItem('crb-theme', next);
      } catch {
        /* private mode: theme just isn't remembered */
      }
    });
  });

  // ---- Mobile sidebar ----
  var body = document.body;
  document.querySelectorAll('[data-sidebar-open]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      body.classList.add('sidebar-open');
    });
  });
  document.querySelectorAll('[data-sidebar-close]').forEach(function (el) {
    el.addEventListener('click', function () {
      body.classList.remove('sidebar-open');
    });
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') body.classList.remove('sidebar-open');
  });

  // ---- Show / hide password + caps lock hint ----
  document.querySelectorAll('[data-toggle-password]').forEach(function (btn) {
    var input = document.getElementById(btn.getAttribute('data-toggle-password'));
    if (!input) return;
    btn.addEventListener('click', function () {
      var show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      btn.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
      var icon = btn.querySelector('.bi');
      if (icon) icon.className = 'bi bi-' + (show ? 'eye-slash' : 'eye');
      input.focus();
    });
  });
  document.querySelectorAll('[data-caps-hint]').forEach(function (hint) {
    var input = document.getElementById(hint.getAttribute('data-caps-hint'));
    if (!input) return;
    var check = function (e) {
      if (e.getModifierState) hint.hidden = !e.getModifierState('CapsLock');
    };
    input.addEventListener('keydown', check);
    input.addEventListener('keyup', check);
    input.addEventListener('blur', function () {
      hint.hidden = true;
    });
  });

  // ---- Password strength meter (hint only; the server enforces the rule) ----
  document.querySelectorAll('[data-pw-meter]').forEach(function (meter) {
    var input = document.getElementById(meter.getAttribute('data-pw-meter'));
    if (!input) return;
    input.addEventListener('input', function () {
      var v = input.value;
      var score = 0;
      if (v.length >= 8) score++;
      if (/[A-Za-z]/.test(v) && /\d/.test(v)) score++;
      if (v.length >= 12) score++;
      if (/[^A-Za-z0-9]/.test(v) && /[a-z]/.test(v) && /[A-Z]/.test(v)) score++;
      meter.setAttribute('data-score', v ? String(Math.max(1, score)) : '0');
    });
  });

  // ---- Busy state on submit (prevents double posts) ----
  document.querySelectorAll('form[data-busy-form]').forEach(function (form) {
    form.addEventListener('submit', function () {
      if (form.classList.contains('is-busy')) return;
      window.setTimeout(function () {
        form.classList.add('is-busy');
        form.querySelectorAll('button[type="submit"]').forEach(function (b) {
          b.disabled = true;
        });
      }, 0);
    });
  });

  // ---- Toasts ----
  function dismiss(toast) {
    toast.classList.add('is-leaving');
    window.setTimeout(function () {
      toast.remove();
    }, 300);
  }
  document.querySelectorAll('.app-toast').forEach(function (toast) {
    var close = toast.querySelector('[data-dismiss-toast]');
    if (close)
      close.addEventListener('click', function () {
        dismiss(toast);
      });
    if (toast.hasAttribute('data-autohide')) {
      window.setTimeout(function () {
        dismiss(toast);
      }, 5000);
    }
  });

  // ---- Chart tooltips (hover, keyboard focus, touch) ----
  var tip = document.getElementById('chart-tooltip');
  function showTip(target) {
    if (!tip) return;
    tip.textContent = '';
    [
      ['tt-title', target.getAttribute('data-tip-title')],
      ['tt-value', target.getAttribute('data-tip-value')],
      ['tt-detail', target.getAttribute('data-tip-detail')],
    ].forEach(function (part) {
      if (!part[1]) return;
      var div = document.createElement('div');
      div.className = part[0];
      div.textContent = part[1];
      tip.appendChild(div);
    });
    var r = target.getBoundingClientRect();
    var x = Math.min(Math.max(r.left + r.width / 2, 90), window.innerWidth - 90);
    tip.style.left = x + 'px';
    tip.style.top = Math.max(r.top + 40, 80) + 'px';
    tip.hidden = false;
  }
  function hideTip() {
    if (tip) tip.hidden = true;
  }
  document.querySelectorAll('.chart-hit').forEach(function (hit) {
    hit.addEventListener('mouseenter', function () {
      showTip(hit);
    });
    hit.addEventListener('focus', function () {
      showTip(hit);
    });
    hit.addEventListener('click', function () {
      showTip(hit);
    });
    hit.addEventListener('mouseleave', hideTip);
    hit.addEventListener('blur', hideTip);
  });
  window.addEventListener('scroll', hideTip, { passive: true });
  // Scrollable charts on phones open at the latest day.
  var charts = document.querySelectorAll('[data-chart]');
  function toLatest() {
    charts.forEach(function (chart) {
      chart.scrollLeft = chart.scrollWidth;
    });
  }
  charts.forEach(function (chart) {
    chart.addEventListener('scroll', hideTip, { passive: true });
  });
  toLatest();
  window.addEventListener('resize', toLatest);
})();
