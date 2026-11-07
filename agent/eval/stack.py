"""Disposable Compose stack for evaluation tooling (standard library only).

Mirrors scripts/smoke-p5.sh: only the public synthetic configuration in compose.smoke.env is used, the
implicit .env is never read, ports are random host ports, and the project is always torn down on exit.
"""

from __future__ import annotations

import os
import secrets
import signal
import subprocess
import sys
import threading
from collections.abc import Callable, Mapping
from pathlib import Path
from types import TracebackType

REPO_ROOT = Path(__file__).resolve().parents[2]
PROJECT_PREFIX = "ledgerlab-agent-eval-"
ENV_FILE = "compose.smoke.env"
COMPOSE_FILES: tuple[str, ...] = ("compose.yaml",)
LOG_DIR = Path("target") / "agent-eval"
APP_JAR = Path("target") / "ledgerlab-0.0.1-SNAPSHOT.jar"
SIMULATOR_JAR = Path("simulator") / "target" / "ledgerlab-simulator-0.0.1-SNAPSHOT.jar"
# Variables that would silently redirect Compose to another project, file set or env file.
AMBIENT_COMPOSE_VARIABLES: tuple[str, ...] = (
    "COMPOSE_FILE",
    "COMPOSE_PROFILES",
    "COMPOSE_ENV_FILES",
    "COMPOSE_PROJECT_NAME",
)

Runner = Callable[..., "subprocess.CompletedProcess[str]"]


class StackError(RuntimeError):
    pass


def new_project_name(token_hex: Callable[[int], str] = secrets.token_hex) -> str:
    return PROJECT_PREFIX + token_hex(6)


def read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def compose_environment(ambient: Mapping[str, str], smoke_values: Mapping[str, str]) -> dict[str, str]:
    env = {k: v for k, v in ambient.items() if k not in AMBIENT_COMPOSE_VARIABLES}
    env.update(smoke_values)
    env["COMPOSE_DISABLE_ENV_FILE"] = "1"
    return env


def compose_command(project: str, *args: str) -> list[str]:
    command = ["docker", "compose", "--env-file", ENV_FILE]
    for compose_file in COMPOSE_FILES:
        command += ["-f", compose_file]
    return [*command, "-p", project, "--profile", "smoke", *args]


def parse_published_port(output: str) -> str:
    """`docker compose port` prints `host:port` (possibly several lines); return the first IPv4 address."""
    for line in output.splitlines():
        address = line.strip()
        host, _, port = address.rpartition(":")
        if host and port.isdigit() and 0 < int(port) < 65536 and "[" not in host:
            return address
    raise StackError(f"Cannot parse a published port from: {output!r}")


def missing_jars(root: Path) -> list[Path]:
    return [jar for jar in (APP_JAR, SIMULATOR_JAR) if not (root / jar).is_file()]


def require_jars(root: Path) -> None:
    missing = missing_jars(root)
    if missing:
        names = ", ".join(str(p).replace("\\", "/") for p in missing)
        raise StackError(
            f"Missing build output: {names}. Build first, from the repository root:\n"
            "  ./mvnw -B -DskipTests package\n"
            "  ./mvnw -B -f simulator/pom.xml -DskipTests package"
        )


class Stack:
    """Context manager: `with Stack() as stack:` gives `stack.app` and `stack.sim` as `host:port`."""

    def __init__(
        self,
        root: Path = REPO_ROOT,
        *,
        ambient_env: Mapping[str, str] | None = None,
        runner: Runner | None = None,
        token_hex: Callable[[int], str] = secrets.token_hex,
        log: Callable[[str], None] | None = None,
    ) -> None:
        self.root = root
        self.project = new_project_name(token_hex)
        self._ambient = dict(os.environ if ambient_env is None else ambient_env)
        self._runner: Runner = runner or subprocess.run
        self._log = log or (lambda message: print(message, file=sys.stderr, flush=True))
        self._started = False
        self.app = ""
        self.sim = ""

    def _compose(self, *args: str, check: bool = True, timeout: int = 600) -> subprocess.CompletedProcess[str]:
        env = compose_environment(self._ambient, read_env_file(self.root / ENV_FILE))
        return self._runner(
            compose_command(self.project, *args),
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
            check=check,
            timeout=timeout,
        )

    def __enter__(self) -> Stack:
        require_jars(self.root)
        if threading.current_thread() is threading.main_thread():
            # Turn SIGTERM into SystemExit so __exit__ still tears the project down.
            signal.signal(signal.SIGTERM, lambda _signum, _frame: sys.exit(143))
        self._started = True  # Even a half-started project must be removed.
        try:
            self._log(f"Starting disposable project {self.project}...")
            self._compose("up", "-d", "--build", "--wait", "--wait-timeout", "180", timeout=900)
            self.app = parse_published_port(self._compose("port", "app", "8080").stdout)
            self.sim = parse_published_port(self._compose("port", "simulator", "8081").stdout)
        except BaseException:
            self._teardown(failed=True)
            raise
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self._teardown(failed=exc_type is not None)

    def _teardown(self, failed: bool = False) -> None:
        if not self._started:
            return
        self._started = False
        try:
            logs = self._compose("logs", "--no-color", check=False)
            log_dir = self.root / LOG_DIR
            log_dir.mkdir(parents=True, exist_ok=True)
            (log_dir / f"{self.project}.log").write_text(logs.stdout + logs.stderr, encoding="utf-8")
            if failed:
                self._log(f"Evaluation failed; compose log: {LOG_DIR / (self.project + '.log')}")
        finally:
            self._compose("down", "-v", "--remove-orphans", check=False)
            self._log(f"Removed project {self.project}.")
