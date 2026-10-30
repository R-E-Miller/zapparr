"""A stand-in Dispatcharr for testing Zapparr without a real server.

Serves the Xtream Codes calls Zapparr makes, with made-up channels, logos and a guide built around
the current time. Every tenth channel has no logo and channel 13 has no guide data, as happens on
real servers. Listens on this computer only; the Android emulator reaches it as 10.0.2.2.

    python tools/fake_dispatcharr.py            # then in Zapparr: 10.0.2.2, user zapparr, password zapparr
    python tools/fake_dispatcharr.py --port 9292 --channels 300
"""
import argparse
import json
import struct
import time
import zlib
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
GROUP_COLORS = [(200, 60, 60), (40, 140, 70), (120, 70, 170), (30, 110, 190), (230, 150, 30), (200, 60, 150)]
NO_GUIDE = 13
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


def logo_png(channel, width=96, height=56):
    """A logo-sized PNG: the group's color, with a white bar whose length follows the channel number."""
    return picture(GROUP_COLORS[channel["group"]], channel["num"], width, height)


def picture(rgb, seed, width, height, marked=True):
    """A PNG in [rgb] with a white bar whose length follows [seed]: logos here, posters in fake_vod_organizer.
    Without [marked], just the color: a bar blown up behind a details page looks like a broken box."""
    color = bytes(rgb)
    bar = 16 + seed * 7 % (width - 32)
    rows = []
    for y in range(height):
        on_bar = marked and height * 2 // 5 <= y < height * 3 // 5
        rows.append(b"\x00" + b"".join(b"\xff\xff\xff" if on_bar and 16 <= x < 16 + bar else color for x in range(width)))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b"")


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
        if url.path.startswith("/logos/") and url.path.endswith(".png"):
            num = url.path[len("/logos/"):-len(".png")]
            channel = next((c for c in self.lineup if str(c["num"]) == num), None)
            if channel:
                return self.reply(200, "image/png", logo_png(channel))
        self.reply(404, "text/plain", "not found")

    def api(self, action):
        if action is None:
            return {"user_info": {"username": USERNAME, "auth": 1, "status": "Active"},
                    "server_info": {"timezone": "UTC", "timestamp_now": int(time.time())}}
        if action == "get_live_categories":
            return [{"category_id": str(i + 1), "category_name": g, "parent_id": 0} for i, g in enumerate(GROUPS)]
        if action == "get_live_streams":
            return [{"num": c["num"], "name": c["name"], "stream_type": "live", "stream_id": c["stream_id"],
                     "stream_icon": None if c["num"] % 10 == 0 else f"/logos/{c['num']}.png", "epg_channel_id": str(c["num"]), "category_id": str(c["group"] + 1),
                     "tv_archive": 0, "tv_archive_duration": 0} for c in self.lineup]
        return []

    def xmltv(self):
        now = time.time()
        out = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fake_dispatcharr">']
        out += [f'<channel id="{c["num"]}"><display-name>{escape(c["name"])}</display-name></channel>'
                for c in self.lineup]
        for c in self.lineup:
            if c["num"] == NO_GUIDE:
                continue
            for start, stop, title, desc in schedule(c, now):
                out.append(f'<programme channel="{c["num"]}" start="{xmltv_time(start)}" stop="{xmltv_time(stop)}">'
                           f'<title>{escape(title)}</title><desc>{escape(desc)}</desc></programme>')
        return "\n".join(out + ["</tv>\n"])

    def reply(self, code, kind, body):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", kind if isinstance(body, bytes) else f"{kind}; charset=utf-8")
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
