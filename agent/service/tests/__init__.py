import sys
from pathlib import Path

_AGENT = Path(__file__).resolve().parents[2]
for _path in (_AGENT / "src", _AGENT / "service"):
    if str(_path) not in sys.path:
        sys.path.insert(0, str(_path))
