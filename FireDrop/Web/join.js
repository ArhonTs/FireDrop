(() => {
  'use strict';

  const curLang = (navigator.languages || [navigator.language]).some((l) =>
    l && l.toLowerCase().startsWith('ru')) ? 'ru' : 'en';

  const I18N = {
    ru: {
      'join.title': 'Подключение — FireDrop',
      'join.brandSub': 'Локальная передача файлов',
      'join.eyebrow': 'Защищённое подключение',
      'join.heroTitle': 'Введите код<br><span>с экрана компьютера</span>',
      'join.sub': 'Шестизначный код показан под QR-кодом в приложении FireDrop на ПК.',
      'join.codeLabel': 'Код доступа',
      'join.btn': 'Подключиться',
      'join.checking': 'Проверяем…',
      'join.errDigits': 'Введите все шесть цифр.',
      'join.errFailed': 'Не удалось подключиться.',
      'join.errNoConnection': 'Нет связи с компьютером. Проверьте, что вы в той же Wi-Fi сети.',
      'join.hint': 'Проще всего отсканировать QR-код камерой телефона — подключение произойдёт автоматически.',
      'join.footerLocal': 'Только ваша локальная сеть',
    },
    en: {
      'join.title': 'Connect — FireDrop',
      'join.brandSub': 'Local file transfer',
      'join.eyebrow': 'Secure connection',
      'join.heroTitle': 'Enter the code<br><span>from the computer screen</span>',
      'join.sub': 'The six-digit code is shown below the QR code in the FireDrop app on the PC.',
      'join.codeLabel': 'Access code',
      'join.btn': 'Connect',
      'join.checking': 'Checking…',
      'join.errDigits': 'Enter all six digits.',
      'join.errFailed': 'Could not connect.',
      'join.errNoConnection': 'No connection to the computer. Make sure you are on the same Wi-Fi network.',
      'join.hint': 'The easiest way is to scan the QR code with your phone camera — the connection happens automatically.',
      'join.footerLocal': 'Your local network only',
    },
  };

  const t = (key) => (I18N[curLang] && I18N[curLang][key]) || I18N.en[key] || key;
  const REPL = (s, args) => String(s).replace(/\{(\d+)\}/g, (m, i) => (args[i] ?? m));

  function applyI18n(root = document) {
    root.querySelectorAll('[data-i18n]').forEach((el) => {
      const key = el.getAttribute('data-i18n');
      const value = t(key);
      if (el.textContent !== value) el.textContent = value;
    });
    root.querySelectorAll('[data-i18n-html]').forEach((el) => {
      el.innerHTML = t(el.getAttribute('data-i18n-html'));
    });
    root.querySelectorAll('[data-i18n-aria]').forEach((el) => {
      el.setAttribute('aria-label', t(el.getAttribute('data-i18n-aria')));
    });
    root.querySelectorAll('[data-i18n-placeholder]').forEach((el) => {
      el.setAttribute('placeholder', t(el.getAttribute('data-i18n-placeholder')));
    });
    root.querySelectorAll('[data-i18n-title]').forEach((el) => {
      el.setAttribute('title', t(el.getAttribute('data-i18n-title')));
    });
  }

  const form = document.getElementById('joinForm');
  const input = document.getElementById('pinInput');
  const button = document.getElementById('joinBtn');
  const buttonLabel = document.getElementById('joinBtnLabel');
  const errorBox = document.getElementById('joinError');
  let busy = false;

  document.documentElement.lang = curLang;
  applyI18n();

  input.addEventListener('input', () => {
    const digits = input.value.replace(/\D/g, '').slice(0, 6);
    if (input.value !== digits) input.value = digits;
    hideError();

    if (digits.length === 6 && !busy) {
      form.requestSubmit();
    }
  });

  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !busy) {
      form.requestSubmit();
    }
  });

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (busy) return;

    const pin = input.value.replace(/\D/g, '');
    if (pin.length !== 6) {
      showError(t('join.errDigits'));
      input.focus();
      return;
    }

    busy = true;
    button.disabled = true;
    button.classList.add('loading');
    buttonLabel.textContent = t('join.checking');
    hideError();

    try {
      const response = await fetch('/api/join', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: JSON.stringify({ pin }),
      });

      if (response.ok) {
        if (navigator.vibrate) navigator.vibrate([20, 40, 20]);
        window.location.replace('/');
        return;
      }

      let message = t('join.errFailed');
      try {
        const body = await response.json();
        if (body?.error) message = body.error;
      } catch {
        // Keep the fallback message.
      }
      showError(message);
      shake();
    } catch {
      showError(t('join.errNoConnection'));
      shake();
    } finally {
      busy = false;
      button.disabled = false;
      button.classList.remove('loading');
      buttonLabel.textContent = t('join.btn');
      input.focus();
      input.select();
    }
  });

  function showError(message) {
    errorBox.textContent = message;
    errorBox.hidden = false;
    input.setAttribute('aria-invalid', 'true');
  }

  function hideError() {
    if (!errorBox.hidden) errorBox.hidden = true;
    input.removeAttribute('aria-invalid');
  }

  function shake() {
    form.classList.remove('shake');
    void form.offsetWidth;
    form.classList.add('shake');
    window.setTimeout(() => form.classList.remove('shake'), 500);
    if (navigator.vibrate) navigator.vibrate([35, 35, 35]);
  }
})();
