package ru.firedrop.app;

/**
 * Strings of the receive flow and the embedded receiver server.
 */
final class L10nR {
    private static final String[][] T = {
            /* ReceiveActivity */
            {"recv.title", "Принять", "Receive"},
            {"recv.subtitle", "Другое устройство отправит файлы на этот телефон",
                    "Another device will send files to this phone"},
            {"recv.back", "← Назад", "← Back"},
            {"recv.pin_label", "Код подключения", "Connection code"},
            {"recv.chip.off", "выключено", "off"},
            {"recv.chip.on", "включён", "on"},
            {"recv.hint", "Откройте FireDrop на компьютере или телефоне, нажмите «Отправить» "
                    + "и отсканируйте этот QR-код либо введите адрес и код вручную.",
                    "Open FireDrop on the computer or phone, tap “Send” and scan this QR "
                    + "code, or enter the address and code manually."},
            {"recv.stats.title", "Принято", "Received"},
            {"recv.stats.empty", "Пока ничего не принято", "Nothing received yet"},
            {"recv.stats.empty_folder", "Пока ничего не принято. Папка: Download/FireDrop",
                    "Nothing received yet. Folder: Download/FireDrop"},
            {"recv.stats.count", "Файлов: %1$d · %2$s · папка Download/FireDrop",
                    "Files: %1$d · %2$s · folder Download/FireDrop"},
            {"recv.stop", "Остановить приём", "Stop receiving"},
            {"recv.start", "Включить приём", "Start receiving"},
            {"recv.open_downloads", "Открыть папку «Загрузки»", "Open the Downloads folder"},
            {"recv.stopped", "Приём остановлен", "Receiving stopped"},
            {"recv.no_wifi", "Нет подключения к Wi-Fi", "No Wi-Fi connection"},
            {"recv.vpn_hint", "На телефоне включён VPN — он может уводить соединение "
                    + "телефона с Wi-Fi. Если компьютер или другой телефон не "
                    + "подключается, отключите VPN.",
                    "A VPN is on on this phone — it can pull the connection away from "
                    + "Wi-Fi. If the computer or another phone cannot connect, turn off "
                    + "the VPN."},
            {"recv.subnet_hint", "Адрес не из домашней подсети (обычно 192.168.x.x): "
                    + "похоже, телефон в гостевой или другой Wi-Fi сети, в мобильном "
                    + "интернете или под VPN. Компьютер из другой подсети сюда не "
                    + "дозвонится — подключите телефон к той же Wi-Fi сети, что и он.",
                    "The address is not from the home subnet (usually 192.168.x.x): the "
                    + "phone seems to be on a guest or another Wi-Fi network, on mobile "
                    + "data, or behind a VPN. A computer from another subnet cannot reach "
                    + "it — connect the phone to the same Wi-Fi network as the computer."},
            {"recv.toast_new_file", "Получен новый файл", "New file received"},
            {"recv.of", "%1$s из %2$s", "%1$s of %2$s"},
            {"recv.folder_dialog.title", "Папка с файлами", "Folder with files"},
            {"recv.folder_dialog.message", "Откройте проводник или приложение «Файлы» и перейдите в:\n\n"
                    + "Загрузки / Download → FireDrop\n\n"
                    + "Вложенные папки сохраняются как на отправителе.",
                    "Open a file manager or the Files app and go to:\n\n"
                    + "Downloads / Download → FireDrop\n\n"
                    + "Nested folders are kept as they were on the sender."},
            {"recv.folder_dialog.ok", "Понятно", "Got it"},
            {"recv.speed", "%1$.1f МБ/с", "%1$.1f MB/s"},

            /* ReceiverServer (HTTP API messages) */
            {"rserver.device.default", "Телефон", "Phone"},
            {"rserver.port_failed", "Не удалось открыть порт", "Could not open the port"},
            {"rserver.error.bad_request", "Некорректный запрос.", "Bad request."},
            {"rserver.error.internal", "Внутренняя ошибка приёма файла.",
                    "Internal error while receiving the file."},
            {"rserver.error.unauthorized", "Требуется подключение к FireDrop.",
                    "A FireDrop connection is required."},
            {"rserver.error.not_found", "Не найдено.", "Not found."},
            {"rserver.error.rate_limited", "Слишком много попыток. Подождите пару минут.",
                    "Too many attempts. Please wait a couple of minutes."},
            {"rserver.error.bad_pin", "Неверный код. Проверьте цифры на экране телефона.",
                    "Wrong code. Check the digits shown on the phone screen."},
            {"rserver.error.bad_size", "Некорректный размер файла.", "Invalid file size."},
            {"rserver.error.no_space", "На телефоне недостаточно места.",
                    "Not enough free space on the phone."},
            {"rserver.error.session_missing", "Передача не найдена.", "Transfer not found."},
            {"rserver.error.session_aborted", "Передача отменена.", "Transfer cancelled."},
            {"rserver.error.already_received", "Файл уже принят.",
                    "The file has already been received."},
            {"rserver.error.bad_chunk_size", "Некорректный размер части файла.",
                    "Invalid chunk size."},
            {"rserver.error.chunk_incomplete", "Часть файла пришла не полностью.",
                    "A chunk arrived incomplete."},
            {"rserver.error.incomplete", "Передача ещё не завершена (не хватает частей: %1$d).",
                    "Transfer not finished yet (%1$d chunk(s) missing)."},
            {"rserver.error.save_failed", "Не удалось сохранить файл в папке «Загрузки».",
                    "Could not save the file to the Downloads folder."},
    };

    static String get(String key) {
        return L10n.lookup(T, key);
    }
}
