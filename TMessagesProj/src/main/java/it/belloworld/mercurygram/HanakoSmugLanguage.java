package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.File;
import java.io.InputStream;

/**
 * hanako: the bundled "Smug" language. A joke translation of the most visible UI strings
 * into a bratty, smug, tsundere voice, shipped as a Telegram-format local language file
 * (assets/hanako/smug.xml).
 *
 * It rides on LocaleController's existing local-language support (the same path as an
 * imported .xml language file): the asset is copied to files/hanako_smug.xml once per
 * install/update and registered as a built-in {@link LocaleController.LocaleInfo} whose
 * pathToFile points at it, so it is listed in Settings > Language as "Smug" and survives
 * restarts via the normal "language" preference (key "local_smug").
 *
 * Untranslated keys fall back to English: the locale's pluralLangCode is "en", so Android
 * resources (and plural rules) resolve to the default English values. The server only ever
 * sees "en" as the language code (see {@link LocaleController.LocaleInfo#getLangCode()}).
 * Built-in, so it cannot be deleted from the language list.
 */
public final class HanakoSmugLanguage {

    public static final String SHORT_NAME = "smug";
    private static final String ASSET = "hanako/smug.xml";
    private static final String FILE = "hanako_smug.xml";
    private static final String PREF_INSTALLED = "hanako_smug_installed";

    private HanakoSmugLanguage() {
    }

    public static LocaleController.LocaleInfo create() {
        LocaleController.LocaleInfo info = new LocaleController.LocaleInfo();
        info.name = "Smug";
        info.nameEnglish = "Smug English";
        info.shortName = SHORT_NAME;
        info.pluralLangCode = "en";
        info.baseLangCode = "";
        info.builtIn = true;
        info.serverLangCode = "en";
        File file = install();
        info.pathToFile = file.getAbsolutePath();
        return info;
    }

    public static boolean isSmug(LocaleController.LocaleInfo info) {
        return info != null && info.isBuiltIn() && SHORT_NAME.equals(info.shortName) && info.isLocal();
    }

    /** Copies the bundled language file into files/ when missing or after an app install/update. */
    private static File install() {
        Context ctx = ApplicationLoader.applicationContext;
        File file = new File(ApplicationLoader.getFilesDirFixed(), FILE);
        try {
            long stamp = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            SharedPreferences prefs = ctx.getSharedPreferences("langconfig", Context.MODE_PRIVATE);
            if (file.exists() && file.length() > 0 && prefs.getLong(PREF_INSTALLED, 0) == stamp) {
                return file;
            }
            try (InputStream in = ctx.getAssets().open(ASSET)) {
                if (AndroidUtilities.copyFile(in, file)) {
                    prefs.edit().putLong(PREF_INSTALLED, stamp).apply();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return file;
    }
}
