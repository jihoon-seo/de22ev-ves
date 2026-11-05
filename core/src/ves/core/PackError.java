package ves.core;

/** A sound pack that can't be used, with the reason (shown in the app and the status line). */
public final class PackError extends Exception {
    private static final long serialVersionUID = 1L;

    public PackError(String why) {
        super(why);
    }
}
