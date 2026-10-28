"""A stand-in Dispatcharr for testing Zapparr without a real server.

Serves the Xtream Codes calls Zapparr makes, with made-up channels and a guide built around the
current time. Listens on this computer only; the Android emulator reaches it as 10.0.2.2.

    python tools/fake_dispatcharr.py            # then in Zapparr: 10.0.2.2, user zapparr, password zapparr
    python tools/fake_dispatcharr.py --port 9292 --channels 300
"""
import argparse
import json
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse
from xml.sax.saxutils import escape

USERNAME = PASSWORD = "zapparr"
GROUPS = ["News", "Sports", "Movies", "Locals", "Kids", "Music"]
SHOWS = {
    "News": ["Morning Report", "Headlines", "World Desk", "The Briefing", "Evening News", "Weather Watch"],
    "Sports": ["SportsCenter Live", "Game Day", "Pregame Show", "College Football", "Highlights", "Postgame"],
    "Movies": ["The Long Road", "Night Train", "Summer of '89", "Harbor Lights", "Double Feature"],
    "Locals": ["Local News at Noon", "Community Hour", "High School Sports", "Local News at Six"],
    "Kids": ["Cartoon Block", "Science Club", "Puppet Town", "Afternoon Adventures"],
    "Music": ["Top 40 Countdown", "Classic Videos", "Live Sessions", "Late Night Mix"],
}
HALF_HOUR = 30 * 60


def channels(count):
    return [{"num": n, "name": f"{GROUPS[(n - 1) % len(GROUPS)]} {n}", "stream_id": 1000 + n,
             "group": (n - 1) % len(GROUPS)} for n in range(1, count + 1)]


def schedule(channel, now, hours_back=2, hours_ahead=24):
    """Programs for [channel], lengths of 30, 60 or 90 minutes, from before [now] to after it."""
    group = GROUPS[channel["group"]]
    t = int(now) // HALF_HOUR * HALF_HOUR - hours_back * 3600
    i = channel["num"]
    while t < now + hours_ahead * 3600:
        length = HALF_HOUR * (1 + (i * 7 + t // HALF_HOUR) % 3)
        title = SHOWS[group][i % len(SHOWS[group])]
        yield t, t + length, title, f"{title} on {channel['name']}."
        t += length
        i += 1


def xmltv_time(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y%m%d%H%M%S +0000")


class Handler(BaseHTTPRequestHandler):
    lineup = []

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path in ("/player_api.php", "/xmltv.php"):
            if q.get("username") != USERNAME or q.get("password") != PASSWORD:
                return self.reply(401, "application/json", '{"user_info":{"auth":0}}')
            if url.path == "/xmltv.php":
                return self.reply(200, "application/xml", self.xmltv())
            return self.reply(200, "application/json", json.dumps(self.api(q.get("action"))))
        self.reply(404, "text/plain", "not found")

    def api(self, action):
        if action is None:
            return {"user_info": {"username": USERNAME, "auth": 1, "status": "Active"},
                    "server_info": {"timezone": "UTC", "timestamp_now": int(time.time())}}
        if action == "get_live_categories":
            return [{"category_id": str(i + 1), "category_name": g, "parent_id": 0} for i, g in enumerate(GROUPS)]
        if action == "get_live_streams":
            return [{"num": c["num"], "name": c["name"], "stream_type": "live", "stream_id": c["stream_id"],
                     "stream_icon": None, "epg_channel_id": str(c["num"]), "category_id": str(c["group"] + 1),
                     "tv_archive": 0, "tv_archive_duration": 0} for c in self.lineup]
        return []

    def xmltv(self):
        now = time.time()
        out = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fake_dispatcharr">']
        out += [f'<channel id="{c["num"]}"><display-name>{escape(c["name"])}</display-name></channel>'
                for c in self.lineup]
        for c in self.lineup:
            for start, stop, title, desc in schedule(c, now):
                out.append(f'<programme channel="{c["num"]}" start="{xmltv_time(start)}" stop="{xmltv_time(stop)}">'
                           f'<title>{escape(title)}</title><desc>{escape(desc)}</desc></programme>')
        return "\n".join(out + ["</tv>\n"])

    def reply(self, code, kind, body):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", f"{kind}; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9191)
    parser.add_argument("--channels", type=int, default=60)
    args = parser.parse_args()
    Handler.lineup = channels(args.channels)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Fake Dispatcharr on http://127.0.0.1:{args.port} (emulator: 10.0.2.2:{args.port}), "
          f"user {USERNAME}, password {PASSWORD}. Ctrl+C stops it.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
