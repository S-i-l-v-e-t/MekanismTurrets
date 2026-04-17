package com.github.x3r.mekanism_turrets.common.block_entity;

public final class Erf {
    // Abramowitz and Stegun formula 7.1.26
    // https://en.wikipedia.org/wiki/Error_function#Approximation_with_elementary_functions

    public static double erf(double x) {
        // save the sign of x
        int sign = (x < 0) ? -1 : 1;
        x = Math.abs(x);

        // constants
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;
        double p = 0.3275911;

        // calculation
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);

        return sign * y;
    }
}
