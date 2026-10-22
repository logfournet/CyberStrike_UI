package io.cyberstrike.ide.jetbrains.service;

import io.cyberstrike.ide.jetbrains.CyberStrikePlugin;
import io.cyberstrike.ide.jetbrains.api.CyberStrikeApiClient;
import io.cyberstrike.ide.jetbrains.api.CyberStrikeEventStream;
import io.cyberstrike.ide.jetbrains.diff.DiffViewerService;
import io.cyberstrike.ide.jetbrains.diff.ResolvedDiff;
import io.cyberstrike.ide.jetbrains.model.Vulnerability;
import io.cyberstrike.ide.jetbrains.patch.UnifiedPatch;
import io.cyberstrike.ide.jetbrains.session.FileDiff;
import io.cyberstrike.ide.jetbrains.session.SessionInfo;
import io.cyberstrike.ide.jetbrains.session.SessionManager;
import io.cyberstrike.ide.jetbrains.terminal.CyberStrikeTerminalLinkFilter;
import io.cyberstrike.ide.jetbrains.ui.CyberStrikeConnectDialog;
import io.cyberstrike.ide.jetbrains.ui.CyberStrikeSettingsConfigurable;
import io.cyberstrike.ide.jetbrains.util.ServerConnection;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalPanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jediterm.terminal.TerminalStarter;
import org.jetbrains.plugins.terminal.ShellTerminalWidget;
import org.jetbrains.plugins.terminal.TerminalView;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central project service: connects to the CyberStrike server, hosts the
 * CyberStrike TUI in the docked tool window, tracks the project session's turns,
 * and exposes findings / diffs to the UI.
 *
 * <p>The TUI is the primary interface (same experience as the CyberStrike
 * terminal). The REST connection is what lets the IDE add value on top: a
 * native diff viewer with Accept / Reject, the findings table, the status bar
 * indicator and turn-completion notifications.
 */
@Service(Service.Level.PROJECT)
public final class CyberStrikeService implements Disposable {

    private static final Logger LOG = Logger.getInstance(CyberStrikeService.class);

    private final Project project;

    private volatile ServerConnection conn;
    private volatile CyberStrikeApiClient api;
    private volatile CyberStrikeEventStream events;
    private volatile boolean connected;
    private volatile boolean connecting;

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> findingsListeners = new CopyOnWriteArrayList<>();
    private final List<ResolvedDiff> lastDiffs = new CopyOnWriteArrayList<>();
    private final List<Vulnerability> findings = new CopyOnWriteArrayList<>();

    /** Consecutive failed polls since the last successful one (drives auto-reconnect). */
    private volatile int consecutiveFailures;
    private static final int RECONNECT_AFTER_FAILURES = 3;

    /**
     * IDE advanced setting that caps the terminal scrollback history. JetBrains'
     * default is 5000 lines; CyberStrike turns can produce a lot of output, so the
     * plugin removes the cap by default. The key is shared with every terminal in
     * the IDE.
     */
    private static final String TERMINAL_MAX_LINES_KEY = "terminal.buffer.max.lines.count";

    private volatile String sessionId;
    private volatile String lastUserMsgId;
    private volatile boolean turnActive;
    private volatile String lastError = "";
    private volatile String serverVersion = "";

    // Terminal UI: the CyberStrike TUI lives in a docked ToolWindow panel (not an
    // editor tab), so the code editor stays visible alongside the agent.
    private volatile ShellTerminalWidget terminalWidget;
    private volatile JPanel terminalPanel;
    private volatile Content terminalContent;
    private volatile String cachedBinary;
    private final AtomicBoolean terminalCommandStarted = new AtomicBoolean(false);
    /** When the CyberStrike TUI process was launched (for boot-delay heuristics). */
    private volatile long terminalStartedAt;

    private final ScheduledExecutorService poller;

