"""A stand-in for vod-organizer, the user's movie and series server, for testing Zapparr's Library.

Answers like vod-organizer does: any login is accepted, the same TMDb genres are listed for movies
and series (some of them empty), category_id is ignored, movies and series carry no dates,
names read "Title (Country) (Year)", and episodes come shaped like an IPTV provider's. /movie/ and
/series/ redirect to a "provider" that answers 404: there is nothing to play. Made-up titles only.
Listens on this computer only; the Android emulator reaches it as 10.0.2.2.

    python tools/fake_vod_organizer.py          # then in Zapparr's Library: 10.0.2.2:8000, any login

Windows sometimes holds port 8000 itself. Then run it on another port and forward the emulator's
own port 8000 to it, and connect Zapparr to 127.0.0.1:

    python tools/fake_vod_organizer.py --port 8100
    adb reverse tcp:8000 tcp:8100
"""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from fake_dispatcharr import GROUP_COLORS, picture

# vod-organizer's categories: the TMDb genres it maps to, plus Uncategorized, sorted, numbered from 1.
GENRES = sorted({"Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
                 "History", "Horror", "Music", "Mystery", "Romance", "Sci-Fi", "TV Movie", "Thriller", "War", "Western",
                 "Action & Adventure", "Kids", "News", "Reality", "Sci-Fi & Fantasy", "Soap", "Talk", "War & Politics",
                 "Uncategorized"})
GENRE_IDS = {name: i for i, name in enumerate(GENRES, 1)}
MOVIE_GENRES = ["Action", "Comedy", "Drama", "Horror", "Sci-Fi", "Documentary", "Animation", "Uncategorized"]
SERIES_GENRES = ["Drama", "Comedy", "Crime", "Kids", "Sci-Fi & Fantasy", "Reality"]
FIRST = ["Last", "Silent", "Golden", "Broken", "Midnight", "Hidden", "Northern", "Final", "Crimson", "Lost", "Wild", "Electric"]
SECOND = ["Harbor", "Signal", "Frontier", "Promise", "Garden", "Engine", "Summer", "Witness", "Horizon", "Kingdom"]
PLACES = ["Street", "Files", "House", "Bay", "Company", "Station"]
COUNTRIES = [None, "US", None, "UK", None, None, "CA"]


def title(name, country, year):
    return " ".join([name] + ([f"({country})"] if country else []) + [f"({year})"])


# Stream ids out of order, so "Recently added" (highest id first) is not just the list order.
MOVIES = [{"id": 70000 + i * 7919 % 100000, "i": i,
           "name": title(f"The {FIRST[i % 12]} {SECOND[i * 7 % 10]}" + (" II" if i >= 60 else ""), COUNTRIES[i % 7], 1980 + i * 13 % 45),
           "genre": MOVIE_GENRES[i % len(MOVIE_GENRES)], "year": 1980 + i * 13 % 45,
           "rating": "0" if i % 9 == 0 else str(round(5 + i * 37 % 50 / 10, 1)), "ext": "mkv" if i % 4 == 0 else "mp4"}
          for i in range(120)]
