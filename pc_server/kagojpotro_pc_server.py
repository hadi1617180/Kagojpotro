#!/usr/bin/env python3
"""
================================================================================
  KAGOJPOTRO (কাগজপত্র) — SELF-HOSTED PC FILE SERVER
================================================================================
- Zero external libraries required (runs on standard Python 3!).
- Creates isolated directories per user (e.g. `users/<username>/Folders/...`).
- Mirrors every folder & subfolder created in the Android app as real Windows/Linux
  folders containing the high-resolution `.jpg` images.
- Supports Local Wi-Fi, Tailscale, or Cloudflare Tunnel (`cloudflared tunnel --url http://localhost:8085`).

HOW TO RUN ON YOUR PC:
  python kagojpotro_pc_server.py --storage "D:/Kagojpotro_Storage" --port 8085
================================================================================
"""

import argparse
import json
import os
import re
import shutil
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

STORAGE_ROOT = Path("./Kagojpotro_Storage").resolve()
ACCESS_KEY = ""


def sanitize_user(raw: str) -> str:
    cleaned = re.sub(r"[^a-z0-9_-]", "_", (raw or "default").strip().lower().split("@")[0]).strip("_")
    return cleaned or "default"


def sanitize_folder_segment(name: str) -> str:
    cleaned = re.sub(r'[\\/:*?"<>|]', "_", (name or "").strip())
    return cleaned or "Untitled_Folder"


def user_root(user: str) -> Path:
    p = STORAGE_ROOT / "users" / sanitize_user(user)
    (p / "Folders").mkdir(parents=True, exist_ok=True)
    (p / ".system" / "edited").mkdir(parents=True, exist_ok=True)
    (p / ".system" / "original").mkdir(parents=True, exist_ok=True)
    (p / ".system" / "thumb").mkdir(parents=True, exist_ok=True)
    return p


