#!/usr/bin/env python3
"""Local-only Miyoushe web login + Battle Chronicle notes demo (CN servers)."""
import argparse
import hashlib
import io
import json
import secrets
import threading
import time
import uuid
from http.cookies import SimpleCookie, CookieError
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib import error, parse, request

import qrcode
import qrcode.image.svg

ROOT = Path(__file__).resolve().parent
PASSPORT = "https://passport-api.miyoushe.com/account/ma-cn-passport/web/"
ROLES = "https://api-takumi.mihoyo.com/binding/api/getUserGameRolesByCookie"
COOKIE_ROLES = "https://passport-api.mihoyo.com/binding/api/getUserGameRolesByCookieToken"
RECORD = "https://api-takumi-record.mihoyo.com/"
GAMES = {
    "hk4e_cn": ("原神", "game_record/genshin/api/dailyNote"),
    "hkrpg_cn": ("崩坏：星穹铁道", "game_record/hkrpg/api/note"),
    "nap_cn": ("绝区零", "event/game_record_zzz/api/zzz/note"),
}
# Only web V2 credentials. No SToken/GameToken acquisition or token exchange.
COOKIE_NAMES = {"ltoken_v2", "ltuid_v2", "ltmid_v2", "cookie_token_v2", "account_id_v2", "account_mid_v2"}
HINTS = {
    -100: "网页登录态失效或缺少该接口接受的 Cookie，请重新登录。",
    -10001: "官方接口认为请求格式不正确（不等于登录失败）；请检查网页路由和请求头。",
    10001: "登录态无效，请重新登录。",
    10102: "可能尚未开启实时便笺。请在米游社战绩页手动开启，然后重试；Demo 不自动改隐私设置。",
    1034: "米游社要求人机验证。请在官方网页/App 完成验证后重试；Demo 不自动过码。",
    10035: "接口要求额外验证。请在官方网页/App 处理后重试；不代表单纯重试就能解除。",
    -3501: "二维码已过期，请重新生成。",
    -3505: "扫码已取消，请重新生成。",
}


class DemoError(Exception):
    def __init__(self, message, *, code=None, hint=None, trace=None):
        self.message, self.code, self.trace = message, code, trace
        self.hint = hint or HINTS.get(code, "")
        super().__init__(message)

    def payload(self):
        return {"message": self.message, "retcode": self.code, "hint": self.hint,
                "verification_required": self.code in (1034, 10035)}


class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward credentials to a redirected host.


def web_ds(timestamp=None, nonce=None):
    """Official H5 DS1, explicitly authorized ONLY for this standalone demo.

    Official Genshin / Star Rail H5 uses app_version=2.3.0 and this web salt.
    No device fingerprint, DS2 or changes to the Android application.
    """
    timestamp = int(time.time()) if timestamp is None else timestamp
    nonce = nonce or "".join(secrets.choice("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") for _ in range(6))
    text = f"salt=h8w582wxwgqvahcdkpvdhbh2w9casgfl&t={timestamp}&r={nonce}"
    digest = hashlib.md5(text.encode()).hexdigest()
    return f"{timestamp},{nonce},{digest}"


def upstream(url, *, method="GET", body=None, cookie=None, device_id=None, headers_extra=None):
    """Return JSON + allowlisted Set-Cookie values. Never log credentials/responses."""
    headers = {
        "Accept": "application/json",
        "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36",
        "Origin": "https://user.mihoyo.com" if url.startswith(PASSPORT) else "https://act.mihoyo.com",
        "Referer": "https://user.mihoyo.com/" if url.startswith(PASSPORT) else "https://act.mihoyo.com/",
        "x-rpc-client_type": "4" if url.startswith(PASSPORT) else "5",
    }
    if url.startswith(RECORD + "game_record/"):
        headers["DS"] = web_ds()
        headers["x-rpc-app_version"] = "2.3.0"
        headers["Referer"] = "https://webstatic.mihoyo.com/"
        headers["Origin"] = "https://webstatic.mihoyo.com"
    if device_id:
        headers["x-rpc-device_id"] = device_id
    if url.startswith(PASSPORT):
        headers["x-rpc-app_id"] = "bll8iq97cem8"
    if cookie:
        headers["Cookie"] = cookie
    if headers_extra:
        headers.update(headers_extra)
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    req = request.Request(url, data=data, headers=headers, method=method)
    try:
        with request.build_opener(NoRedirect()).open(req, timeout=20) as response:
            result = json.loads(response.read(2_000_001))
            trace = response.headers.get("x-trace-id")
            cookies = {}
            for line in response.headers.get_all("Set-Cookie", []):
                jar = SimpleCookie()
                jar.load(line)
                cookies.update({k: v.value for k, v in jar.items() if k in COOKIE_NAMES})
    except error.HTTPError as exc:
        raise DemoError(f"官方接口 HTTP {exc.code}", hint="请求被拒绝或接口已变更；未转发重定向。") from None
    except (error.URLError, TimeoutError, OSError):
        raise DemoError("连接官方接口失败", hint="检查网络后重试；请求超时为 20 秒。") from None
    except (ValueError, CookieError):
        raise DemoError("官方接口返回了无法解析的数据") from None
    if not isinstance(result, dict) or "retcode" not in result:
        raise DemoError("官方接口响应格式不符合预期")
    if result["retcode"] != 0:
        # Do not echo upstream messages that may contain account or request values.
        raise DemoError("官方接口返回业务错误", code=result["retcode"], trace=trace)
    return result.get("data") or {}, cookies


