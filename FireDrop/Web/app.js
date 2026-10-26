(() => {
  'use strict';

  const curLang = (navigator.languages || [navigator.language]).some((l) =>
    l && l.toLowerCase().startsWith('ru')) ? 'ru' : 'en';

  const I18N = {
    ru: {
      // Statuses
      'status.pending': 'В очереди',
      'status.init': 'Подготовка',
      'status.uploading': 'Передача',
      'status.finishing': 'Сохранение',
      'status.done': 'Готово',
      'status.error': 'Ошибка',
      'status.cancelled': 'Отменено',
      // Toasts / snippets
      'msg.reconnected': 'Соединение восстановлено',
      'msg.noConnection': 'Нет связи с компьютером',
      'msg.cancelled': 'Передача отменена',
      'msg.listCleared': 'Список очищен',
      'msg.doneShort': 'готово',
      'msg.allSaved': 'Готово! Файлы сохранены на компьютере.',
      'msg.file': 'файл',
      'device.default': 'Компьютер',
      // Row action labels
      'aria.cancel': 'Отменить передачу',
      'aria.retry': 'Повторить передачу',
      'aria.remove': 'Убрать из списка',
      // Errors
      'err.noId': 'Компьютер не выдал идентификатор передачи.',
      'err.cancelled': 'Передача отменена',
      'err.chunkFailed': 'Не удалось отправить часть файла.',
      'err.connectionLost': 'Связь с компьютером прервалась.',
      'err.timeout': 'Компьютер слишком долго не отвечает.',
      'err.readFailed': 'Не удалось прочитать файл.',
      'err.notConfirmed': 'Компьютер не подтвердил сохранение файла.',
      'err.sendFailed': 'Не удалось отправить файл',
      'err.requestFailed': 'Не удалось выполнить запрос.',
      'err.noConnectionWifi': 'Связь с компьютером прервалась. Проверьте Wi-Fi.',
      'err.needJoin': 'Требуется подключение к FireDrop.',
      // Templates ({0}, {1})
      'tpl.serverReady': '{0} · готов принять',
      'tpl.errServer': 'Ошибка сервера ({0})',
      'tpl.errComputer': 'Ошибка компьютера ({0})',
      'tpl.sendNamed': 'Не удалось отправить «{0}»',
      'tpl.meta': '{0} из {1} файлов',
      'tpl.etaDone': 'за {0}',
      'tpl.etaLeft': 'осталось {0}',
      'tpl.durSeconds': '{0} сек',
      'tpl.durMinutes': '{0} мин',
      'tpl.durHours': '{0} ч {1} мин',
      'dur.zero': '0 сек',
      'dur.moment': 'меньше секунды',
      // Units
      'bytes.units': ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'],
      'unit.speedMB': 'МБ/с',
      'unit.speedGB': 'ГБ/с',
      'unit.speedKB': 'КБ/с',
      // Overview labels
      'lbl.sendingFiles': 'Отправка файлов',
      'lbl.allDone': 'Все файлы на компьютере',
      'lbl.sending': 'Отправка на компьютер',
      'lbl.preparing': 'Подготовка к отправке',
      'lbl.hasErrors': 'Есть ошибки',
      // index.html static labels
      'page.title': 'FireDrop — файлы на компьютер',
      'page.offline': 'Нет связи с компьютером. Проверьте Wi-Fi.',
      'page.connecting': 'Подключение…',
      'page.eyebrow': 'Прямая передача по локальной сети',
      'page.heroTitle': 'Файлы с телефона<br><span>сразу на компьютер</span>',
      'page.heroSub': 'Без облака, кабелей и сжатия. Скорость ограничена только вашим Wi-Fi.',
      'page.dropCardAria': 'Выбрать файлы для отправки',
      'page.chooseTitle': 'Выберите файлы',
      'page.anySize': 'Фото, видео, документы — любые размеры',
      'page.chooseFiles': 'Выбрать файлы',
      'page.dropHint': 'или перетащите их сюда',
      'page.quickPhoto': 'Фото и видео',
      'page.quickFiles': 'Любые файлы',
      'page.quickFolder': 'Папка',
      'page.quickCamera': 'Камера',
      'page.transfer': 'Передача',
      'page.clear': 'Очистить',
      'page.sending': 'Отправка…',
      'page.metaZero': '0 из 0',
      'page.unitMbps': 'МБ/с',
      'page.bytesZero': '0 Б / 0 Б',
      'page.tipsTitle': 'Советы для максимальной скорости',
      'page.tip1': 'Держите телефон рядом с роутером и выбирайте сеть 5 ГГц.',
      'page.tip2': 'Не блокируйте экран — браузер может приостановить передачу.',
      'page.tip3': 'Отключите VPN на телефоне: он может направить трафик через интернет.',
      'page.footerLocal': 'Только ваша локальная сеть',
    },
    en: {
      // Statuses
      'status.pending': 'Queued',
      'status.init': 'Preparing',
      'status.uploading': 'Uploading',
      'status.finishing': 'Saving',
      'status.done': 'Done',
      'status.error': 'Error',
      'status.cancelled': 'Cancelled',
      // Toasts / snippets
      'msg.reconnected': 'Connection restored',
      'msg.noConnection': 'No connection to the computer',
      'msg.cancelled': 'Transfer cancelled',
      'msg.listCleared': 'List cleared',
      'msg.doneShort': 'done',
      'msg.allSaved': 'Done! Files are saved on the computer.',
      'msg.file': 'file',
      'device.default': 'Computer',
      // Row action labels
      'aria.cancel': 'Cancel transfer',
      'aria.retry': 'Retry transfer',
      'aria.remove': 'Remove from list',
      // Errors
      'err.noId': 'The computer did not issue a transfer id.',
      'err.cancelled': 'Transfer cancelled',
      'err.chunkFailed': 'Failed to send a part of the file.',
      'err.connectionLost': 'Connection to the computer was lost.',
      'err.timeout': 'The computer is taking too long to respond.',
      'err.readFailed': 'Failed to read the file.',
      'err.notConfirmed': 'The computer did not confirm saving the file.',
      'err.sendFailed': 'Failed to send the file',
      'err.requestFailed': 'The request failed.',
      'err.noConnectionWifi': 'Connection to the computer was lost. Check Wi-Fi.',
      'err.needJoin': 'FireDrop connection required.',
      // Templates ({0}, {1})
      'tpl.serverReady': '{0} · ready to receive',
      'tpl.errServer': 'Server error ({0})',
      'tpl.errComputer': 'Computer error ({0})',
      'tpl.sendNamed': 'Could not send "{0}"',
      'tpl.meta': '{0} of {1} files',
      'tpl.etaDone': 'in {0}',
      'tpl.etaLeft': '{0} left',
      'tpl.durSeconds': '{0} s',
      'tpl.durMinutes': '{0} min',
      'tpl.durHours': '{0} h {1} min',
      'dur.zero': '0 s',
      'dur.moment': 'less than a second',
      // Units
      'bytes.units': ['B', 'KB', 'MB', 'GB', 'TB'],
      'unit.speedMB': 'MB/s',
      'unit.speedGB': 'GB/s',
      'unit.speedKB': 'KB/s',
      // Overview labels
      'lbl.sendingFiles': 'Sending files',
      'lbl.allDone': 'All files are on the computer',
      'lbl.sending': 'Sending to the computer',
      'lbl.preparing': 'Preparing to send',
      'lbl.hasErrors': 'Some files failed',
      // index.html static labels
      'page.title': 'FireDrop — files to the computer',
      'page.offline': 'No connection to the computer. Check Wi-Fi.',
      'page.connecting': 'Connecting…',
      'page.eyebrow': 'Direct transfer over the local network',
      'page.heroTitle': 'Files from your phone<br><span>straight to the computer</span>',
      'page.heroSub': 'No cloud, no cables, no compression. Speed is limited only by your Wi-Fi.',
      'page.dropCardAria': 'Choose files to send',
      'page.chooseTitle': 'Choose files',
      'page.anySize': 'Photos, videos, documents — any size',
      'page.chooseFiles': 'Choose files',
      'page.dropHint': 'or drop them here',
      'page.quickPhoto': 'Photos & videos',
      'page.quickFiles': 'Any files',
      'page.quickFolder': 'Folder',
      'page.quickCamera': 'Camera',
      'page.transfer': 'Transfer',
      'page.clear': 'Clear',
      'page.sending': 'Sending…',
      'page.metaZero': '0 of 0',
      'page.unitMbps': 'MB/s',
      'page.bytesZero': '0 B / 0 B',
      'page.tipsTitle': 'Tips for maximum speed',
      'page.tip1': 'Keep the phone close to the router and use a 5 GHz network.',
      'page.tip2': 'Do not lock the screen — the browser may pause the transfer.',
      'page.tip3': 'Disable VPN on the phone: it may route traffic through the internet.',
      'page.footerLocal': 'Your local network only',
    },
  };

  const t = (key) => (I18N[curLang] && I18N[curLang][key]) || I18N.en[key] || key;
  const REPL = (s, args) => String(s).replace(/\{(\d+)\}/g, (m, i) => (args[i] ?? m));
  const DECIMAL_SEP = curLang === 'ru' ? ',' : '.';

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

  const DEFAULT_CHUNK = 8 * 1024 * 1024;
  const DEFAULT_PARALLEL = 6;
  const MAX_CHUNK_RETRIES = 4;

  const STATUS_LABELS = {
    pending: 'status.pending',
    init: 'status.init',
    uploading: 'status.uploading',
    finishing: 'status.finishing',
    done: 'status.done',
    error: 'status.error',
    cancelled: 'status.cancelled',
  };

  const ICONS = {
    stop: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="7" y="7" width="10" height="10" rx="2"/></svg>',
    remove: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7l10 10M17 7 7 17"/></svg>',
    retry: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 1 0-2.35 5.65"/><path d="M20 4v7h-7"/></svg>',
  };

  const els = {
    serverName: document.getElementById('serverName'),
    dropCard: document.getElementById('dropCard'),
    chooseBtn: document.getElementById('chooseBtn'),
    fileInput: document.getElementById('fileInput'),
    folderInput: document.getElementById('folderInput'),
    photoInput: document.getElementById('photoInput'),
    cameraInput: document.getElementById('cameraInput'),
    transferSection: document.getElementById('transferSection'),
    queue: document.getElementById('queue'),
    clearBtn: document.getElementById('clearBtn'),
    fileCount: document.getElementById('fileCount'),
    overallCard: document.getElementById('overallCard'),
    overallLabel: document.getElementById('overallLabel'),
    overallMeta: document.getElementById('overallMeta'),
    overallSpeed: document.getElementById('overallSpeed'),
    overallUnit: document.getElementById('overallUnit'),
    overallBar: document.getElementById('overallBar'),
    overallBytes: document.getElementById('overallBytes'),
    overallEta: document.getElementById('overallEta'),
    tips: document.getElementById('tips'),
    toast: document.getElementById('toast'),
    offlineBanner: document.getElementById('offlineBanner'),
  };

  const state = {
    config: null,
    transfers: [],
    pumpRunning: false,
    wakeLock: null,
    toastTimer: 0,
    dragDepth: 0,
    wasAllDone: false,
    lastFailureToast: 0,
    infoRetry: 0,
  };

  class TransferError extends Error {
    constructor(message, status = 0, aborted = false) {
      super(message);
      this.name = 'TransferError';
      this.status = status;
      this.aborted = aborted;
    }
  }

  init();

  function init() {
    document.documentElement.lang = curLang;
    applyI18n();
    bindEvents();
    updateOfflineState();
    loadInfo();
    setInterval(tick, 200);
    window.addEventListener('beforeunload', onBeforeUnload);
  }

  function bindEvents() {
    const inputs = {
      fileInput: els.fileInput,
      folderInput: els.folderInput,
      photoInput: els.photoInput,
      cameraInput: els.cameraInput,
    };

    const openPicker = (input) => {
      if (!input) return;
      input.value = '';
      input.click();
    };

    els.dropCard.addEventListener('click', () => openPicker(els.fileInput));
    els.dropCard.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openPicker(els.fileInput);
      }
    });

    els.chooseBtn.addEventListener('click', (event) => {
      event.stopPropagation();
      openPicker(els.fileInput);
    });

    document.querySelectorAll('[data-input]').forEach((button) => {
      button.addEventListener('click', () => {
        const input = inputs[button.dataset.input];
        openPicker(input);
      });
    });

    Object.values(inputs).forEach((input) => {
      input.addEventListener('change', () => {
        const files = Array.from(input.files || []);
        input.value = '';
        if (files.length) addFiles(files);
      });
    });

    els.dropCard.addEventListener('dragenter', (event) => {
      event.preventDefault();
      state.dragDepth += 1;
      els.dropCard.classList.add('dragover');
    });
    els.dropCard.addEventListener('dragover', (event) => {
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy';
      els.dropCard.classList.add('dragover');
    });
    els.dropCard.addEventListener('dragleave', () => {
      state.dragDepth = Math.max(0, state.dragDepth - 1);
      if (state.dragDepth === 0) els.dropCard.classList.remove('dragover');
    });
    els.dropCard.addEventListener('drop', (event) => {
      event.preventDefault();
      state.dragDepth = 0;
      els.dropCard.classList.remove('dragover');
      const files = Array.from(event.dataTransfer?.files || []);
      if (files.length) addFiles(files);
    });

    window.addEventListener('dragover', (event) => event.preventDefault());
    window.addEventListener('drop', (event) => event.preventDefault());

    els.clearBtn.addEventListener('click', clearFinished);

    window.addEventListener('online', () => {
      updateOfflineState();
      showToast(t('msg.reconnected'), 'success');
      loadInfo();
    });
    window.addEventListener('offline', updateOfflineState);

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && hasUnfinished()) requestWakeLock();
    });
  }

  async function loadInfo(attempt = 0) {
    try {
      const info = await apiJson('/api/info');
      state.config = {
        deviceName: info.deviceName || t('device.default'),
        chunkSize: Number(info.chunkSize) || DEFAULT_CHUNK,
        parallelChunks: Number(info.parallelChunks) || DEFAULT_PARALLEL,
      };
      state.infoRetry = 0;
      setText(els.serverName, REPL(t('tpl.serverReady'), [state.config.deviceName]));
      document.title = `FireDrop — ${state.config.deviceName}`;
    } catch (error) {
      if (error?.status === 401) return;
      if (attempt < 5) {
        setTimeout(() => loadInfo(attempt + 1), 1600);
      } else {
        setText(els.serverName, t('msg.noConnection'));
      }
    }
  }

  function addFiles(files) {
    const wasEmpty = state.transfers.length === 0;
    const wasHidden = els.transferSection.hidden;
    state.wasAllDone = false;

    for (const file of files) {
      const transfer = createTransfer(file);
      state.transfers.push(transfer);
      addRow(transfer);
    }

    els.transferSection.hidden = false;
    updateUi(true);

    if (wasEmpty || wasHidden) {
      window.setTimeout(() => {
        els.transferSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }, 90);
    }

    if (navigator.vibrate) navigator.vibrate(12);
    requestWakeLock();
    updateOfflineState();
    pump();
  }

  function createTransfer(file) {
    const relativePath = String(file.webkitRelativePath || '').replace(/\\/g, '/');
    const slash = relativePath.lastIndexOf('/');
    const folder = slash > 0 ? relativePath.slice(0, slash) : '';

    return {
      key: `t${Date.now().toString(36)}${Math.random().toString(36).slice(2, 9)}`,
      file,
      relativePath: folder,
      name: relativePath || file.name || 'file',
      size: Number(file.size) || 0,
      status: 'pending',
      stage: 'init',
      serverId: null,
      finalName: null,
      chunkSize: state.config?.chunkSize || DEFAULT_CHUNK,
      parallel: state.config?.parallelChunks || DEFAULT_PARALLEL,
      doneBytes: 0,
      inflight: new Map(),
      loadedBytes: 0,
      speed: 0,
      avgSpeed: 0,
      samples: [],
      error: '',
      chunkError: null,
      autoRestarts: 0,
      startedAt: 0,
      completedAt: 0,
      xhrs: new Set(),
      row: null,
      refs: null,
      actionIcon: '',
      lastPct: -1,
    };
  }

  function addRow(transfer) {
    const row = document.createElement('div');
    row.className = 'file-row';
    row.dataset.status = transfer.status;
    row.innerHTML = `
      <div class="file-icon"><span class="file-ext"></span></div>
      <div class="file-body">
        <div class="file-top">
          <span class="file-name"></span>
          <span class="file-pct">0%</span>
        </div>
        <div class="file-progress"><span></span></div>
        <div class="file-meta">
          <span class="file-status">${t('status.pending')}</span>
          <span class="file-speed"></span>
        </div>
      </div>
      <button class="file-action" type="button" aria-label="${t('aria.cancel')}"></button>
    `;

    const refs = {
      icon: row.querySelector('.file-icon'),
      ext: row.querySelector('.file-ext'),
      name: row.querySelector('.file-name'),
      pct: row.querySelector('.file-pct'),
      bar: row.querySelector('.file-progress > span'),
      status: row.querySelector('.file-status'),
      speed: row.querySelector('.file-speed'),
      action: row.querySelector('.file-action'),
    };

    const info = fileVisual(transfer.name);
    if (info.className) refs.icon.classList.add(info.className);
    refs.ext.textContent = info.label;
    refs.name.textContent = transfer.name;
    refs.name.title = transfer.name;

    transfer.row = row;
    transfer.refs = refs;
    refs.action.addEventListener('click', () => handleAction(transfer));

    els.queue.appendChild(row);
    renderRow(transfer);
  }

  function handleAction(transfer) {
    if (transfer.status === 'error') {
      retryTransfer(transfer);
    } else if (transfer.status === 'done') {
      removeTransfer(transfer);
    } else {
      cancelTransfer(transfer);
    }
  }

  async function pump() {
    if (state.pumpRunning) return;
    state.pumpRunning = true;
    try {
      while (true) {
        const next = state.transfers.find((transfer) => transfer.status === 'pending');
        if (!next) break;
        await uploadTransfer(next);
      }
    } finally {
      state.pumpRunning = false;
      updateUi();
      releaseWakeLockIfIdle();
    }
  }

  async function uploadTransfer(transfer) {
    if (transfer.status !== 'pending') return;

    transfer.status = 'init';
    transfer.stage = 'init';
    transfer.error = '';
    transfer.chunkError = null;
    transfer.startedAt = performance.now();
    transfer.completedAt = 0;
    transfer.doneBytes = 0;
    transfer.loadedBytes = 0;
    transfer.inflight.clear();
    transfer.samples = [];
    transfer.speed = 0;
    renderRow(transfer);
    requestWakeLock();

    try {
      const init = await withRetry(() => apiJson('/api/uploads', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: transfer.file.name || transfer.name,
          size: transfer.size,
          lastModified: Number.isFinite(transfer.file.lastModified) ? transfer.file.lastModified : null,
          relativePath: transfer.relativePath || null,
        }),
      }), 3);

      if (!init || !init.id) throw new TransferError(t('err.noId'));

      if (transfer.status === 'cancelled' || !state.transfers.includes(transfer)) {
        deleteUpload(init.id);
        return;
      }

      transfer.serverId = init.id;
      transfer.chunkSize = Math.max(64 * 1024, Number(init.chunkSize) || DEFAULT_CHUNK);
      transfer.parallel = Math.max(1, Math.min(8, Number(init.parallelChunks) || DEFAULT_PARALLEL));
      transfer.stage = 'chunks';

      if (transfer.size === 0) {
        await finishTransfer(transfer);
        return;
      }

      const totalChunks = Math.ceil(transfer.size / transfer.chunkSize);
      let nextIndex = 0;
      const workerCount = Math.min(transfer.parallel, totalChunks);
      const workers = [];

      transfer.status = 'uploading';
      renderRow(transfer);

      for (let worker = 0; worker < workerCount; worker += 1) {
        workers.push((async () => {
          while (true) {
            if (transfer.status !== 'uploading' || transfer.chunkError) return;
            const index = nextIndex;
            nextIndex += 1;
            if (index >= totalChunks) return;
            try {
              await uploadChunk(transfer, index);
            } catch (error) {
              if (!transfer.chunkError) transfer.chunkError = error;
              throw error;
            }
          }
        })());
      }

      const results = await Promise.allSettled(workers);
      if (transfer.status !== 'uploading') return;

      const failure = results.find((result) => result.status === 'rejected');
      if (failure) throw failure.reason;
      if (transfer.chunkError) throw transfer.chunkError;

      transfer.stage = 'complete';
      await finishTransfer(transfer);
    } catch (error) {
      if (transfer.status === 'cancelled') return;
      if ((error?.status === 404 || error?.status === 410) && transfer.autoRestarts < 1) {
        transfer.autoRestarts += 1;
        resetForFullRetry(transfer);
        return;
      }
      failTransfer(transfer, error);
    }
  }

  async function uploadChunk(transfer, index) {
    const start = index * transfer.chunkSize;
    const end = Math.min(start + transfer.chunkSize, transfer.size);
    const length = end - start;

    await withChunkRetry(transfer, index, async () => {
      await putChunk(transfer, index, start, length);
    });

    transfer.inflight.delete(index);
    transfer.doneBytes += length;
    transfer.loadedBytes = Math.min(transfer.size, transfer.doneBytes + inflightBytes(transfer));
  }

  async function withChunkRetry(transfer, index, operation) {
    let lastError = null;

    for (let attempt = 0; attempt < MAX_CHUNK_RETRIES; attempt += 1) {
      if (transfer.status !== 'uploading') {
        throw new TransferError(t('err.cancelled'), 0, true);
      }

      try {
        await operation();
        return;
      } catch (error) {
        lastError = error;
        transfer.inflight.delete(index);
        transfer.loadedBytes = Math.min(
          transfer.size,
          transfer.doneBytes + inflightBytes(transfer),
        );

        if (transfer.status !== 'uploading' || error?.aborted) throw error;
        if (error?.status === 401) {
          window.location.replace('/join');
          throw error;
        }

        const retryable = !error?.status ||
          error.status === 408 ||
          error.status === 429 ||
          error.status >= 500;
        if (!retryable || attempt === MAX_CHUNK_RETRIES - 1) throw error;

        await sleep(350 * (2 ** attempt));
      }
    }

    throw lastError || new TransferError(t('err.chunkFailed'));
  }

  function putChunk(transfer, index, start, length) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      let settled = false;

      const cleanup = () => {
        transfer.xhrs.delete(xhr);
        transfer.inflight.delete(index);
      };

      const fail = (error) => {
        if (settled) return;
        settled = true;
        cleanup();
        reject(error);
      };

      const succeed = () => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve();
      };

      xhr.open(
        'PUT',
        `/api/uploads/${encodeURIComponent(transfer.serverId)}/chunk?offset=${start}`,
        true,
      );
      xhr.timeout = 600000;
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');

      xhr.upload.onprogress = (event) => {
        if (!event.lengthComputable) return;
        transfer.inflight.set(index, Math.min(event.loaded, length));
        transfer.loadedBytes = Math.min(
          transfer.size,
          transfer.doneBytes + inflightBytes(transfer),
        );
      };

      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          transfer.inflight.set(index, length);
          succeed();
          return;
        }

        let message = REPL(t('tpl.errServer'), [xhr.status]);
        try {
          const body = JSON.parse(xhr.responseText);
          if (body?.error) message = body.error;
        } catch {
          // The server may return an empty body for some errors.
        }

        if (xhr.status === 401) window.location.replace('/join');
        fail(new TransferError(message, xhr.status));
      };

      xhr.onerror = () => fail(new TransferError(t('err.connectionLost')));
      xhr.ontimeout = () => fail(new TransferError(t('err.timeout')));
      xhr.onabort = () => fail(new TransferError(t('err.cancelled'), 0, true));

      transfer.xhrs.add(xhr);
      try {
        xhr.send(transfer.file.slice(start, start + length));
      } catch (error) {
        fail(new TransferError(error?.message || t('err.readFailed')));
      }
    });
  }

  async function finishTransfer(transfer) {
    transfer.status = 'finishing';
    transfer.stage = 'complete';
    transfer.chunkError = null;
    renderRow(transfer);

    try {
      const result = await withRetry(() => apiJson(
        `/api/uploads/${encodeURIComponent(transfer.serverId)}/complete`,
        { method: 'POST' },
      ), 3);

      if (!result || !result.ok) throw new TransferError(t('err.notConfirmed'));

      transfer.doneBytes = transfer.size;
      transfer.loadedBytes = transfer.size;
      transfer.completedAt = performance.now();
      transfer.finalName = result.file?.name || transfer.name;
      transfer.avgSpeed = Number(result.file?.speed) > 0
        ? Number(result.file.speed)
        : transfer.size / Math.max(0.05, (transfer.completedAt - transfer.startedAt) / 1000);
      transfer.speed = 0;

      if (transfer.finalName && transfer.finalName !== transfer.name) {
        transfer.name = transfer.finalName;
        if (transfer.refs) {
          transfer.refs.name.textContent = transfer.finalName;
          transfer.refs.name.title = transfer.finalName;
          const info = fileVisual(transfer.finalName);
          transfer.refs.icon.className = `file-icon ${info.className}`;
          transfer.refs.ext.textContent = info.label;
        }
      }

      transfer.status = 'done';
      renderRow(transfer);
      if (transfer.row) {
        transfer.row.classList.add('just-done');
        window.setTimeout(() => transfer.row?.classList.remove('just-done'), 750);
      }
      return 'done';
    } catch (error) {
      if ((error?.status === 404 || error?.status === 410) && transfer.autoRestarts < 1) {
        transfer.autoRestarts += 1;
        resetForFullRetry(transfer);
        return 'restart';
      }
      throw error;
    }
  }

  function failTransfer(transfer, error) {
    if (transfer.status === 'cancelled' || transfer.status === 'done') return;
    if (error?.status === 401) return;

    transfer.status = 'error';
    transfer.error = error?.message || t('err.sendFailed');
    transfer.speed = 0;
    transfer.chunkError = null;

    for (const xhr of transfer.xhrs) {
      try { xhr.abort(); } catch { }
    }
    transfer.xhrs.clear();
    transfer.inflight.clear();

    if (transfer.stage !== 'complete' && transfer.serverId) {
      deleteUpload(transfer.serverId);
    }

    renderRow(transfer);
    releaseWakeLockIfIdle();

    const now = Date.now();
    if (now - state.lastFailureToast > 3500) {
      state.lastFailureToast = now;
      showToast(REPL(t('tpl.sendNamed'), [shortName(transfer.name)]), 'error', 4200);
    }
  }

  function resetForFullRetry(transfer) {
    if (transfer.serverId) deleteUpload(transfer.serverId);
    transfer.serverId = null;
    transfer.finalName = null;
    transfer.stage = 'init';
    transfer.doneBytes = 0;
    transfer.loadedBytes = 0;
    transfer.inflight.clear();
    transfer.samples = [];
    transfer.speed = 0;
    transfer.avgSpeed = 0;
    transfer.error = '';
    transfer.chunkError = null;
    transfer.completedAt = 0;
    transfer.startedAt = 0;
    transfer.status = 'pending';
    renderRow(transfer);
  }

  async function retryTransfer(transfer) {
    if (transfer.status !== 'error') return;

    transfer.error = '';
    transfer.samples = [];
    transfer.speed = 0;
    transfer.autoRestarts = 0;

    if (transfer.stage === 'complete' && transfer.serverId && transfer.doneBytes >= transfer.size) {
      try {
        const outcome = await finishTransfer(transfer);
        if (outcome === 'restart') pump();
      } catch (error) {
        failTransfer(transfer, error);
      }
      return;
    }

    resetForFullRetry(transfer);
    requestWakeLock();
    pump();
  }

  function cancelTransfer(transfer) {
    if (transfer.status === 'done') {
      removeTransfer(transfer);
      return;
    }

    transfer.status = 'cancelled';
    for (const xhr of transfer.xhrs) {
      try { xhr.abort(); } catch { }
    }
    transfer.xhrs.clear();
    transfer.inflight.clear();

    if (transfer.serverId) deleteUpload(transfer.serverId);
    removeTransfer(transfer);
    showToast(t('msg.cancelled'));
    releaseWakeLockIfIdle();
  }

  function removeTransfer(transfer) {
    const index = state.transfers.indexOf(transfer);
    if (index >= 0) state.transfers.splice(index, 1);
    if (transfer.row) transfer.row.remove();
    transfer.row = null;
    transfer.refs = null;
    state.wasAllDone = false;
    updateUi();
    if (state.transfers.length === 0) {
      els.transferSection.hidden = true;
      releaseWakeLockIfIdle();
    }
  }

  function clearFinished() {
    const removable = state.transfers.filter((transfer) =>
      transfer.status === 'done' || transfer.status === 'error');
    for (const transfer of removable) {
      if (transfer.status === 'error' && transfer.serverId) deleteUpload(transfer.serverId);
      removeTransfer(transfer);
    }
    if (removable.length) showToast(t('msg.listCleared'));
  }

  function tick() {
    if (!state.transfers.length) {
      return;
    }

    const now = performance.now();
    for (const transfer of state.transfers) {
      if (isActive(transfer)) {
        transfer.samples.push({ t: now, b: transfer.loadedBytes });
        while (transfer.samples.length > 2 && now - transfer.samples[0].t > 2600) {
          transfer.samples.shift();
        }
        transfer.speed = calculateSpeed(transfer.samples, now);
        renderRow(transfer);
      }
    }

    updateUi();
  }

  function updateUi(force = false) {
    const transfers = state.transfers;
    const totalFiles = transfers.length;
    if (!totalFiles) {
      if (!els.transferSection.hidden) els.transferSection.hidden = true;
      return;
    }

    const active = transfers.filter(isActive);
    const done = transfers.filter((transfer) => transfer.status === 'done');
    const pending = transfers.filter((transfer) => transfer.status === 'pending');
    const errors = transfers.filter((transfer) => transfer.status === 'error');
    const totalBytes = transfers.reduce((sum, transfer) => sum + transfer.size, 0);
    const loadedBytes = transfers.reduce(
      (sum, transfer) => sum + Math.min(transfer.loadedBytes, transfer.size),
      0,
    );
    const allDone = done.length === totalFiles;
    const speed = active.reduce((sum, transfer) => sum + transfer.speed, 0);
    const parts = formatSpeedParts(speed);

    setText(els.fileCount, String(totalFiles));
    setText(els.overallMeta, REPL(t('tpl.meta'), [done.length, totalFiles]));
    setText(els.overallSpeed, parts.value);
    setText(els.overallUnit, parts.unit);
    setText(
      els.overallBytes,
      `${formatBytes(loadedBytes)} / ${formatBytes(totalBytes)}`,
    );

    let label = t('lbl.sendingFiles');
    if (allDone) label = t('lbl.allDone');
    else if (active.length) label = t('lbl.sending');
    else if (pending.length) label = t('lbl.preparing');
    else if (errors.length) label = t('lbl.hasErrors');
    setText(els.overallLabel, label);

    const progress = totalBytes > 0
      ? Math.min(100, (loadedBytes / totalBytes) * 100)
      : (allDone ? 100 : 0);
    els.overallBar.style.width = `${progress.toFixed(1)}%`;

    if (allDone) {
      const seconds = transfers.reduce(
        (max, transfer) => Math.max(max, transfer.completedAt - transfer.startedAt),
        0,
      ) / 1000;
      setText(els.overallEta, seconds > 0 ? REPL(t('tpl.etaDone'), [formatDuration(seconds)]) : t('msg.doneShort'));
    } else if (active.length && speed > 0 && totalBytes > loadedBytes) {
      setText(els.overallEta, REPL(t('tpl.etaLeft'), [formatDuration((totalBytes - loadedBytes) / speed)]));
    } else {
      setText(els.overallEta, '');
    }

    els.overallCard.classList.toggle('complete', allDone);
    els.overallCard.classList.toggle('has-error', errors.length > 0 && !allDone);

    const removable = done.length + errors.length > 0;
    els.clearBtn.hidden = !removable;
    els.clearBtn.disabled = !removable;

    if (allDone && !state.wasAllDone) {
      state.wasAllDone = true;
      if (navigator.vibrate) navigator.vibrate([22, 45, 30]);
      showToast(t('msg.allSaved'), 'success', 4200);
      releaseWakeLockIfIdle();
    } else if (!allDone) {
      state.wasAllDone = false;
    }

    if (force) {
      for (const transfer of transfers) renderRow(transfer);
    }
  }

  function renderRow(transfer) {
    if (!transfer.row || !transfer.refs) return;

    const percentValue = percentOf(transfer);
    const percent = `${percentValue}%`;

    if (percentValue !== transfer.lastPct) {
      transfer.lastPct = percentValue;
      setText(transfer.refs.pct, percent);
      transfer.refs.bar.style.width = `${percentValue}%`;
    }

    if (transfer.row.dataset.status !== transfer.status) {
      transfer.row.dataset.status = transfer.status;
    }

    let statusText = t(STATUS_LABELS[transfer.status]) || transfer.status;
    if (transfer.status === 'error' && transfer.error) statusText = transfer.error;
    setText(transfer.refs.status, statusText);

    let speedText = '';
    if (isActive(transfer)) {
      speedText = transfer.speed > 0 ? formatSpeed(transfer.speed) : '';
    } else if (transfer.status === 'done') {
      speedText = transfer.avgSpeed > 0 ? formatSpeed(transfer.avgSpeed) : t('msg.doneShort');
    } else if (transfer.status === 'pending') {
      speedText = formatBytes(transfer.size);
    }
    setText(transfer.refs.speed, speedText);

    let icon = ICONS.stop;
    let label = t('aria.cancel');
    if (transfer.status === 'error') {
      icon = ICONS.retry;
      label = t('aria.retry');
    } else if (transfer.status === 'done') {
      icon = ICONS.remove;
      label = t('aria.remove');
    }

    if (transfer.actionIcon !== icon) {
      transfer.actionIcon = icon;
      transfer.refs.action.innerHTML = icon;
    }
    transfer.refs.action.setAttribute('aria-label', label);
    transfer.refs.action.title = label;
  }

  function percentOf(transfer) {
    if (transfer.status === 'done') return 100;
    if (transfer.size <= 0) return transfer.status === 'error' ? 0 : 100;
    return Math.max(0, Math.min(100, Math.floor((transfer.loadedBytes / transfer.size) * 100)));
  }

  function isActive(transfer) {
    return transfer.status === 'init' ||
      transfer.status === 'uploading' ||
      transfer.status === 'finishing';
  }

  function hasUnfinished() {
    return state.transfers.some((transfer) =>
      transfer.status === 'pending' || isActive(transfer));
  }

  function inflightBytes(transfer) {
    let total = 0;
    for (const value of transfer.inflight.values()) total += value;
    return total;
  }

  function calculateSpeed(samples, now) {
    if (samples.length < 2) return 0;
    const newest = samples[samples.length - 1];
    if (now - newest.t > 1600) return 0;

    let oldest = samples[0];
    for (const sample of samples) {
      if (now - sample.t <= 2600) {
        oldest = sample;
        break;
      }
    }

    const seconds = (newest.t - oldest.t) / 1000;
    if (seconds < 0.15) return 0;
    return Math.max(0, (newest.b - oldest.b) / seconds);
  }

  function fileVisual(name) {
    const raw = String(name || '');
    const dot = raw.lastIndexOf('.');
    const extension = dot >= 0 ? raw.slice(dot + 1).toLowerCase() : '';
    const label = extension ? extension.slice(0, 4).toUpperCase() : 'FILE';

    const image = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'heif', 'bmp', 'svg', 'tif', 'tiff', 'avif', 'raw', 'dng'];
    const video = ['mp4', 'mov', 'mkv', 'avi', 'webm', 'm4v', '3gp', 'mts', 'm2ts'];
    const audio = ['mp3', 'wav', 'flac', 'aac', 'm4a', 'ogg', 'opus', 'wma'];
    const archive = ['zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'iso', 'dmg'];
    const doc = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'rtf', 'csv', 'epub', 'md'];

    if (image.includes(extension)) return { className: 'type-image', label };
    if (video.includes(extension)) return { className: 'type-video', label };
    if (audio.includes(extension)) return { className: 'type-audio', label };
    if (archive.includes(extension)) return { className: 'type-archive', label };
    if (doc.includes(extension)) return { className: 'type-doc', label };
    return { className: '', label };
  }

  function updateOfflineState() {
    els.offlineBanner.hidden = navigator.onLine;
    if (!navigator.onLine) setText(els.serverName, t('msg.noConnection'));
  }

  async function requestWakeLock() {
    if (!('wakeLock' in navigator) ||
        document.visibilityState !== 'visible' ||
        state.wakeLock ||
        !hasUnfinished()) {
      return;
    }

    try {
      state.wakeLock = await navigator.wakeLock.request('screen');
      state.wakeLock.addEventListener('release', () => {
        state.wakeLock = null;
      });
    } catch {
      state.wakeLock = null;
    }
  }

  function releaseWakeLockIfIdle() {
    if (hasUnfinished() || !state.wakeLock) return;
    try { state.wakeLock.release(); } catch { }
    state.wakeLock = null;
  }

  function deleteUpload(id) {
    if (!id) return;
    try {
      fetch(`/api/uploads/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        keepalive: true,
      }).catch(() => { });
    } catch {
      // Best effort only.
    }
  }

  async function withRetry(operation, attempts) {
    let lastError = null;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
      try {
        return await operation();
      } catch (error) {
        lastError = error;
        if (error?.status === 401) throw error;

        const retryable = !error?.status ||
          error.status === 408 ||
          error.status === 429 ||
          error.status >= 500;
        if (!retryable || attempt === attempts - 1) throw error;
        await sleep(450 * (2 ** attempt));
      }
    }
    throw lastError || new TransferError(t('err.requestFailed'));
  }

  async function apiJson(url, options = {}) {
    let response;
    try {
      response = await fetch(url, {
        cache: 'no-store',
        ...options,
        headers: {
          'Accept': 'application/json',
          ...(options.headers || {}),
        },
      });
    } catch {
      throw new TransferError(t('err.noConnectionWifi'));
    }

    if (response.status === 401) {
      window.location.replace('/join');
      throw new TransferError(t('err.needJoin'), 401);
    }

    if (!response.ok) {
      let message = REPL(t('tpl.errComputer'), [response.status]);
      try {
        const body = await response.json();
        if (body?.error) message = body.error;
      } catch {
        // Ignore malformed error bodies.
      }
      throw new TransferError(message, response.status);
    }

    if (response.status === 204) return null;
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  function showToast(message, type = '', duration = 3200) {
    if (!els.toast) return;
    window.clearTimeout(state.toastTimer);
    els.toast.textContent = message;
    els.toast.className = `toast show ${type}`.trim();
    state.toastTimer = window.setTimeout(() => {
      els.toast.className = 'toast';
    }, duration);
  }

  function onBeforeUnload(event) {
    if (!hasUnfinished()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  function formatBytes(bytes) {
    const units = t('bytes.units');
    if (!Number.isFinite(bytes) || bytes <= 0) return `0\u00a0${units[0]}`;
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    const digits = value >= 100 || unit === 0 ? 0 : (value >= 10 ? 1 : 2);
    return `${value.toFixed(digits).replace('.', DECIMAL_SEP)}\u00a0${units[unit]}`;
  }

  function formatSpeed(bytesPerSecond) {
    const parts = formatSpeedParts(bytesPerSecond);
    return `${parts.value}\u00a0${parts.unit}`;
  }

  function formatSpeedParts(bytesPerSecond) {
    if (!Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) {
      return { value: '0', unit: t('unit.speedMB') };
    }

    const megabytes = bytesPerSecond / (1024 * 1024);
    if (megabytes >= 1024) {
      return { value: (megabytes / 1024).toFixed(1).replace('.', DECIMAL_SEP), unit: t('unit.speedGB') };
    }
    if (megabytes >= 1) {
      return { value: megabytes.toFixed(1).replace('.', DECIMAL_SEP), unit: t('unit.speedMB') };
    }
    return {
      value: Math.max(0, Math.round(bytesPerSecond / 1024)).toString(),
      unit: t('unit.speedKB'),
    };
  }

  function formatDuration(seconds) {
    if (!Number.isFinite(seconds) || seconds <= 0) return t('dur.zero');
    if (seconds < 1) return t('dur.moment');
    if (seconds < 60) return REPL(t('tpl.durSeconds'), [Math.ceil(seconds)]);
    if (seconds < 3600) {
      return REPL(t('tpl.durMinutes'), [Math.ceil(seconds / 60)]);
    }
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.ceil((seconds % 3600) / 60);
    return REPL(t('tpl.durHours'), [hours, minutes]);
  }

  function shortName(name) {
    if (!name) return t('msg.file');
    return name.length > 32 ? `${name.slice(0, 29)}…` : name;
  }

  function setText(element, value) {
    if (element && element.textContent !== value) element.textContent = value;
  }

  function sleep(milliseconds) {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
  }
})();
