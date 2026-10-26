(() => {
  'use strict';

  const form = document.getElementById('joinForm');
  const input = document.getElementById('pinInput');
  const button = document.getElementById('joinBtn');
  const buttonLabel = document.getElementById('joinBtnLabel');
  const errorBox = document.getElementById('joinError');
  let busy = false;

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
      showError('Введите все шесть цифр.');
      input.focus();
      return;
    }

    busy = true;
    button.disabled = true;
    button.classList.add('loading');
    buttonLabel.textContent = 'Проверяем…';
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

      let message = 'Не удалось подключиться.';
      try {
        const body = await response.json();
        if (body?.error) message = body.error;
      } catch {
        // Keep the fallback message.
      }
      showError(message);
      shake();
    } catch {
      showError('Нет связи с компьютером. Проверьте, что вы в той же Wi-Fi сети.');
      shake();
    } finally {
      busy = false;
      button.disabled = false;
      button.classList.remove('loading');
      buttonLabel.textContent = 'Подключиться';
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
