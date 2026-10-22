package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.CyberStrikePlugin;
import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import io.cyberstrike.ide.jetbrains.ui.CyberStrikeToolWindowFactory;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * Opens the CyberStrike panel on the Findings tab and refreshes the list.
 */
public final class OpenFindingsAction extends AnAction {

    public OpenFindingsAction() {
        super("Findings");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ToolWindow tw = ToolWindowManager.getInstance(p).getToolWindow(CyberStrikePlugin.TOOL_WINDOW_ID);
        if (tw == null) return;
        tw.activate(null, false);
        CyberStrikeToolWindowFactory.showFindings(p, tw);
        ServiceManager.getService(p, CyberStrikeService.class).refreshFindingsAsync();
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
