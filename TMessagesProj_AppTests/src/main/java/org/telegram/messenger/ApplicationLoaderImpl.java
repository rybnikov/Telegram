package org.telegram.messenger;

import android.os.Handler;

import org.telegram.tgnet.ConnectionsManager;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected boolean isAndroidTestEnv() {
        return true;
    }

    @Override
    public void onCreate() {
        applicationLoaderInstance = this;
        applicationContext = getApplicationContext();

        NativeLoader.initNativeLibs(applicationContext);
        try {
            ConnectionsManager.native_setJava(false);
        } catch (UnsatisfiedLinkError error) {
            throw new RuntimeException("can't load native libraries", error);
        }
        applicationHandler = new Handler(applicationContext.getMainLooper());
    }
}
