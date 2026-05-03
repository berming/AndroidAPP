#!/usr/bin/env python3
"""
Minimal HTTP test server - run on a DIFFERENT port (8081) to test network connectivity
without JVM. If this works from browser but Ktor doesn't, the issue is Ktor-specific.

Usage: python3 test_server.py
Then access: http://<SERVER_IP>:8081/
"""
from http.server import HTTPServer, BaseHTTPRequestHandler
import datetime

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        print(f"[{datetime.datetime.now()}] GET {self.path} from {self.client_address}")
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain')
        self.end_headers()
        self.wfile.write(b'Python test server OK - network connectivity works!\n')

    def log_message(self, format, *args):
        pass  # suppress default logging, we have custom above

if __name__ == '__main__':
    server = HTTPServer(('0.0.0.0', 8081), Handler)
    print(f"Test server running on http://0.0.0.0:8081/")
    print("Access from browser: http://<YOUR_SERVER_IP>:8081/")
    print("Press Ctrl+C to stop")
    server.serve_forever()