def parse_cookie(text):
    if not isinstance(text, str) or len(text) > 32768 or "\r" in text or "\n" in text:
        raise DemoError("Cookie 必须是单行文本，最长 32 KiB。")
    jar = SimpleCookie()
    try:
        jar.load(text)
    except CookieError:
        raise DemoError("无法解析 Cookie。") from None
    values = {k: v.value for k, v in jar.items() if k in COOKIE_NAMES and v.value}
    if not values.get("ltoken_v2") and not values.get("cookie_token_v2"):
        raise DemoError("未找到 ltoken_v2 或 cookie_token_v2；仅支持网页 V2 登录态。")
    return values


def duration(value):
    try:
        return max(0, int(value))
    except (TypeError, ValueError):
        return None


def summarize(game, data, now):
    """Do not conflate completed work, daily activity and collected rewards."""
    if game == "hk4e_cn":
        daily = data.get("daily_task") or {}
        return {
            "stamina": {"current": data.get("current_resin"), "max": data.get("max_resin"),
                        "recovery_seconds": duration(data.get("resin_recovery_time"))},
            "daily": {"label": "每日委托 / 历练点", "current": daily.get("finished_num", data.get("finished_task_num")),
                      "max": daily.get("total_num", data.get("total_task_num")),
                      "extra_reward_received": daily.get("is_extra_task_reward_received", data.get("is_extra_task_reward_received")),
                      "task_rewards": daily.get("task_rewards"), "attendance_rewards": daily.get("attendance_rewards")},
            "fetched_at": now,
        }
    if game == "hkrpg_cn":
        return {
            "stamina": {"current": data.get("current_stamina"), "max": data.get("max_stamina"),
                        "recovery_seconds": duration(data.get("stamina_recover_time"))},
            "daily": {"label": "每日实训活跃度", "current": data.get("current_train_score"), "max": data.get("max_train_score")},
            "reserved_stamina": data.get("current_reserve_stamina"), "fetched_at": now,
        }
    energy = data.get("energy") or {}
    progress = energy.get("progress") or {}
    vitality = data.get("vitality") or {}
    return {
        "stamina": {"current": progress.get("current"), "max": progress.get("max"),
                    "recovery_seconds": duration(energy.get("restore"))},
        "daily": {"label": "每日活跃度", "current": vitality.get("current"), "max": vitality.get("max")},
        "fetched_at": now,
    }


