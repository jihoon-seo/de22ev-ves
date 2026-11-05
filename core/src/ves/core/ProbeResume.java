package ves.core;

/**
 * What the car-check screen does when it comes back to the front (pure logic, host-tested). In car check 2
 * the voice-recognition screen covered it during the 60 s hold; the hold's result went to the old screen
 * instance and the step never advanced.
 * <ul>
 * <li>REATTACH: a hold step whose hold vesd is still running: show "running" and wait for its end;</li>
 * <li>ADVANCE: the step's command finished while the screen was away (its result was saved): go on;</li>
 * <li>SHOW: show the saved step as it is.</li>
 * </ul>
 */
public final class ProbeResume {
    public static final int SHOW = 0, REATTACH = 1, ADVANCE = 2;

    private ProbeResume() {}

    /**
     * @param step   the saved step id (null: none)
     * @param status vesd's STATUS answer (null or "error ...": unknown)
     * @param result the saved result of that step's command, if it finished while away (null: none)
     */
    public static int decide(String step, String status, String result) {
        if (step == null) return SHOW;
        boolean hold = step.equals("hold") || step.equals("chime") || step.startsWith("pack_");
        if (!hold) return SHOW;
        if (status != null && status.contains("running hold")) return REATTACH;
        if (result != null && !result.startsWith("error")) return ADVANCE;
        return SHOW;
    }
}
