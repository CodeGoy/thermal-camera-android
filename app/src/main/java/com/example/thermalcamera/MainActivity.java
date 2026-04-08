package com.example.thermalcamera;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("thermalcamera");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tv = findViewById(R.id.sample_text);
        String version = getLibUvcVersion();
        tv.setText("libuvc version: " + version);
    }

    /** Returns the libuvc version string (implemented in native-lib.cpp). */
    public native String getLibUvcVersion();

    /** Initialises a libuvc context (implemented in native-lib.cpp). */
    public native boolean initUvc();
}
