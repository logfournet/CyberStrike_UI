package io.cyberstrike.ide.jetbrains.ui;

import io.cyberstrike.ide.jetbrains.actions.CopyOutputAction;
import io.cyberstrike.ide.jetbrains.actions.InterruptAction;
import io.cyberstrike.ide.jetbrains.actions.NewSessionAction;
import io.cyberstrike.ide.jetbrains.actions.OpenDiffsAction;
import io.cyberstrike.ide.jetbrains.actions.OpenFindingsAction;
import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * CyberStrike docked panel.
 *
 * <p>Two tabs live in this tool window:
 * <ul>
 *   <li><b>CyberStrike</b> — the real CyberStrike TUI (a terminal widget), so the
 *       code editor stays visible next to the agent ("files | code | agent").</li>
 *   <li><b>Findings</b> — the vulnerabilities the agents recorded in this
 *       session, colour-coded by severity.</li>
 * </ul>
 */
public final class CyberStrikeToolWindowFactory implements ToolWindowFactory, DumbAware {

    public static final String FINDINGS_TAB = "Findings";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CyberStrikeService svc = ServiceManager.getService(project, CyberStrikeService.class);
        // The TUI content is moved in from the Terminal tool window.
        svc.ensureTerminal(toolWindow);
        ensureFindingsContent(project, toolWindow);
        toolWindow.setTitleActions(List.of(
                new NewSessionAction(),
                new InterruptAction(),
                new OpenFindingsAction(),
                new OpenDiffsAction(),
                new CopyOutputAction()
        ));
    }

    /** Add the findings tab (idempotent). */
    public static void ensureFindingsContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ContentManager cm = toolWindow.getContentManager();
        for (Content c : cm.getContents()) {
            if (FINDINGS_TAB.equals(c.getDisplayName())) {
                cm.setSelectedContent(c, false);
                return;
            }
        }
        FindingsPanel panel = new FindingsPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, FINDINGS_TAB, false);
        content.setDisposer(panel);
        content.setCloseable(false);
        cm.addContent(content);
    }

    /** Select the findings tab, creating it when needed. */
    public static void showFindings(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        ensureFindingsContent(project, toolWindow);
        for (Content c : toolWindow.getContentManager().getContents()) {
            if (FINDINGS_TAB.equals(c.getDisplayName())) {
                toolWindow.getContentManager().setSelectedContent(c);
                return;
            }
        }
    }
}