class Demo:
    def __init__(self, transport=upstream):
        self.transport = transport
        self.lock = threading.RLock()
        self.device_id = str(uuid.uuid4())
        self.csrf = secrets.token_urlsafe(32)
        self.cookies = {}
        self.pending = None
        self.qr_svg = None
        self.roles = []
        self.cache = {}
        self.last_poll = 0
        self.risks = {}
        self.captcha = None
        self.challenges = {}
        self.diagnostics = []

    def record_operation(self, stage, key, code=0):
        self.diagnostics.append({"stage": stage, "game": key.split(":", 1)[0], "retcode": code,
                                 "at": int(time.time())})
        self.diagnostics = self.diagnostics[-12:]

    def clear_verification(self):
        self.risks.clear()
        self.challenges.clear()
        self.captcha = None
        self.diagnostics.clear()

    def cookie_header(self):
        jar = SimpleCookie()
        for name, value in self.cookies.items():
            jar[name] = value
        return "; ".join(m.OutputString() for m in jar.values())

    def state(self):
        return {"authenticated": bool(self.cookies), "cookie_names": sorted(self.cookies),
                "roles": self.roles, "csrf": self.csrf,
                "credential_storage": "memory-only", "region": "CN", "diagnostics": self.diagnostics}

    def create_qr(self):
        data, _ = self.transport(PASSPORT + "createQRLogin", method="POST", body={}, device_id=self.device_id)
        url, ticket = data.get("url"), data.get("ticket")
        parsed = parse.urlsplit(url or "")
        if parsed.scheme != "https" or parsed.hostname != "user.mihoyo.com" or not ticket:
            raise DemoError("官方二维码响应格式不符合预期。")
        image = qrcode.make(url, image_factory=qrcode.image.svg.SvgPathImage, box_size=6, border=4)
        buffer = io.BytesIO()
        image.save(buffer)
        self.qr_svg = buffer.getvalue()
        self.pending = ticket
        self.last_poll = 0
        return {"status": "Created", "login_url": url}

    def poll_qr(self):
        if not self.pending:
            raise DemoError("请先生成二维码。")
        if time.monotonic() - self.last_poll < 2:
            raise DemoError("扫码轮询太快，请稍后重试。")
        self.last_poll = time.monotonic()
        try:
            data, cookies = self.transport(PASSPORT + "queryQRLoginStatus", method="POST",
                                           body={"ticket": self.pending}, device_id=self.device_id)
        except DemoError as exc:
            if exc.code in (-3501, -3505):
                self.pending = None
                self.qr_svg = None
            raise
        status = data.get("status")
        if status == "Confirmed":
            self.pending = None
            self.qr_svg = None
            self.cookies = {k: v for k, v in cookies.items() if k in COOKIE_NAMES and v}
            self.roles, self.cache = [], {}
            self.clear_verification()
            if not self.cookies.get("ltoken_v2") and not self.cookies.get("cookie_token_v2"):
                self.cookies = {}
                raise DemoError("扫码已确认，但未返回网页 V2 Cookie。", hint="不尝试获取 SToken；可从官方网页手动导入 Cookie。")
        elif status not in ("Created", "Scanned"):
            raise DemoError("未知二维码状态；请重新生成。")
        return {"status": status}

    def import_cookie(self, text):
        values = parse_cookie(text)
        self.cookies, self.roles, self.cache = values, [], {}
        self.pending, self.qr_svg = None, None
        self.clear_verification()
        return {"cookie_names": sorted(self.cookies)}

    def get_roles(self):
        if not self.cookies:
            raise DemoError("请先扫码登录或导入网页 Cookie。")
        url = ROLES if self.cookies.get("ltoken_v2") else COOKIE_ROLES
        # Query each game explicitly; keep server returned by official binding API.
        roles = []
        for biz, (label, _) in GAMES.items():
            data, _ = self.transport(url + "?" + parse.urlencode({"game_biz": biz}),
                                     cookie=self.cookie_header(), device_id=self.device_id)
            for role in data.get("list", []):
                if role.get("game_biz") != biz:
                    continue
                roles.append({"key": f"{biz}:{role['region']}:{role['game_uid']}",
                              "game_biz": biz, "game": label, "uid": str(role["game_uid"]),
                              "server": role["region"], "nickname": role.get("nickname", ""),
                              "region_name": role.get("region_name", ""), "level": role.get("level")})
        self.roles = roles
        return {"roles": roles}

    def notes(self, key):
        role = next((role for role in self.roles if role["key"] == key), None)
        if role is None:
            raise DemoError("请先加载并选择当前登录账号绑定的角色。")
        previous = self.cache.get(key)
        if previous and time.monotonic() - previous[0] < 60:
            return {**previous[1], "cached": True}
        query = parse.urlencode({"role_id": role["uid"], "server": role["server"]})
        url = RECORD + GAMES[role["game_biz"]][1] + "?" + query
        extra = {}
        challenge = self.challenges.get(key)
        if challenge and time.monotonic() - challenge[0] < 300:
            extra["x-rpc-challenge"] = challenge[1]
        try:
            data, _ = self.transport(url, cookie=self.cookie_header(), device_id=self.device_id, headers_extra=extra)
        except DemoError as exc:
            self.record_operation("note", key, exc.code)
            if exc.code in (1034, 10035):
                self.risks[key] = {"path": url.split("?", 1)[0], "trace": exc.trace}
                self.challenges.pop(key, None)
                exc.hint = "请点击下方「手动完成人机验证」，验证当前查询请求；不自动过码。"
            raise
        expected = {"hk4e_cn": "current_resin", "hkrpg_cn": "current_stamina", "nap_cn": "energy"}[role["game_biz"]]
        if expected not in data:
            raise DemoError("官方未返回预期的便笺字段，不能认定查询成功。")
        self.risks.pop(key, None)
        self.record_operation("note", key)
        result = {"role": role, "summary": summarize(role["game_biz"], data, int(time.time())),
                  "raw": data, "cached": False}
        self.cache[key] = (time.monotonic(), result)
        return result

    def verification_headers(self, key):
        role = next((r for r in self.roles if r["key"] == key), None)
        risk = self.risks.get(key)
        if not role or not risk:
            raise DemoError("请先查询角色便笺，只有官方要求验证时才能创建验证码。")
        headers = {"x-rpc-challenge_game": {"hk4e_cn": "2", "hkrpg_cn": "6", "nap_cn": "8"}[role["game_biz"]],
                   "x-rpc-challenge_path": risk["path"]}
        if risk.get("trace"):
            headers["x-rpc-challenge_trace"] = risk["trace"]
        return headers

    def create_verification(self, key):
        headers = self.verification_headers(key)
        self.captcha = None
        zzz = key.startswith("nap_cn:")
        # ZZZ uses toolcomsrv (with app_key); its create response has a nested data object.
        url = ("https://api-takumi.mihoyo.com/event/toolcomsrv/risk/createGeetest?app_key=game_record_zzz&is_high=true"
               if zzz else RECORD + "game_record/card/wapi/createVerification?is_high=true")
        try:
            data, _ = self.transport(url, cookie=self.cookie_header(), device_id=self.device_id, headers_extra=headers)
        except DemoError as exc:
            self.record_operation("captcha.create", key, exc.code)
            raise
        self.record_operation("captcha.create", key)
        if zzz:
            data = data.get("data") or {}
        if not isinstance(data.get("gt"), str) or not isinstance(data.get("challenge"), str):
            raise DemoError("官方没有返回可交互的 Geetest V3 验证参数；暂不能继续。")
        captcha_id = secrets.token_urlsafe(24)
        self.captcha = {"id": captcha_id, "key": key, "created": time.monotonic(),
                        "challenge": data["challenge"], "headers": headers, "zzz": zzz}
        return {"id": captcha_id, "gt": data["gt"], "challenge": data["challenge"],
                "new_captcha": bool(data.get("new_captcha", True)), "success": bool(data.get("success", True))}

    def verify(self, captcha_id, validation):
        pending = self.captcha
        if not pending or pending["id"] != captcha_id or time.monotonic() - pending["created"] > 300:
            raise DemoError("本地验证码会话已失效，请重新创建。")
        names = ("geetest_challenge", "geetest_validate", "geetest_seccode")
        if not isinstance(validation, dict) or any(not isinstance(validation.get(k), str)
                or not 0 < len(validation[k]) <= 4096 or "\r" in validation[k] or "\n" in validation[k] for k in names):
            raise DemoError("验证码组件返回格式不正确。")
        if not validation["geetest_challenge"].startswith(pending["challenge"]):
            raise DemoError("验证码 challenge 不匹配。")
        url = ("https://api-takumi.mihoyo.com/event/toolcomsrv/risk/verifyGeetest" if pending["zzz"]
               else RECORD + "game_record/card/wapi/verifyVerification")
        body = {k: validation[k] for k in names}
        if pending["zzz"]:
            body["app_key"] = "game_record_zzz"
        try:
            data, cookies = self.transport(url, method="POST", body=body, cookie=self.cookie_header(),
                                           device_id=self.device_id, headers_extra=pending["headers"])
        except DemoError as exc:
            self.record_operation("captcha.submit", pending["key"], exc.code)
            raise
        self.record_operation("captcha.submit", pending["key"])
        challenge = data.get("challenge")
        if not isinstance(challenge, str) or not challenge or len(challenge) > 4096 or "\r" in challenge or "\n" in challenge:
            raise DemoError("官方验证未返回可用的 challenge，不能认定验证成功。")
        self.cookies.update({k: v for k, v in cookies.items() if k in COOKIE_NAMES and v})
        self.challenges[pending["key"]] = (time.monotonic(), challenge)
        self.cache.pop(pending["key"], None)
        self.captcha = None
        return {"verified": True, "message": "官方验证码验证通过，请重新点击查询实时便笺。"}

    def logout(self):
        self.clear_verification()
        self.cookies.clear()
        self.roles.clear()
        self.cache.clear()
        self.pending, self.qr_svg = None, None
        return {"message": "已清除本 Demo 的内存登录态；这不等于撤销官方会话。"}


