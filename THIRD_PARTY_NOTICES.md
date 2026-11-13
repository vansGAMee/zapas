# Сторонние компоненты

Код Запаса — MIT (см. LICENSE). Сторонние компоненты сохраняют собственные условия:

- PySide6 / Qt / QtWebEngine: LGPLv3/GPL/commercial и лицензии включённых компонентов. Пакет PySide6 включает лицензии; при бинарной упаковке они должны сохраняться.
- FFmpeg: условия конкретной сборки, обычно GPL при наличии libx264/libx265. Источники: https://ffmpeg.org/download.html и источник используемой Windows-сборки. Упаковка FFmpeg не меняет его лицензию.
- yt-dlp: Unlicense; yt-dlp-ejs и остальные зависимости имеют собственные лицензии.
- Node.js: MIT и сторонние компоненты; https://github.com/nodejs/node/blob/main/LICENSE.
- AndroidX Compose, WorkManager, Media3 и Material Icons: Apache License 2.0.
- Roboto: SIL Open Font License, текст в `desktop/web/fonts/OFL.txt`.
- FastAPI, Uvicorn, Pillow, qrcode и их зависимости: собственные открытые лицензии из поставляемых пакетов.

YouTube — товарный знак Google. Запас — независимое приложение, не официальный клиент YouTube. Google-аккаунт и сервис YouTube этим приложением не предоставляются.

Перед распространением бинарных сборок сохраните полные лицензионные тексты и выполните требования поставляемой сборки FFmpeg/Qt. CI в этом репозитории является заготовкой сборки, а не юридической проверкой распространения.

Google Material Design Icons: значок settings из https://github.com/google/material-design-icons, Apache License 2.0. Android использует стандартные Material Icons из зависимостей Compose. Переносимая Windows-сборка дополнительно содержит CPython (PSF), Node.js (MIT), FFmpeg (лицензия и ссылка на исходники поставщика Gyan.dev); лицензии Python и Python-пакетов сохранены внутри runtime.
