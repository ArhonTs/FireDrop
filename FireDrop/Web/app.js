(() => {
  'use strict';

  const DEFAULT_CHUNK = 8 * 1024 * 1024;
  const DEFAULT_PARALLEL = 6;
  const MAX_CHUNK_RETRIES = 4;

  const STATUS_LABELS = {
    pending: 'В очереди',
    init: 'Подготовка',
    uploading: 'Передача',
    finishing: 'Сохранение',
    done: 'Готово',
    error: 'Ошибка',
    cancelled: 'Отменено',
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
      showToast('Соединение восстановлено', 'success');
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
        deviceName: info.deviceName || 'Компьютер',
        chunkSize: Number(info.chunkSize) || DEFAULT_CHUNK,
        parallelChunks: Number(info.parallelChunks) || DEFAULT_PARALLEL,
      };
      state.infoRetry = 0;
      setText(els.serverName, `${state.config.deviceName} · готов принять`);
      document.title = `FireDrop — ${state.config.deviceName}`;
    } catch (error) {
      if (error?.status === 401) return;
      if (attempt < 5) {
        setTimeout(() => loadInfo(attempt + 1), 1600);
      } else {
        setText(els.serverName, 'Нет связи с компьютером');
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
          <span class="file-status">В очереди</span>
          <span class="file-speed"></span>
        </div>
      </div>
      <button class="file-action" type="button" aria-label="Отменить передачу"></button>
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

      if (!init || !init.id) throw new TransferError('Компьютер не выдал идентификатор передачи.');

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
        throw new TransferError('Передача отменена', 0, true);
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

    throw lastError || new TransferError('Не удалось отправить часть файла.');
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

        let message = `Ошибка сервера (${xhr.status})`;
        try {
          const body = JSON.parse(xhr.responseText);
          if (body?.error) message = body.error;
        } catch {
          // The server may return an empty body for some errors.
        }

        if (xhr.status === 401) window.location.replace('/join');
        fail(new TransferError(message, xhr.status));
      };

      xhr.onerror = () => fail(new TransferError('Связь с компьютером прервалась.'));
      xhr.ontimeout = () => fail(new TransferError('Компьютер слишком долго не отвечает.'));
      xhr.onabort = () => fail(new TransferError('Передача отменена', 0, true));

      transfer.xhrs.add(xhr);
      try {
        xhr.send(transfer.file.slice(start, start + length));
      } catch (error) {
        fail(new TransferError(error?.message || 'Не удалось прочитать файл.'));
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

      if (!result || !result.ok) throw new TransferError('Компьютер не подтвердил сохранение файла.');

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
    transfer.error = error?.message || 'Не удалось отправить файл';
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
      showToast(`Не удалось отправить «${shortName(transfer.name)}»`, 'error', 4200);
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
    showToast('Передача отменена');
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
    if (removable.length) showToast('Список очищен');
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
    setText(els.overallMeta, `${done.length} из ${totalFiles} файлов`);
    setText(els.overallSpeed, parts.value);
    setText(els.overallUnit, parts.unit);
    setText(
      els.overallBytes,
      `${formatBytes(loadedBytes)} / ${formatBytes(totalBytes)}`,
    );

    let label = 'Отправка файлов';
    if (allDone) label = 'Все файлы на компьютере';
    else if (active.length) label = 'Отправка на компьютер';
    else if (pending.length) label = 'Подготовка к отправке';
    else if (errors.length) label = 'Есть ошибки';
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
      setText(els.overallEta, seconds > 0 ? `за ${formatDuration(seconds)}` : 'готово');
    } else if (active.length && speed > 0 && totalBytes > loadedBytes) {
      setText(els.overallEta, `осталось ${formatDuration((totalBytes - loadedBytes) / speed)}`);
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
      showToast('Готово! Файлы сохранены на компьютере.', 'success', 4200);
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

    let statusText = STATUS_LABELS[transfer.status] || transfer.status;
    if (transfer.status === 'error' && transfer.error) statusText = transfer.error;
    setText(transfer.refs.status, statusText);

    let speedText = '';
    if (isActive(transfer)) {
      speedText = transfer.speed > 0 ? formatSpeed(transfer.speed) : '';
    } else if (transfer.status === 'done') {
      speedText = transfer.avgSpeed > 0 ? formatSpeed(transfer.avgSpeed) : 'готово';
    } else if (transfer.status === 'pending') {
      speedText = formatBytes(transfer.size);
    }
    setText(transfer.refs.speed, speedText);

    let icon = ICONS.stop;
    let label = 'Отменить передачу';
    if (transfer.status === 'error') {
      icon = ICONS.retry;
      label = 'Повторить передачу';
    } else if (transfer.status === 'done') {
      icon = ICONS.remove;
      label = 'Убрать из списка';
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
    if (!navigator.onLine) setText(els.serverName, 'Нет связи с компьютером');
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
    throw lastError || new TransferError('Не удалось выполнить запрос.');
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
      throw new TransferError('Связь с компьютером прервалась. Проверьте Wi-Fi.');
    }

    if (response.status === 401) {
      window.location.replace('/join');
      throw new TransferError('Требуется подключение к FireDrop.', 401);
    }

    if (!response.ok) {
      let message = `Ошибка компьютера (${response.status})`;
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
    if (!Number.isFinite(bytes) || bytes <= 0) return '0\u00a0Б';
    const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    const digits = value >= 100 || unit === 0 ? 0 : (value >= 10 ? 1 : 2);
    return `${value.toFixed(digits).replace('.', ',')}\u00a0${units[unit]}`;
  }

  function formatSpeed(bytesPerSecond) {
    const parts = formatSpeedParts(bytesPerSecond);
    return `${parts.value}\u00a0${parts.unit}`;
  }

  function formatSpeedParts(bytesPerSecond) {
    if (!Number.isFinite(bytesPerSecond) || bytesPerSecond <= 0) {
      return { value: '0', unit: 'МБ/с' };
    }

    const megabytes = bytesPerSecond / (1024 * 1024);
    if (megabytes >= 1024) {
      return { value: (megabytes / 1024).toFixed(1).replace('.', ','), unit: 'ГБ/с' };
    }
    if (megabytes >= 1) {
      return { value: megabytes.toFixed(1).replace('.', ','), unit: 'МБ/с' };
    }
    return {
      value: Math.max(0, Math.round(bytesPerSecond / 1024)).toString(),
      unit: 'КБ/с',
    };
  }

  function formatDuration(seconds) {
    if (!Number.isFinite(seconds) || seconds <= 0) return '0 сек';
    if (seconds < 1) return 'меньше секунды';
    if (seconds < 60) return `${Math.ceil(seconds)} сек`;
    if (seconds < 3600) {
      const minutes = Math.ceil(seconds / 60);
      return `${minutes} мин`;
    }
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.ceil((seconds % 3600) / 60);
    return `${hours} ч ${minutes} мин`;
  }

  function shortName(name) {
    if (!name) return 'файл';
    return name.length > 32 ? `${name.slice(0, 29)}…` : name;
  }

  function setText(element, value) {
    if (element && element.textContent !== value) element.textContent = value;
  }

  function sleep(milliseconds) {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
  }
})();
