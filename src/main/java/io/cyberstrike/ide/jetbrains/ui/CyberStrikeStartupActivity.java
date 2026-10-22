package io.cyberstrike.ide.jetbrains.ui;

import io.cyberstrike.ide.jetbrains.service.CyberStrikeService;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Auto-connects the CyberStrike server when a project opens, controlled by the
 * "Connect when the project opens" setting.
 *
 * <p>Implemented as a {@link ProjectActivity} (the replacement for the
 * deprecated {@code StartupActivity}). The method is a Kotlin suspend function;
 * we never suspend, so it just runs the work and returns {@link Unit#INSTANCE}.
 */
public final class CyberStrikeStartupActivity implements ProjectActivity {

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                    @NotNull Continuation<? super Unit> continuation) {
        if (project.isDisposed()) return Unit.INSTANCE;
        PropertiesComponent pc = PropertiesComponent.getInstance(project);
        if (pc.getBoolean(CyberStrikeSettingsConfigurable.AUTO_CONNECT_KEY, true)) {
            ServiceManager.getService(project, CyberStrikeService.class).autoConnect();
        }
        return Unit.INSTANCE;
    }
}
