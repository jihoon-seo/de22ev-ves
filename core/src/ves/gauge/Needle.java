package ves.gauge;

/** The needle between 10 Hz data points: an exponential follow (tau 70 ms: within 2 % in 300 ms, no overshoot). */
public final class Needle {
    static final double TAU_S = 0.07;
    private double value = Double.NaN;

    public void jump(double v) {
        value = v;
    }

    public double value() {
        return value;
    }

    public double step(double target, double dtSec) {
        if (Double.isNaN(target)) return value;
        if (Double.isNaN(value)) return value = target;
        if (dtSec > 0) value += (target - value) * (1 - Math.exp(-dtSec / TAU_S));
        return value;
    }
}
