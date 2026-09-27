"""Render the small Markdown privacy notice as a standalone GitHub Pages page."""

from html import escape
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/privacy-policy-web.md"
TARGET = ROOT / "docs/privacy/index.html"


def inline(text: str) -> str:
    parts = re.split(r"(\[[^]]+\]\([^)]+\))", text)
    rendered = []
    for part in parts:
        match = re.fullmatch(r"\[([^]]+)\]\(([^)]+)\)", part)
        if match:
            rendered.append(f'<a href="{escape(match.group(2), quote=True)}">{escape(match.group(1))}</a>')
        else:
            rendered.append(escape(part))
    return "".join(rendered)


blocks = []
for block in SOURCE.read_text(encoding="utf-8").strip().split("\n\n"):
    if block.startswith("# "):
        blocks.append(f"<h1>{inline(block[2:])}</h1>")
    elif block.startswith("## "):
        blocks.append(f"<h2>{inline(block[3:])}</h2>")
    else:
        blocks.append(f"<p>{inline(block)}</p>")

TARGET.parent.mkdir(parents=True, exist_ok=True)
TARGET.write_text(
    """<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Datenschutzhinweise für robinrehbein.de und die Android-App Mini Networks.">
  <title>Datenschutz – Robin Rehbein &amp; Mini Networks</title>
  <style>
    :root { font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; line-height: 1.65; color: #20312f; background: #f6f6f1; }
    body { max-width: 48rem; padding: 2rem 1.25rem 5rem; margin: auto; }
    h1, h2 { line-height: 1.2; }
    h1 { font-size: clamp(2rem, 5vw, 3.5rem); letter-spacing: -.035em; margin: .2rem 0 1rem; }
    h2 { font-size: 1.28rem; margin-top: 2.7rem; border-top: 1px solid #bed0c7; padding-top: 1.2rem; }
    a { color: #126c62; text-underline-offset: .18em; }
    a:focus-visible { outline: 3px solid #126c62; outline-offset: 3px; }
    p { margin: .85rem 0 1.2rem; }
    @media (prefers-color-scheme: dark) { :root { color: #e8f0e8; background: #14211f; } h2 { border-color: #3a5650; } a { color: #87ded2; } }
  </style>
</head>
<body>
<main>
"""
    + "\n".join(blocks)
    + "\n</main>\n</body>\n</html>\n",
    encoding="utf-8",
)
