# CyberStrike UI — JetBrains IDE plugin

The [CyberStrike](https://cyberstrike.io) AI pentesting agent, docked next to your code.
A native JetBrains UI around the CyberStrike TUI + HTTP API: the real agent in a
tool window, a colour-coded findings table, a native diff viewer with
Accept / Reject, and security-focused editor actions.

> Layout: **files | code | agent** — the agent lives in a docked panel, not in an
> editor tab, so you keep seeing the code it is attacking.

---

## Features

### CyberStrike panel (the TUI, docked)
- Real CyberStrike TUI running in a tool window (`Ctrl+\` / `Cmd+Esc`).
- `ESC` is forwarded to the TUI instead of moving focus to the editor.
- Drag & drop files onto the panel to insert `@path` references.
- Clickable `@path#L10-20` links in the output open the file in the editor.
- Unlimited scrollback by default (configurable).
- `Ctrl+Alt+K` sends the current selection (or file) as context.

### Findings tab
- Every vulnerability recorded in the session (`GET /session/{id}/vulnerability`).
- Severity colour coding (critical / high / medium / low / info), status
  (`new`, `approved`, `duplicate`, `open`, `fixed`, `ignored`), CWE, endpoint and
  file location.
- Filter by severity, hide closed findings, sort by severity.
- Double-click → open the affected file at the reported line.
- Right-click → *Verify with CyberStrike* (sends `@file#Lx-y` + the finding to the
  agent), *Open endpoint in browser*, *Copy finding as Markdown*, *Copy PoC*.

### Diffs
- Every file the agent changed, in the native multi-file diff viewer.
- `Accept` writes the agent's version, `Reject` restores the previous content.
- Opened automatically when a turn finishes (configurable).

### Status bar
- `◆ CyberStrike 2C 3H 5M` — live finding counts per severity.
- Spinner while a turn is running; click to open the panel.

### Editor actions
`Editor → CyberStrike` submenu: **Security Review (OWASP)**, **Find
Vulnerabilities**, **Write a Proof of Concept**, Explain, Fix, Add Tests,
Refactor, and a free-form prompt.

### Quick commands (Tools menu)
New session, Stop, Findings, Review Diffs, Copy output as Markdown,
Launch HackBrowser (`/hackbrowser`), Generate report (`/report`),
Attack surface (`/web`), Start CyberStrike server.

---

## Requirements

| | |
|---|---|
| IDE | IntelliJ Platform 2024.2+ (built and tested on **PyCharm 2026.2.3**, build 262) |
| Plugin | `org.jetbrains.plugins.terminal` (bundled in all JetBrains IDEs) |
| CyberStrike CLI | `cyberstrike` on `PATH` (npm: `@cyberstrike-io/cyberstrike`) |
| Server | `cyberstrike web` or `cyberstrike serve` (default `http://127.0.0.1:4096`) |

Loopback requests are trusted by the CyberStrike server, so **no password is
required for local use**. A password (`CYBERSTRIKE_SERVER_PASSWORD`) is only
needed for remote / tunneled servers.

---

## Build & install

### Fast path — compile against an installed IDE (no downloads)

```powershell
.\build.ps1                       # compile + package
.\build.ps1 -Install              # also copy into <IDE config>\plugins
```

`build.ps1` uses the IDE's own JDK (`<IDE>\jbr\bin\javac.exe`) and the IDE jars,
so the produced classes are always binary-compatible with the IDE you install
into. Options:

| flag | meaning |
|---|---|
| `-IdePath <dir>` | IDE installation (default: `%LOCALAPPDATA%\Programs\PyCharm`) |
| `-Install` | copy the built plugin into the IDE's `plugins` directory |
| `-NoClean` | incremental build |

Output:
```
build/plugin/CyberStrike-UI/lib/CyberStrike-UI-1.0.0.jar
build/distributions/CyberStrike-UI-1.0.0.zip
```

Install manually with **Settings → Plugins → ⚙ → Install Plugin from Disk…** and
pick the zip, then restart the IDE.

### Gradle (for CI / distribution)

```powershell
.\gradlew.bat buildPlugin        # build/distributions/CyberStrike-UI-1.0.0.zip
.\gradlew.bat runIde             # sandbox IDE
.\gradlew.bat verifyPlugin       # plugin verifier
```

Gradle downloads the IntelliJ Platform (2026.2) on first run.

---

## Usage

1. Start the agent backend once: `cyberstrike web` (serves the web UI + API on
   `http://127.0.0.1:4096`).
2. Open a project in the IDE — the plugin auto-connects and creates/attaches the
   session for that directory.
3. Press `Ctrl+\` (macOS `Cmd+Esc`) to open the CyberStrike panel and start
   hacking, e.g.
   ```
   @src/auth/login.py#L20-80 find auth bypasses in this handler
   ```
4. When a turn finishes the diff viewer opens; the **Findings** tab and the
   status bar show what was found.

The TUI is launched as `cyberstrike attach <url> --dir <project>` so the TUI and
the IDE talk to the **same** server instance. Disable *Run the TUI attached to the
server* in settings to run it standalone instead.

### Settings — `Settings → Tools → CyberStrike`

| Setting | Default | Notes |
|---|---|---|
| Server URL | `http://127.0.0.1:4096` | any reachable CyberStrike server |
| Password | env `CYBERSTRIKE_SERVER_PASSWORD` | only for non-loopback servers |
| `cyberstrike` binary | auto-detect | npm shim, Scoop, `~/.bun/bin`, `/usr/local/bin` |
| Connect when the project opens | on | |
| Run the TUI attached to the server | on | `cyberstrike attach` |
| Open the diff viewer when a turn finishes | on | |
| Start `cyberstrike serve` when unreachable | off | needs a password |
| Terminal scrollback | unlimited | applies to all IDE terminals |

---

## How it talks to CyberStrike

| Purpose | Endpoint |
|---|---|
| Health / version | `GET /global/health` |
| Sessions | `GET /session?limit=`, `POST /session`, `GET /session/{id}` |
| Turn state | `GET /session/status` (polled) + SSE `session.status` |
| Messages | `GET /session/{id}/message?limit=` |
| Diffs | `GET /session/{id}/diff?messageID=` |
| Findings | `GET /session/{id}/vulnerability` |
| Todos / requests / agents | `GET /session/{id}/todo`, `GET /session/{id}/request`, `GET /agent` |
| Abort | `POST /session/{id}/abort` |
| Prompt (queued) | `POST /session/{id}/prompt_async` |
| Events | `GET /event` (SSE) |

Notes:

- Responses are **plain JSON** (no `{ "data": ... }` envelope).
- Requests are scoped with the `x-cyberstrike-directory` header.
- `/diff` returns resolved `before` / `after` content, so the viewer needs no
  patch reconstruction (a unified `patch` is still honoured as a fallback).
- Auth is HTTP Basic `cyberstrike:<password>`; loopback requests bypass it.

---

## Project layout

```
src/main/java/io/cyberstrike/ide/jetbrains/
├── CyberStrikePlugin.java          plugin constants
├── api/                            REST client + SSE listener
├── model/Vulnerability.java        findings model
├── session/                        session info, file diffs, snapshot tracking
├── patch/UnifiedPatch.java         patch fallback helpers
├── diff/                           diff viewer + Accept/Reject handler
├── service/CyberStrikeService.java core: connection, TUI hosting, turns, findings
├── terminal/                       @path#Lx-y hyperlink filter
├── ui/                             tool window, findings table, status bar, settings
├── actions/                        editor + tools actions
└── util/                           server connection, reference builder
```

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Not connected` in the Findings tab | Start `cyberstrike web`, then run *Tools → CyberStrike: Start Server* or reopen the project |
| Panel is blank | The terminal plugin must be enabled (`Settings → Plugins → Terminal`) |
| TUI says `attach` failed | The server died or the port changed — restart the panel (`Tools → CyberStrike: New Session`) |
| Diffs empty after a turn | The turn changed no tracked files, or the project directory differs from the session's |
| Wrong session | The session for the project directory is auto-detected; use `/new` in the TUI to start fresh |

Logs: **Help → Show Log in Explorer** → `idea.log`, filter `cyberstrike`.

---

## License

Plugin code: MIT. CyberStrike itself is AGPL-3.0 — see
[CyberStrikeus/CyberStrike](https://github.com/CyberStrikeus/CyberStrike).
