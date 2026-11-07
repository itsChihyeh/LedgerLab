import re
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parent.parent / "src"

FORBIDDEN = {
    "subprocess": r"\bsubprocess\b",
    "os.system": r"\bos\.system\b",
    "os.popen / exec*": r"\bos\.(popen|exec\w*|spawn\w*)\b",
    "socket": r"\bsocket\b",
    "database driver": r"\b(sqlite3|psycopg2?|pg8000|asyncpg|pymysql|sqlalchemy)\b",
    "write-mode open()": r"\bopen\([^)]*[\"'][^\"']*[wax+][^\"']*[\"']",
    "path write": r"\.(write_text|write_bytes|unlink|rmdir|rmtree|mkdir)\(",
    "eval/exec": r"(?<![\w.])(eval|exec)\(",
    "HTTP write verbs": r"\b(POST|PUT|DELETE|PATCH)\b",
}


# The one file allowed to send a POST (to its fixed base URL only); every other verb stays forbidden there too.
EXTERNAL_MODEL_FILE = "deepseek_model.py"
POST_ALLOWED_PATTERN = r"\b(PUT|DELETE|PATCH)\b"


class StaticChecksTest(unittest.TestCase):
    def test_ac_10_1_agent_source_has_no_write_shell_socket_or_database_access(self) -> None:
        files = sorted(SRC.rglob("*.py"))
        self.assertTrue(files)
        problems: list[str] = []
        for path in files:
            text = path.read_text(encoding="utf-8")
            for label, pattern in FORBIDDEN.items():
                if label == "HTTP write verbs" and path.name == EXTERNAL_MODEL_FILE:
                    pattern = POST_ALLOWED_PATTERN
                for match in re.finditer(pattern, text):
                    line = text.count("\n", 0, match.start()) + 1
                    problems.append(f"{path.relative_to(SRC)}:{line}: {label}")
        self.assertEqual(problems, [])

    def test_ac_10_10_only_the_deepseek_adapter_posts_and_only_to_the_fixed_base_url(self) -> None:
        posting = [p.name for p in sorted(SRC.rglob("*.py")) if re.search(r"\bPOST\b", p.read_text("utf-8"))]
        self.assertEqual(posting, [EXTERNAL_MODEL_FILE])
        text = (SRC / "ledgerlab_agent" / EXTERNAL_MODEL_FILE).read_text("utf-8")
        self.assertEqual(re.findall(r'^BASE_URL = "([^"]*)"$', text, re.M), ["https://api.deepseek.com"])
        # the base URL is the only URL written in the file (the docstring names none), and nothing reads it from outside
        self.assertEqual(re.findall(r"https?://[^\s\"')]+", text), ["https://api.deepseek.com"])
        self.assertEqual(re.findall(r'^API_KEY_ENV = "([^"]*)"$', text, re.M), ["DEEPSEEK_API_KEY"])
        self.assertEqual(re.findall(r"\.get\(([A-Z_]+)[,)]", text), ["API_KEY_ENV"])  # the only environment lookup
        self.assertNotIn("getenv", text)
        self.assertNotIn("BASE_URL =", text.replace('BASE_URL = "https://api.deepseek.com"', "", 1))

    def test_ac_10_1_agent_source_only_imports_the_standard_library_or_itself(self) -> None:
        import sys

        stdlib = set(sys.stdlib_module_names) | {"ledgerlab_agent", "__future__"}
        problems: list[str] = []
        for path in sorted(SRC.rglob("*.py")):
            for match in re.finditer(r"^\s*(?:from|import)\s+([A-Za-z_][\w]*)", path.read_text("utf-8"), re.M):
                if match.group(1) not in stdlib:
                    problems.append(f"{path.relative_to(SRC)}: {match.group(1)}")
        self.assertEqual(problems, [])


if __name__ == "__main__":
    unittest.main()
