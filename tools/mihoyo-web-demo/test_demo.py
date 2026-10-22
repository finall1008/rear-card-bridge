"""Synthetic fixtures only: tests never log in or access a real account."""
import http.client
import json
import threading
import unittest
from email.message import Message
from unittest.mock import patch

import server


class FakeTransport:
    def __init__(self):
        self.calls = []
        self.status = "Created"
        self.error = None

    def __call__(self, url, **kwargs):
        self.calls.append((url, kwargs))
        if self.error:
            raise self.error
        if url.endswith("createQRLogin"):
            return {"url": "https://user.mihoyo.com/login-platform/mobile.html?tk=synthetic", "ticket": "synthetic"}, {}
        if url.endswith("queryQRLoginStatus"):
            return {"status": self.status}, ({"ltoken_v2": "fake-secret", "ltmid_v2": "fake-mid"} if self.status == "Confirmed" else {})
        if "createGeetest" in url:
            return {"data": {"gt": "synthetic-gt", "challenge": "synthetic-challenge", "success": 1, "new_captcha": True}}, {}
        if "createVerification" in url:
            return {"gt": "synthetic-gt", "challenge": "synthetic-challenge", "success": 1, "new_captcha": True}, {}
        if "verifyGeetest" in url or "verifyVerification" in url:
            return {"challenge": "synthetic-verified"}, {}
        if "getUserGameRoles" in url:
            biz = server.parse.parse_qs(server.parse.urlsplit(url).query)["game_biz"][0]
            return {"list": [{"game_biz": biz, "game_uid": "100000001", "region": "prod_gf_cn",
                              "nickname": "Synthetic Test", "region_name": "Test"}]}, {}
        return {"current_resin": 10, "max_resin": 200, "resin_recovery_time": "900",
                "finished_task_num": 4, "total_task_num": 4, "is_extra_task_reward_received": False}, {}


