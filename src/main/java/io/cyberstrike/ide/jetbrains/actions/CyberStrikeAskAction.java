package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import io.cyberstrike.ide.jetbrains.util.CyberStrikeReference;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * A code action that pastes a canned prompt (plus the current selection/file
 * reference) into the CyberStrike TUI input. The user reviews it and presses Enter.
 */
public class CyberStrikeAskAction extends AnAction {

    private final String prompt;

    public CyberStrikeAskAction(@NotNull String text, @NotNull String prompt) {
        super(text);
        this.prompt = prompt;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null || project.isDisposed()) return;
        String ref = CyberStrikeReference.from(project, e);
        // Put the reference first, terminated by a space, so the TUI's @mention
        // completion popup is already closed by the time the prompt is submitted.
        String text = ref.isEmpty() ? prompt : ref + " " + prompt;
        ServiceManager.getService(project, CyberStrikeService.class).focusOrCreateTerminalAndRun(text);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
