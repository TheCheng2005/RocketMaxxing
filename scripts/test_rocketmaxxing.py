"""Starts the installed RocketMaxxing app without a window and checks its local server end to end.

    ./gradlew rocketmaxxing && python3 scripts/test_rocketmaxxing.py
    python3 scripts/test_rocketmaxxing.py LAUNCHER [ARGS...]   # a packaged app instead, e.g.
        build/installers/RocketMaxxing.app/Contents/MacOS/RocketMaxxing
    python3 scripts/test_rocketmaxxing.py --window [LAUNCHER...]   # the app's own window (needs a screen): it opens
        Maple as a double-clicked design, then a second one handed over by another launch
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BIN = os.path.join(ROOT, "build", "install", "openrocket-mcp", "bin", "rocketmaxxing" + (".bat" if os.name == "nt" else ""))


def tool(url, token, name, args=None):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": name, "arguments": args or {}}}).encode()
    req = urllib.request.Request(url + "/rpc", body, {"Authorization": "Bearer " + token, "Origin": url})
    r = json.loads(urllib.request.urlopen(req, timeout=60).read())["result"]
    text = r["content"][0]["text"]
    assert not r["isError"], name + ": " + text
    return json.loads(text)


def window(cmd):
    """The app's own window opens Maple, double-clicked to start it: its page, in the app's Chromium, did that."""
    workspace = tempfile.mkdtemp()
    maple = os.path.join(ROOT, "docs", "examples", "maple-10k.ork")
    # Where the running app leaves its address (Instance.java: the designs folder's SHA-256, 6 bytes).
    key = hashlib.sha256(workspace.encode()).hexdigest()[:12]
    address = os.path.join(os.path.expanduser("~"), ".rocketmaxxing", f"instance-{key}.json")
    log = tempfile.TemporaryFile(mode="w+")
    p = subprocess.Popen(cmd + ["--workspace", workspace, maple], stdout=log, stderr=subprocess.STDOUT, text=True)

    def tail():
        log.seek(0)
        out = log.read()[-3000:]
        # Chromium's own log (AppWindow: next to its cache, one folder per designs folder)
        chromium = os.path.join(os.path.expanduser("~"), ".rocketmaxxing", "chromium", key, "chromium.log")
        if os.path.exists(chromium):
            with open(chromium, errors="replace") as f:
                out += "\n--- chromium.log:\n" + f.read()[-2000:]
        # The window's pages, when the app was started with --remote-debugging-port=$DEVTOOLS_PORT (CI diagnostics).
        if os.environ.get("DEVTOOLS_PORT"):
            try:
                pages = subprocess.run(["node", os.path.join(ROOT, "scripts", "devtools_pages.mjs"), os.environ["DEVTOOLS_PORT"]],
                                       capture_output=True, text=True, timeout=60)
                out = "pages:\n" + pages.stdout + pages.stderr[-500:] + "\n" + out
            except (OSError, subprocess.SubprocessError) as e:
                out = f"pages: unknown ({e})\n" + out
        return out

    def wait_for(test, what):
        deadline = time.time() + 180
        while time.time() < deadline:
            assert p.poll() is None, f"the app stopped ({p.returncode}) before {what}:\n{tail()}"
            try:
                with open(address) as f:
                    a = json.load(f)
                url, token = f"http://127.0.0.1:{a['port']}", a["token"]
                session = tool(url, token, "app_session")
                if test(session):
                    return url, token, session
            except (OSError, ValueError, KeyError, urllib.error.URLError):
                pass  # still starting
            time.sleep(1)
        raise AssertionError(f"no {what} after 3 minutes:\n{tail()}")

    try:
        url, token, session = wait_for(lambda s: s["window"] != "none", "window")
        assert session["window"] == "app", f"a {session['window']} window opened instead of the app's own:\n{tail()}"
        names = lambda s: [d["name"] for d in s["designs"]]
        url, token, session = wait_for(lambda s: "Maple 10K" in names(s), "Maple in the window")
        # Another launch with a design: the open window opens it too.
        second = subprocess.run(cmd + ["--workspace", workspace, os.path.join(ROOT, "docs", "examples", "maple-10k-pdr.ork")],
                                capture_output=True, text=True, timeout=120)
        assert second.returncode == 0, second.stdout[-1000:] + second.stderr[-2000:]
        wait_for(lambda s: len(s["designs"]) == 2, "the handed-over design in the window")
        p.terminate()  # as Ctrl+C or a logout does
        p.wait(timeout=30)
        if os.name != "nt":  # Windows has no signal to stop it gently: the shutdown hooks do not run there
            assert not os.path.exists(address), "the app left its address behind (shutdown hooks did not run)"
        print(f"RocketMaxxing window OK: opened {names(session)} in its own window, and a handed-over design")
    finally:
        if p.poll() is None:
            p.kill()


def main():
    if sys.argv[1:2] == ["--window"]:
        window(sys.argv[2:] or [BIN])
        return
    cmd = sys.argv[1:] or [BIN]
    workspace = tempfile.mkdtemp()
    p = subprocess.Popen(cmd + ["--no-browser", "--port", "0", "--workspace", workspace],
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    try:
        url = None
        deadline = time.time() + 90
        while time.time() < deadline:
            line = p.stdout.readline()
            m = re.search(r"Open (http://127\.0\.0\.1:\d+)/#token=([0-9a-f]+)", line)
            if m:
                url, token = m.group(1), m.group(2)
                break
        assert url, "the app did not print its address"
        page = urllib.request.urlopen(url + "/").read().decode()
        assert "<title>RocketMaxxing</title>" in page, "the interface is not served (was it built?)"

        def call(name, args=None):
            return tool(url, token, name, args)

        session = call("app_session")
        assert len(session["examples"]) >= 10 and session["materials"]["BULK"], session
        d = call("open_design", {"example": "Two stage high power rocket"})["designId"]
        design = call("app_design", {"designId": d})
        assert design["stability"]["margin"] > 1, design["stability"]
        assert call("app_model", {"designId": d})["parts"], "no 3-D model"
        flight = call("app_quick_flight", {"designId": d})
        assert flight["apogee"] > 100, flight
        # OpenRocket's motor database and a rendered picture: the packaged runtime has what they need.
        motors = call("search_motors", {"limit": 3})
        assert motors["matches"] > 1000 and motors["motors"], motors
        assert call("draw_rocket", {"designId": d, "path": "rocket.svg"}), "no drawing"
        if sys.platform == "darwin":
            # The Dock, Finder and the menu bar reach the app (handlers need the Mac's window server).
            assert {"openFile", "reopen", "quit"} <= set(session["macOS"]), session["macOS"]

        # A second launch for the same folder hands its design to the running app and exits.
        example = os.path.join(ROOT, "docs", "examples", "maple-10k.ork")
        second = subprocess.run(cmd + ["--no-browser", "--workspace", workspace, example],
                                capture_output=True, text=True, timeout=120)
        assert second.returncode == 0, second.stderr[-2000:]
        handed = re.search(r"Open (http://127\.0\.0\.1:\d+)/#token=[0-9a-f]+(\S*)", second.stdout)
        assert handed and handed.group(1) == url, "the second launch did not hand over: " + second.stdout + second.stderr[-500:]
        assert "maple-10k.ork" in urllib.parse.unquote(handed.group(2)), handed.group(0)

        try:
            urllib.request.urlopen(urllib.request.Request(url + "/rpc", b"{}", {"Authorization": "Bearer wrong"}))
            raise AssertionError("a wrong token was accepted")
        except urllib.error.HTTPError as e:
            assert e.code == 401
        print(f"RocketMaxxing OK: {session['version']} (engine {session['engineVersion']}), "
              f"apogee {flight['apogee']:.0f} m, stability {design['stability']['margin']:.2f} cal")
    finally:
        p.kill()


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("FAILED:", e)
        if os.environ.get("GITHUB_ACTIONS"):
            # Also as an annotation on the run's summary, readable without the job's log.
            text = str(e)[-6000:].replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print("::error title=RocketMaxxing test failed::" + text)
        sys.exit(1)
