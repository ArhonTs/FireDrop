package ru.firedrop.app;

/** Strings of the send flow and upload task. */
final class L10nS {
    private static final String[][] T = {
            // SendActivity: screen and bottom bar.
            {"send.title", "Отправить", "Send"},
            {"send.subtitle", "Выберите получателя и файлы", "Choose a recipient and files"},
            {"send.back", "← Назад", "← Back"},
            {"send.status.none", "Файлы ещё не выбраны", "No files selected yet"},
            {"send.button.again", "Отправить ещё", "Send more"},
            {"send.running.hint", "Идёт передача… Не выключайте экран.",
                    "Transfer in progress… Keep the screen on."},

            // SendActivity: target card.
            {"send.target.title", "Получатель", "Recipient"},
            {"send.target.hint", "Найдите устройство в сети или введите адрес вручную.",
                    "Find a device on the network or enter the address manually."},
            {"send.find", "Найти рядом", "Find nearby"},
            {"send.pin.hint", "Код из 6 цифр", "6-digit code"},
            {"send.scan", "Сканировать QR-код", "Scan QR code"},
            {"send.rows.limit", "Показаны первые %1$d файлов из %2$d",
                    "Showing the first %1$d files of %2$d"},
            {"send.searching", "Ищем устройства…", "Searching for devices…"},
            {"send.notfound.hint",
                    "Устройства не найдены. Проверьте, что оба подключены к одной Wi-Fi "
                            + "сети (адрес компьютера — вида 192.168.x.x), выключите VPN и "
                            + "мобильный интернет, затем введите адрес вручную.",
                    "No devices found. Make sure both devices are connected to the same "
                            + "Wi-Fi network (the computer address looks like 192.168.x.x), "
                            + "turn off VPN and mobile data, then enter the address manually."},
            {"send.tap.device", "Нажмите на устройство, чтобы подставить адрес.",
                    "Tap a device to fill in its address."},
            {"send.pin.phone", "Введите код, который показан на экране телефона",
                    "Enter the code shown on the phone screen"},
            {"send.pin.pc", "Введите код, который показан на экране компьютера",
                    "Enter the code shown on the computer screen"},
            {"send.kind.phone", "телефон", "phone"},
            {"send.kind.pc", "ПК", "PC"},

            // SendActivity: source card.
            {"send.source.title", "Что отправить", "What to send"},
            {"send.none", "Ничего не выбрано", "Nothing selected"},
            {"send.files", "Файлы", "Files"},
            {"send.folder", "Папка", "Folder"},
            {"send.merge.none", "Новых файлов нет", "No new files"},
            {"send.merge.added", "Добавлено файлов: %1$d", "Added %1$d files"},
            {"send.merge.skipped", " · пропущено: %1$d", " · %1$d skipped"},
            {"send.more.files", "и ещё %1$d файлов", "and %1$d more files"},

            // SendActivity: transfer card.
            {"send.transfer.title", "Передача", "Transfer"},
            {"send.chip.wifi", "по Wi-Fi", "via Wi-Fi"},
            {"send.preparing", "Подготовка…", "Preparing…"},
            {"send.of", "из", "of"},
            {"send.state.error", "ошибка", "error"},
            {"send.state.done", "готово · %1$s", "done · %1$s"},
            {"send.state.queue", "в очереди", "queued"},

            // SendActivity: picking and QR.
            {"send.no.filepicker", "На этом устройстве нет файлового менеджера",
                    "No file manager on this device"},            {"send.no.folderpicker", "Выбор папки недоступен на этом устройстве",
                    "Folder picking is unavailable on this device"},
            {"send.reading.files", "Читаем выбранные файлы…", "Reading the selected files…"},
            {"send.reading.folder", "Читаем папку…", "Reading the folder…"},
            {"send.qr.notfiredrop", "Это не QR-код FireDrop", "This is not a FireDrop QR code"},
            {"send.qr.bad", "QR-код не распознан. Введите адрес и код вручную.",
                    "QR code not recognized. Enter the address and code manually."},
            {"send.scanner.unavailable", "Сканер недоступен. Введите адрес и код вручную.",
                    "Scanner unavailable. Enter the address and code manually."},
            {"send.qr.applied", "Получатель подставлен. Выберите файлы.",
                    "Recipient filled in. Pick files."},
            {"send.link.applied", "Ссылка с компьютера распознана. Код не нужен.",
                    "Computer link recognized. No code needed."},

            // SendActivity: summary and validation toasts.
            {"send.status.pick", "Выберите файлы или папку", "Pick files or a folder"},
            {"send.folders.empty", "Пустых папок: %1$d", "Empty folders: %1$d"},
            {"send.ready.folders", "Готово к отправке · только папки", "Ready to send · folders only"},
            {"send.files.count", "Файлов: %1$d · %2$s", "Files: %1$d · %2$s"},
            {"send.ready.bytes", "Готово к отправке · %1$s", "Ready to send · %1$s"},
            {"send.button.emptyFolders", "Отправить пустые папки", "Send empty folders"},
            {"send.button.count", "Отправить · %1$d", "Send · %1$d"},
            {"send.need.pick", "Сначала выберите файлы или папку", "Pick files or a folder first"},
            {"send.need.host", "Укажите адрес компьютера или телефона",
                    "Enter the address of the computer or phone"},
            {"send.need.pin", "Введите шестизначный код с экрана получателя",
                    "Enter the six-digit code from the recipient's screen"},

            // SendActivity: progress and finish.
            {"send.cancel", "Отмена", "Cancel"},
            {"send.connecting", "Подключение…", "Connecting…"},
            {"send.progress.title", "Передача · %1$d%%", "Transfer · %1$d%%"},
            {"send.ttl", " · осталось %1$s", " · %1$s left"},
            {"send.done.count", "Файлов готово: %1$d из %2$d%3$s", "Files done: %1$d of %2$d%3$s"},
            {"send.finished", "Готово", "Done"},
            {"send.finished.errors", "Завершено с ошибками", "Finished with errors"},
            {"send.meta.total", "%1$s за %2$s · %3$s", "%1$s in %2$s · %3$s"},
            {"send.failed.count", "Не удалось отправить файлов: %1$d", "Failed to send %1$d files"},
            {"send.all.saved", "Все файлы переданы. Получатель сохранил их в папку загрузок.",
                    "All files transferred. The recipient saved them to the downloads folder."},
            {"send.status.done", "Передача завершена", "Transfer complete"},
            {"send.status.errors", "Есть ошибки передачи", "The transfer has errors"},
            {"send.dur.sec", "%1$d с", "%1$d s"},
            {"send.dur.hours", "%1$d ч %2$d мин", "%1$d h %2$d min"},

            // SendActivity: cancel dialog.
            {"send.confirm.title", "Отменить передачу?", "Cancel the transfer?"},
            {"send.confirm.message", "Незавершённые файлы не будут сохранены.",
                    "Unfinished files will not be saved."},
            {"send.confirm.cancel", "Отменить передачу", "Cancel the transfer"},
            {"send.confirm.continue", "Продолжить", "Continue"},
            {"send.cancelling", "Отмена…", "Cancelling…"},

            // UploadTask: fatal errors.
            {"upload.fail", "Не удалось завершить передачу: %1$s",
                    "Failed to finish the transfer: %1$s"},
            {"upload.cancelled", "Передача отменена", "Transfer cancelled"},
            {"upload.failed.count", "Не удалось отправить файлов: %1$d", "Failed to send %1$d files"},

            // UploadTask: transfer protocol errors.
            {"upload.no.id", "Сервер не выдал идентификатор передачи",
                    "The server did not issue a transfer id"},
            {"upload.no.confirm", "Сервер не подтвердил сохранение файла",
                    "The server did not confirm saving the file"},
            {"upload.chunk.failed", "Часть файла не отправилась", "A chunk of the file failed to send"},
            {"upload.no.access", "Нет доступа к файлу %1$s", "No access to file %1$s"},
            {"upload.file.ended", "Файл закончился раньше времени", "The file ended too early"},
            {"upload.file.changed", "Файл изменился во время передачи",
                    "The file changed during the transfer"},

            // UploadTask.describe(): short network error labels.
            {"upload.err.unknown", "неизвестная ошибка", "unknown error"},
            {"upload.err.refused", "устройство не отвечает", "the device is not responding"},
            {"upload.err.timeout", "превышено время ожидания", "the connection timed out"},
            {"upload.err.unreachable", "нет связи по локальной сети",
                    "no local network connection"},
    };

    static String get(String key) {
        return L10n.lookup(T, key);
    }
}
