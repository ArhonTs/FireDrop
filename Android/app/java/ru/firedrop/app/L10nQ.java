package ru.firedrop.app;

/** Strings of the QR scanner. */
final class L10nQ {
    private static final String[][] T = {
            {"scan.title", "Сканирование QR-кода", "QR scanning"},
            {"scan.subtitle", "Наведите камеру на код получателя", "Point the camera at the recipient's code"},
            {"scan.close", "Закрыть", "Close"},
            {"scan.permission.title", "Нужен доступ к камере", "Camera access required"},
            {"scan.permission.message", "Разрешите доступ к камере, чтобы навести камеру прямо на QR-код, или снимите кадр системной камерой.",
                    "Allow camera access so you can point the camera straight at the QR code, or capture a frame with the system camera."},
            {"scan.photo_button", "Снять кадр для распознавания", "Capture a frame to decode"},
            {"scan.retry_button", "Включить камеру заново", "Restart the camera"},
            {"scan.hint", "Наводите камеру прямо на QR-код…", "Point the camera straight at the QR code…"},
            {"scan.camera_unavailable_title", "Камера не доступна", "Camera unavailable"},
            {"scan.camera_unavailable_message", "Не удалось подключиться к камере. Снимите кадр системной камерой или введите адрес вручную.",
                    "Could not connect to the camera. Capture a frame with the system camera or enter the address manually."},
            {"scan.error.stream", "камера не смогла начать передачу", "the camera could not start streaming"},
            {"scan.error.busy", "камера занята другим приложением", "the camera is busy with another app"},
            {"scan.error.code", "ошибка камеры: %1$d", "camera error: %1$d"},
            {"scan.error.session", "камера не введена", "the camera session was not started"},
            {"scan.unresponsive.title", "Камера не отвечает", "Camera not responding"},
            {"scan.unresponsive.message", "%1$s. Снимите кадр системной камерой или введите адрес вручную.",
                    "%1$s. Capture a frame with the system camera or enter the address manually."},
            {"scan.toast.unavailable", "Камера недоступна. Введите адрес и код вручную.",
                    "Camera unavailable. Enter the address and code manually."},
            {"scan.recognizing", "Распознаём кадр…", "Decoding the frame…"},
            {"scan.not_recognized.title", "QR-код не распознан", "QR code not recognized"},
            {"scan.not_recognized.message", "Кадр получился тёмным или размытым. Подсветите экран, держите камеру ровно и попробуйте ещё раз — либо введите адрес вручную.",
                    "The frame came out dark or blurry. Light up the screen, hold the camera steady and try again — or enter the address manually."},
    };

    static String get(String key) {
        return L10n.lookup(T, key);
    }
}
