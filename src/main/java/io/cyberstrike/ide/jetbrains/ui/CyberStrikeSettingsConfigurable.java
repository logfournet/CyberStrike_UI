package io.cyberstrike.ide.jetbrains.ui;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import io.cyberstrike.ide.jetbrains.util.ServerConnection;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Settings page for the CyberStrike plugin (Settings &gt; Tools &gt; CyberStrike).
 * Stored per project through {@link PropertiesComponent}.
 */
public final class CyberStrikeSettingsConfigurable implements Configurable {

    public static final String PREFIX = "cyberstrike.";
    /** Base URL of the CyberStrike server. */
    public static final String SERVER_URL_KEY = PREFIX + "serverUrl";
    /** Basic-auth password (blank = read CYBERSTRIKE_SERVER_PASSWORD). */
    public static final String PASSWORD_KEY = PREFIX + "password";
    /** Explicit path to the cyberstrike CLI (blank = auto-detect). */
    public static final String BINARY_PATH_KEY = PREFIX + "binaryPath";
    public static final String AUTO_CONNECT_KEY = PREFIX + "autoConnect";
    /** Launch the TUI with {@code cyberstrike attach <url>} instead of standalone. */
    public static final String ATTACH_TO_SERVER_KEY = PREFIX + "attachToServer";
    /** Open the native diff viewer when a turn finishes. */
    public static final String AUTO_OPEN_DIFFS_KEY = PREFIX + "autoOpenDiffs";
    /** Spawn {@code cyberstrike serve} when no server is reachable (needs a password). */
    public static final String AUTO_START_SERVER_KEY = PREFIX + "autoStartServer";
    /** Terminal scrollback lines; 0 = unlimited. */
    public static final String TERMINAL_BUFFER_KEY = PREFIX + "terminalBufferLines";

    public static final int UNLIMITED_TERMINAL_BUFFER_LINES = 0;
    public static final int DEFAULT_TERMINAL_BUFFER_LINES = UNLIMITED_TERMINAL_BUFFER_LINES;
    public static final int DEFAULT_FINITE_TERMINAL_BUFFER_LINES = 100000;
    public static final int MIN_TERMINAL_BUFFER_LINES = 1000;
    public static final int MAX_TERMINAL_BUFFER_LINES = 1000000;

    private final Project project;
    private JPanel root;
    private JBTextField serverUrlField;
    private JBPasswordField passwordField;
    private JBTextField binaryPathField;
    private JBCheckBox autoConnectCheck;
    private JBCheckBox attachCheck;
    private JBCheckBox autoOpenDiffsCheck;
    private JBCheckBox autoStartServerCheck;
    private JSpinner terminalBufferSpinner;
    private JBCheckBox unlimitedBufferCheck;

