"""Fail closed on missing, unexpected or mislabeled APK native libraries."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

ARCHITECTURES = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
LIBRARIES = {"libtgwsproxy.so", "libjnidispatch.so"}


def audit(path, variant):
    expected = set(ARCHITECTURES) if variant == "universal" else {variant}
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP entries")
        native = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        wanted = {f"lib/{abi}/{lib}" for abi in expected for lib in LIBRARIES}
        if native != wanted:
            raise ValueError(f"Unexpected native payload: missing={wanted-native}, extra={native-wanted}")
        for name in sorted(native):
            header = apk.read(name)[:20]
            abi = name.split("/")[1]
            elf_class, machine = ARCHITECTURES[abi]
            if (len(header) != 20 or header[:4] != b"\x7fELF" or
                    header[4] != elf_class or header[5] != 1 or
                    struct.unpack_from("<HH", header, 16) != (3, machine)):
                raise ValueError(f"Wrong ELF class/machine/type: {name}")
        forbidden = (".jks", ".keystore", ".pem", ".key", ".env")
        if any(name.lower().endswith(forbidden) for name in names):
            raise ValueError("Sensitive file extension in APK")
    path = Path(path)
    return {"path": str(path.resolve()), "variant": variant, "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "libraries": sorted(native)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("variant", choices=[*ARCHITECTURES, "universal"])
    args = parser.parse_args()
    print(json.dumps(audit(args.apk, args.variant), indent=2))
