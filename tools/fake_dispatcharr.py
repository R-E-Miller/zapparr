"""A stand-in Dispatcharr for testing Zapparr without a real server.

Serves the Xtream Codes calls Zapparr makes, with made-up channels, logos and a guide built around
the current time, and a library of movies and series with posters. Every tenth channel has no logo
and channel 13 has no guide data, as happens on real servers. Video URLs answer 404: there is
nothing to play. Listens on this computer only; the Android emulator reaches it as 10.0.2.2.

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
DAY = 24 * 3600

MOVIE_GROUPS = ["Action", "Comedy", "Drama", "Family", "Documentary"]
SERIES_GROUPS = ["Drama Series", "Comedy Series", "Kids Series"]
FIRST = ["Last", "Silent", "Golden", "Broken", "Midnight", "Hidden", "Northern", "Final", "Crimson", "Lost", "Wild", "Electric"]
SECOND = ["Harbor", "Signal", "Frontier", "Promise", "Garden", "Engine", "Summer", "Witness", "Horizon", "Kingdom"]
PLACES = ["Street", "Files", "House", "Bay", "Company", "Station"]


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


def movies(count=120):
    """Made-up movies across MOVIE_GROUPS, added at scattered times over the last month."""
    now = int(time.time())
    return [{"id": 2000 + i, "name": f"The {FIRST[i % 12]} {SECOND[i * 7 % 10]}" + (" II" if i >= 60 else ""),
             "group": i % len(MOVIE_GROUPS), "added": now - (i * 37 % count) * DAY // 4, "year": 1980 + i * 13 % 45,
             "rating": round(5 + i * 37 % 50 / 10, 1), "ext": "mkv" if i % 4 == 0 else "mp4"} for i in range(count)]


def series(count=24):
    """Made-up series across SERIES_GROUPS, each with two or three seasons."""
    now = int(time.time())
    return [{"id": 500 + i, "name": f"{SECOND[i % 10]} {PLACES[i % 6]}", "group": i % len(SERIES_GROUPS),
             "updated": now - (i * 11 % count) * DAY, "year": 1995 + i * 7 % 30, "rating": round(6 + i * 13 % 40 / 10, 1),
             "seasons": {n: [30000 + i * 100 + n * 20 + e for e in range(1, 7 + i % 3 * 2)] for n in range(1, 3 + i % 2)}}
            for i in range(count)]


def logo_png(channel, width=96, height=56):
    """A logo-sized PNG: the group's color, with a white bar whose length follows the channel number."""
    return picture(GROUP_COLORS[channel["group"]], channel["num"], width, height)


def picture(rgb, seed, width, height):
    """A PNG in [rgb] with a white bar whose length follows [seed]: logos, posters, backdrops."""
    color = bytes(rgb)
    bar = 16 + seed * 7 % (width - 32)
    rows = []
    for y in range(height):
        on_bar = height * 2 // 5 <= y < height * 3 // 5
        rows.append(b"\x00" + b"".join(b"\xff\xff\xff" if on_bar and 16 <= x < 16 + bar else color for x in range(width)))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b"")


