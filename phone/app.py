import os
import sys
from flask import Flask, render_template, make_response

app = Flask(__name__, template_folder="templates", static_folder="static")

# Set the IP or hostname of your NUC via MAIN_SERVER_HOST env var.
MAIN_SERVER_HOST = os.environ.get("MAIN_SERVER_HOST", "")
MAIN_SERVER_PORT = os.environ.get("MAIN_SERVER_PORT", "5000")

def get_server_url():
    return f"http://{MAIN_SERVER_HOST}:{MAIN_SERVER_PORT}"

server_url = get_server_url()

@app.route("/")
def index():
    response = make_response(render_template("index.html", server_url=server_url))
    response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
    response.headers["Pragma"] = "no-cache"
    response.headers["Expires"] = "0"
    return response

if __name__ == "__main__":
    if len(sys.argv) > 1:
        MAIN_SERVER_HOST = sys.argv[1]
        server_url = get_server_url()
    print(f"[*] Targeting Main Assistant Server (NUC) at: {server_url}")
    print("[+] Running phone client host at http://0.0.0.0:8002")
    app.run(host="0.0.0.0", port=8002, debug=False)
