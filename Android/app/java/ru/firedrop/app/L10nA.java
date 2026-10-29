package ru.firedrop.app;

/** Strings of the main screen, about, prefs and small helpers. */
final class L10nA {
    private static final String[][] T = {
            // MainActivity: main screen.
            {"main.subtitle", "Файлы и папки по Wi-Fi. Без интернета и облака.",
                    "Files and folders over Wi-Fi. No internet, no cloud."},
            {"main.send.title", "Отправить", "Send"},
            {"main.send.subtitle", "Компьютер или другой телефон в этой же сети Wi-Fi",
                    "A computer or another phone on the same Wi-Fi network"},
            {"main.send.pick", "Выбрать файлы или папку", "Pick files or a folder"},
            {"main.receive.title", "Принять", "Receive"},
            {"main.chip.off", "выключено", "off"},
            {"main.chip.on", "включён", "on"},
            {"main.receive.subtitle", "Другой телефон или компьютер отправит файлы прямо на этот аппарат",
                    "Another phone or computer will send files straight to this device"},
            {"main.receive.enable", "Включить приём", "Turn receiving on"},
            {"main.receive.open", "Открыть экран приёма", "Open the receive screen"},
            {"main.settings.title", "Настройки", "Settings"},
            {"main.settings.device", "Имя устройства", "Device name"},
            {"main.settings.port", "Порт приёма", "Receive port"},
            {"main.settings.folder", "Папка приёма", "Receive folder"},
            {"main.settings.language", "Язык", "Language"},
            {"lang.auto", "Системный", "System"},
            {"lang.ru", "Русский", "Russian"},
            {"lang.en", "English", "English"},
            {"main.how.title", "Как это работает", "How it works"},
            {"main.step1", "1. Запустите FireDrop на компьютере или включите «Принять» на другом телефоне.",
                    "1. Start FireDrop on a computer or turn on \u201cReceive\u201d on another phone."},
            {"main.step2", "2. Нажмите «Отправить», найдите устройство и введите шестизначный код.",
                    "2. Tap \u201cSend\u201d, find the device and enter the six-digit code."},
            {"main.step3", "3. Выберите файлы или целую папку — структура папок сохранится.",
                    "3. Pick files or a whole folder — the folder structure is preserved."},
            {"main.footer", "FireDrop %1$s · передача не выходит за пределы локальной сети",
                    "FireDrop %1$s · transfers never leave the local network"},
            {"main.storage.denied", "Без доступа к памяти принятые файлы не сохранятся в «Загрузки».",
                    "Without storage access, received files will not be saved to Downloads."},
            {"main.device.hint", "Например: Телефон Ани", "For example: Anne's phone"},
            {"main.device.message", "Так вас увидят другие устройства в сети.",
                    "This is how other devices on the network will see you."},
            {"main.save", "Сохранить", "Save"},
            {"main.cancel", "Отмена", "Cancel"},
            {"main.ok", "Понятно", "Got it"},
            {"main.device.apply", "Имя применится после повторного включения приёма",
                    "The new name will apply after receiving is turned on again"},
            {"main.port.message", "По умолчанию 47820. Меняйте только если порт занят.",
                    "Defaults to 47820. Change it only if the port is taken."},
            {"main.port.saved", "Порт сохранён. Перезапустите приём.", "Port saved. Restart receiving."},
            {"main.port.invalid", "Нужен порт от 1024 до 65535",
                    "The port must be between 1024 and 65535"},
            {"main.folder.message", "Принятые файлы сохраняются в общую папку «Загрузки»:\n\n"
                    + "Download/FireDrop\n\nСтруктура вложенных папок сохраняется. "
                    + "Файлы видны в любом проводнике и в галерее.",
                    "Received files are saved to the shared Downloads folder:\n\n"
                    + "Download/FireDrop\n\nThe folder structure is preserved. "
                    + "Files show up in any file manager and in the gallery."},

            // Default device name (Prefs / MainActivity), shown to peers over the wire.
            {"disc.phone.default", "Телефон", "Phone"},

            // AboutActivity.
            {"about.title", "О приложении", "About the app"},
            {"about.back", "← Назад", "← Back"},
            {"about.footer", "FireDrop %1$s", "FireDrop %1$s"},
            {"about.hero.title", "Что такое FireDrop", "What FireDrop is"},
            {"about.hero.body", "FireDrop — быстрый обмен файлами и целыми папками по локальной Wi-Fi сети: "
                    + "с компьютера на телефон, с телефона на компьютер и с телефона на телефон. "
                    + "Работает без интернета, облака и регистрации — достаточно, чтобы устройства "
                    + "были подключены к одной сети.",
                    "FireDrop is a fast way to exchange files and whole folders over the local "
                    + "Wi-Fi network: from computer to phone, from phone to computer and from "
                    + "phone to phone. It works without internet, cloud or registration — the "
                    + "devices only need to be connected to the same network."},
            {"about.facts.title", "Главное", "Highlights"},
            {"about.fact1", "• Папки передаются целиком, структура вложенных каталогов сохраняется.",
                    "• Folders are transferred whole; the structure of nested directories is preserved."},
            {"about.fact2", "• Скорость ограничена только Wi-Fi сетью: без облака в пути.",
                    "• Speed is limited only by the Wi-Fi network: no cloud in between."},
            {"about.fact3", "• Подключение по QR-коду или шестизначному коду, данные не уходят в интернет.",
                    "• Connect with a QR code or a six-digit code; no data goes to the internet."},
            {"about.fact4", "• Принятые файлы не перезаписываются: рядом появляется «имя (1)», «имя (2)»…",
                    "• Received files are never overwritten: a \u201cname (1)\u201d, \u201cname (2)\u201d… copy appears next to them."},
            {"about.author.title", "Автор", "Author"},
            {"about.author.body", "Приложение сделал @LoioshS. Идеи, вопросы и сообщения о проблемах можно "
                    + "присылать прямо в Telegram.",
                    "The app was made by @LoioshS. Ideas, questions and bug reports are welcome "
                    + "right in Telegram."},
            {"about.author.button", "Написать автору · @LoioshS", "Message the author · @LoioshS"},
            {"about.author.fail", "Нет браузера или Telegram. Ссылка: t.me/LoioshS",
                    "No browser or Telegram. Link: t.me/LoioshS"},

            // Net.java: device subtitles in the discovered list.
            {"net.device.phone", "телефон", "phone"},
            {"net.device.pc", "компьютер", "computer"},

            // Http.java: error messages.
            {"http.server.error", "Ошибка сервера (%1$d)", "Server error (%1$d)"},
            {"http.transfer.error", "Ошибка передачи (%1$d)", "Transfer error (%1$d)"},

            // Store.java: writing into Downloads.
            {"store.save.failed", "Не удалось создать файл в папке «Загрузки»",
                    "Could not create the file in the Downloads folder"},
            {"store.downloads.unavailable", "Нет доступа к папке «Загрузки»",
                    "No access to the Downloads folder"},
            {"store.folder.failed", "Не удалось создать папку %1$s", "Could not create the folder %1$s"},

            // Picker.java: fallback name for the selected tree root.
            {"pick.folder.default", "Папка", "Folder"},

            // ReceiverService.java: foreground notification.
            {"svc.receiving", "Приём файлов", "Receiving files"},
            {"svc.receiving.code", "Приём включён · код %1$s", "Receiving on · code %1$s"},
            {"svc.received.count", "Получено файлов: %1$d · %2$s", "Files received: %1$d · %2$s"},
            {"svc.waiting", "Откройте FireDrop на другом устройстве",
                    "Open FireDrop on another device"},
            {"svc.stop", "Остановить", "Stop"},
    };

    private L10nA() {
    }

    static String get(String key) {
        return L10n.lookup(T, key);
    }
}
