namespace FireDrop;

/// User-facing strings of the Core layer.
internal static class L10nCore
{
    // { key, ru, en }
    private static readonly string[][] Table =
    {
        // FirewallHelper
        new[] { "core.firewall.check.failed", "Не удалось проверить брандмауэр (недостаточно прав для чтения).", "Could not check the firewall (not enough permission to read it)." },
        new[] { "core.firewall.ok", "FireDrop уже разрешён в брандмауэре для этого пути и порта.", "FireDrop is already allowed in the firewall for this path and port." },
        new[] { "core.firewall.missing", "Правила для FireDrop не найдены. Если другие устройства не подключаются — разрешите FireDrop в сети.", "No rules for FireDrop were found. If other devices cannot connect, allow FireDrop in your network." },
        new[] { "core.firewall.nopath", "Не удалось определить путь программы для правила.", "Could not determine the program path for the rule." },
        new[] { "core.firewall.uac.timeout", "Windows ждала подтверждения UAC слишком долго. Нажмите ещё раз и подтвердите.", "Windows waited too long for the UAC confirmation. Click again and confirm." },
        new[] { "core.firewall.create.failed", "Правила не удалось создать (нужно подтверждение администратора).", "Could not create the rules (an administrator confirmation is required)." },
        new[] { "core.firewall.created", "Готово: входящие подключения для FireDrop разрешены.", "Done: inbound connections for FireDrop are now allowed." },

        // UploadManager
        new[] { "core.upload.badsize", "Некорректный размер файла.", "Invalid file size." },
        new[] { "core.upload.create.failed", "Не удалось создать файл на диске. Проверьте папку сохранения.", "Could not create the file on disk. Check the save folder." },
        new[] { "core.upload.aborted", "Передача отменена.", "Transfer cancelled." },
        new[] { "core.upload.cancelled", "Передача отменена", "Transfer cancelled" },
        new[] { "core.upload.cancelledbyphone", "Отменено с телефона", "Cancelled from the phone" },
        new[] { "core.upload.alreadydone", "Файл уже принят.", "File already received." },
        new[] { "core.upload.badchunksize", "Некорректный размер части файла.", "Invalid chunk size." },
        new[] { "core.upload.chunkincomplete", "Часть файла пришла не полностью.", "The chunk arrived incomplete." },
        new[] { "core.upload.incomplete", "Передача ещё не завершена (не хватает частей: {0}).", "The transfer is not complete yet ({0} chunks missing)." },
        new[] { "core.upload.save.failed", "Не удалось сохранить файл на диск.", "Could not save the file to disk." },
        new[] { "core.upload.rename.failed", "Не удалось сохранить файл в папке назначения.", "Could not save the file to the destination folder." },
        new[] { "core.upload.notfound", "Передача не найдена.", "Transfer not found." },
        new[] { "core.upload.timeout", "Истекло время ожидания", "Timed out" },
        new[] { "core.upload.nospace", "На диске недостаточно места. Свободно {0}.", "Not enough space on disk. {0} free." },
        new[] { "core.upload.nofolder", "Не указан путь папки.", "Folder path is not specified." },
        new[] { "core.upload.foldercreate.failed", "Не удалось создать папку в папке сохранения.", "Could not create the folder in the save location." },
        new[] { "core.upload.nooffset", "Не указано смещение части файла.", "Chunk offset is not specified." },
        new[] { "core.upload.nolength", "Не указан размер части файла.", "Chunk size is not specified." },

        // TransferServer
        new[] { "core.server.alreadyrunning", "Сервер уже запущен.", "The server is already running." },
        new[] { "core.server.internal", "Внутренняя ошибка сервера.", "Internal server error." },
        new[] { "core.server.unauthorized", "Требуется подключение к FireDrop.", "FireDrop connection required." },
        new[] { "core.server.join.ratelimited", "Слишком много попыток. Подождите пару минут.", "Too many attempts. Wait a couple of minutes." },
        new[] { "core.server.join.badpin", "Неверный код. Проверьте цифры на экране компьютера.", "Wrong code. Check the digits on the computer screen." },

        // PhoneSender
        new[] { "core.send.filenotfound", "файл не найден", "File not found" },
        new[] { "core.send.filereadfailed", "файл не читается", "File cannot be read" },
        new[] { "core.send.health.dead", "Устройство не отвечает", "Device is not responding" },
        new[] { "core.send.health.notfiredrop", "Это не FireDrop", "This is not FireDrop" },
        new[] { "core.send.interrupted", "передача прервана", "Transfer interrupted" },
        new[] { "core.send.failedcount", "Не удалось отправить файлов: {0}", "Failed to send files: {0}" },
        new[] { "core.send.join.notconfirmed", "Получатель не подтвердил подключение. Проверьте код приёма.", "The receiver did not confirm the connection. Check the accept code." },
        new[] { "core.send.start.denied", "Получатель не дал начать передачу файла", "The receiver did not allow the file transfer to start" },
        new[] { "core.send.save.notconfirmed", "Получатель не подтвердил сохранение файла", "The receiver did not confirm saving the file" },
        new[] { "core.send.chunkfailed", "Не удалось отправить фрагмент файла", "Could not send a file chunk" },
        new[] { "core.send.connectionclosed", "Соединение закрыто", "The connection is closed" },
        new[] { "core.send.filechanged", "Файл изменился во время отправки: {0}", "File changed while sending: {0}" },
        new[] { "core.send.httperror", "Ошибка передачи ({0})", "Transfer error ({0})" },
        new[] { "core.send.servererror", "Ошибка сервера ({0})", "Server error ({0})" },
        new[] { "core.send.timeout", "превышено время ожидания", "Request timed out" },
        new[] { "core.send.unreachable", "устройство не отвечает", "Device is not responding" },
        new[] { "core.send.nonetwork", "нет связи по локальной сети", "No connection on the local network" },
        new[] { "core.send.address.notfound", "адрес не найден в сети", "Address not found on the network" },

        // UploadManager.FormatBytes units
        new[] { "core.bytes.b", "Б", "B" },
        new[] { "core.bytes.kb", "КБ", "KB" },
        new[] { "core.bytes.mb", "МБ", "MB" },
        new[] { "core.bytes.gb", "ГБ", "GB" },
        new[] { "core.bytes.tb", "ТБ", "TB" },

        new[] { "core.net.display", "{0} · {1}", "{0} · {1}" },
        new[] { "core.net.virtual", "{0} (виртуальный) · {1}", "{0} (virtual) · {1}" },

        // PhoneDiscovery.LanDevice.KindText
        new[] { "core.device.kind.phone", "телефон", "phone" },
        new[] { "core.device.kind.pc", "ПК", "PC" },
    };

    internal static string Lookup(string key)
    {
        for (int i = 0; i < Table.Length; i++)
        {
            if (Table[i][0] == key) return L10n.IsRu ? Table[i][1] : Table[i][2];
        }
        return "";
    }
}
