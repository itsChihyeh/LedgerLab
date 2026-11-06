"""AC-11.2: the service package gets no way to reach the main application except the read-only tool layer."""

import re
import sys
import unittest
from pathlib import Path

PACKAGE = Path(__file__).resolve().parents[1] / "ledgerlab_agent_service"

FORBIDDEN = {
    "subprocess": r"\bsubprocess\b",
    "os.system": r"\bos\.system\b",
    "os.popen / exec*": r"\bos\.(popen|exec\w*|spawn\w*)\b",
    "socket": r"\bsocket\b",
    "urllib / http.client": r"\b(urllib|http\.client|httplib|requests|httpx|aiohttp)\b",
    "database driver": r"\b(sqlite3|psycopg2?|pg8000|asyncpg|pymysql|sqlalchemy)\b",
    "write-mode open()": r"\bopen\([^)]*[\"'][^\"']*[wax+][^\"']*[\"']",
    "path write": r"\.(write_text|write_bytes|unlink|rmdir|rmtree|mkdir)\(",
    "eval/exec": r"(?<![\w.])(eval|exec)\(",
    "HTTP write verbs": r"\b(POST|PUT|DELETE|PATCH)\b",
    "transport internals": r"\bvalidate_base_url\b|\b_NoRedirect\b|\bMAX_READ_BYTES\b",
}
# The only writes the package may contain: the append-mode open in store.py. (The inbound do_POST handler and the
# directory creation are not matched by the patterns above; they are pinned by the tests below.)
ALLOWED = {("store.py", "write-mode open()"): 1}


def sources() -> list[Path]:
    files = sorted(PACKAGE.rglob("*.py"))
    assert files
    return files


class ServicePackageStaticChecksTest(unittest.TestCase):
    def test_ac_11_2_only_the_listed_writes_and_no_shell_socket_database_or_http_client(self) -> None:
        counts: dict[tuple[str, str], int] = {}
        problems: list[str] = []
        for path in sources():
            text = path.read_text(encoding="utf-8")
            for label, pattern in FORBIDDEN.items():
                for match in re.finditer(pattern, text):
                    key = (path.name, label)
                    counts[key] = counts.get(key, 0) + 1
                    if counts[key] > ALLOWED.get(key, 0):
                        line = text.count("\n", 0, match.start()) + 1
                        problems.append(f"{path.relative_to(PACKAGE)}:{line}: {label}")
        self.assertEqual(problems, [])
        self.assertEqual(counts, ALLOWED)

    def test_ac_11_2_the_only_file_opened_for_writing_is_the_append_mode_jsonl_in_store(self) -> None:
        text = (PACKAGE / "store.py").read_text(encoding="utf-8")
        opens = re.findall(r"\bopen\(([^)]*)\)", text)
        self.assertEqual(len(opens), 1)
        self.assertRegex(opens[0], r',\s*"a"\s*,')
        for path in sources():
            if path.name != "store.py":
                self.assertNotRegex(path.read_text(encoding="utf-8"), r"\bopen\(", path.name)

    def test_ac_11_2_exactly_one_inbound_write_handler_exists(self) -> None:
        handlers = []
        for path in sources():
            handlers += re.findall(r"def (do_[A-Za-z]+)\(", path.read_text(encoding="utf-8"))
        self.assertEqual(sorted(handlers), ["do_GET", "do_POST"])

    def test_ac_11_2_directories_are_made_in_one_place_only(self) -> None:
        hits = [p.name for p in sources() if re.search(r"\bos\.makedirs\(", p.read_text(encoding="utf-8"))]
        self.assertEqual(hits, ["store.py"])

    def test_ac_11_2_only_the_standard_library_the_agent_package_and_itself_are_imported(self) -> None:
        allowed = set(sys.stdlib_module_names) | {"ledgerlab_agent", "ledgerlab_agent_service", "__future__"}
        problems: list[str] = []
        for path in sources():
            for match in re.finditer(r"^\s*(?:from|import)\s+([A-Za-z_]\w*)", path.read_text("utf-8"), re.M):
                if match.group(1) not in allowed:
                    problems.append(f"{path.name}: {match.group(1)}")
        self.assertEqual(problems, [])

    def test_ac_11_2_the_only_import_from_the_transport_module_is_the_public_read_only_pair(self) -> None:
        imported: list[str] = []
        for path in sources():
            for match in re.finditer(r"^from ledgerlab_agent\.transport import (.+)$", path.read_text("utf-8"), re.M):
                imported += [name.strip() for name in match.group(1).split(",")]
        self.assertEqual(sorted(imported), ["HttpTransport", "Transport"])


if __name__ == "__main__":
    unittest.main()
