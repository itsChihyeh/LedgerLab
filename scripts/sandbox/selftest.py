"""Run all sandbox offline tests without credentials, Stripe, or Docker."""
import unittest
from pathlib import Path

if __name__ == '__main__':
    tests = unittest.defaultTestLoader.discover(str(Path(__file__).parent), pattern='test_*.py')
    raise SystemExit(not unittest.TextTestRunner(verbosity=2).run(tests).wasSuccessful())
