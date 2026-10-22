package io.cyberstrike.ide.jetbrains.actions;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Starts a CyberStrike server ({@code cyberstrike serve}) for the project when no
 * server is reachable, then reconnects.
 */
public final class StartServerAction extends AnAction {

    public StartServerAction() {
        super("Start CyberStrike Server");
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        if (p == null || p.isDisposed()) return;
        CyberStrikeService svc = ServiceManager.getService(p, CyberStrikeService.class);
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            if (svc.startServer()) {
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                svc.reconnect();
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project p = e.getProject();
        e.getPresentation().setEnabledAndVisible(p != null && !p.isDisposed());
    }
}
