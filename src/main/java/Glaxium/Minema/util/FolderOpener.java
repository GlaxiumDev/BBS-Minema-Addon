package Glaxium.Minema.util;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Opens a folder in the system file manager. Shared by the quick-capture
 * screen and the full settings screen so both use the same fallback path
 * instead of drifting apart over time. Ported from the standalone
 * BBS-Minema mod's own {@code util.FolderOpener} -- identical behaviour,
 * just living in this addon's package.
 */
public final class FolderOpener
{
    private FolderOpener()
    {
    }

    /** Returns true if a file manager was successfully asked to open the folder. */
    public static boolean open(Path folder)
    {
        return openWithDesktopApi(folder) || openWithSystemCommand(folder);
    }

    /**
     * Tries {@link Desktop} first, since it works out of the box on Windows and macOS. On many
     * Linux desktops it reports {@code Desktop.Action.OPEN} as unsupported even though a file
     * manager (Nemo, Nautilus, Dolphin, ...) is installed and working fine, so a "no" here isn't
     * treated as final -- {@link #openWithSystemCommand} is tried next.
     */
    private static boolean openWithDesktopApi(Path folder)
    {
        try
        {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
            {
                return false;
            }

            Desktop.getDesktop().open(folder.toFile());

            return true;
        }
        catch (Exception exception)
        {
            return false;
        }
    }

    /**
     * Falls back to the platform's own "open a folder" command. On Linux this is {@code xdg-open},
     * which delegates to whatever file manager the user has set as default, rather than relying on
     * AWT's often-missing native integration.
     */
    private static boolean openWithSystemCommand(Path folder)
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] command = os.contains("win") ? new String[] { "explorer.exe", folder.toString() }
                : os.contains("mac") ? new String[] { "open", folder.toString() }
                : new String[] { "xdg-open", folder.toString() };

        try
        {
            new ProcessBuilder(command).start();

            return true;
        }
        catch (IOException exception)
        {
            return false;
        }
    }
}
