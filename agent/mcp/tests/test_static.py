"""AC-11.7/11.8: the MCP package obeys the core package's static rules, with no exceptions."""

import importlib.util
import re
import sys
import unittest
from pathlib import Path

PACKAGE = Path(__file__).resolve().parents[1] / "ledgerlab_agent_mcp"
CORE_STATIC = Path(__file__).resolve().parents[2] / "tests" / "test_static.py"


def _core_forbidden() -> dict[str, str]:
    # Loaded by path: the name `tests` is already taken by this package's own tests.
    spec = importlib.util.spec_from_file_location("core_test_static", CORE_STATIC)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.FORBIDDEN


class McpPackageStaticChecksTest(unittest.TestCase):
    def test_ac_11_8_package_has_no_write_shell_socket_or_database_access(self) -> None:
        forbidden = _core_forbidden()
        self.assertTrue(forbidden)
        files = sorted(PACKAGE.rglob("*.py"))
        self.assertTrue(files)
        problems: list[str] = []
        for path in files:
            text = path.read_text(encoding="utf-8")
            for label, pattern in forbidden.items():
                for match in re.finditer(pattern, text):
                    line = text.count("\n", 0, match.start()) + 1
                    problems.append(f"{path.relative_to(PACKAGE)}:{line}: {label}")
        self.assertEqual(problems, [])

    def test_ac_11_8_package_only_imports_the_standard_library_the_agent_package_or_itself(self) -> None:
        allowed = set(sys.stdlib_module_names) | {"ledgerlab_agent", "ledgerlab_agent_mcp", "__future__"}
        problems: list[str] = []
        for path in sorted(PACKAGE.rglob("*.py")):
            for match in re.finditer(r"^\s*(?:from|import)\s+([A-Za-z_]\w*)", path.read_text("utf-8"), re.M):
                if match.group(1) not in allowed:
                    problems.append(f"{path.name}: {match.group(1)}")
        self.assertEqual(problems, [])

    def test_ac_11_8_the_only_transport_imports_are_the_public_read_only_ones(self) -> None:
        imported: list[str] = []
        for path in sorted(PACKAGE.rglob("*.py")):
            for match in re.finditer(r"^from ledgerlab_agent\.transport import (.+)$", path.read_text("utf-8"), re.M):
                imported += [name.strip() for name in match.group(1).split(",")]
        self.assertEqual(sorted(imported), ["HttpTransport", "Transport"])


if __name__ == "__main__":
    unittest.main()
