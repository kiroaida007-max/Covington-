package com.example.fixtures;

/**
 * Hidden ground truth for the V0 method laboratory. Only the benchmark harness reads this file;
 * recovery jobs see nothing but the compiled APK.
 */
public class Pure {
    public static int twice(int x) {
        return x + x;
    }

    public static int doubled(int x) {
        return x * 2;
    }

    public static int abs(int x) {
        return x < 0 ? -x : x;
    }

    public static int max(int a, int b) {
        return a >= b ? a : b;
    }

    public static int min(int a, int b) {
        if (a < b) {
            return a;
        }
        return b;
    }

    public static int clamp(int x, int lo, int hi) {
        if (x < lo) {
            return lo;
        }
        if (x > hi) {
            return hi;
        }
        return x;
    }

    public static int sign(int x) {
        if (x > 0) {
            return 1;
        }
        if (x < 0) {
            return -1;
        }
        return 0;
    }

    public static int isNegative(int x) {
        return x < 0 ? 1 : 0;
    }

    public static int increment(int x) {
        return x == Integer.MAX_VALUE ? Integer.MIN_VALUE : x + 1;
    }

    public static int answer() {
        return 0x7f000000 + 42;
    }

    public static int scramble(int x) {
        return x ^ 0x12345678;
    }

    public static int mix(int a, int b) {
        return (a * 31 + b) ^ (a >>> 3) & 0xff;
    }

    public static int rotate(int x, int s) {
        return (x << s) | (x >>> (32 - s));
    }

    public static int divide(int a, int b) {
        return a / b;
    }

    public static int remainder(int a, int b) {
        return a % b;
    }

    public static int safeDivide(int a, int b) {
        return b == 0 ? 0 : a / b;
    }

    public static int average(int a, int b) {
        return (a + b) >>> 1;
    }

    public static int narrow(int x) {
        return (byte) x + (short) (x >> 8) + (char) x;
    }

    public static int poly(int x) {
        return 3 * x * x - 7 * x + 11;
    }

    public static int nestedChoice(int a, int b, int c) {
        int r;
        if (a > b) {
            if (b > c) {
                r = b;
            } else {
                r = a > c ? c : a;
            }
        } else {
            r = a + b + c;
        }
        return r * 2;
    }

    public static int bitCount(int x) {
        int y = x - ((x >>> 1) & 0x55555555);
        y = (y & 0x33333333) + ((y >>> 2) & 0x33333333);
        return ((y + (y >>> 4) & 0x0f0f0f0f) * 0x01010101) >>> 24;
    }
}
