package dev.apkproject.runtimefixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Ground truth consumed only by this benchmark's original APK builder. */
public class MainActivity extends Activity {
    public static int calculate(int a, int b) {
        int difference = a - b;
        return (difference > 0 ? difference : 0) * 2 + 4;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.util.Log.i("RuntimeFixture", "session started");
        getSharedPreferences("runtime-fixture", 0).edit().putInt("result", calculate(23, 4)).commit();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    java.net.Socket socket = new java.net.Socket();
                    socket.connect(new java.net.InetSocketAddress("198.51.100.1", 443), 1000);
                    socket.close();
                } catch (java.io.IOException expected) {
                    android.util.Log.i("RuntimeFixture", "network attempt denied");
                }
            }
        }).start();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        final TextView result = new TextView(this);
        result.setText("Result: " + calculate(23, 4));
        Button button = new Button(this);
        button.setText("Calculate");
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                result.setText("Result: " + calculate(24, 4));
                getSharedPreferences("runtime-fixture", 0).edit().putInt("result", calculate(24, 4)).commit();
            }
        });
        layout.addView(result);
        layout.addView(button);
        setContentView(layout);
    }
}
