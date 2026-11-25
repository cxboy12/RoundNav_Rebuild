"""Loopback proxy for testing RoundNav with a Vietmap Services key."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from select import select
from socket import create_connection
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import urlopen


CONFIG = Path(__file__).resolve().parents[1] / "Android" / "local.properties"


def config_value(name):
    for line in CONFIG.read_text(encoding="utf-8-sig").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name:
            return value.strip()
    return ""


def coordinate_pair(value):
    latitude, longitude = (float(part) for part in value.split(","))
    if not (-90 <= latitude <= 90 and -180 <= longitude <= 180):
        raise ValueError("Invalid waypoint")
    return f"{latitude},{longitude}"


class VietmapProxy(BaseHTTPRequestHandler):
    def log_message(self, _format, *_args):
        # A request path can contain the rider's location.
        pass

    def send_json(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        request = urlsplit(self.path)
        params = parse_qs(request.query)
        if request.path != "/route":
            self.send_json(404, b'{}')
            return
        try:
            start, destination = map(coordinate_pair, params.get("point", []))
        except (ValueError, TypeError):
            self.send_json(400, b'{"error":"Two valid waypoints required"}')
            return

        query = urlencode([
            ("point", start), ("point", destination), ("vehicle", "motorcycle"),
            ("points_encoded", "true"), ("annotations", "congestion,congestion_distance"),
            ("apikey", self.server.services_key),
        ])
        try:
            with urlopen("https://maps.vietmap.vn/api/route/v4?" + query, timeout=20) as response:
                body = response.read(4_000_001)
        except HTTPError as error:
            print(f"Vietmap route HTTP {error.code}", flush=True)
            self.send_json(error.code, b'{"error":"Vietmap route request failed"}')
            return
        except (URLError, TimeoutError):
            self.send_json(502, b'{"error":"Vietmap connection failed"}')
            return
        if len(body) > 4_000_000:
            self.send_json(502, b'{"error":"Vietmap route response too large"}')
            return
        self.send_json(200, body)

    def do_CONNECT(self):
        if self.path != "maps.vietmap.vn:443":
            self.send_json(403, b'{}')
            return
        try:
            upstream = create_connection(("maps.vietmap.vn", 443), timeout=12)
        except OSError:
            self.send_json(502, b'{}')
            return
        with upstream:
            self.send_response(200, "Connection Established")
            self.end_headers()
            peers = {self.connection: upstream, upstream: self.connection}
            try:
                while readable := select(list(peers), [], [], 30)[0]:
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        peers[source].sendall(data)
            except OSError:
                return


def main():
    try:
        key = config_value("VIETMAP_SERVICES_KEY")
    except FileNotFoundError:
        key = ""
    if not key:
        raise SystemExit("Set VIETMAP_SERVICES_KEY in Android/local.properties")
    with ThreadingHTTPServer(("127.0.0.1", 8765), VietmapProxy) as server:
        server.services_key = key
        print("Vietmap test proxy listening on 127.0.0.1:8765", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