    public CyberStrikeService(Project project) {
        this.project = project;
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cyberstrike-service");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /** Resolve the configured server connection. */
    private ServerConnection resolveConnection() {
        String url = readSetting(CyberStrikeSettingsConfigurable.SERVER_URL_KEY, ServerConnection.DEFAULT_URL);
        String password = readSetting(CyberStrikeSettingsConfigurable.PASSWORD_KEY, "");
        if (password.isBlank()) password = ServerConnection.envPassword();
        return new ServerConnection(url, password, null);
    }

    /** Kick off connection discovery in the background (shows the dialog on failure). */
    public void connect() {
        if (connecting || connected || disposed.get()) return;
        connecting = true;
        notifyChanged();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                if (tryConnect(resolveConnection(), true)) return;
                // Ask the user for a manual address (dialog on EDT, then connect in background).
                ApplicationManager.getApplication().invokeLater(() ->
                        CyberStrikeConnectDialog.show(project, result -> {
                            if (result != null) {
                                AppExecutorUtil.getAppExecutorService()
                                        .execute(() -> applyConnection(result));
                            }
                        }));
            } finally {
                connecting = false;
                notifyChanged();
            }
        });
    }

    /**
     * Connect on project open without ever showing the manual dialog, so a project
     * without a running CyberStrike server stays quiet. The panel still opens (the
     * TUI runs standalone); findings / diffs light up once a server is reachable.
     */
    public void autoConnect() {
        if (connected || connecting || disposed.get()) return;
        connecting = true;
        notifyChanged();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                ServerConnection c = resolveConnection();
                if (tryConnect(c, false)) return;
                // Optional: spawn `cyberstrike serve` when a password is configured.
                if (readBoolean(CyberStrikeSettingsConfigurable.AUTO_START_SERVER_KEY, false)
                        && !c.password.isBlank()) {
                    startServer();
                    for (int i = 0; i < 10 && !disposed.get(); i++) {
                        sleep(700);
                        if (tryConnect(c, false)) return;
                    }
                }
            } finally {
                connecting = false;
                notifyChanged();
            }
        });
    }

    private boolean tryConnect(ServerConnection c, boolean notifyFailure) {
        if (c == null || disposed.get()) return false;
        CyberStrikeApiClient candidate = new CyberStrikeApiClient(c.url, c.password, project.getBasePath());
        String version = candidate.health();
        if (version == null) {
            if (notifyFailure) {
                notify("Cannot reach CyberStrike",
                        "No CyberStrike server responded at " + c.display()
                                + ". Start it with: cyberstrike web  (or cyberstrike serve)",
                        NotificationType.WARNING);
            }
            return false;
        }
        return applyConnection(new ServerConnection(c.url, c.password, version));
    }

    /** Apply a discovered or hand-entered connection. Returns true when connected. */
    public boolean applyConnection(ServerConnection c) {
        if (c == null || disposed.get()) return false;
        teardown();
        CyberStrikeApiClient candidate = new CyberStrikeApiClient(c.url, c.password, project.getBasePath());
        String version = c.version != null ? c.version : candidate.health();
        if (version == null) {
            connected = false;
            notifyChanged();
            return false;
        }
        this.conn = c;
        this.api = candidate;
        this.connected = true;
        this.serverVersion = version;
        this.consecutiveFailures = 0;
        this.lastError = "";
        lastUserMsgId = null;
        turnActive = false;
        discoverSession();
        startEventStream();
        startPolling();
        refreshFindingsAsync();
        notifyChanged();
        return true;
    }

    public void teardown() {
        CyberStrikeEventStream e = events;
        if (e != null) {
            events = null;
            try {
                e.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void reconnect() {
        reconnectSilently();
    }

    // ------------------------------------------------------------------
    // Session selection
    // ------------------------------------------------------------------

    private void discoverSession() {
        if (api == null) return;
        String base = normalize(project.getBasePath());
        String match = null;
        for (JsonObject s : api.listSessions(60)) {
            String dir = normalize(str(s, "directory"));
            if (!base.isEmpty() && base.equalsIgnoreCase(dir)) {
                match = str(s, "id");
                break;
            }
        }
        if (match == null || match.isBlank()) {
            match = api.createSession(project.getName());
        }
        sessionId = match;
        lastUserMsgId = null;
    }

    /** Pick a different existing session (called from the tool window combo). */
    public void setSession(String id) {
        if (id == null || id.isBlank()) return;
        sessionId = id;
        lastUserMsgId = null;
        turnActive = false;
        findings.clear();
        notifyFindingsChanged();
        refreshFindingsAsync();
        notifyChanged();
    }

    public List<SessionInfo> listSessions() {
        List<SessionInfo> out = new ArrayList<>();
        if (api == null) return out;
        for (JsonObject s : api.listSessions(60)) {
            try {
                SessionInfo info = SessionInfo.fromJson(s);
                if (info.id == null || info.id.isBlank()) continue;
                out.add(info);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public List<SessionInfo> listProjectSessions() {
        String base = normalize(project.getBasePath());
        List<SessionInfo> out = new ArrayList<>();
        for (SessionInfo s : listSessions()) {
            if (base.isEmpty() || base.equalsIgnoreCase(normalize(s.directory))) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Terminal UI (CyberStrike TUI docked in a ToolWindow panel)
    // ------------------------------------------------------------------

    /**
     * Show the CyberStrike tool window and make sure the TUI is running inside it.
     */
    public void focusOrCreateTerminal() {
        if (project.isDisposed()) return;
        if (!isConnected() && !connecting) autoConnect();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                ToolWindow tw = getToolWindow();
                if (tw != null) tw.activate(null, false);
            } catch (Exception ignored) {
            }
        });
        ensureTerminalOnEdt();
    }

    /** True when the CyberStrike terminal widget has been created (panel exists). */
    public boolean hasTerminalUI() {
        return terminalWidget != null;
    }

    /** Return (creating if needed) the JPanel hosting the TUI and attach it to the tool window. */
    public synchronized JPanel ensureTerminal() {
        return ensureTerminal(getToolWindow());
    }

    /**
     * Return (creating if needed) the JPanel hosting the CyberStrike TUI and make
     * sure it is a content of the given tool window.
     *
     * <p>{@code TerminalView.createLocalShellWidget} appends a tab to the global
     * Terminal tool window, so re-parenting the widget's component leaves an empty
     * tab behind and lets the Terminal window steal the component back (the panel
     * then went blank). We instead move the terminal's {@link Content} itself into
     * the CyberStrike tool window, which keeps the widget owned by a single
     * ContentManager.
     */
    public synchronized JPanel ensureTerminal(ToolWindow toolWindow) {
        ToolWindow tw = toolWindow != null ? toolWindow : getToolWindow();
        if (terminalWidget != null && terminalPanel != null) {
            attachPanel(tw, terminalPanel);
            return terminalPanel;
        }
        try {
            // The scrollback cap is read once, when the terminal buffer is created.
            applyTerminalScrollback();
            String wd = project.getBasePath();
            // requestFocus=false: do NOT activate the global Terminal tool window.
            ShellTerminalWidget widget = TerminalView.getInstance(project)
                    .createLocalShellWidget(wd, CyberStrikePlugin.NAME, false, true);
            CyberStrikeTerminalLinkFilter.install(project, widget);
            installEscIntercept(widget);

            JPanel panel = null;
            Content moved = moveTerminalContentInto(tw, widget);
            if (moved != null) {
                terminalContent = moved;
                java.awt.Component comp = moved.getComponent();
                if (comp instanceof JPanel) {
                    panel = (JPanel) comp;
                } else if (comp != null) {
                    panel = new JPanel(new BorderLayout());
                    panel.add(comp, BorderLayout.CENTER);
                }
            }
            if (panel == null) {
                // Older platform: createLocalShellWidget returns a detached widget.
                panel = new JPanel(new BorderLayout());
                panel.add(widget.getComponent(), BorderLayout.CENTER);
                attachPanel(tw, panel);
            }
            installFileDrop(panel, widget);
            terminalWidget = widget;
            terminalPanel = panel;
            // Defer the command so the widget is attached to a visible component first.
            ApplicationManager.getApplication().invokeLater(this::startTerminalCommandIfNeeded);
            LOG.info("CyberStrike terminal panel created");
        } catch (Throwable t) {
            LOG.warn("CyberStrike terminal unavailable", t);
            notify("CyberStrike", "Could not open the terminal panel: " + t.getMessage(),
                    NotificationType.ERROR);
            return null;
        }
        return terminalPanel;
    }

    /**
     * Move the {@link Content} that hosts {@code widget} from the global Terminal
     * tool window into {@code target}. Returns the moved content, or {@code null}
     * when the widget is not hosted in a Content (older platform API).
     */
    private Content moveTerminalContentInto(ToolWindow target, ShellTerminalWidget widget) {
        if (target == null) return null;
        try {
            ToolWindow terminalTw = ToolWindowManager.getInstance(project).getToolWindow("Terminal");
            if (terminalTw == null) return null;
            java.awt.Component widgetComponent = widget.getComponent();
            if (widgetComponent == null) return null;
            ContentManager terminalCm = terminalTw.getContentManager();
            for (Content c : terminalCm.getContents()) {
                java.awt.Component host = c.getComponent();
                if (host != null && javax.swing.SwingUtilities.isDescendingFrom(widgetComponent, host)) {
                    ContentManager targetCm = target.getContentManager();
                    // Drop only a previously moved CyberStrike terminal content.
                    for (Content old : targetCm.getContents()) {
                        if (old == terminalContent || old.getComponent() == terminalPanel) {
                            targetCm.removeContent(old, true);
                        }
                    }
                    terminalCm.removeContent(c, false);
                    targetCm.addContent(c);
                    c.setDisplayName(CyberStrikePlugin.NAME);
                    targetCm.setSelectedContent(c, false);
                    LOG.info("Moved CyberStrike terminal content into the CyberStrike tool window");
                    return c;
                }
            }
        } catch (Throwable t) {
            LOG.warn("Could not relocate terminal content", t);
        }
        return null;
    }

    /** Make sure the given panel is a content of the tool window (idempotent). */
    private void attachPanel(ToolWindow tw, JPanel panel) {
        try {
            if (tw == null) tw = getToolWindow();
            if (tw == null) return;
            ContentManager cm = tw.getContentManager();
            for (Content c : cm.getContents()) {
                if (c.getComponent() == panel) return; // already attached
            }
            cm.addContent(ContentFactory.getInstance().createContent(panel, CyberStrikePlugin.NAME, false));
        } catch (Exception e) {
            LOG.warn("Failed to attach terminal content", e);
        }
    }

    private void ensureTerminalOnEdt() {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ensureTerminal();
        } else {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) ensureTerminal();
            });
        }
    }

    /**
     * Accept files dropped onto the CyberStrike panel and paste them as
     * {@code @path} references into the TUI input.
     */
    private void installFileDrop(JPanel panel, ShellTerminalWidget widget) {
        javax.swing.TransferHandler handler = new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDrop() && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<File> files = (List<File>)
                            support.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) return false;
                    String base = project.getBasePath();
                    StringBuilder sb = new StringBuilder();
                    for (File f : files) {
                        String path = f.getAbsolutePath().replace('\\', '/');
                        String rel = path;
                        if (base != null) {
                            String bp = base.replace('\\', '/');
                            if (path.startsWith(bp + "/")) rel = path.substring(bp.length() + 1);
                        }
                        sb.append('@').append(rel.contains(" ") ? "\"" + rel + "\"" : rel).append(' ');
                    }
                    if (sb.length() > 0) {
                        String text = sb.toString().trim();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                widget.getTerminalStarter().sendString(text, false);
                            } catch (Throwable t) {
                                LOG.debug("file drop paste failed", t);
                            }
                        });
                    }
                    return true;
                } catch (Exception ex) {
                    LOG.debug("file drop import failed", ex);
                    return false;
                }
            }
        };
        panel.setTransferHandler(handler);
        try {
            widget.getComponent().setTransferHandler(handler);
        } catch (Throwable ignored) {
        }
    }

    /** Copy the whole terminal buffer to the clipboard as a Markdown code block. */
    public void copyOutputAsMarkdown() {
        ShellTerminalWidget w = terminalWidget;
        if (w == null) return;
        try {
            String text = w.getText();
            if (text == null || text.isBlank()) return;
            String md = "```\n" + text.stripTrailing() + "\n```";
            com.intellij.openapi.ide.CopyPasteManager.getInstance()
                    .setContents(new java.awt.datatransfer.StringSelection(md));
        } catch (Throwable t) {
            LOG.debug("copy output failed", t);
        }
    }

    /**
     * Make ESC in the CyberStrike panel interrupt the running turn instead of
     * letting IntelliJ move focus to the editor.
     *
     * <p>The terminal panel installs an internal escape listener that consumes ESC
     * and calls {@code ToolWindowManager.activateEditorComponent()} unless the
     * terminal uses the alternate screen buffer. The CyberStrike TUI renders
     * inline, so that listener always stole ESC. A "pre key event" handler runs
     * before it: consuming the event stops IntelliJ from hijacking ESC and we
     * forward the raw ESC byte to the TUI.
     */
    private void installEscIntercept(ShellTerminalWidget widget) {
        try {
            JBTerminalPanel panel = widget.getTerminalPanel();
            panel.addPreKeyEventHandler(e -> {
                if (e.getID() != KeyEvent.KEY_PRESSED || e.getKeyCode() != KeyEvent.VK_ESCAPE) return;
                e.consume();
                try {
                    TerminalStarter starter = widget.getTerminalStarter();
                    if (starter != null) starter.sendBytes(new byte[]{0x1b}, false);
                } catch (Throwable t) {
                    LOG.debug("Could not forward ESC to the CyberStrike TUI", t);
                }
            });
        } catch (Throwable t) {
            LOG.warn("Could not install ESC intercept on the CyberStrike terminal", t);
        }
    }

    /** Launch the CyberStrike TUI in the widget exactly once. */
    private void startTerminalCommandIfNeeded() {
        ShellTerminalWidget widget = terminalWidget;
        if (widget == null) return;
        if (!terminalCommandStarted.compareAndSet(false, true)) return;
        try {
            widget.executeCommand(buildLaunchCommand());
            terminalStartedAt = System.currentTimeMillis();
        } catch (Throwable t) {
            LOG.warn("Failed to start CyberStrike in terminal", t);
        }
    }

    /**
     * Command that launches the CyberStrike TUI.
     *
     * <p>When a server is reachable and "Attach TUI to server" is enabled, the TUI
     * attaches to it ({@code cyberstrike attach <url> --dir <project>}) so the TUI
     * and the IDE talk to the same instance — the findings and diff views then
     * update live. Otherwise the TUI is started standalone in the project
     * directory.
     */
    private String buildLaunchCommand() {
        String binary = quoteArg(getCyberStrikeBinary());
        String base = project.getBasePath();
        boolean attach = readBoolean(CyberStrikeSettingsConfigurable.ATTACH_TO_SERVER_KEY, true);
        ServerConnection c = conn;
        if (attach && c != null && connected) {
            StringBuilder sb = new StringBuilder(binary);
            sb.append(" attach ").append(c.url);
            if (base != null && !base.isBlank()) sb.append(" --dir ").append(quoteArg(base));
            if (!c.password.isBlank() && !c.isLoopback()) sb.append(" --password ").append(quoteArg(c.password));
            return sb.toString();
        }
        return binary;
    }

    private String quoteArg(String s) {
        if (s == null) return "";
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    private String readSetting(String key, String def) {
        try {
            String v = PropertiesComponent.getInstance(project).getValue(key, def);
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private boolean readBoolean(String key, boolean def) {
        try {
            return PropertiesComponent.getInstance(project).getBoolean(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * Apply the configured terminal scrollback size to the IDE's advanced setting
     * {@code terminal.buffer.max.lines.count}. The history storage only evicts once
     * the line count exceeds the cap and never pre-allocates, so "unlimited" costs
     * nothing until the lines are really produced.
     */
    private void applyTerminalScrollback() {
        try {
            int lines = PropertiesComponent.getInstance(project).getInt(
                    CyberStrikeSettingsConfigurable.TERMINAL_BUFFER_KEY,
                    CyberStrikeSettingsConfigurable.DEFAULT_TERMINAL_BUFFER_LINES);
            if (lines <= 0) lines = Integer.MAX_VALUE;
            AdvancedSettings.getInstance().setInt(TERMINAL_MAX_LINES_KEY, lines);
        } catch (Throwable t) {
            LOG.debug("could not apply terminal scrollback setting", t);
        }
    }

    /** Forget the cached binary path (called after the setting changes). */
    public void resetCachedBinary() {
        cachedBinary = null;
    }

    /** Restart the TUI terminal so launch-time settings take effect. */
    public void restartTerminal() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ShellTerminalWidget old = terminalWidget;
            terminalWidget = null;
            terminalPanel = null;
            terminalCommandStarted.set(false);
            terminalStartedAt = 0;
            if (old != null) {
                try {
                    old.dispose();
                } catch (Exception ignored) {
                }
            }
            ensureTerminal();
        });
    }

    /** Resolve the CyberStrike CLI binary (cached). */
    public String getCyberStrikeBinary() {
        if (cachedBinary != null) return cachedBinary;
        // 1) Explicit path from settings.
        String configured = readSetting(CyberStrikeSettingsConfigurable.BINARY_PATH_KEY, "");
        if (!configured.isBlank()) {
            File f = new File(configured.trim());
            if (f.exists()) {
                cachedBinary = f.getAbsolutePath();
                return cachedBinary;
            }
            LOG.warn("Configured CyberStrike binary does not exist, auto-detecting: " + configured);
        }
        String home = System.getProperty("user.home");
        String appData = System.getenv("APPDATA");
        String localAppData = System.getenv("LOCALAPPDATA");
        File[] candidates = isWindows()
                ? new File[]{
                        appData == null ? null : new File(appData, "npm\\cyberstrike.cmd"),
                        home == null ? null : new File(home, "scoop\\shims\\cyberstrike.cmd"),
                        localAppData == null ? null : new File(localAppData, "Programs\\cyberstrike\\cyberstrike.exe"),
                        new File("C:\\Program Files\\cyberstrike\\cyberstrike.exe"),
                }
                : new File[]{
                        home == null ? null : new File(home, ".bun/bin/cyberstrike"),
                        new File("/opt/homebrew/bin/cyberstrike"),
                        new File("/usr/local/bin/cyberstrike"),
                        new File("/usr/bin/cyberstrike"),
                };
        for (File c : candidates) {
            if (c != null && c.exists()) {
                cachedBinary = c.getAbsolutePath();
                return cachedBinary;
            }
        }
        cachedBinary = "cyberstrike";
        return cachedBinary;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private ToolWindow getToolWindow() {
        try {
            return ToolWindowManager.getInstance(project).getToolWindow(CyberStrikePlugin.TOOL_WINDOW_ID);
        } catch (Exception e) {
            return null;
        }
    }

    /** Focus the TUI and paste a reference (e.g. "@path#L1-5 ") as context. */
    public void focusOrCreateTerminalAndPaste(String text) {
        focusOrCreateTerminalAndType(text, false);
    }

    /** Focus the TUI, type the given text and press Enter so it runs immediately. */
    public void focusOrCreateTerminalAndRun(String text) {
        focusOrCreateTerminalAndType(text, true);
    }

    private void focusOrCreateTerminalAndType(String text, boolean submit) {
        if (text == null || text.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        long sinceStart = System.currentTimeMillis() - terminalStartedAt;
        long bootWait = terminalStartedAt == 0 ? 0 : Math.max(0, 4000 - sinceStart);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                try {
                    final ShellTerminalWidget w = widget;
                    final Project p = project;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        try {
                            ToolWindow tw = ToolWindowManager.getInstance(p)
                                    .getToolWindow(CyberStrikePlugin.TOOL_WINDOW_ID);
                            if (tw != null) tw.activate(null, false);
                        } catch (Exception ignored) {
                        }
                    });
                    if (bootWait > 0) {
                        appScheduler().schedule(
                                () -> ApplicationManager.getApplication().invokeLater(
                                        () -> typeIntoTerminal(w, text, submit)),
                                bootWait, TimeUnit.MILLISECONDS);
                    } else {
                        typeIntoTerminal(w, text, submit);
                    }
                } catch (Exception e) {
                    LOG.debug("paste via terminal failed", e);
                }
            });
        });
    }

    /** Send the text to the TUI and, when requested, press Enter shortly after. */
    private void typeIntoTerminal(ShellTerminalWidget w, String text, boolean submit) {
        if (project.isDisposed()) return;
        try {
            w.getTerminalStarter().sendString(text, false);
            if (submit) {
                // Let the TUI render the typed text (and close any @mention
                // completion popup) before submitting.
                appScheduler().schedule(
                        () -> ApplicationManager.getApplication().invokeLater(() -> {
                            try {
                                if (!project.isDisposed()) {
                                    w.getTerminalStarter().sendBytes(new byte[]{'\r'}, false);
                                }
                            } catch (Throwable ignored) {
                            }
                        }), 450, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable t) {
            LOG.debug("type into terminal failed", t);
        }
    }

    private static ScheduledExecutorService appScheduler() {
        return AppExecutorUtil.getAppScheduledExecutorService();
    }

    /** Type a line into the CyberStrike TUI and press Enter. */
    public void sendCommandToTerminal(String command) {
        if (command == null || command.isBlank() || project.isDisposed()) return;
        focusOrCreateTerminal();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            ShellTerminalWidget widget = terminalWidget;
            if (widget == null) return;
            try {
                widget.getTerminalStarter().sendString(command + "\r", false);
            } catch (Throwable t) {
                LOG.debug("sendCommandToTerminal failed", t);
            }
        });
    }

    /**
     * Ask the TUI to start a fresh session ({@code /new}) and, shortly after,
     * re-discover the project's session so turn tracking follows the new one.
     */
    public void startNewSession() {
        sendCommandToTerminal("/new");
        poller.schedule(() -> {
            try {
                discoverSession();
                refreshFindingsAsync();
                notifyChanged();
            } catch (Exception e) {
                LOG.debug("session re-discovery after /new failed", e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    /** Run a TUI slash command (e.g. {@code /hackbrowser}, {@code /report}). */
    public void runSlashCommand(String command) {
        sendCommandToTerminal(command);
    }

    /** Called when the terminal panel was disposed. */
    public void onTerminalDisposed() {
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
        terminalStartedAt = 0;
        LOG.info("CyberStrike terminal disposed");
    }

    // ------------------------------------------------------------------
    // Turn tracking + diff
    // ------------------------------------------------------------------

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) return;
        poller.scheduleWithFixedDelay(this::pollOnBackground, 1, 2, TimeUnit.SECONDS);
    }

    private void pollOnBackground() {
        if (disposed.get() || api == null || sessionId == null) return;
        try {
            Map<String, String> statuses = api.sessionStatuses();
            consecutiveFailures = 0;
            String type = statuses.getOrDefault(sessionId, "idle");
            boolean running = "busy".equals(type) || "retry".equals(type);
            boolean was = turnActive;
            turnActive = running;
            if (running != was) {
                if (running) {
                    lastUserMsgId = null;
                    notifyChanged();
                } else if (was) {
                    // Turn just finished -> fetch diffs + findings, notify the user.
                    refreshDiffs(true);
                    refreshFindingsAsync();
                    notifyChanged();
                }
            }
        } catch (Exception e) {
            consecutiveFailures++;
            if (consecutiveFailures == RECONNECT_AFTER_FAILURES) {
                LOG.warn("CyberStrike: " + RECONNECT_AFTER_FAILURES + " consecutive poll failures - reconnecting");
                reconnectSilently();
            }
        }
    }

    /** Re-resolve the server and reconnect without showing the manual dialog. */
    public void reconnectSilently() {
        if (connecting || disposed.get()) return;
        teardown();
        connected = false;
        api = null;
        conn = null;
        connecting = true;
        notifyChanged();
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                tryConnect(resolveConnection(), false);
            } finally {
                connecting = false;
                notifyChanged();
            }
        });
    }

    private void startEventStream() {
        ServerConnection c = conn;
        if (c == null) return;
        events = new CyberStrikeEventStream(c.url, c.password, project.getBasePath(),
                this::onEvent, up -> {
                });
        events.start();
    }

    private void onEvent(String type, JsonObject props) {
        try {
            String sid = props.has("sessionID") && !props.get("sessionID").isJsonNull()
                    ? props.get("sessionID").getAsString() : null;
            if ("session.status".equals(type) && sid != null && sid.equals(sessionId)) {
                String statusType = "";
                if (props.has("status") && props.get("status").isJsonObject()) {
                    JsonObject st = props.getAsJsonObject("status");
                    if (st.has("type") && !st.get("type").isJsonNull()) statusType = st.get("type").getAsString();
                }
                boolean running = "busy".equals(statusType) || "retry".equals(statusType);
                boolean was = turnActive;
                turnActive = running;
                if (was && !running) {
                    refreshDiffs(true);
                    refreshFindingsAsync();
                }
                notifyChanged();
                return;
            }
            if ("session.idle".equals(type) && (sid == null || sid.equals(sessionId))) {
                if (turnActive) {
                    turnActive = false;
                    refreshDiffs(true);
                    refreshFindingsAsync();
                    notifyChanged();
                }
                return;
            }
            if ("vulnerability.updated".equals(type)) {
                refreshFindingsAsync();
                return;
            }
            if ("session.error".equals(type) && (sid == null || sid.equals(sessionId))) {
                lastError = "The agent reported an error — check the CyberStrike panel.";
                notifyChanged();
            }
        } catch (Exception e) {
            LOG.debug("event handling failed", e);
        }
    }

    /** Send a prompt through the REST API (queued, non-blocking). */
    public void sendPrompt(String text) {
        if (api == null || sessionId == null || text == null || text.isBlank()) {
            notify("CyberStrike", "Not connected to a CyberStrike session.", NotificationType.WARNING);
            return;
        }
        final String sid = sessionId;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (api != null && api.promptAsync(sid, text)) {
                turnActive = true;
                notifyChanged();
            }
        });
    }

    public void interrupt() {
        final String sid = sessionId;
        if (api == null || sid == null) return;
        AppExecutorUtil.getAppExecutorService().execute(() -> api.abort(sid));
    }

    /** Fetch /diff for the last completed turn and open the diff viewer. */
    public void refreshDiffs() {
        refreshDiffs(false);
    }

    /**
     * Fetch /diff for the last completed turn and open the diff viewer.
     *
     * @param notifyOnComplete when true (turn finished on its own) also show a
     *                         balloon notification summarising the result.
     */
    public void refreshDiffs(boolean notifyOnComplete) {
        final CyberStrikeApiClient client = api;
        final String sid = sessionId;
        if (client == null || sid == null) return;
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                String anchor = lastUserMsgId;
                if (anchor == null || anchor.isBlank()) {
                    List<JsonObject> msgs = client.listMessages(sid, 30);
                    for (int i = msgs.size() - 1; i >= 0; i--) {
                        JsonObject m = msgs.get(i);
                        if (m.has("info") && m.get("info").isJsonObject()) {
                            JsonObject info = m.getAsJsonObject("info");
                            String role = info.has("role") && !info.get("role").isJsonNull()
                                    ? info.get("role").getAsString() : "";
                            if ("user".equals(role) && info.has("id")) {
                                anchor = info.get("id").getAsString();
                                lastUserMsgId = anchor;
                                break;
                            }
                        }
                    }
                }
                List<JsonObject> raw = client.diffs(sid, anchor);
                List<ResolvedDiff> resolved = new ArrayList<>();
                SessionManager sm = ServiceManager.getService(project, SessionManager.class);
                for (JsonObject o : raw) {
                    try {
                        FileDiff fd = FileDiff.fromJson(o);
                        String after = fd.after;
                        if (after == null) after = readCurrentContent(fd.file);
                        String before = fd.before;
                        if (before == null) {
                            if (fd.isNewFile()) {
                                before = "";
                            } else if (sm != null && sm.beforeContent(fd.file) != null) {
                                before = sm.beforeContent(fd.file);
                            } else {
                                String rev = UnifiedPatch.reverseApply(fd.patch, after);
                                before = rev != null ? rev : "";
                            }
                        }
                        if (after == null) after = "";
                        resolved.add(new ResolvedDiff(fd, before, after));
                    } catch (Exception e) {
                        LOG.debug("resolve diff failed", e);
                    }
                }
                lastDiffs.clear();
                lastDiffs.addAll(resolved);
                notifyChanged();
                boolean autoOpen = readBoolean(CyberStrikeSettingsConfigurable.AUTO_OPEN_DIFFS_KEY, true);
                if (!resolved.isEmpty() && autoOpen && !project.isDisposed()) {
                    new DiffViewerService(project).showDiffs(resolved);
                }
                if (notifyOnComplete) notifyTurnComplete(resolved);
            } catch (Exception e) {
                LOG.warn("diff refresh failed", e);
            }
        });
    }

    /** Balloon shown when a turn finishes, with a shortcut to the diff viewer. */
    private void notifyTurnComplete(List<ResolvedDiff> resolved) {
        if (project.isDisposed()) return;
        final List<ResolvedDiff> snapshot = new ArrayList<>(resolved);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            try {
                String content = snapshot.isEmpty()
                        ? "The agent finished with no file changes."
                        : "The agent changed " + snapshot.size()
                        + (snapshot.size() == 1 ? " file." : " files.");
                Notification n = NotificationGroupManager.getInstance()
                        .getNotificationGroup(CyberStrikePlugin.NOTIFICATION_GROUP)
                        .createNotification("CyberStrike finished", content, NotificationType.INFORMATION);
                if (!snapshot.isEmpty()) {
                    n.addAction(NotificationAction.createSimpleExpiring(
                            "Review Diffs", () -> new DiffViewerService(project).showDiffs(snapshot)));
                }
                n.notify(project);
            } catch (Exception e) {
                LOG.debug("completion notification failed", e);
            }
        });
    }

    private String readCurrentContent(String rel) {
        try {
            String base = project.getBasePath();
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(
                    base == null ? rel : base.replace('\\', '/') + "/" + rel.replace('\\', '/'));
            if (file == null || !file.isValid() || file.isDirectory()) return "";
            return new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Findings
    // ------------------------------------------------------------------

    /** Re-read the session findings in the background. */
    public void refreshFindingsAsync() {
        final CyberStrikeApiClient client = api;
        final String sid = sessionId;
        if (client == null || sid == null || disposed.get()) {
            notifyFindingsChanged();
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                List<Vulnerability> parsed = new ArrayList<>();
                for (JsonObject o : client.vulnerabilities(sid)) {
                    try {
                        parsed.add(Vulnerability.fromJson(o));
                    } catch (Exception ignored) {
                    }
                }
                parsed.sort((a, b) -> {
                    int r = Integer.compare(a.severityRank(), b.severityRank());
                    return r != 0 ? r : Long.compare(b.created, a.created);
                });
                findings.clear();
                findings.addAll(parsed);
                notifyFindingsChanged();
            } catch (Exception e) {
                LOG.debug("findings refresh failed", e);
            }
        });
    }

    public List<Vulnerability> getFindings() {
        return new ArrayList<>(findings);
    }

    /** Count of open findings per severity, index 0..4 = critical, high, medium, low, info. */
    public int[] findingCounts() {
        int[] counts = new int[5];
        for (Vulnerability v : findings) {
            if (!v.isOpen()) continue;
            counts[Math.min(v.severityRank(), 4)]++;
        }
        return counts;
    }

    /** Ask the agent to verify / exploit a finding, with the file reference as context. */
    public void askAbout(Vulnerability v) {
        if (v == null) return;
        StringBuilder sb = new StringBuilder();
        if (!v.file.isBlank()) {
            sb.append('@').append(v.file.contains(" ") ? "\"" + v.file + "\"" : v.file);
            if (v.lineStart > 0) {
                sb.append("#L").append(v.lineStart);
                if (v.lineEnd > v.lineStart) sb.append("-").append(v.lineEnd);
            }
            sb.append(' ');
        }
        sb.append("Verify this finding and prove it is exploitable: ").append(v.displayTitle());
        if (!v.endpoint.isBlank()) sb.append(" (").append(v.endpoint).append(")");
        focusOrCreateTerminalAndRun(sb.toString());
    }

    // ------------------------------------------------------------------
    // Server process
    // ------------------------------------------------------------------

    /**
     * Start {@code cyberstrike serve} in the background. Only possible when a
     * password is configured: the server refuses to start without one.
     */
    public boolean startServer() {
        ServerConnection c = resolveConnection();
        if (c.password.isBlank()) {
            notify("CyberStrike", "Set CYBERSTRIKE_SERVER_PASSWORD (or the password in "
                    + "Settings > Tools > CyberStrike) before starting a server.", NotificationType.WARNING);
            return false;
        }
        try {
            String binary = getCyberStrikeBinary();
            ProcessBuilder pb = new ProcessBuilder(binary, "serve", "--port", String.valueOf(c.port()));
            String base = project.getBasePath();
            if (base != null) pb.directory(new File(base));
            pb.environment().put("CYBERSTRIKE_SERVER_PASSWORD", c.password);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            notify("CyberStrike", "Starting server on " + c.display() + "\u2026", NotificationType.INFORMATION);
            return true;
        } catch (Exception e) {
            LOG.warn("could not start CyberStrike server", e);
            notify("CyberStrike", "Could not start the server: " + e.getMessage(), NotificationType.ERROR);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    public boolean isBusy() {
        return turnActive;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getLastError() {
        return lastError;
    }

    public ServerConnection getConnection() {
        return conn;
    }

    public String getStatusText() {
        if (!connected) return connecting ? "Connecting to CyberStrike\u2026" : "Not connected";
        String where = conn == null ? "" : " \u00b7 " + conn.display();
        String ver = serverVersion == null || serverVersion.isBlank() ? "" : " (v" + serverVersion + ")";
        String state = turnActive ? " \u00b7 turn running" : "";
        return "Connected" + where + ver + state;
    }

    public List<ResolvedDiff> getLastDiffs() {
        return lastDiffs;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    public void addFindingsListener(Runnable r) {
        findingsListeners.add(r);
    }

    public void removeFindingsListener(Runnable r) {
        findingsListeners.remove(r);
    }

    private void notifyChanged() {
        runOnEdt(listeners);
    }

    private void notifyFindingsChanged() {
        runOnEdt(findingsListeners);
    }

    private void runOnEdt(List<Runnable> list) {
        List<Runnable> snapshot = new ArrayList<>(list);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            for (Runnable r : snapshot) r.run();
        } else {
            ApplicationManager.getApplication().invokeLater(() -> {
                for (Runnable r : snapshot) r.run();
            });
        }
    }

    private void notify(String title, String content, NotificationType type) {
        try {
            ApplicationManager.getApplication().invokeLater(() ->
                    NotificationGroupManager.getInstance()
                            .getNotificationGroup(CyberStrikePlugin.NOTIFICATION_GROUP)
                            .createNotification(title, content, type)
                            .notify(project));
        } catch (Exception ignored) {
        }
    }

    private static String normalize(String p) {
        if (p == null) return "";
        String v = p.replace('\\', '/');
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void dispose() {
        disposed.set(true);
        teardown();
        poller.shutdownNow();
        listeners.clear();
        findingsListeners.clear();
        lastDiffs.clear();
        findings.clear();
        ShellTerminalWidget w = terminalWidget;
        terminalWidget = null;
        terminalPanel = null;
        terminalCommandStarted.set(false);
        terminalStartedAt = 0;
        if (w != null) {
            try {
                w.dispose();
            } catch (Exception ignored) {
            }
        }
    }
}
