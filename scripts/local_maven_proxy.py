#!/usr/bin/env python3
"""Tiny caching Maven proxy for restricted CI sandboxes.

Normal developers never need this helper: Gradle uses google()/mavenCentral().
Set LOCAL_MAVEN_PROXY=http://127.0.0.1:8765 and run this script only when the
build process itself cannot access the network but curl can.
"""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
import sys
import threading
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASES = {
    "google": "https://dl.google.com/dl/android/maven2",
    "maven": "https://repo.maven.apache.org/maven2",
    "plugins": "https://plugins.gradle.org/m2",
}
CACHE = Path(os.environ.get("LOCAL_MAVEN_CACHE", "/tmp/agnes-maven-proxy-cache"))
CACHE.mkdir(parents=True, exist_ok=True)
_locks_guard = threading.Lock()
_path_locks: dict[str, threading.Lock] = {}
_fetchers: dict[str, "UpstreamFetcher"] = {}


def path_lock(path: Path) -> threading.Lock:
    key = str(path)
    with _locks_guard:
        return _path_locks.setdefault(key, threading.Lock())


class UpstreamFetcher:
    """Fetch artifacts through the runtime's configured HTTP(S) proxy."""

    def __init__(self, base: str):
        self.base = base.rstrip("/")

    def fetch(self, relative: str, target: Path) -> bool:
        for _ in range(3):
            try:
                request = Request(
                    f"{self.base}/{relative}",
                    headers={"User-Agent": "AgnesBuildProxy/1.0"},
                )
                response = urlopen(request, timeout=120)
                with target.open("wb") as output:
                    with response:
                        while chunk := response.read(1024 * 1024):
                            output.write(chunk)
                return True
            except HTTPError as error:
                if error.code == 404:
                    return False
            except (OSError, URLError):
                pass
        return False


for _name, _base in BASES.items():
    _fetchers[_name] = UpstreamFetcher(_base)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_HEAD(self):
        self._serve(False)

    def do_GET(self):
        self._serve(True)

    def _serve(self, include_body: bool):
        parts = self.path.split("?", 1)[0].lstrip("/").split("/", 1)
        if len(parts) != 2 or parts[0] not in BASES or ".." in parts[1]:
            self.send_error(404)
            return
        repository, relative = parts
        if repository == "google" and not relative.startswith(
            ("androidx/", "com/android/", "com/google/android/", "com/google/firebase/", "com/google/testing/platform/")
        ):
            # Avoid a slow network 404 for ordinary Maven Central artifacts.
            self.send_error(404)
            return
        cache_path = CACHE / repository / relative
        if not cache_path.exists():
            with path_lock(cache_path):
                if not cache_path.exists():
                    cache_path.parent.mkdir(parents=True, exist_ok=True)
                    temporary = cache_path.with_suffix(cache_path.suffix + ".part")
                    if not _fetchers[repository].fetch(relative, temporary):
                        temporary.unlink(missing_ok=True)
                        self.send_error(404)
                        return
                    temporary.replace(cache_path)
        size = cache_path.stat().st_size
        self.send_response(200)
        self.send_header("Content-Length", str(size))
        self.send_header("Content-Type", content_type(cache_path.name))
        self.send_header("Cache-Control", "public, max-age=31536000, immutable")
        self.end_headers()
        if include_body:
            with cache_path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    self.wfile.write(chunk)

    def log_message(self, fmt, *args):
        if os.environ.get("LOCAL_MAVEN_PROXY_VERBOSE"):
            super().log_message(fmt, *args)


def content_type(name: str) -> str:
    if name.endswith(".pom") or name.endswith(".xml"):
        return "application/xml"
    if name.endswith(".module") or name.endswith(".json"):
        return "application/json"
    return "application/octet-stream"


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    print(f"Local Maven proxy listening on 127.0.0.1:{port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
