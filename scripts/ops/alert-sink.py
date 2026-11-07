"""Entry point: python scripts/ops/alert-sink.py --out <file> [--host 127.0.0.1] [--port 9095]."""

import sys

from alert_sink import main

if __name__ == "__main__":
    sys.exit(main())
