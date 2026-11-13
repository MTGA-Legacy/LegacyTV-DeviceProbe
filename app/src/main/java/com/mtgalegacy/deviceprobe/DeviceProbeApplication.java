package com.mtgalegacy.deviceprobe;

import android.app.Application;

public final class DeviceProbeApplication extends Application {
    private ProbeRuntime runtime;

    @Override
    public void onCreate() {
        super.onCreate();
        runtime = new ProbeRuntime(this);
        runtime.start();
    }

    public ProbeRuntime getRuntime() {
        return runtime;
    }
}