class FlowTests(unittest.TestCase):
    def setUp(self):
        self.transport = FakeTransport()
        self.demo = server.Demo(self.transport)

    def login(self):
        self.demo.create_qr()
        self.transport.status = "Confirmed"
        self.demo.poll_qr()

    def test_qr_creates_svg_locally(self):
        result = self.demo.create_qr()
        self.assertTrue(self.demo.qr_svg.startswith(b"<?xml"))
        self.assertIn(b"<svg", self.demo.qr_svg)
        self.assertNotIn("ticket", result)
        self.assertEqual(self.transport.calls[0][1]["body"], {})

    def test_poll_transitions_and_minimal_state(self):
        self.demo.create_qr()
        self.assertEqual(self.demo.poll_qr()["status"], "Created")
        with self.assertRaises(server.DemoError):
            self.demo.poll_qr()
        self.demo.last_poll = 0
        self.transport.status = "Scanned"
        self.assertEqual(self.demo.poll_qr()["status"], "Scanned")
        self.demo.last_poll = 0
        self.transport.status = "Confirmed"
        self.demo.poll_qr()
        self.assertTrue(self.demo.state()["authenticated"])
        self.assertNotIn("fake-secret", json.dumps(self.demo.state()))
        self.assertIsNone(self.demo.pending)
        self.assertIsNone(self.demo.qr_svg)
        devices = {call[1]["device_id"] for call in self.transport.calls}
        self.assertEqual(len(devices), 1)

    def test_qr_expiry_clears_pending(self):
        self.demo.create_qr()
        self.transport.error = server.DemoError("expired", code=-3501)
        with self.assertRaises(server.DemoError):
            self.demo.poll_qr()
        self.assertIsNone(self.demo.pending)
        self.assertIsNone(self.demo.qr_svg)

    def test_reject_nonofficial_qr(self):
        self.demo.transport = lambda *a, **k: ({"url": "https://example.org/?token=x", "ticket": "x"}, {})
        with self.assertRaises(server.DemoError):
            self.demo.create_qr()

    def test_role_query_and_cache(self):
        self.login()
        roles = self.demo.get_roles()["roles"]
        self.assertEqual(len(roles), 3)
        result = self.demo.notes(roles[0]["key"])
        self.assertEqual(result["summary"]["stamina"]["max"], 200)
        self.assertEqual(result["summary"]["daily"]["current"], 4)
        self.assertFalse(result["summary"]["daily"]["extra_reward_received"])
        self.assertFalse(result["cached"])
        count = len(self.transport.calls)
        self.assertTrue(self.demo.notes(roles[0]["key"])["cached"])
        self.assertEqual(len(self.transport.calls), count)
        self.assertNotIn("fake-secret", json.dumps(result))
        self.assertIn("role_id=100000001&server=prod_gf_cn", self.transport.calls[-1][0])

    def test_cache_expires(self):
        self.login()
        key = self.demo.get_roles()["roles"][0]["key"]
        self.demo.notes(key)
        timestamp, result = self.demo.cache[key]
        self.demo.cache[key] = (timestamp - 61, result)
        self.assertFalse(self.demo.notes(key)["cached"])

    def test_no_arbitrary_role_or_upstream_url(self):
        self.login()
        with self.assertRaises(server.DemoError):
            self.demo.notes("hk4e_cn:cn_gf01:someone-else")
        self.assertEqual(len(self.transport.calls), 2)

    def test_wind_control_not_cached_or_retried(self):
        self.login()
        key = self.demo.get_roles()["roles"][0]["key"]
        self.transport.error = server.DemoError("risk", code=1034)
        count = len(self.transport.calls)
        with self.assertRaises(server.DemoError) as caught:
            self.demo.notes(key)
        self.assertIn("人机验证", caught.exception.hint)
        self.assertEqual(len(self.transport.calls), count + 1)
        self.assertFalse(self.demo.cache)

    def test_pure_web_routes_do_not_use_app_prefix(self):
        for biz in ("hk4e_cn", "hkrpg_cn"):
            self.assertNotIn("/app/", server.GAMES[biz][1])

    def test_manual_captcha_bound_to_role_and_device(self):
        self.login()
        key = self.demo.get_roles()["roles"][0]["key"]
        self.demo.risks[key] = {"path": server.RECORD + server.GAMES["hk4e_cn"][1], "trace": "test-trace"}
        mmt = self.demo.create_verification(key)
        self.assertEqual(self.transport.calls[-1][1]["headers_extra"]["x-rpc-challenge_trace"], "test-trace")
        validation = {"geetest_challenge": mmt["challenge"] + "suffix", "geetest_validate": "fake-validate", "geetest_seccode": "fake-seccode"}
        result = self.demo.verify(mmt["id"], validation)
        self.assertTrue(result["verified"])
        self.demo.notes(key)
        self.assertEqual(self.transport.calls[-1][1]["headers_extra"], {"x-rpc-challenge": "synthetic-verified"})
        self.assertIsNone(self.demo.captcha)
        with self.assertRaises(server.DemoError):
            self.demo.verify(mmt["id"], validation)
        self.demo.logout()
        self.assertFalse(self.demo.challenges)
        self.assertFalse(self.demo.risks)

    def test_zzz_captcha_uses_separate_risk_service(self):
        self.login()
        key = self.demo.get_roles()["roles"][2]["key"]
        self.demo.risks[key] = {"path": server.RECORD + server.GAMES["nap_cn"][1], "trace": "test-trace"}
        mmt = self.demo.create_verification(key)
        self.assertIn("toolcomsrv/risk/createGeetest?app_key=game_record_zzz", self.transport.calls[-1][0])
        self.demo.verify(mmt["id"], {"geetest_challenge": mmt["challenge"], "geetest_validate": "fake", "geetest_seccode": "fake"})
        url, kwargs = self.transport.calls[-1]
        self.assertEqual(url, "https://api-takumi.mihoyo.com/event/toolcomsrv/risk/verifyGeetest")
        self.assertEqual(kwargs["body"]["app_key"], "game_record_zzz")
        self.assertNotIn("fake-secret", json.dumps(self.demo.state()))
        self.assertEqual(self.demo.state()["diagnostics"][-1]["stage"], "captcha.submit")

    def test_captcha_validation_rejects_unrequested_and_expired(self):
        self.login()
        key = self.demo.get_roles()["roles"][0]["key"]
        with self.assertRaises(server.DemoError):
            self.demo.create_verification(key)
        self.demo.risks[key] = {"path": "https://api-takumi-record.mihoyo.com/game_record/genshin/api/dailyNote"}
        mmt = self.demo.create_verification(key)
        with self.assertRaises(server.DemoError):
            self.demo.verify(mmt["id"], {"geetest_challenge": "wrong", "geetest_validate": "x", "geetest_seccode": "x"})
        self.demo.captcha["created"] -= 301
        with self.assertRaises(server.DemoError):
            self.demo.verify(mmt["id"], {})

    def test_cookie_import_allowlist_and_logout(self):
        self.demo.import_cookie("ltoken_v2=fake-secret; ltmid_v2=fake-mid; stoken=do-not-retain; unrelated=value")
        self.assertEqual(set(self.demo.cookies), {"ltoken_v2", "ltmid_v2"})
        self.demo.get_roles()
        self.demo.logout()
        self.assertFalse(self.demo.cookies)
        self.assertFalse(self.demo.roles)
        self.assertFalse(self.demo.cache)
        self.assertFalse(server.Demo(self.transport).cookies)

    def test_cookie_token_roles_route(self):
        self.demo.import_cookie("cookie_token_v2=fake; account_id_v2=1")
        self.demo.get_roles()
        self.assertTrue(self.transport.calls[0][0].startswith(server.COOKIE_ROLES))

    def test_bad_cookies_rejected_without_clearing_existing(self):
        self.login()
        for text in ("stoken=bad", "ltoken_v2=bad\r\nHost: evil", "", None, "x" * 32769):
            with self.assertRaises(server.DemoError):
                self.demo.import_cookie(text)
        self.assertTrue(self.demo.cookies)

    def test_game_summaries_do_not_assume_reward_collection(self):
        sr = server.summarize("hkrpg_cn", {"current_stamina": 55, "max_stamina": 300,
                              "stamina_recover_time": 60, "current_train_score": 500, "max_train_score": 500}, 1)
        self.assertEqual(sr["daily"]["current"], 500)
        self.assertNotIn("extra_reward_received", sr["daily"])
        zzz = server.summarize("nap_cn", {"energy": {"progress": {"current": 20, "max": 240}, "restore": 600},
                              "vitality": {"current": 400, "max": 400}}, 1)
        self.assertEqual(zzz["stamina"]["recovery_seconds"], 600)
        self.assertEqual(zzz["daily"]["current"], 400)
        self.assertIsNone(server.summarize("nap_cn", {}, 1)["stamina"]["max"])
        self.assertIsNone(server.duration("invalid"))


