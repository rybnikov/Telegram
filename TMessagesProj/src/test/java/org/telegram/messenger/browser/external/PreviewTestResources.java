package org.telegram.messenger.browser.external;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.telegram.messenger.BuildVars;

public final class PreviewTestResources {

    private PreviewTestResources() {
    }

    public static String readPreviewFixture(String name) throws Exception {
        InputStream stream = PreviewTestResources.class.getClassLoader().getResourceAsStream("preview/" + name);
        if (stream == null) {
            throw new IllegalArgumentException("Missing preview fixture: " + name);
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
    }

    public static boolean disableLogs() {
        boolean previous = BuildVars.LOGS_ENABLED;
        BuildVars.LOGS_ENABLED = false;
        return previous;
    }

    public static void restoreLogs(boolean enabled) {
        BuildVars.LOGS_ENABLED = enabled;
    }
}
