package com.example.fixtures;

/**
 * Hidden ground truth mixing in-scope methods with constructs V0 does not model. Expected: the
 * in-scope methods are recovered and every other method is reported with its capability gap.
 */
public class Mixed {
    static int counter;

    public static int twice(int x) {
        return x + x;
    }

    public static int guarded(int x) {
        return x > 100 ? 100 : x;
    }

    public static int sumTo(int n) {
        int s = 0;
        for (int i = 1; i <= n; i++) {
            s += i;
        }
        return s;
    }

    public static long widen(int x) {
        return (long) x * 3L;
    }

    public static int callsOther(int x) {
        return Math.abs(x) + 1;
    }

    public static int readsField(int x) {
        return x + counter;
    }

    public int instance(int x) {
        return x * counter;
    }

    public static int choose(int k) {
        switch (k) {
            case 0: return 7;
            case 1: return 9;
            case 2: return 13;
            case 3: return 21;
            case 4: return 34;
            case 5: return 55;
            case 6: return 89;
            case 7: return 144;
            case 8: return 233;
            case 9: return 377;
            default: return -1;
        }
    }
}
