"""Self-contained mobile showcases. All values are explicitly illustrative."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / "android-native/app/src/debug/assets/ui-poc"
SOURCE = Path(__file__).with_name("widgets")


def widgets(base):
    base = base.rstrip("/")
    css = (SOURCE / "mobile.css").read_text()
    runtime = (SOURCE / "runtime.js").read_text()
    entries = []
    for name, title, libraries in [
        ("chart", "Chart and table", []),
        ("calculator", "Calculator", []),
        ("diagram", "Diagram", []),
        ("scene", "3D scene", ["three.js"]),
        ("map", "Map", ["leaflet.js"]),
    ]:
        extras = "".join(f'<script src="{base}/vendor/{lib}"></script>' for lib in libraries)
        if name == "map":
            extras += f'<link rel="stylesheet" href="{base}/vendor/leaflet.css">'
        html = f'''<!doctype html><html lang="en"><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none';script-src 'unsafe-inline' {base}/;style-src 'unsafe-inline' {base}/;img-src data: blob: {base}/ https://basemap.nationalmap.gov;connect-src 'none';font-src data:;base-uri 'none';form-action 'none'">
<style>{css}</style>{extras}</head><body>
{(SOURCE / (name + '.html')).read_text()}
<script>{runtime}\n{(SOURCE / (name + '.js')).read_text()}</script>
</body></html>'''
        entries.append({"title": title, "html": html})
    return entries


if __name__ == "__main__":
    (ROOT / "demo.json").write_text(json.dumps(widgets("https://appassets.androidplatform.net/assets/ui-poc")))
