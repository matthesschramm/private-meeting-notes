#!/usr/bin/env python3
"""Download, verify and unpack the on-device models and native build libraries.

Reads scripts/model-sources.json. Models go to models/<id>/, archives are cached in models/_downloads/,
and models/manifest.json lists every model file with size and SHA-256 for the phone-side check.
Safe to re-run: verified files are kept and interrupted downloads resume.
"""

import hashlib
import http.client
import json
import shutil
import sys
import tarfile
import urllib.error
import urllib.request
from pathlib import Path, PurePosixPath
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "scripts" / "model-sources.json"
MODELS = ROOT / "models"
CACHE = MODELS / "_downloads"
REGISTRY = ROOT / "app" / "src" / "main" / "assets" / "model-registry.json"
DOWNLOAD_ATTEMPTS = 5
TIMEOUT_SECONDS = 60


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url: str, dest: Path, size: int) -> None:
    """Download url to dest through dest.part, resuming whatever an earlier attempt or run left there.

    A dropped connection can end the response early without an error, so only exactly `size` bytes count as done.
    """
    dest.parent.mkdir(parents=True, exist_ok=True)
    partial = dest.with_name(dest.name + ".part")
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        have = partial.stat().st_size if partial.exists() else 0
        if have > size:
            partial.unlink()
            have = 0
        if have < size:
            try:
                have = receive(url, partial, have, size)
            except urllib.error.HTTPError:
                raise
            except (OSError, http.client.HTTPException) as error:
                print(f"  {dest.name}: {error}", flush=True)
                have = partial.stat().st_size if partial.exists() else 0
        if have == size:
            partial.rename(dest)
            return
        print(f"  {dest.name}: {have} of {size} bytes after attempt {attempt} of {DOWNLOAD_ATTEMPTS}", flush=True)
    sys.exit(f"Download of {dest.name} is incomplete; run the script again to resume it")


def receive(url: str, partial: Path, have: int, size: int) -> int:
    """Append the rest of url to partial and return its new length, starting over if the server ignores the range."""
    headers = {"User-Agent": "private-meeting-notes-fetch/1"}
    if have:
        headers["Range"] = f"bytes={have}-"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        if have and response.status != 206:
            print(f"  {partial.stem}: server cannot resume, starting again", flush=True)
            have = 0
        length = response.headers.get("Content-Length")
        if length is not None and have + int(length) != size:
            sys.exit(f"The server has {have + int(length)} bytes for {partial.stem}; model-sources.json says {size}")
        with partial.open("ab" if have else "wb") as out:
            next_report = have * 100 // size // 10 * 10
            while True:
                block = response.read(4 * 1024 * 1024)
                if not block:
                    break
                out.write(block)
                have += len(block)
                if have * 100 // size >= next_report:
                    print(f"  {partial.stem}: {have * 100 // size}%", flush=True)
                    next_report += 10
    return have


def fetch_verified(entry: dict, dest: Path) -> bool:
    """Download entry['url'] to dest unless a verified copy exists. Returns True if a hash was pinned."""
    size = entry.get("size")
    if not size:
        sys.exit(f"{dest.name} needs its size in model-sources.json, which is how incomplete downloads are caught")
    if not dest.exists() or dest.stat().st_size != size:
        print(f"Downloading {dest.name}", flush=True)
        download(entry["url"], dest, size)
    actual = sha256_of(dest)
    if entry.get("sha256") is None:
        entry["sha256"] = actual
        entry["pinnedOnFirstDownload"] = True
        print(f"  pinned sha256 {actual}", flush=True)
        return True
    if actual != entry["sha256"]:
        dest.unlink()
        sys.exit(f"Checksum mismatch for {dest.name}: expected {entry['sha256']}, got {actual}")
    return False


def extract_tar_bz2(archive: Path, target: Path, strip: int) -> None:
    with tarfile.open(archive, mode="r:bz2") as tar:
        for member in tar.getmembers():
            parts = PurePosixPath(member.name).parts[strip:]
            if not parts or not member.isfile():
                continue
            if any(part in ("..", "") for part in parts) or PurePosixPath(member.name).is_absolute():
                sys.exit(f"Unsafe path in archive {archive.name}: {member.name}")
            out_path = target.joinpath(*parts)
            if out_path.exists() and out_path.stat().st_size == member.size:
                continue
            out_path.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as src, out_path.open("wb") as dst:
                shutil.copyfileobj(src, dst)


def write_manifest() -> dict:
    entries = []
    for model_dir in sorted(p for p in MODELS.iterdir() if p.is_dir() and not p.name.startswith("_")):
        for file in sorted(f for f in model_dir.rglob("*") if f.is_file()):
            entries.append({
                "path": file.relative_to(MODELS).as_posix(),
                "size": file.stat().st_size,
                "sha256": sha256_of(file),
            })
    (MODELS / "manifest.json").write_text(json.dumps({"version": 1, "files": entries}, indent=2) + "\n")
    print(f"Wrote models/manifest.json ({len(entries)} files)")
    return {entry["path"]: entry for entry in entries}


def write_registry(sources: dict, manifest: dict) -> None:
    """Writes the app's list of model files it needs on the phone, with their sizes and checksums."""
    models = []
    for model in sources["models"]:
        files = [manifest.get(f"{model['id']}/{name}") for name in model["phoneFiles"]]
        optional = model.get("optional", False)
        if None in files:
            if optional:
                # Not fetched: the registry still lists the files, from the pinned sources, so the app can explain.
                files = [{"path": f"{model['id']}/{d['saveAs']}", "size": d["size"], "sha256": d["sha256"]}
                         for d in model["downloads"] if d["saveAs"] in model["phoneFiles"]]
            else:
                print(f"Not writing {REGISTRY.relative_to(ROOT)}: {model['id']} is not fully downloaded")
                return
        models.append({"id": model["id"], "purpose": model["purpose"], "licence": model["licence"],
                       "optional": optional, "files": files})
    REGISTRY.parent.mkdir(parents=True, exist_ok=True)
    REGISTRY.write_text(json.dumps({"version": 1, "models": models}, indent=2) + "\n")
    print(f"Wrote {REGISTRY.relative_to(ROOT)} ({sum(len(m['files']) for m in models)} files)")


def main() -> None:
    only = set(sys.argv[1:])
    sources = json.loads(SOURCES.read_text())
    pinned = False

    for lib in sources.get("buildLibraries", []):
        if only and lib["id"] not in only:
            continue
        pinned |= fetch_verified(lib, ROOT / lib["saveAs"])

    for model in sources["models"]:
        if only and model["id"] not in only:
            continue
        target = MODELS / model["id"]
        target.mkdir(parents=True, exist_ok=True)
        for entry in model["downloads"]:
            if entry["type"] == "file":
                pinned |= fetch_verified(entry, target / entry["saveAs"])
            elif entry["type"] == "tar.bz2":
                archive = CACHE / PurePosixPath(urlparse(entry["url"]).path).name
                pinned |= fetch_verified(entry, archive)
                extract_tar_bz2(archive, target, entry.get("stripComponents", 0))
            else:
                sys.exit(f"Unknown download type {entry['type']}")

    if pinned:
        SOURCES.write_text(json.dumps(sources, indent=2) + "\n")
        print("Recorded first-download checksums in scripts/model-sources.json")

    write_registry(sources, write_manifest())


if __name__ == "__main__":
    main()
