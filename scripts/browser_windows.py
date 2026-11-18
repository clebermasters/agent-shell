"""Profile-bound native messaging for the managed Linux Chrome display."""
import argparse
import base64
import contextlib
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import select
import shlex
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time

HOST = "com.agentshell.windows"
LIMIT = 1024 * 1024
OFFLINE = "WINDOW_EXTENSION_OFFLINE"
STALE = "STALE_RUNTIME"
PAIR_FAILED = "WINDOW_PAIRING_FAILED"


class WindowError(Exception):
    pass


def runtime_module():
    path = Path(__file__).with_name("browser-runtime.py")
    spec = importlib.util.spec_from_file_location("browser_runtime", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def extension_id(public_der):
    digest = hashlib.sha256(public_der).hexdigest()[:32]
    return digest.translate(str.maketrans("0123456789abcdef", "abcdefghijklmnop"))


def private_directory(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise WindowError("WINDOW_CONFIGURATION_INVALID")
    return path


def install_package(config_path, source, output):
    """Generate one stable, private signing identity per AgentShell installation."""
    runtime = runtime_module()
    output = private_directory(output)
    with (output / "install.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        key = output / "extension.pem"
        with tempfile.TemporaryDirectory(dir=output) as temporary:
            work = Path(temporary)
            if not key.exists():
                new_key = work / "signing.pem"
                subprocess.run(["openssl", "genrsa", "-out", str(new_key), "2048"], check=True, capture_output=True)
                new_key.chmod(0o600)
                new_key.replace(key)
            if key.is_symlink() or key.stat().st_uid != os.getuid() or key.stat().st_mode & 0o077:
                raise WindowError("WINDOW_CONFIGURATION_INVALID")
            public = subprocess.check_output(["openssl", "pkey", "-in", str(key), "-pubout", "-outform", "DER"])
            identifier = extension_id(public)
            staged = work / "extension"
            shutil.copytree(source, staged)
            manifest = json.loads((staged / "manifest.json").read_text())
            manifest["key"] = base64.b64encode(public).decode("ascii")
            runtime.write_json(staged / "manifest.json", manifest)
            chrome = shutil.which("google-chrome") or shutil.which("google-chrome-stable") or shutil.which("chromium")
            if not chrome:
                raise WindowError("CHROME_NOT_INSTALLED")
            subprocess.run([chrome, "--user-data-dir=" + str(work / "pack-profile"),
                            "--pack-extension=" + str(staged), "--pack-extension-key=" + str(key)],
                           cwd=work, stdin=subprocess.DEVNULL, capture_output=True, timeout=25, check=True)
            (work / "extension.crx").replace(output / "agentshell-windows.crx")
            # Keep the unpacked directory stable for Chrome's file picker and Reload.
            target = output / "extension"
            target.mkdir(mode=0o700, exist_ok=True)
            for child in staged.iterdir():
                if not child.is_file():
                    raise WindowError("WINDOW_CONFIGURATION_INVALID")
                child.replace(target / child.name)
            launcher = output / "native-host"
            wrapper = work / "native-host"
            wrapper.write_text("#!/bin/sh\nexec /usr/bin/python3 " + shlex.quote(str(Path(__file__).resolve()))
                               + " --config " + shlex.quote(str(config_path.resolve())) + ' "$@"\n')
            wrapper.chmod(0o700)
            wrapper.replace(launcher)
            return {"id": identifier, "host": str(launcher), "package": str(output / "agentshell-windows.crx")}


def configured(value):
    settings = value.get("windowExtension")
    return (isinstance(settings, dict) and isinstance(settings.get("id"), str)
            and re.fullmatch(r"[a-p]{32}", settings["id"]) is not None
            and isinstance(settings.get("host"), str) and os.path.isabs(settings["host"])
            and os.access(settings["host"], os.X_OK))


def register_host(runtime, value):
    if not configured(value):
        return
    settings = value["windowExtension"]
    destination = Path(value["userDataDir"]) / "NativeMessagingHosts" / (HOST + ".json")
    manifest = {"name": HOST, "description": "AgentShell managed browser windows", "type": "stdio",
                "path": settings["host"], "allowed_origins": ["chrome-extension://" + settings["id"] + "/"]}
    if runtime.read_json(destination) != manifest:
        runtime.write_json(destination, manifest)


def selected_profile(runtime, value, record):
    root = value["userDataDir"]
    profile = value.get("profileDirectory") or record.get("windowProfileDirectory")
    if profile is None:
        # Older supervisors did not record the startup profile.
        cache = runtime.profile_metadata(root).get("info_cache", {})
        choices = [key for key in cache if runtime.safe_profile(root, key)] if isinstance(cache, dict) else []
        if len(choices) == 1:
            profile = choices[0]
    if not runtime.safe_profile(root, profile):
        raise WindowError("WINDOW_PROFILE_REQUIRED")
    return profile


def identity(runtime, config_path, expected_generation=None, parent=None):
    value = runtime.config(config_path)
    if not configured(value):
        raise WindowError(OFFLINE)
    record = runtime.read_json(Path(value["runtimeDir"]) / "runtime.json")
    state = runtime.status(value)
    if (state["state"] != "ready" or state.get("runtimeGeneration") != record.get("runtimeGeneration")
            or expected_generation is not None and state.get("runtimeGeneration") != expected_generation):
        raise WindowError(STALE)
    owner = runtime.verified_chrome_owner(value)
    if (owner is None or owner["chromePid"] != record.get("chromePid")
            or owner["chromeGeneration"] != record.get("chromeGeneration")
            or parent is not None and owner["chromePid"] != parent):
        raise WindowError(STALE)
    profile = selected_profile(runtime, value, record)
    scope = {"generation": state["runtimeGeneration"], "profile": runtime.profile_id(value["userDataDir"], profile)}
    return value, owner, profile, scope


def get_peer(sock):
    return struct.unpack("3i", sock.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i")))[:2]


def start_pairing(runtime, value, owner, profile, nonce):
    # Send the normal Linux singleton notification to the already running browser.
    # An executable invocation could start a replacement on a concurrent handoff.
    pid = owner["chromePid"]
    root = Path(value["userDataDir"])
    target = "chrome-extension://" + value["windowExtension"]["id"] + "/pair.html#" + nonce
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(3)
        sock.connect(str((root / "SingletonSocket").resolve(strict=True)))
        if get_peer(sock) != (pid, os.getuid()) or runtime.generation(pid) != owner["chromeGeneration"]:
            raise WindowError(STALE)
        args = ["START", "/", "chrome", "--profile-directory=" + profile, target]
        sock.sendall(b"\x00".join(os.fsencode(arg) for arg in args))
        sock.shutdown(socket.SHUT_WR)
        with sock.makefile("rb") as reply:
            if reply.read(3) != b"ACK":
                raise WindowError(PAIR_FAILED)


def read_packet(stream):
    header = stream.read(4)
    if len(header) != 4:
        raise EOFError
    length, = struct.unpack("=I", header)
    if not 0 < length <= LIMIT:
        raise WindowError("WINDOW_PROTOCOL_ERROR")
    data = stream.read(length)
    if len(data) != length:
        raise EOFError
    result = json.loads(data)
    if not isinstance(result, dict):
        raise WindowError("WINDOW_PROTOCOL_ERROR")
    return result


def packet(value):
    data = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode()
    if len(data) > LIMIT:
        raise WindowError("WINDOW_RESPONSE_TOO_LARGE")
    return struct.pack("=I", len(data)) + data


@contextlib.contextmanager
def socket_path(directory, name):
    # A private home directory may exceed sockaddr_un's 107-byte pathname limit.
    descriptor = os.open(directory, os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        yield f"/proc/self/fd/{descriptor}/{name}"
    finally:
        os.close(descriptor)


def worker_name(binding):
    return "worker-" + str(binding["pid"]) + "." + binding["birth"]


def binding_alive(runtime, directory, binding, scope):
    return (binding.get("scope") == scope and type(binding.get("pid")) is int
            and binding.get("birth") and runtime.generation(binding["pid"]) == binding["birth"]
            and (directory / worker_name(binding)).is_socket())


def worker_available(runtime, directory):
    available = False
    for path in directory.glob("worker-*"):
        pid, _, birth = path.name.removeprefix("worker-").partition(".")
        if not pid.isdigit() or not path.is_socket():
            continue
        if runtime.generation(int(pid)) == birth:
            available = True
        else:
            # Chrome may kill a native host before its finally block runs.
            path.unlink(missing_ok=True)
    return available


def pair(runtime, directory, value, owner, profile, scope):
    with (directory / "pair.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise WindowError("WINDOW_PAIRING_IN_PROGRESS")
        binding = runtime.read_json(directory / "bound.json")
        if binding_alive(runtime, directory, binding, scope):
            return binding
        if not worker_available(runtime, directory):
            raise WindowError(OFFLINE)
        challenge = directory / "challenge.json"
        old = runtime.read_json(challenge)
        if old.get("scope") == scope and old.get("nonce") and old.get("attempted", 0) + 45 > time.monotonic():
            raise WindowError(PAIR_FAILED)
        nonce = secrets.token_hex(32)
        now = time.monotonic()
        runtime.write_json(challenge, {"nonce":nonce, "scope":scope, "expires":now + 6, "attempted":now})
        start_pairing(runtime, value, owner, profile, nonce)
        while time.monotonic() < now + 6:
            binding = runtime.read_json(directory / "bound.json")
            if binding_alive(runtime, directory, binding, scope):
                return binding
            time.sleep(.05)
        raise WindowError(PAIR_FAILED)


def query(runtime, config_path, generation, request, *, allow_pairing=False):
    if not isinstance(request, dict) or not isinstance(request.get("id"), str):
        raise WindowError("WINDOW_PROTOCOL_ERROR")
    value, owner, profile, scope = identity(runtime, config_path, generation)
    directory = private_directory(Path(value["runtimeDir"]) / "windows")
    binding = runtime.read_json(directory / "bound.json")
    if not binding_alive(runtime, directory, binding, scope):
        if request.get("action") != "list":
            raise WindowError("WINDOW_CATALOG_STALE")
        if not allow_pairing:
            raise WindowError("WINDOW_PAIRING_REQUIRED")
        binding = pair(runtime, directory, value, owner, profile, scope)
    if identity(runtime, config_path, generation)[-1] != scope:
        raise WindowError(STALE)
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
        client.settimeout(56 if request.get("action") == "merge" else 20)
        with socket_path(directory, worker_name(binding)) as address:
            client.connect(address)
        if get_peer(client) != (binding["pid"], os.getuid()) or not binding_alive(runtime, directory, binding, scope):
            raise WindowError(STALE)
        client.sendall(packet({"scope": scope, "request": request}))
        with client.makefile("rb") as stream:
            result = read_packet(stream)
        if result.get("id") != request["id"] or identity(runtime, config_path, generation)[-1] != scope:
            raise WindowError(STALE)
        return result


def native_host(runtime, config_path, origin):
    parent = os.getppid()
    value, _, _, scope = identity(runtime, config_path, parent=parent)
    if origin != "chrome-extension://" + value["windowExtension"]["id"] + "/":
        raise WindowError(OFFLINE)
    directory = private_directory(Path(value["runtimeDir"]) / "windows")
    hello = read_packet(sys.stdin.buffer)
    if hello.get("type") != "hello" or hello.get("version") != 1:
        raise WindowError("WINDOW_PROTOCOL_ERROR")
    pid = os.getpid()
    pointer = {"pid":pid, "birth":runtime.generation(pid), "scope":scope}
    name = worker_name(pointer)

    def current():
        return identity(runtime, config_path, scope["generation"], parent)[-1] == scope

    def publish(message):
        sys.stdout.buffer.write(packet(message))
        sys.stdout.buffer.flush()

    def prove(proof):
        challenge = runtime.read_json(directory / "challenge.json")
        nonce = proof.get("nonce")
        if (proof.get("type") == "pair" and proof.get("version") == 1 and isinstance(nonce, str)
                and re.fullmatch(r"[0-9a-f]{64}", nonce) and challenge.get("scope") == scope
                and secrets.compare_digest(challenge.get("nonce", ""), nonce)
                and time.monotonic() < challenge.get("expires", 0) and current()):
            runtime.write_json(directory / "bound.json", pointer)
            challenge.pop("nonce")
            runtime.write_json(directory / "challenge.json", challenge)
            publish({"paired":True, "nonce":nonce})

    def relay(client):
        client.settimeout(3)
        if get_peer(client)[1] != os.getuid():
            return
        with client.makefile("rb") as stream:
            message = read_packet(stream)
        if message.get("scope") != scope or runtime.read_json(directory / "bound.json") != pointer or not current():
            return
        request = message["request"]
        publish(request)
        timeout = 53 if request.get("action") == "merge" else 18
        if not select.select([sys.stdin.buffer], [], [], timeout)[0]:
            raise WindowError("WINDOW_COMMAND_TIMEOUT")
        response = read_packet(sys.stdin.buffer)
        if response.get("id") != request["id"] or not current():
            raise WindowError(STALE)
        try:
            client.sendall(packet(response))
        except (BrokenPipeError, ConnectionResetError):
            pass  # The caller may have disconnected while Chrome completed the action.

    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
        with socket_path(directory, name) as path:
            server.bind(path)
        os.chmod(directory / name, 0o600)
        server.listen(4)
        try:
            while current():
                ready, _, _ = select.select([server, sys.stdin.buffer], [], [], 10)
                if sys.stdin.buffer in ready:
                    prove(read_packet(sys.stdin.buffer))
                if server in ready:
                    client, _ = server.accept()
                    with client:
                        relay(client)
        finally:
            (directory / name).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--install", type=Path, metavar="EXTENSION_SOURCE")
    parser.add_argument("--output", type=Path)
    parser.add_argument("origin", nargs="?")
    args = parser.parse_args()
    os.umask(0o077)
    if args.install and args.output:
        settings = install_package(args.config, args.install, args.output)
        print(json.dumps(settings))
    elif args.origin:
        try:
            native_host(runtime_module(), args.config, args.origin)
        except (WindowError, OSError, EOFError, ValueError, KeyError):
            pass  # Native messaging stdout is reserved for framed replies.
    else:
        parser.error("An extension origin or --install and --output is required")


if __name__ == "__main__":
    main()
