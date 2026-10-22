package io.cyberstrike.ide.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import org.jetbrains.annotations.NotNull;

/**
 * "CyberStrike" submenu shown in the editor context menu: security-focused
 * actions (security review, find vulnerabilities, write a PoC, fix, tests) plus
 * a free-form prompt entry. Each action pastes a prompt + the current selection
 * reference into the CyberStrike TUI and submits it.
 */
public final class CyberStrikeActionsGroup extends DefaultActionGroup {

    public CyberStrikeActionsGroup() {
        getTemplatePresentation().setText("CyberStrike");
        getTemplatePresentation().setIcon(
                com.intellij.openapi.util.IconLoader.getIcon("/icons/cyberstrike.svg", CyberStrikeActionsGroup.class));
        setPopup(true);

        add(new CyberStrikeAskAction("Security Review (OWASP)", "Perform an OWASP-aligned security review of"));
        add(new CyberStrikeAskAction("Find Vulnerabilities", "Find exploitable vulnerabilities in"));
        add(new CyberStrikeAskAction("Write a Proof of Concept", "Write a proof of concept that proves the vulnerability in"));
        add(Separator.getInstance());
        add(new CyberStrikeAskAction("Explain", "Explain"));
        add(new CyberStrikeAskAction("Fix", "Fix the problems in"));
        add(new CyberStrikeAskAction("Add Tests", "Write tests for"));
        add(new CyberStrikeAskAction("Refactor", "Refactor"));
        add(Separator.getInstance());
        add(new CyberStrikeAskWithPromptAction());
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }
}
