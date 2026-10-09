// Ground-truth input for decoder tests. Compiled by scripts/regen-d8-fixtures.sh with
// javac (JDK 17, --release 8) and D8 8.7.18 (--release --min-api 28); the resulting
// broad.dex is checked in so decoder tests do not depend on the SDK at test time.
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class Broad implements Runnable {
    static int counter;
    static long wide = 0x123456789abcdefL;
    int field;
    double d = 2.5;

    interface Shape { int area(); }

    static final class Square implements Shape {
        final int side;
        Square(int side) { this.side = side; }
        public int area() { return side * side; }
    }

    public void run() { counter++; }

    static int arith(int a, int b) {
        int x = a + b; x -= a * 3; x *= b; x /= (b | 1); x %= 7;
        x &= 0xff; x |= a; x ^= b; x <<= 2; x >>= 1; x >>>= 3;
        return -x + ~a + (a + 1000) + (b * 100000);
    }

    static long longs(long a, long b, int s) {
        return (a + b) * (a - b) / (b | 1) % 13 & a | b ^ (a << s) ^ (b >> s) ^ (a >>> s) ^ -a ^ ~b;
    }

    static double floats(float f, double d) {
        float g = f * 2.5f + f / 3f - f % 2f;
        double e = d * g - d / 7.0 + d % 3.0 - (-d);
        return e + (int) g + (long) e + (float) e + (f < g ? 1 : 0) + (d > e ? 1 : 0);
    }

    static int conversions(long l, double d, int i) {
        return (int) l + (int) d + (byte) i + (char) i + (short) i + (int) (float) l + (int) (long) d;
    }

    static int arrays(int n) {
        int[] a = new int[n + 1];
        a[0] = 7;
        int[] b = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        long[] c = {1L, -2L, 300000000000L};
        byte[] bytes = {1, -1, 127};
        char[] chars = {'a', 'b'};
        short[] shorts = {-3, 3};
        boolean[] bools = new boolean[2];
        String[] s = new String[]{"x", "y"};
        int[][] grid = new int[2][3];
        bools[1] = true;
        Object[] o = s;
        return a[0] + b.length + (int) c[2] + bytes[1] + chars[1] + shorts[0] + (bools[1] ? 1 : 0) + o.length + grid[1].length;
    }

    static int switches(int k, int j) {
        int r = 0;
        switch (k) {
            case 0: r = 10; break;
            case 1: r = 21; break;
            case 2: r = 32; break;
            case 3: r = 43; break;
            case 4: r = 54; break;
            case 5: r = 65; break;
            case 6: r = 76; break;
            case 7: r = 87; break;
            default: r = -1;
        }
        switch (j) {
            case -100: return r + 1;
            case 7: return r * 2;
            case 1000: return r - 3;
            case 100000: return r ^ 4;
            case 2000000: return r | 5;
            case 30000000: return r & 6;
            case 400000000: return r << 7;
        }
        return r;
    }

    static String strings(Object o, int i) {
        if (o instanceof String) return ((String) o).trim();
        Class<?> c = Broad.class;
        return "value: " + i + c.getName();
    }

    static int tryCatch(int a, int b) {
        try {
            return a / b;
        } catch (ArithmeticException e) {
            return -1;
        } catch (RuntimeException e) {
            return -2;
        } finally {
            counter++;
        }
    }

    synchronized int locked() {
        synchronized (Broad.class) { return field++; }
    }

    int virtual(Shape s, Runnable r) {
        r.run();
        int v = s.area() + field + (int) d + super.hashCode() + Math.max(field, 3);
        field = v;
        d = d * 2;
        wide = wide + v;
        return v;
    }

    static int manyArgs(int a, int b, int c, int d, int e, int f, int g) {
        return a + b + c + d + e + f + g + manyArgs2(a, b, c, d, e, f, g, a);
    }

    static int manyArgs2(int a, int b, int c, int d, int e, int f, int g, int h) { return h; }

    static int loops(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) { s += i; if (s > 1000) break; }
        while (s > 10) s /= 2;
        return s;
    }

    static Object handles() throws Throwable {
        MethodHandle h = MethodHandles.lookup().findStatic(Broad.class, "switches", MethodType.methodType(int.class, int.class, int.class));
        int r = (int) h.invokeExact(3, 4);
        return r;
    }

    static int constants() {
        int a = 5; int b = 200; int c = 70000; int d = 0x7f000000; int e = -1;
        long f = 3L; long g = 70000L; long h = 0x7ff0000000000000L; long i = 0x123456789L;
        return a + b + c + d + e + (int) (f + g + h + i);
    }
}