SERIES = [{"id": 5000 + i * 131 % 1000, "i": i, "name": title(f"{SECOND[i % 10]} {PLACES[i % 6]}", COUNTRIES[i % 7], 1995 + i * 7 % 30),
           "genre": SERIES_GENRES[i % len(SERIES_GENRES)], "year": 1995 + i * 7 % 30, "rating": str(round(6 + i * 13 % 40 / 10, 1)),
           "seasons": {n: 6 + i % 3 * 2 for n in range(1, 3 + i % 2)}} for i in range(24)]


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        here = f"http://{self.headers.get('Host', '127.0.0.1')}"
        if url.path.startswith(("/movie/", "/series/", "/live/")):
            self.send_response(302)
            self.send_header("Location", f"{here}/provider{url.path}")
            self.end_headers()
            return
        sizes = {"/posters/": (120, 180), "/backdrops/": (320, 180), "/stills/": (160, 90)}
        for prefix, (width, height) in sizes.items():
            if url.path.startswith(prefix) and url.path.endswith(".png") and url.path[len(prefix):-4].isdigit():
                seed = int(url.path[len(prefix):-4])
                return self.reply(200, "image/png", picture(GROUP_COLORS[seed % len(GROUP_COLORS)], seed, width, height))
        if url.path != "/player_api.php":
            return self.reply(404, "text/plain", "not found")
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        self.reply(200, "application/json", json.dumps(self.api(q.get("action", ""), q, here)))

    def api(self, action, q, here):
        if not action:
            return {"user_info": {"username": q.get("username", ""), "password": q.get("password", ""), "auth": 1, "status": "Active"},
                    "server_info": {"url": "127.0.0.1", "port": "8000", "server_protocol": "http"}}
        if action in ("get_vod_categories", "get_series_categories"):
            return [{"category_id": str(cid), "category_name": name, "parent_id": 0} for name, cid in GENRE_IDS.items()]
        if action == "get_vod_streams":  # every movie, whatever category_id says
            return [self.movie(m, here) for m in MOVIES]
        if action == "get_vod_info":
            m = next((self.movie(m, here) for m in MOVIES if str(m["id"]) == q.get("vod_id")), {})
            return {"info": {"name": m.get("name", ""), "cover_big": m.get("cover", ""), "movie_image": m.get("cover", ""),
                             "plot": m.get("plot", ""), "cast": "", "director": "", "rating": m.get("rating", "0"),
                             "releasedate": m.get("year", ""), "youtube_trailer": "", "backdrop_path": m.get("backdrop_path", [])},
                    "movie_data": m}
        if action == "get_series":
            return [self.series(s, here) for s in SERIES]
        if action == "get_series_info":
            s = next((s for s in SERIES if str(s["id"]) == q.get("series_id")), None)
            return self.seasons(s, here) if s else {}
        return []

    @staticmethod
    def movie(m, here):
        poster = f"{here}/posters/{m['id']}.png"
        return {"num": m["i"] + 1, "name": m["name"], "stream_type": "movie", "stream_id": m["id"], "stream_icon": poster,
                "cover": poster, "backdrop_path": [f"{here}/backdrops/{m['id']}.png"],
                "plot": f"{m['name']}: a made-up {m['genre'].lower()} movie for testing Zapparr's Library.", "cast": "",
                "director": "", "rating": m["rating"], "year": str(m["year"]), "youtube_trailer": "",
                "category_id": str(GENRE_IDS[m["genre"]]), "container_extension": m["ext"]}

    @staticmethod
    def series(s, here):
        poster = f"{here}/posters/{s['id']}.png"
        return {"num": s["i"] + 1, "name": s["name"], "series_id": s["id"], "cover": poster, "stream_icon": poster,
                "backdrop_path": [f"{here}/backdrops/{s['id']}.png"], "plot": f"{s['name']}: a made-up series.", "cast": "",
                "director": "", "genre": s["genre"], "releaseDate": str(s["year"]), "rating": s["rating"],
                "youtube_trailer": "", "category_id": str(GENRE_IDS[s["genre"]])}

    @staticmethod
    def seasons(s, here):
        """Shaped like an IPTV provider's get_series_info, which vod-organizer passes through: string ids, "plot"."""
        episodes = {}
        for season, count in s["seasons"].items():
            episodes[str(season)] = [
                {"id": str(s["id"] * 100 + season * 20 + k), "episode_num": k, "title": f"Episode {k}", "container_extension": "mkv",
                 "info": {"movie_image": f"{here}/stills/{s['id'] * 100 + season * 20 + k}.png",
                          "plot": f"Season {season}, episode {k} of {s['name']}.", "duration_secs": 1500 + k * 60,
                          "duration": f"00:{25 + k:02d}:00"},
                 "season": season, "added": "", "direct_source": ""} for k in range(1, count + 1)]
        return {"seasons": [{"season_number": n, "name": f"Season {n}"} for n in s["seasons"]],
                "info": {"name": s["name"], "cover": f"{here}/posters/{s['id']}.png", "plot": f"{s['name']}: a made-up series.",
                         "genre": s["genre"], "backdrop_path": [f"{here}/backdrops/{s['id']}.png"], "rating": s["rating"]},
                "episodes": episodes}

    def reply(self, code, kind, body):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        print(f"{self.address_string()} {format % args}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Fake vod-organizer on http://127.0.0.1:{args.port} (emulator: 10.0.2.2:{args.port}), any login. "
          f"{len(MOVIES)} movies, {len(SERIES)} series. Ctrl+C stops it.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