    public CyberStrikeSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable JComponent createComponent() {
        root = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;

        // Server URL
        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        root.add(new JLabel("Server URL:"), c);
        serverUrlField = new JBTextField();
        serverUrlField.getEmptyText().setText(ServerConnection.DEFAULT_URL);
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        root.add(serverUrlField, c);

        // Password
        c.gridx = 0; c.gridy = 1; c.gridwidth = 1; c.weightx = 0;
        root.add(new JLabel("Password:"), c);
        passwordField = new JBPasswordField();
        passwordField.getEmptyText().setText("CYBERSTRIKE_SERVER_PASSWORD");
        c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
        root.add(passwordField, c);

        // Binary path
        c.gridx = 0; c.gridy = 2; c.gridwidth = 1; c.weightx = 0;
        root.add(new JLabel("cyberstrike binary:"), c);
        binaryPathField = new JBTextField();
        binaryPathField.getEmptyText().setText("Auto-detect");
        c.gridx = 1; c.weightx = 1;
        root.add(binaryPathField, c);
        JButton browse = new JButton("...");
        c.gridx = 2; c.weightx = 0;
        root.add(browse, c);
        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setDialogTitle("Select the cyberstrike CLI");
            if (fc.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                binaryPathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // Behaviour
        c.gridx = 0; c.gridy = 3; c.gridwidth = 3; c.weightx = 1;
        autoConnectCheck = new JBCheckBox("Connect to the CyberStrike server when the project opens");
        root.add(autoConnectCheck, c);

        c.gridy = 4;
        attachCheck = new JBCheckBox("Run the TUI attached to the server (cyberstrike attach)");
        root.add(attachCheck, c);

        c.gridy = 5;
        autoOpenDiffsCheck = new JBCheckBox("Open the diff viewer when a turn finishes");
        root.add(autoOpenDiffsCheck, c);

        c.gridy = 6;
        autoStartServerCheck = new JBCheckBox("Start cyberstrike serve when no server is reachable (needs a password)");
        root.add(autoStartServerCheck, c);

        // Terminal scrollback
        c.gridx = 0; c.gridy = 7; c.gridwidth = 1; c.weightx = 0;
        root.add(new JLabel("Terminal scrollback:"), c);
        terminalBufferSpinner = new JSpinner(new SpinnerNumberModel(
                DEFAULT_FINITE_TERMINAL_BUFFER_LINES, MIN_TERMINAL_BUFFER_LINES,
                MAX_TERMINAL_BUFFER_LINES, 1000));
        c.gridx = 1; c.weightx = 0;
        root.add(terminalBufferSpinner, c);
        unlimitedBufferCheck = new JBCheckBox("Unlimited (no line limit)");
        c.gridx = 2; c.weightx = 0;
        root.add(unlimitedBufferCheck, c);
        unlimitedBufferCheck.addActionListener(e ->
                terminalBufferSpinner.setEnabled(!unlimitedBufferCheck.isSelected()));

        c.gridx = 0; c.gridy = 8; c.gridwidth = 3; c.weightx = 1;
        root.add(new JBLabel("<html><small>Applies to every IDE terminal "
                + "(<code>terminal.buffer.max.lines.count</code>). RAM grows only with real output.</small></html>"), c);

        c.gridy = 9;
        root.add(new JBLabel("<html><small>Local requests (127.0.0.1) are trusted by CyberStrike; "
                + "a password is only needed for remote / tunneled servers.</small></html>"), c);

        loadSettings();
        return root;
    }

    @Override
    public boolean isModified() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        return !serverUrlField.getText().trim().equals(pc.getValue(SERVER_URL_KEY, ServerConnection.DEFAULT_URL))
                || !new String(passwordField.getPassword()).equals(pc.getValue(PASSWORD_KEY, ""))
                || !binaryPathField.getText().trim().equals(pc.getValue(BINARY_PATH_KEY, ""))
                || autoConnectCheck.isSelected() != pc.getBoolean(AUTO_CONNECT_KEY, true)
                || attachCheck.isSelected() != pc.getBoolean(ATTACH_TO_SERVER_KEY, true)
                || autoOpenDiffsCheck.isSelected() != pc.getBoolean(AUTO_OPEN_DIFFS_KEY, true)
                || autoStartServerCheck.isSelected() != pc.getBoolean(AUTO_START_SERVER_KEY, false)
                || selectedTerminalBufferLines() != pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);
    }

    @Override
    public void apply() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        String oldUrl = pc.getValue(SERVER_URL_KEY, ServerConnection.DEFAULT_URL);
        String oldPassword = pc.getValue(PASSWORD_KEY, "");
        String oldBinary = pc.getValue(BINARY_PATH_KEY, "");
        int oldBuffer = pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);

        String newUrl = serverUrlField.getText().trim();
        if (newUrl.isBlank()) newUrl = ServerConnection.DEFAULT_URL;
        String newPassword = new String(passwordField.getPassword());
        String newBinary = binaryPathField.getText().trim();
        int newBuffer = selectedTerminalBufferLines();

        pc.setValue(SERVER_URL_KEY, newUrl);
        pc.setValue(PASSWORD_KEY, newPassword);
        pc.setValue(BINARY_PATH_KEY, newBinary);
        pc.setValue(AUTO_CONNECT_KEY, autoConnectCheck.isSelected());
        pc.setValue(ATTACH_TO_SERVER_KEY, attachCheck.isSelected());
        pc.setValue(AUTO_OPEN_DIFFS_KEY, autoOpenDiffsCheck.isSelected());
        pc.setValue(AUTO_START_SERVER_KEY, autoStartServerCheck.isSelected());
        pc.setValue(TERMINAL_BUFFER_KEY, newBuffer, DEFAULT_TERMINAL_BUFFER_LINES);

        CyberStrikeService svc = ServiceManager.getService(project, CyberStrikeService.class);
        svc.resetCachedBinary();
        // Launch-time settings: restart the TUI so they take effect.
        if (!newUrl.equals(oldUrl) || !newPassword.equals(oldPassword) || !newBinary.equals(oldBinary)) {
            svc.restartTerminal();
        }
        if (newBuffer != oldBuffer) svc.restartTerminal();
    }

    @Override
    public void reset() {
        loadSettings();
    }

    private int selectedTerminalBufferLines() {
        if (unlimitedBufferCheck != null && unlimitedBufferCheck.isSelected()) {
            return UNLIMITED_TERMINAL_BUFFER_LINES;
        }
        Object v = terminalBufferSpinner == null ? null : terminalBufferSpinner.getValue();
        return v == null ? DEFAULT_FINITE_TERMINAL_BUFFER_LINES : ((Number) v).intValue();
    }

    private void loadSettings() {
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        serverUrlField.setText(pc.getValue(SERVER_URL_KEY, ServerConnection.DEFAULT_URL));
        passwordField.setText(pc.getValue(PASSWORD_KEY, ""));
        binaryPathField.setText(pc.getValue(BINARY_PATH_KEY, ""));
        autoConnectCheck.setSelected(pc.getBoolean(AUTO_CONNECT_KEY, true));
        attachCheck.setSelected(pc.getBoolean(ATTACH_TO_SERVER_KEY, true));
        autoOpenDiffsCheck.setSelected(pc.getBoolean(AUTO_OPEN_DIFFS_KEY, true));
        autoStartServerCheck.setSelected(pc.getBoolean(AUTO_START_SERVER_KEY, false));

        int lines = pc.getInt(TERMINAL_BUFFER_KEY, DEFAULT_TERMINAL_BUFFER_LINES);
        boolean unlimited = lines <= 0;
        unlimitedBufferCheck.setSelected(unlimited);
        terminalBufferSpinner.setEnabled(!unlimited);
        terminalBufferSpinner.setValue(unlimited ? DEFAULT_FINITE_TERMINAL_BUFFER_LINES : lines);
    }

    @Override
    public String getDisplayName() {
        return "CyberStrike";
    }
}
