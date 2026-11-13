import argparse
import logging
from logging.handlers import RotatingFileHandler
import os
import sys
import threading
from pathlib import Path

import uvicorn

from .server import create_app
from .store import Store


def main():
    parser = argparse.ArgumentParser(description="Запас — видео с собой")
    parser.add_argument("--headless", action="store_true", help="Запустить сервер без окна")
    parser.add_argument("--port", type=int, default=9473)
    parser.add_argument("--data", help="Папка медиатеки")
    parser.add_argument("--import-file", help="Добавить личный видеофайл")
    parser.add_argument("--connect-youtube", action="store_true", help="Открыть окно входа в YouTube")
    args = parser.parse_args()
    if getattr(sys, "frozen", False):
        os.environ["PATH"] = str(Path(sys.executable).parent) + os.pathsep + os.environ.get("PATH", "")
    bundled_tools = Path(__file__).resolve().parents[2] / 'tools'
    if bundled_tools.is_dir():
        os.environ['PATH'] = str(bundled_tools) + os.pathsep + os.environ.get('PATH', '')
    store = Store(args.data)
    handler = RotatingFileHandler(store.root / 'application.log', maxBytes=256*1024, backupCount=2, encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(asctime)s %(levelname)s %(name)s: %(message)s'))
    logging.getLogger('zapas').addHandler(handler)
    logging.getLogger('zapas').setLevel(logging.INFO)
    # Hold a process lock for the entire lifetime; reopening a library cannot start a second worker.
    lock = (store.root / "process.lock").open("a+b")
    try:
        if os.name == "nt":
            import msvcrt
            lock.seek(0); lock.write(b"0"); lock.flush(); lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        if args.headless:
            parser.error("Запас уже работает с этой медиатекой")
        from PySide6.QtWidgets import QApplication, QMessageBox
        notice_app = QApplication([])
        QMessageBox.information(None, "Запас уже запущен", "Открой окно через значок Запаса в системном трее.")
        return
    app = create_app(store, port=args.port)
    if args.import_file:
        if not Path(args.import_file).is_file():
            parser.error("Видео не найдено")
        app.state.pipeline.enqueue(local=args.import_file)
    url = f"http://127.0.0.1:{args.port}/#" + app.state.admin_token
    server = uvicorn.Server(uvicorn.Config(app, host="0.0.0.0", port=args.port, log_level="warning", use_colors=False))
    if args.headless:
        if hasattr(sys.stdout, 'reconfigure'):
            sys.stdout.reconfigure(encoding='utf-8')
        print("Открой интерфейс на этом компьютере:\n" + url, flush=True)
        server.run()
        return
    from PySide6.QtCore import QUrl, QTimer, QObject, Slot
    from PySide6.QtGui import QIcon, QAction
    from PySide6.QtWidgets import QApplication, QMainWindow, QFileDialog, QSystemTrayIcon, QMenu, QMessageBox
    from PySide6.QtWebEngineWidgets import QWebEngineView
    from PySide6.QtWebChannel import QWebChannel
    qt = QApplication(sys.argv)
    qt.setApplicationName("Запас")
    qt.setQuitOnLastWindowClosed(False)
    window = QMainWindow()
    window.setWindowTitle("Запас · Видео с собой")
    icon = QIcon(str(Path(__file__).resolve().parent.parent / "web" / "icon.svg"))
    window.setWindowIcon(icon)
    window.resize(1440, 940)
    view = QWebEngineView()
    window.setCentralWidget(view)

    class Bridge(QObject):
        @Slot()
        def importVideo(self):
            paths, _ = QFileDialog.getOpenFileNames(window, "Добавить личные видео", "", "Видео (*.mp4 *.mkv *.webm *.mov *.avi *.m4v)")
            for path in paths:
                app.state.pipeline.enqueue(local=path)
        @Slot()
        def openData(self):
            from PySide6.QtGui import QDesktopServices
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(store.root)))
        @Slot(bool, result=str)
        def setAutostart(self, enabled):
            try:
                command = [sys.executable] if getattr(sys, "frozen", False) else [sys.executable, str(Path(__file__).resolve().parent.parent / "run.py")]
                if os.name == "nt":
                    import winreg, subprocess
                    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as reg:
                        if enabled:
                            winreg.SetValueEx(reg, "Zapas", 0, winreg.REG_SZ, subprocess.list2cmdline(command))
                        else:
                            try:
                                winreg.DeleteValue(reg, "Zapas")
                            except FileNotFoundError:
                                pass
                else:
                    target = Path(os.getenv("XDG_CONFIG_HOME", Path.home() / ".config")) / "autostart" / "zapas.desktop"
                    if enabled:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        quoted = " ".join('"' + x.replace('\\', '\\\\').replace('"', '\\"').replace('$', '\\$').replace('`', '\\`') + '"' for x in command)
                        target.write_text("[Desktop Entry]\nType=Application\nName=Запас\nExec=" + quoted + "\nTerminal=false\n")
                    else:
                        target.unlink(missing_ok=True)
                store.save_settings({"autostart": enabled})
                return ""
            except Exception as e:
                return str(e)

    bridge = Bridge()
    first_setup = not store.settings().get("setup_complete", False)
    if first_setup:
        autostart_error = bridge.setAutostart(True)
        store.save_settings({"setup_complete": True})
    channel = QWebChannel()
    channel.registerObject("desktop", bridge)
    view.page().setWebChannel(channel)
    # Block external navigation in the privileged local interface.
    from PySide6.QtWebEngineCore import QWebEnginePage
    class Page(QWebEnginePage):
        def acceptNavigationRequest(self, qurl, nav_type, is_main):
            return qurl.host() == "127.0.0.1" and qurl.port() == args.port
    page = Page(view)
    page.setWebChannel(channel)
    view.setPage(page)
    tray = QSystemTrayIcon(icon, qt)
    menu = QMenu()
    show = menu.addAction("Открыть Запас")
    show.triggered.connect(lambda: (window.show(), window.raise_(), window.activateWindow()))
    menu.addAction("Добавить видео…").triggered.connect(bridge.importVideo)
    def quit_app():
        server.should_exit = True
        qt.quit()
    menu.addAction("Выйти").triggered.connect(quit_app)
    tray.setContextMenu(menu)
    tray.activated.connect(lambda _: window.show())
    tray.show()
    if not QSystemTrayIcon.isSystemTrayAvailable():
        qt.setQuitOnLastWindowClosed(True)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    def load():
        if server.started:
            view.setUrl(QUrl(url))
            window.show()
            if args.connect_youtube or (first_setup and not app.state.pipeline.account.connected):
                def connect_youtube():
                    try:
                        app.state.pipeline.account.open_login()
                    except Exception as e:
                        app.state.pipeline.account.message = str(e)
                threading.Thread(target=connect_youtube, daemon=True, name="zapas-youtube-login").start()
        elif thread.is_alive():
            QTimer.singleShot(100, load)
        else:
            QMessageBox.critical(window, "Не удалось запустить Запас", "Порт занят. Закрой другой экземпляр приложения или запусти с --port 9474.")
            qt.quit()
    load()
    qt.aboutToQuit.connect(lambda: setattr(server, "should_exit", True))
    qt.exec()
    server.should_exit = True
    thread.join(timeout=25)


if __name__ == "__main__":
    main()
