import importlib.util
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("audit", Path(__file__).parents[1] / "audit-apk-abis.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ApkAbiAuditTest(unittest.TestCase):
    def fixture(self, root, abis, wrong=False, missing=False):
        path = Path(root) / "test.apk"
        with zipfile.ZipFile(path, "w") as apk:
            for abi in abis:
                elf_class, machine = module.ARCHITECTURES[abi]
                for library in module.LIBRARIES:
                    if missing and library == "libjnidispatch.so":
                        continue
                    header = bytearray(20)
                    header[:6] = b"\x7fELF" + bytes([2 if wrong else elf_class, 1])
                    struct.pack_into("<HH", header, 16, 3, machine)
                    apk.writestr(f"lib/{abi}/{library}", header)
        return path

    def test_each_variant(self):
        with tempfile.TemporaryDirectory() as root:
            for variant in [*module.ARCHITECTURES, "universal"]:
                abis = module.ARCHITECTURES if variant == "universal" else [variant]
                self.assertEqual(variant, module.audit(self.fixture(root, abis), variant)["variant"])

    def test_rejects_wrong_elf_missing_jna_and_extra_abi(self):
        with tempfile.TemporaryDirectory() as root:
            for wrong, missing, abis in [(True, False, ["armeabi-v7a"]),
                                         (False, True, ["armeabi-v7a"]),
                                         (False, False, module.ARCHITECTURES)]:
                with self.assertRaises(ValueError):
                    module.audit(self.fixture(root, abis, wrong, missing), "armeabi-v7a")


if __name__ == "__main__":
    unittest.main()
