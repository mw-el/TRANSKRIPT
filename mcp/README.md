# Minimal Web Research MCP Server

Dieser Server spricht MCP ueber `stdio` und nutzt nur die Python-Standardbibliothek.

## Start

```bash
python3 mcp/web_research_server.py
```

## Tools

- `web_search`: sucht ueber DuckDuckGo HTML und liefert Titel, URL und kurze Snippets.
- `fetch_url`: ruft eine oeffentliche `http://`- oder `https://`-Seite ab und extrahiert lesbaren Text.

`fetch_url` blockiert private, lokale und reservierte Netzwerkadressen, damit der Server nicht als ungewollter Zugriff auf lokale Dienste benutzt wird.

## Beispiel-Konfiguration

```json
{
  "mcpServers": {
    "transkript-web-research": {
      "command": "python3",
      "args": [
        "/Users/erlkoenig/Documents/AA/_AA_TRANSKRIPT/mcp/web_research_server.py"
      ]
    }
  }
}
```

Der Server schreibt auf `stdout` ausschliesslich JSON-RPC-Nachrichten, wie es der MCP-stdio-Transport erwartet.
