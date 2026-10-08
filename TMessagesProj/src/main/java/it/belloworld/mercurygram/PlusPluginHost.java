package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;

/** plus f11: what the engine hands a code plugin in {@link PlusPlugin#onLoad}. */
public final class PlusPluginHost {

    private final String id;

    PlusPluginHost(String id) {
        this.id = id;
    }

    /** The plugin id from its manifest. */
    public String getId() {
        return id;
    }

    /** Private preferences for this plugin (file plus_f11_p_&lt;id&gt;). */
    public SharedPreferences getPreferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("plus_f11_p_" + id, Context.MODE_PRIVATE);
    }

    /** Private data folder for this plugin; removed when the plugin is deleted. */
    public File getDataDir() {
        File dir = new File(PlusPlugins.dataRoot(), id);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Write a line to the app log (only when logs are enabled). */
    public void log(String message) {
        FileLog.d("plus f11 [" + id + "] " + message);
    }
}