def xmltv_time(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%Y%m%d%H%M%S +0000")


class Handler(BaseHTTPRequestHandler):
    lineup = []
    films = movies()
    shows = series()

    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        if url.path in ("/player_api.php", "/xmltv.php"):
            if q.get("username") != USERNAME or q.get("password") != PASSWORD:
                return self.reply(401, "application/json", '{"user_info":{"auth":0}}')
            if url.path == "/xmltv.php":
                return self.reply(200, "application/xml", self.xmltv())
            reply = self.vod(q.get("action"), q)
            if reply is None:
                reply = self.api(q.get("action"))
            return self.reply(200, "application/json", json.dumps(reply))
        if url.path.startswith("/logos/") and url.path.endswith(".png"):
            num = url.path[len("/logos/"):-len(".png")]
            channel = next((c for c in self.lineup if str(c["num"]) == num), None)
            if channel:
                return self.reply(200, "image/png", logo_png(channel))
        sizes = {"/posters/": (120, 180), "/backdrops/": (320, 180), "/stills/": (160, 90)}
        for prefix, (width, height) in sizes.items():
            if url.path.startswith(prefix) and url.path.endswith(".png") and url.path[len(prefix):-4].isdigit():
                seed = int(url.path[len(prefix):-4])
                return self.reply(200, "image/png", picture(GROUP_COLORS[seed % len(GROUP_COLORS)], seed, width, height))
        self.reply(404, "text/plain", "not found")

    def vod(self, action, q):
        """The movie and series calls, shaped like Dispatcharr's (apps/output/views.py)."""
        group_id = q.get("category_id")
        if action == "get_vod_categories":
            return [{"category_id": str(100 + i), "category_name": g, "parent_id": 0} for i, g in enumerate(MOVIE_GROUPS)]
        if action == "get_series_categories":
            return [{"category_id": str(200 + i), "category_name": g, "parent_id": 0} for i, g in enumerate(SERIES_GROUPS)]
        if action == "get_vod_streams":
            return [{"num": n, "name": m["name"], "stream_type": "movie", "stream_id": m["id"],
                     "stream_icon": f"/posters/{m['id']}.png", "rating": str(m["rating"]),
                     "rating_5based": round(m["rating"] / 2, 2), "added": str(m["added"]), "is_adult": 0,
                     "tmdb_id": "", "imdb_id": "", "trailer": "", "plot": f"{m['name']}, a made-up movie.",
                     "genre": MOVIE_GROUPS[m["group"]], "year": m["year"], "director": "", "cast": "", "release_date": "",
                     "category_id": str(100 + m["group"]), "category_ids": [100 + m["group"]],
                     "container_extension": m["ext"], "custom_sid": None, "direct_source": ""}
                    for n, m in enumerate((m for m in self.films if not group_id or str(100 + m["group"]) == group_id), 1)]
        if action == "get_vod_info":
            m = next((m for m in self.films if str(m["id"]) == q.get("vod_id")), None)
            if not m:
                return {"info": {}, "movie_data": {}}
            return {"info": {"name": m["name"], "o_name": m["name"], "cover_big": f"/posters/{m['id']}.png",
                             "movie_image": f"/posters/{m['id']}.png", "description": f"{m['name']}, a made-up movie.",
                             "plot": f"{m['name']}: a made-up {MOVIE_GROUPS[m['group']].lower()} movie for testing Zapparr's Library.",
                             "year": m["year"], "release_date": "", "genre": MOVIE_GROUPS[m["group"]], "director": "A. Director",
                             "actors": "First Actor, Second Actor", "cast": "First Actor, Second Actor", "country": "",
                             "rating": m["rating"], "imdb_id": "", "tmdb_id": "", "youtube_trailer": "",
                             "backdrop_path": [f"/backdrops/{m['id']}.png"], "bitrate": 0, "video": {}, "audio": {}},
                    "movie_data": {"stream_id": m["id"], "name": m["name"], "added": str(m["added"]),
                                   "category_id": str(100 + m["group"]), "category_ids": [100 + m["group"]],
                                   "container_extension": m["ext"], "custom_sid": None, "direct_source": ""}}
        if action == "get_series":
            return [{"num": n, "name": t["name"], "series_id": t["id"], "cover": f"/posters/{t['id']}.png",
                     "plot": f"{t['name']}, a made-up series.", "cast": "", "director": "", "genre": SERIES_GROUPS[t["group"]],
                     "release_date": str(t["year"]), "releaseDate": str(t["year"]), "last_modified": str(t["updated"]),
                     "rating": str(t["rating"]), "rating_5based": str(round(t["rating"] / 2, 2)), "backdrop_path": [],
                     "youtube_trailer": "", "episode_run_time": "", "category_id": str(200 + t["group"]),
                     "category_ids": [200 + t["group"]], "tmdb_id": "", "imdb_id": ""}
                    for n, t in enumerate((t for t in self.shows if not group_id or str(200 + t["group"]) == group_id), 1)]
        if action == "get_series_info":
            t = next((t for t in self.shows if str(t["id"]) == q.get("series_id")), None)
            if not t:
                return {"seasons": [], "info": {}, "episodes": {}}
            return {"seasons": [{"season_number": n, "name": f"Season {n}"} for n in t["seasons"]],
                    "info": {"name": t["name"], "cover": f"/posters/{t['id']}.png",
                             "plot": f"{t['name']}: a made-up series for testing Zapparr's Library.",
                             "genre": SERIES_GROUPS[t["group"]], "cast": "Lead Actor", "director": "",
                             "release_date": str(t["year"]), "rating": str(t["rating"]),
                             "backdrop_path": [f"/backdrops/{t['id']}.png"]},
                    "episodes": {str(n): [{"id": e, "season": n, "episode_num": k, "title": f"Episode {k}",
                                           "container_extension": "mkv", "added": str(t["updated"]), "custom_sid": None,
                                           "direct_source": "",
                                           "info": {"id": e, "name": f"Episode {k}", "overview": f"Season {n}, episode {k} of {t['name']}.",
                                                    "movie_image": f"/stills/{e}.png", "rating": 0.0,
                                                    "duration_secs": 1500 + k * 60, "duration": f"00:{25 + k:02d}:00"}}
                                          for k, e in enumerate(eps, 1)] for n, eps in t["seasons"].items()}}
        return None

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