class TransportTests(unittest.TestCase):
    def test_response_cookies_and_no_custom_signature(self):
        headers = Message()
        headers.add_header("Set-Cookie", "ltoken_v2=fake; HttpOnly; Domain=.miyoushe.com; Path=/")
        headers.add_header("Set-Cookie", "ltmid_v2=mid; Path=/")
        headers.add_header("Set-Cookie", "irrelevant=not-retained")
        response = unittest.mock.MagicMock()
        response.__enter__.return_value = response
        response.headers = headers
        response.read.return_value = b'{"retcode":0,"data":{"status":"Confirmed"}}'
        opener = unittest.mock.MagicMock()
        opener.open.return_value = response
        with patch.object(server.request, "build_opener", return_value=opener):
            data, cookies = server.upstream(server.PASSPORT + "queryQRLoginStatus", method="POST",
                                            body={"ticket": "test"}, device_id="device")
        self.assertEqual(cookies, {"ltoken_v2": "fake", "ltmid_v2": "mid"})
        self.assertEqual(data["status"], "Confirmed")
        req = opener.open.call_args.args[0]
        self.assertIsNone(req.get_header("Ds"))
        self.assertIsNone(req.get_header("X-rpc-device_fp"))
        self.assertEqual(req.get_header("X-rpc-device_id"), "device")

    def test_web_ds_only_for_pure_chronicle(self):
        headers = Message()
        response = unittest.mock.MagicMock()
        response.__enter__.return_value = response
        response.headers = headers
        response.read.return_value = b'{"retcode":0,"data":{}}'
        opener = unittest.mock.MagicMock()
        opener.open.return_value = response
        with patch.object(server.request, "build_opener", return_value=opener), patch.object(server, "web_ds", return_value="test-ds"):
            server.upstream(server.RECORD + server.GAMES["hkrpg_cn"][1])
            req = opener.open.call_args.args[0]
            self.assertEqual(req.get_header("Ds"), "test-ds")
            self.assertEqual(req.get_header("X-rpc-app_version"), "2.3.0")
            server.upstream(server.RECORD + server.GAMES["nap_cn"][1])
            self.assertIsNone(opener.open.call_args.args[0].get_header("Ds"))
        value = server.web_ds(1700000000, "abcdef")
        self.assertRegex(value, r"^1700000000,abcdef,[0-9a-f]{32}$")
        self.assertEqual(value, server.web_ds(1700000000, "abcdef"))

    def test_no_redirect_forwarding(self):
        self.assertIsNone(server.NoRedirect().redirect_request(None, None, 302, "", {}, "https://evil.example"))


class HTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.httpd = server.Server(("127.0.0.1", 0), server.Handler)
        cls.httpd.demo = server.Demo(FakeTransport())
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join()

    def send(self, path, body=None, headers=None, method=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        connection.request(method or ("POST" if body else "GET"), path, body=body, headers=headers or {})
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_state_no_store(self):
        status, headers, body = self.send("/api/state")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        self.assertIn("csrf", json.loads(body))

    def test_reject_dns_rebinding_host(self):
        self.assertEqual(self.send("/api/state", headers={"Host": "attacker.example"})[0], 403)

    def test_reject_cross_origin_and_missing_csrf(self):
        self.assertEqual(self.send("/api/qr/create", "{}", {"Content-Type": "application/json"})[0], 403)
        headers = {"Content-Type": "application/json", "Origin": "https://evil.example",
                   "X-Demo-Token": self.httpd.demo.csrf}
        self.assertEqual(self.send("/api/qr/create", "{}", headers)[0], 403)

    def test_json_validation_and_body_limit(self):
        headers = {"Content-Type": "application/json", "X-Demo-Token": self.httpd.demo.csrf}
        for body in ("[]", "{", "x" * 65537):
            self.assertEqual(self.send("/api/logout", body, headers)[0], 400)

    def test_no_file_or_secret_exposure(self):
        for path in ("/server.py", "/requirements.txt", "/../.git/config", "/api/cookies"):
            self.assertEqual(self.send(path)[0], 404)

    def test_end_to_end_synthetic_flow(self):
        headers = {"Content-Type": "application/json", "X-Demo-Token": self.httpd.demo.csrf}
        status, _, body = self.send("/api/cookie", json.dumps({"cookie": "ltoken_v2=fake-secret; ltmid_v2=mid"}), headers)
        self.assertEqual(status, 200)
        self.assertNotIn(b"fake-secret", body)
        status, _, body = self.send("/api/roles", "{}", headers)
        key = json.loads(body)["roles"][0]["key"]
        status, _, body = self.send("/api/notes", json.dumps({"key": key}), headers)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["summary"]["stamina"]["current"], 10)
        self.assertEqual(self.send("/api/logout", "{}", headers)[0], 200)

    def test_captcha_isolation_headers(self):
        status, headers, body = self.send("/captcha.html")
        self.assertEqual(status, 200)
        self.assertIn("https://*.geetest.com", headers["Content-Security-Policy"])
        self.assertNotIn(b"fake-secret", body)
        _, headers, _ = self.send("/")
        self.assertNotIn("geetest.com", headers["Content-Security-Policy"])

    def test_static_assets(self):
        for path in ("/", "/app.js", "/style.css", "/captcha.html", "/captcha.js"):
            self.assertEqual(self.send(path)[0], 200)


if __name__ == "__main__":
    unittest.main()
