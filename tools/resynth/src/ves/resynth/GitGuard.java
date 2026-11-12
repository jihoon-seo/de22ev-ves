package ves.resynth;

import java.io.File;
import java.io.IOException;

/**
 * Private outputs never land in a git worktree: a path is refused when it, or any folder above it, holds a
 * {@code .git} entry (a folder, or the file a linked worktree has). Main exits with 2 on a refusal.
 */
public final class GitGuard {
    private GitGuard() {
    }

    public static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(String msg) {
            super(msg);
        }
    }

    /** the folder holding .git above (or at) the path, or null */
    public static File worktree(File path) {
        File f;
        try {
            f = path.getAbsoluteFile().getCanonicalFile();
        } catch (IOException e) {
            f = path.getAbsoluteFile();
        }
        for (File d = f; d != null; d = d.getParentFile()) if (new File(d, ".git").exists()) return d;
        return null;
    }

    /** throws Refused if the path is inside a git worktree */
    public static void check(File path) throws Refused {
        File w = worktree(path);
        if (w != null)
            throw new Refused("refusing to write " + path + ": it is inside the git worktree " + w
                    + " (recordings and everything derived from them stay out of git)");
    }
}