class Server(ThreadingHTTPServer):
    daemon_threads = True


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # Avoid cookies, QR tickets, query values and account data in access logs.

    def allowed_host(self):
        return self.headers.get("Host") == f"127.0.0.1:{self.server.server_port}"

    def respond(self, status, data, content_type="application/json; charset=utf-8"):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        if parse.urlsplit(self.path).path == "/captcha.html":
            vendors = "https://*.geetest.com https://*.geevisit.com https://*.geetest.cn"
            local = f"http://127.0.0.1:{self.server.server_port}"
            # The remote CAPTCHA SDK runs only inside an opaque-origin sandbox,
            # never in the page that can access our local API / CSRF token.
            policy = (f"default-src 'none'; script-src {local} {vendors} 'unsafe-inline' 'unsafe-eval'; "
                      f"style-src 'unsafe-inline' {vendors}; img-src {vendors} data: blob:; "
                      f"connect-src {vendors}; frame-src {vendors}; font-src {vendors} data:; "
                      f"frame-ancestors {local}; base-uri 'none'; form-action 'none'")
        else:
            policy = "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'; connect-src 'self'; frame-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        self.send_header("Content-Security-Policy", policy)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if not self.allowed_host():
            return self.respond(403, {"error": {"message": "仅允许本机 127.0.0.1 访问。"}})
        path = parse.urlsplit(self.path).path
        files = {"/": ("index.html", "text/html; charset=utf-8"),
                 "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                 "/style.css": ("style.css", "text/css; charset=utf-8"),
                 "/captcha.html": ("captcha.html", "text/html; charset=utf-8"),
                 "/captcha.js": ("captcha.js", "text/javascript; charset=utf-8")}
        if path in files:
            filename, mime = files[path]
            return self.respond(200, (ROOT / filename).read_bytes(), mime)
        with self.server.demo.lock:
            if path == "/api/state":
                return self.respond(200, self.server.demo.state())
            if path == "/api/qr.svg" and self.server.demo.qr_svg:
                return self.respond(200, self.server.demo.qr_svg, "image/svg+xml")
        return self.respond(404, {"error": {"message": "Not found"}})

    def do_POST(self):
        expected_origin = f"http://127.0.0.1:{self.server.server_port}"
        origin = self.headers.get("Origin")
        if not self.allowed_host() or (origin and origin != expected_origin):
            return self.respond(403, {"error": {"message": "拒绝非本机同源请求。"}})
        if self.headers.get("X-Demo-Token") != self.server.demo.csrf:
            return self.respond(403, {"error": {"message": "缺少本地会话校验。"}})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 65536 or self.headers.get_content_type() != "application/json":
                return self.respond(400, {"error": {"message": "仅接受最多 64 KiB 的 JSON。"}})
            body = json.loads(self.rfile.read(size))
            if not isinstance(body, dict):
                raise ValueError()
        except (ValueError, UnicodeError):
            return self.respond(400, {"error": {"message": "无效的 JSON 请求。"}})
        demo = self.server.demo
        try:
            with demo.lock:
                if self.path == "/api/qr/create":
                    result = demo.create_qr()
                elif self.path == "/api/qr/poll":
                    result = demo.poll_qr()
                elif self.path == "/api/cookie":
                    result = demo.import_cookie(body.get("cookie", ""))
                elif self.path == "/api/roles":
                    result = demo.get_roles()
                elif self.path == "/api/notes":
                    result = demo.notes(body.get("key"))
                elif self.path == "/api/verification/create":
                    result = demo.create_verification(body.get("key"))
                elif self.path == "/api/verification/submit":
                    result = demo.verify(body.get("id"), body.get("validation"))
                elif self.path == "/api/logout":
                    result = demo.logout()
                else:
                    return self.respond(404, {"error": {"message": "Not found"}})
            self.respond(200, result)
        except DemoError as exc:
            self.respond(400, {"error": exc.payload()})
        except Exception:
            self.respond(500, {"error": {"message": "本地处理失败；响应未输出敏感调试信息。"}})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    server = Server(("127.0.0.1", args.port), Handler)
    server.demo = Demo()
    print(f"米游社网页便笺 Demo: http://127.0.0.1:{server.server_port}", flush=True)
    print("仅国服；Cookie 只存内存。Ctrl+C 退出清除登录态。使用网页 DS1（本 Demo 已获例外授权），不生成设备指纹。", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.demo.logout()
        server.server_close()


if __name__ == "__main__":
    main()
