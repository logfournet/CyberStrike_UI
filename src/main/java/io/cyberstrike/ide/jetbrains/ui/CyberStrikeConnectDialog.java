package io.cyberstrike.ide.jetbrains.ui;

import io.cyberstrike.ide.jetbrains.util.ServerConnection;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;

/**
 * Dialog for manually specifying the CyberStrike server URL + password.
 */
public final class CyberStrikeConnectDialog extends DialogWrapper {

    private final JBTextField urlField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();

    private CyberStrikeConnectDialog(Project project, ServerConnection suggested) {
        super(project, true);
        setTitle("Connect to CyberStrike Server");
        urlField.setText(suggested != null ? suggested.url : ServerConnection.DEFAULT_URL);
        if (suggested != null && !suggested.password.isBlank()) passwordField.setText(suggested.password);
        init();
    }

    public static void show(Project project, Consumer<ServerConnection> onResult) {
        CyberStrikeConnectDialog dlg = new CyberStrikeConnectDialog(project, ServerConnection.local());
        if (dlg.showAndGet()) {
            onResult.accept(dlg.buildConnection());
        }
    }

    private ServerConnection buildConnection() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) url = ServerConnection.DEFAULT_URL;
        return new ServerConnection(url, new String(passwordField.getPassword()), null);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        panel.add(new JBLabel("Server URL:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(urlField, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        panel.add(new JBLabel("Password:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(passwordField, c);

        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JBLabel("<html><small>Start the server with <code>cyberstrike web</code> "
                + "(default port 4096). Local requests do not need a password.</small></html>"), c);

        return panel;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return urlField;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (urlField.getText().trim().isEmpty()) return new ValidationInfo("Server URL must not be empty", urlField);
        return null;
    }
}
