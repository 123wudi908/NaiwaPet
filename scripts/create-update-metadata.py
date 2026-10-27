"""Generate the exact UTF-8 bytes to sign for a Naiwa GitHub Release."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def make_manifest(apk: Path, badging: str, tag: str, notes: str) -> dict:
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    if not match or match.group(1) != "com.naiwa.androidpet" or int(match.group(2)) <= 0:
        raise ValueError("APK package or version is invalid")
    if not tag.strip() or not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", apk.name):
        raise ValueError("Release tag or APK filename is invalid")
    if len(notes) > 65536:
        raise ValueError("Release notes are too long")
    size = apk.stat().st_size
    if not 0 < size <= 200 * 1024 * 1024:
        raise ValueError("APK size is outside the supported range")
    digest = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return {"tag": tag, "versionName": match.group(3), "versionCode": int(match.group(2)),
            "applicationId": match.group(1), "assetName": apk.name, "size": size,
            "sha256": digest.hexdigest(), "notes": notes}


def write_manifest(output: Path, apk: Path, manifest: dict) -> None:
    if output.resolve() == apk.resolve() or (output.exists() and output.samefile(apk)):
        raise ValueError("Metadata output must not overwrite the APK")
    payload = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > 131072:
        raise ValueError("Metadata exceeds the client's 128 KiB limit")
    output.write_bytes(payload)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    badging = subprocess.run([str(args.aapt), "dump", "badging", str(args.apk)], check=True,
                             capture_output=True, text=True, encoding="utf-8").stdout
    manifest = make_manifest(args.apk, badging, args.tag, args.notes_file.read_text(encoding="utf-8"))
    write_manifest(args.output, args.apk, manifest)


if __name__ == "__main__":
    main()
