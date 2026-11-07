import inspect
import unittest

from ledgerlab_agent import transport as transport_module
from ledgerlab_agent.transport import HttpTransport

from tests.fakes import FakeBackend


class TransportTest(unittest.TestCase):
    def test_ac_10_1_rejects_non_http_base_urls(self) -> None:
        for url in ["ftp://host/", "file:///etc/passwd", "localhost:8080", "", "//host", "javascript:alert(1)",
                    "http://", "http://host with space"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                HttpTransport(url)

    def test_ac_10_1_rejects_userinfo_query_and_fragment_in_base_url(self) -> None:
        for url in ["http://user:pw@host:8080", "http://user@host", "https://@host", "http://host?x=1",
                    "http://host/#frag"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                HttpTransport(url)

    def test_ac_10_1_accepts_plain_http_and_https(self) -> None:
        HttpTransport("http://127.0.0.1:8080")
        HttpTransport("https://ledger.example/")

    def test_ac_10_1_transport_exposes_only_get(self) -> None:
        public = {n for n, _ in inspect.getmembers(HttpTransport, inspect.isfunction) if not n.startswith("_")}
        self.assertEqual(public, {"get"})

    def test_ac_10_1_request_method_is_fixed_to_get(self) -> None:
        source = inspect.getsource(transport_module)
        self.assertIn('method="GET"', source)
        for verb in ("POST", "PUT", "DELETE", "PATCH"):
            self.assertNotIn(verb, source)

    def test_ac_10_1_redirects_are_errors_not_followed(self) -> None:
        routes = {
            "/api/a": (302, b"", {"Location": "/api/b"}),
            "/api/b": (200, b'{"ok":true}', {}),
        }
        with FakeBackend(routes) as backend:
            response = HttpTransport(backend.base_url).get("/api/a", {})
            self.assertEqual(response.status, 302)
            self.assertEqual(response.error, "redirect_not_followed")
            self.assertIsNone(response.body)
            self.assertEqual([p for _, p, _ in backend.requests], ["/api/a"])

    def test_ac_10_1_get_returns_status_and_body_for_errors_too(self) -> None:
        with FakeBackend({"/api/x": (503, b'{"e":1}', {})}) as backend:
            response = HttpTransport(backend.base_url).get("/api/x", {"q": "a b"})
            self.assertEqual((response.status, response.body), (503, b'{"e":1}'))
            self.assertEqual(backend.requests, [("GET", "/api/x", {"q": "a b"})])

    def test_ac_10_1_connection_failure_is_an_error_value(self) -> None:
        with FakeBackend({}) as backend:
            url = backend.base_url
        response = HttpTransport(url, timeout=1).get("/api/x", {})
        self.assertEqual(response.status, 0)
        self.assertTrue(response.error and response.error.startswith("transport_error"))

    def test_ac_10_1_rejects_paths_that_could_change_the_host(self) -> None:
        transport = HttpTransport("http://127.0.0.1:1")
        for path in ["//evil.example/x", "http://evil.example/x", "api/x"]:
            self.assertEqual(transport.get(path, {}).error, "invalid_path")


if __name__ == "__main__":
    unittest.main()
