"""
Chrome 浏览器启动配置。

支持两种运行形态：
1. 本地开发（Windows/macOS）：使用本机已安装的 Chrome，可视化运行。
2. Docker 容器：无 GUI，需 headless + --no-sandbox。

环境变量：
- CHROME_BROWSER_PATH  : Chrome 可执行文件路径（留空则由 DrissionPage 自动探测）
- CHROME_HEADLESS      : 1/true 表示无头模式（容器内必需）
- CHROME_USER_DATA_DIR : 用户数据目录，用于保留登录态
- CHROME_LOCAL_PORT    : 远程调试端口
"""
import os
import re

from DrissionPage import ChromiumOptions


def _pid_alive(pid: int) -> bool:
    """判断进程是否存活（跨平台）。"""
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes
        PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        STILL_ACTIVE = 259
        kernel32 = ctypes.windll.kernel32
        handle = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
        if not handle:
            return False
        try:
            code = ctypes.c_ulong()
            if not kernel32.GetExitCodeProcess(handle, ctypes.byref(code)):
                return False
            return code.value == STILL_ACTIVE
        finally:
            kernel32.CloseHandle(handle)
    else:
        try:
            os.kill(pid, 0)
        except OSError:
            return False
        return True


def cleanup_stale_locks(user_data_dir: str) -> None:
    """清理 Chrome 用户目录里失效的 Singleton 锁。

    Chrome 异常退出（进程被杀、容器重建）时会残留 SingletonLock/Cookie/Socket，
    下次启动会因"用户目录被占用"而失败。仅在锁的属主进程确实已退出时才清理，
    避免误删正在使用中的浏览器锁。
    """
    if not user_data_dir or not os.path.isdir(user_data_dir):
        return

    for name in ("SingletonLock", "SingletonCookie", "SingletonSocket"):
        path = os.path.join(user_data_dir, name)
        if not os.path.lexists(path):
            continue

        if name == "SingletonLock":
            try:
                target = os.readlink(path)  # 形如 "hostname-1234"
                m = re.search(r"-(\d+)$", target)
                if m and _pid_alive(int(m.group(1))):
                    return  # 属主进程仍在运行，不清理，交由 Chrome 自己处理
            except OSError:
                pass

        try:
            os.remove(path)
        except OSError:
            pass


def build_options() -> ChromiumOptions:
    """构造 ChromiumOptions，兼容本地与容器环境。"""
    co = ChromiumOptions()

    browser_path = os.environ.get("CHROME_BROWSER_PATH")
    if browser_path:
        co.set_browser_path(browser_path)

    if os.environ.get("CHROME_HEADLESS", "").lower() in ("1", "true", "yes"):
        # 无头模式：容器内无 X11，必须开启
        co.headless(True)

    user_data_dir = os.environ.get("CHROME_USER_DATA_DIR")
    if user_data_dir:
        cleanup_stale_locks(user_data_dir)
        co.set_user_data_path(user_data_dir)

    local_port = os.environ.get("CHROME_LOCAL_PORT")
    if local_port:
        co.set_local_port(int(local_port))

    # 容器内以 root 运行，Chrome 沙箱不可用；同时 /dev/shm 往往过小
    if os.environ.get("CHROME_NO_SANDBOX", "1") in ("1", "true", "yes"):
        co.set_argument("--no-sandbox")
        co.set_argument("--disable-dev-shm-usage")

    co.set_argument("--disable-gpu")
    co.set_argument("--no-first-run")
    co.set_argument("--no-default-browser-check")

    return co


if __name__ == "__main__":
    build_options().save()
    print("Chrome浏览器配置完成")
