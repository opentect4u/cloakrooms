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

  // ---- Confirmation dialog for destructive or important forms ----
  var modalEl = document.getElementById('confirm-modal');
  var pending = null;
  if (modalEl && window.bootstrap) {
    var modal = window.bootstrap.Modal.getOrCreateInstance(modalEl);
    var okBtn = modalEl.querySelector('[data-confirm-ok]');
    modalEl.addEventListener('hidden.bs.modal', function () {
      pending = null;
    });
    okBtn.addEventListener('click', function () {
      var form = pending;
      modal.hide();
      if (form) {
        form.setAttribute('data-confirmed', '1');
        form.submit();
      }
    });
    document.addEventListener(
      'submit',
      function (e) {
        var form = e.target;
        if (!(form instanceof HTMLFormElement) || !form.hasAttribute('data-confirm')) return;
        if (form.getAttribute('data-confirmed') === '1') return;
        e.preventDefault();
        pending = form;
        var danger = form.getAttribute('data-confirm-tone') === 'danger';
        modalEl.querySelector('.confirm-title').textContent =
          form.getAttribute('data-confirm-title') || 'Are you sure?';
        modalEl.querySelector('[data-confirm-text]').textContent =
          form.getAttribute('data-confirm');
        okBtn.textContent = form.getAttribute('data-confirm-button') || 'Confirm';
        okBtn.className = 'btn ' + (danger ? 'btn-danger' : 'btn-primary');
        var icon = modalEl.querySelector('[data-confirm-icon]');
        icon.className = 'confirm-icon' + (danger ? ' is-danger' : '');
        icon.innerHTML = danger
          ? '<i class="bi bi-exclamation-triangle"></i>'
          : '<i class="bi bi-question-lg"></i>';
        modal.show();
      },
      true,
    );
  }

  // ---- Auto-submit filter bars and instant settings ----
  document.querySelectorAll('form[data-autosubmit]').forEach(function (form) {
    form.addEventListener('change', function (e) {
      if (e.target && e.target.type === 'search') return;
      form.classList.add('is-saving');
      form.submit();
    });
  });

  // ---- A single select that reloads its form (e.g. the report customer) ----
  document.querySelectorAll('[data-autosubmit-field]').forEach(function (field) {
    field.addEventListener('change', function () {
      if (field.form) field.form.submit();
    });
  });

  // ---- Generate a temporary password (letters + digits, no look-alikes) ----
  document.querySelectorAll('[data-generate-password]').forEach(function (btn) {
    var input = document.getElementById(btn.getAttribute('data-generate-password'));
    if (!input || !window.crypto) return;
    btn.addEventListener('click', function () {
      var letters = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ';
      var digits = '23456789';
      var all = letters + digits;
      var bytes = new Uint32Array(12);
      window.crypto.getRandomValues(bytes);
      var out = '';
      for (var i = 0; i < bytes.length; i++) out += all[bytes[i] % all.length];
      out =
        letters[bytes[0] % letters.length] + out.slice(1, 10) + digits[bytes[11] % digits.length];
      input.type = 'text';
      input.value = out;
      input.focus();
      input.select();
    });
  });

  // ---- Show the chosen file name in drop zones ----
  document.querySelectorAll('[data-file-input]').forEach(function (input) {
    var label = input.closest('label');
    var name = label && label.querySelector('[data-file-name]');
    input.addEventListener('change', function () {
      if (name && input.files && input.files[0]) {
        var kb = Math.ceil(input.files[0].size / 1024);
        name.textContent = input.files[0].name + ' · ' + kb + ' KB';
        label.classList.add('has-file');
      }
    });
  });

  // ---- Typed confirmation (delete customer) ----
  document.querySelectorAll('[data-confirm-match]').forEach(function (input) {
    var btn = input.form && input.form.querySelector('[data-confirm-target]');
    if (!btn) return;
    input.addEventListener('input', function () {
      btn.disabled = input.value.trim() !== input.getAttribute('data-confirm-match');
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
  // Tariff slab editor: each slab starts where the previous one ends; "Add slab" reveals a row.
  document.querySelectorAll('[data-slab-editor]').forEach(function (editor) {
    var rows = Array.prototype.slice.call(editor.querySelectorAll('[data-slab-row]'));
    var addBtn = editor.querySelector('[data-slab-add]');
    function refresh() {
      var from = 0;
      rows.forEach(function (row) {
        row.querySelector('[data-slab-from]').textContent = String(from);
        var to = row.querySelector('[data-slab-to]').value;
        if (/^\d+$/.test(to)) from = Number(to);
      });
      addBtn.hidden = !rows.some(function (row) {
        return row.hasAttribute('data-slab-closed');
      });
    }
    addBtn.addEventListener('click', function () {
      var next = rows.find(function (row) {
        return row.hasAttribute('data-slab-closed');
      });
      if (!next) return;
      next.removeAttribute('data-slab-closed');
      next.querySelector('[data-slab-to]').focus();
      refresh();
    });
    editor.addEventListener('input', refresh);
    refresh();
  });
})();
