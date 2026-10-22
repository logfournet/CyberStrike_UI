package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Runs a CyberStrike TUI slash command in the docked panel.
 */
public class SlashCommandAction extends AnAction {

    private final String command;

    public SlashCommandAction(@NotNull String text, @NotNull String command) {
        super(text);
        this.command = command;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        ServiceManager.getService(p, CyberStrikeService.class).runSlashCommand(command);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