def load_manifest(user: str) -> dict:
    mfile = user_root(user) / ".system" / "manifest.json"
    if mfile.exists():
        try:
            return json.loads(mfile.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {
        "userDirectory": sanitize_user(user),
        "updatedAt": 0,
        "folders": [],
        "scans": [],
        "deletedFolderIds": [],
        "deletedScanIds": [],
    }


def rebuild_human_readable_folders(user: str, manifest: dict):
    """
    Mirrors the exact app folder & subfolder hierarchy inside:
      <STORAGE_ROOT>/users/<user>/Folders/<Folder>/<Subfolder>/<filename>.jpg
    so you can browse all folders and JPGs directly on your PC in File Explorer!
    """
    u_root = user_root(user)
    folders_dir = u_root / "Folders"
    edited_store = u_root / ".system" / "edited"

    folders_by_id = {f["id"]: f for f in manifest.get("folders", []) if "id" in f}

    def resolve_rel_path(folder_id: str) -> Path:
        parts = []
        visited = set()
        curr = folder_id
        while curr and curr not in visited:
            visited.add(curr)
            f = folders_by_id.get(curr)
            if not f:
                break
            parts.append(sanitize_folder_segment(f.get("name", "Folder")))
            curr = f.get("parentId")
        parts.reverse()
        rel = Path(*parts) if parts else Path("Uncategorized")
        return folders_dir / rel

    expected_dirs = set()
    for fid in folders_by_id:
        dpath = resolve_rel_path(fid)
        dpath.mkdir(parents=True, exist_ok=True)
        expected_dirs.add(dpath.resolve())

    expected_files = set()
    for scan in manifest.get("scans", []):
        sid = scan.get("id", "")
        fid = scan.get("folderId", "")
        fname = sanitize_folder_segment(scan.get("filename", f"Scan_{sid}.jpg"))
        if not fname.lower().endswith(".jpg"):
            fname += ".jpg"
        src_edited = edited_store / f"{sid}.jpg"
        if src_edited.exists():
            target_dir = resolve_rel_path(fid)
            target_dir.mkdir(parents=True, exist_ok=True)
            dest_file = target_dir / fname
            if dest_file.resolve() in expected_files:
                stem = dest_file.stem
                dest_file = target_dir / f"{stem}_{sid[:4]}.jpg"
            src_stat = src_edited.stat()
            needs_copy = (
                not dest_file.exists()
                or dest_file.stat().st_size != src_stat.st_size
                or int(dest_file.stat().st_mtime) != int(src_stat.st_mtime)
            )
            if needs_copy:
                shutil.copy2(src_edited, dest_file)
            expected_files.add(dest_file.resolve())

    for root, dirs, files in os.walk(folders_dir, topdown=False):
        root_path = Path(root)
        for f in files:
            fpath = (root_path / f).resolve()
            if fpath not in expected_files:
                try:
                    fpath.unlink()
                except Exception:
                    pass
        for d in dirs:
            dpath = (root_path / d).resolve()
            if dpath not in expected_dirs and not any(dpath.iterdir()):
                try:
                    dpath.rmdir()
                except Exception:
                    pass


def compute_user_bytes(user: str) -> int:
    u_root = user_root(user) / ".system"
    total = 0
    for r, _, files in os.walk(u_root):
        for f in files:
            try:
                total += (Path(r) / f).stat().st_size
            except Exception:
                pass
    return total


class KagojpotroHandler(BaseHTTPRequestHandler):
    def _check_auth(self) -> bool:
        if not ACCESS_KEY:
            return True
        client_key = self.headers.get("X-Access-Key", "").strip()
        if client_key != ACCESS_KEY:
            self._send_json(401, {"ok": False, "error": "Invalid Access Key"})
            return False
        return True

    def _send_json(self, status: int, data: dict):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if not self._check_auth():
            return
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        user = sanitize_user(qs.get("user", ["default"])[0])

        if parsed.path == "/api/ping":
            usage = shutil.disk_usage(STORAGE_ROOT)
            manifest = load_manifest(user)
            self._send_json(
                200,
                {
                    "ok": True,
                    "serverName": "Kagojpotro PC Server",
                    "userDirectory": user,
                    "freeSpaceBytes": usage.free,
                    "totalSpaceBytes": usage.total,
                    "userUsedBytes": compute_user_bytes(user),
                    "folderCount": len(manifest.get("folders", [])),
                    "scanCount": len(manifest.get("scans", [])),
                },
            )
            return

        if parsed.path == "/api/manifest":
            manifest = load_manifest(user)
            self._send_json(200, manifest)
            return

        if parsed.path == "/api/file":
            scan_id = re.sub(r"[^a-zA-Z0-9_-]", "", qs.get("scanId", [""])[0])
            kind = qs.get("kind", ["edited"])[0]
            if kind not in ("edited", "original", "thumb"):
                kind = "edited"
            f_path = user_root(user) / ".system" / kind / f"{scan_id}.jpg"
            if not f_path.exists():
                self._send_json(404, {"ok": False, "error": "File not found"})
                return
            size = f_path.stat().st_size
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(f_path, "rb") as fp:
                shutil.copyfileobj(fp, self.wfile)
            return

        self._send_json(200, {"ok": True, "message": "Kagojpotro PC Server is running"})

    def do_PUT(self):
        if not self._check_auth():
            return
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        user = sanitize_user(qs.get("user", ["default"])[0])

        if parsed.path == "/api/file":
            scan_id = re.sub(r"[^a-zA-Z0-9_-]", "", qs.get("scanId", [""])[0])
            kind = qs.get("kind", ["edited"])[0]
            if kind not in ("edited", "original", "thumb"):
                kind = "edited"
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except (TypeError, ValueError):
                length = 0
            if not scan_id or length <= 0:
                self._send_json(400, {"ok": False, "error": "Missing scanId or Content-Length"})
                return

            dest = user_root(user) / ".system" / kind / f"{scan_id}.jpg"
            tmp = dest.with_suffix(".part")
            remaining = length
            with open(tmp, "wb") as out:
                while remaining > 0:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        break
                    out.write(chunk)
                    remaining -= len(chunk)
            if remaining > 0:
                try:
                    tmp.unlink()
                except Exception:
                    pass
                self._send_json(400, {"ok": False, "error": "Incomplete upload"})
                return
            tmp.replace(dest)
            self._send_json(200, {"ok": True, "scanId": scan_id, "kind": kind})
            return

        self._send_json(404, {"ok": False})

    def do_POST(self):
        if not self._check_auth():
            return
        parsed = urlparse(self.path)
        qs = parse_qs(parsed.query)
        user = sanitize_user(qs.get("user", ["default"])[0])

        if parsed.path == "/api/manifest":
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except (TypeError, ValueError):
                length = 0
            raw = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else "{}"
            try:
                manifest = json.loads(raw)
            except Exception:
                self._send_json(400, {"ok": False, "error": "Invalid JSON manifest"})
                return
            manifest["userDirectory"] = user
            mfile = user_root(user) / ".system" / "manifest.json"
            mfile.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")

            u_root = user_root(user)
            for del_id in manifest.get("deletedScanIds", []):
                safe_id = re.sub(r"[^a-zA-Z0-9_-]", "", str(del_id))
                for k in ("edited", "original", "thumb"):
                    p = u_root / ".system" / k / f"{safe_id}.jpg"
                    if p.exists():
                        try:
                            p.unlink()
                        except Exception:
                            pass

            rebuild_human_readable_folders(user, manifest)
            usage = shutil.disk_usage(STORAGE_ROOT)
            self._send_json(
                200,
                {
                    "ok": True,
                    "userDirectory": user,
                    "freeSpaceBytes": usage.free,
                    "totalSpaceBytes": usage.total,
                    "userUsedBytes": compute_user_bytes(user),
                },
            )
            return

        self._send_json(404, {"ok": False})


def get_local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Kagojpotro 4TB Office PC Server")
    parser.add_argument("--storage", default="./Kagojpotro_Storage", help="Folder path on your 4TB drive (e.g. D:/Kagojpotro_Storage)")
    parser.add_argument("--port", type=int, default=8085, help="Port number (default: 8085)")
    parser.add_argument("--key", default="", help="Optional secret access key")
    args = parser.parse_args()

    STORAGE_ROOT = Path(args.storage).resolve()
    STORAGE_ROOT.mkdir(parents=True, exist_ok=True)
    ACCESS_KEY = args.key.strip()

    local_ip = get_local_ip()
    print("=" * 72)
    print("  KAGOJPOTRO (কাগজপত্র) OFFICE PC SERVER IS RUNNING!")
    print("=" * 72)
    print(f"  Storage Location : {STORAGE_ROOT}")
    print(f"  User Directories : {STORAGE_ROOT / 'users' / '<username>' / 'Folders'}")
    print(f"  Local Wi-Fi URL  : http://{local_ip}:{args.port}")
    print("  Internet Sync    : Run `cloudflared tunnel --url http://localhost:" + str(args.port) + "`")
    print("=" * 72)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), KagojpotroHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server...")
        server.server_close()
