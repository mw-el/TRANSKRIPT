#!/usr/bin/env python3
"""
Minimal MCP stdio server for web research.

It intentionally uses only the Python standard library so it can run without
installing packages. The server exposes two tools:
  - web_search: search DuckDuckGo's HTML endpoint
  - fetch_url: fetch and extract readable text from an HTTP(S) page
"""

from __future__ import annotations

import html
import ipaddress
import json
import socket
import sys
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from typing import Any


SERVER_NAME = "transkript-web-research"
SERVER_VERSION = "0.1.0"
DEFAULT_PROTOCOL_VERSION = "2025-06-18"
USER_AGENT = (
    "Mozilla/5.0 (compatible; TranskriptMCP/0.1; "
    "+https://modelcontextprotocol.io)"
)


def send(message: dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def result(request_id: Any, payload: dict[str, Any]) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": payload}


def error(request_id: Any, code: int, message: str) -> dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}}


def tool_text(text: str, is_error: bool = False) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


TOOLS = [
    {
        "name": "web_search",
        "title": "Web Search",
        "description": "Search the public web and return concise result titles, URLs, and snippets.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query"},
                "max_results": {
                    "type": "integer",
                    "description": "Maximum number of results to return",
                    "minimum": 1,
                    "maximum": 10,
                    "default": 5,
                },
            },
            "required": ["query"],
        },
    },
    {
        "name": "fetch_url",
        "title": "Fetch URL",
        "description": "Fetch an HTTP(S) page and return extracted readable text.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "HTTP or HTTPS URL to fetch"},
                "max_chars": {
                    "type": "integer",
                    "description": "Maximum characters to return",
                    "minimum": 500,
                    "maximum": 20000,
                    "default": 6000,
                },
            },
            "required": ["url"],
        },
    },
]


class DuckDuckGoParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.results: list[dict[str, str]] = []
        self._current: dict[str, str] | None = None
        self._capture: str | None = None
        self._buf: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_dict = dict(attrs)
        classes = attrs_dict.get("class", "") or ""
        if tag == "a" and "result__a" in classes:
            self._current = {"title": "", "url": normalize_ddg_url(attrs_dict.get("href", "") or ""), "snippet": ""}
            self._capture = "title"
            self._buf = []
        elif self._current is not None and "result__snippet" in classes:
            self._capture = "snippet"
            self._buf = []

    def handle_data(self, data: str) -> None:
        if self._capture:
            self._buf.append(data)

    def handle_endtag(self, tag: str) -> None:
        if self._current is None or self._capture is None:
            return
        if self._capture == "title" and tag == "a":
            self._current["title"] = clean_text(" ".join(self._buf))
            if self._current["title"] and self._current["url"]:
                self.results.append(self._current)
            self._capture = None
            self._buf = []
        elif self._capture == "snippet" and tag in {"a", "div"}:
            self._current["snippet"] = clean_text(" ".join(self._buf))
            self._capture = None
            self._buf = []


class TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in {"script", "style", "noscript", "svg"}:
            self._skip_depth += 1
        if tag in {"p", "br", "div", "li", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag in {"script", "style", "noscript", "svg"} and self._skip_depth:
            self._skip_depth -= 1
        if tag in {"p", "li", "h1", "h2", "h3", "h4"}:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if self._skip_depth == 0:
            text = clean_text(data)
            if text:
                self.parts.append(text)

    def text(self) -> str:
        return clean_text(" ".join(self.parts))


def clean_text(value: str) -> str:
    return " ".join(html.unescape(value).split())


def normalize_ddg_url(url: str) -> str:
    if not url:
        return ""
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query)
    if "uddg" in query and query["uddg"]:
        return query["uddg"][0]
    return urllib.parse.urljoin("https://duckduckgo.com", url)


def get_int(args: dict[str, Any], key: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(args.get(key, default))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def request_url(url: str, timeout: int = 20) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read()


def web_search(args: dict[str, Any]) -> str:
    query = clean_text(str(args.get("query", "")))
    if not query:
        raise ValueError("query is required")
    max_results = get_int(args, "max_results", 5, 1, 10)
    url = "https://duckduckgo.com/html/?" + urllib.parse.urlencode({"q": query})
    body = request_url(url).decode("utf-8", errors="replace")
    parser = DuckDuckGoParser()
    parser.feed(body)
    seen: set[str] = set()
    rows: list[str] = []
    for item in parser.results:
        result_url = item["url"]
        if result_url in seen:
            continue
        seen.add(result_url)
        rows.append(
            f"{len(rows) + 1}. {item['title']}\n"
            f"   URL: {result_url}\n"
            f"   {item.get('snippet', '').strip()}"
        )
        if len(rows) >= max_results:
            break
    if not rows:
        return f"Keine Suchergebnisse für: {query}"
    return "\n\n".join(rows)


def fetch_url(args: dict[str, Any]) -> str:
    url = str(args.get("url", "")).strip()
    max_chars = get_int(args, "max_chars", 6000, 500, 20000)
    validate_public_http_url(url)
    body = request_url(url).decode("utf-8", errors="replace")
    parser = TextExtractor()
    parser.feed(body)
    text = parser.text()
    if len(text) > max_chars:
        text = text[:max_chars].rstrip() + "\n\n[gekürzt]"
    return f"URL: {url}\n\n{text}"


def validate_public_http_url(url: str) -> None:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise ValueError("Only http:// and https:// URLs are allowed")
    if not parsed.hostname:
        raise ValueError("URL must include a hostname")
    host = parsed.hostname
    try:
        addresses = socket.getaddrinfo(host, parsed.port or (443 if parsed.scheme == "https" else 80))
    except socket.gaierror as exc:
        raise ValueError(f"Cannot resolve host: {host}") from exc
    for family, _, _, _, sockaddr in addresses:
        if family not in {socket.AF_INET, socket.AF_INET6}:
            continue
        ip = ipaddress.ip_address(sockaddr[0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_reserved:
            raise ValueError("Refusing to fetch private, local, or reserved network addresses")


def handle_request(message: dict[str, Any]) -> dict[str, Any] | None:
    request_id = message.get("id")
    method = message.get("method")
    params = message.get("params") or {}

    if request_id is None:
        return None

    try:
        if method == "initialize":
            protocol_version = params.get("protocolVersion") or DEFAULT_PROTOCOL_VERSION
            return result(
                request_id,
                {
                    "protocolVersion": protocol_version,
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                },
            )
        if method == "ping":
            return result(request_id, {})
        if method == "tools/list":
            return result(request_id, {"tools": TOOLS})
        if method == "tools/call":
            name = params.get("name")
            args = params.get("arguments") or {}
            if not isinstance(args, dict):
                return result(request_id, tool_text("arguments must be an object", True))
            if name == "web_search":
                return result(request_id, tool_text(web_search(args)))
            if name == "fetch_url":
                return result(request_id, tool_text(fetch_url(args)))
            return error(request_id, -32602, f"Unknown tool: {name}")
        return error(request_id, -32601, f"Method not found: {method}")
    except Exception as exc:
        return result(request_id, tool_text(str(exc), True))


def main() -> None:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            send(error(None, -32700, f"Parse error: {exc}"))
            continue
        if isinstance(message, list):
            responses = [handle_request(item) for item in message if isinstance(item, dict)]
            responses = [item for item in responses if item is not None]
            if responses:
                send(responses)
            continue
        if not isinstance(message, dict):
            send(error(None, -32600, "Invalid Request"))
            continue
        response = handle_request(message)
        if response is not None:
            send(response)


if __name__ == "__main__":
    main()
