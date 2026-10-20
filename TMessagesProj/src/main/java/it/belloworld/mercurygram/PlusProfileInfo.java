package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

/**
 * plus f14: data centre and approximate registration date on profiles.
 *
 * Everything here is computed from data already cached on the device (the peer's photo dc_id,
 * the cached UserFull/ChatFull, and a static id -> date table). No network requests are made,
 * so this is ghost-safe.
 */
public final class PlusProfileInfo {

    private static final String PREFS = "plus_f14";
    private static final String KEY_SHOW_DC = "show_dc";
    private static final String KEY_SHOW_REG_DATE = "show_reg_date";

    private PlusProfileInfo() {
    }

    // ---------------------------------------------------------------- settings

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isShowDc() {
        return prefs().getBoolean(KEY_SHOW_DC, true);
    }

    public static void setShowDc(boolean value) {
        prefs().edit().putBoolean(KEY_SHOW_DC, value).apply();
    }

    public static boolean isShowRegDate() {
        return prefs().getBoolean(KEY_SHOW_REG_DATE, true);
    }

    public static void setShowRegDate(boolean value) {
        prefs().edit().putBoolean(KEY_SHOW_REG_DATE, value).apply();
    }

    // ---------------------------------------------------------------- data centre

    /**
     * Returns the data centre (1..5) of a user or chat, or 0 when unknown.
     * Users: own account = the account's home DC; others = dc_id of their public profile photo
     * (a "personal" photo set by us is skipped because it lives on our DC).
     * Chats/channels: dc_id of the chat photo. Peers without a photo have no known DC.
     */
    public static int getDc(int account, long userId, long chatId) {
        try {
            MessagesController mc = MessagesController.getInstance(account);
            if (userId != 0) {
                if (userId == UserConfig.getInstance(account).getClientUserId()) {
                    int dc = ConnectionsManager.getInstance(account).getCurrentDatacenterId();
                    if (dc > 0) {
                        return dc;
                    }
                }
                TLRPC.User user = mc.getUser(userId);
                if (user != null && user.photo != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty)
                        && !user.photo.personal && user.photo.dc_id > 0) {
                    return user.photo.dc_id;
                }
                TLRPC.UserFull full = mc.getUserFull(userId);
                if (full != null && full.profile_photo != null && !(full.profile_photo instanceof TLRPC.TL_photoEmpty)
                        && full.profile_photo.dc_id > 0) {
                    return full.profile_photo.dc_id;
                }
            } else if (chatId != 0) {
                TLRPC.Chat chat = mc.getChat(chatId);
                if (chat != null && chat.photo != null && !(chat.photo instanceof TLRPC.TL_chatPhotoEmpty) && chat.photo.dc_id > 0) {
                    return chat.photo.dc_id;
                }
                TLRPC.ChatFull full = mc.getChatFull(chatId);
                if (full != null && full.chat_photo != null && !(full.chat_photo instanceof TLRPC.TL_photoEmpty)
                        && full.chat_photo.dc_id > 0) {
                    return full.chat_photo.dc_id;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    /** Short location code for a production DC ("MIA", "AMS", "SIN"), or "" when unknown. */
    public static String dcShort(int dc) {
        switch (dc) {
            case 1:
            case 3:
                return "MIA";
            case 2:
            case 4:
                return "AMS";
            case 5:
                return "SIN";
            default:
                return "";
        }
    }

    /** Long location name for a production DC, or "" when unknown. */
    public static String dcCity(int dc) {
        switch (dc) {
            case 1:
            case 3:
                return LocaleController.getString(R.string.PlusF14DcMiami);
            case 2:
            case 4:
                return LocaleController.getString(R.string.PlusF14DcAmsterdam);
            case 5:
                return LocaleController.getString(R.string.PlusF14DcSingapore);
            default:
                return "";
        }
    }

    // ---------------------------------------------------------------- registration date

    /**
     * Calibration points: user id -> approximate registration time (unix seconds).
     * Sorted by id. Taken from the community dataset used by Nagram / FAgram Desktop
     * (fa_profile_values.cpp). A few points in the source go backwards in time; the static
     * block below clamps them so the interpolation is monotonic.
     */
    private static final long[][] REG_POINTS = {
            {1000000L, 1376438400L},    // 2013-08
            {2768409L, 1383264000L},    // 2013-11
            {7679610L, 1388448000L},    // 2013-12
            {11538514L, 1391212000L},   // 2014-02
            {15835244L, 1392940000L},   // 2014-02
            {23646077L, 1393459000L},   // 2014-02
            {38015510L, 1393632000L},   // 2014-03
            {44634663L, 1399334000L},   // 2014-05
            {46145305L, 1400198000L},   // 2014-05
            {54845238L, 1411257000L},   // 2014-09
            {63263518L, 1414454000L},   // 2014-10
            {101260938L, 1425600000L},  // 2015-03
            {101323197L, 1426204000L},  // 2015-03
            {103151531L, 1433376000L},  // 2015-06
            {109393468L, 1439078000L},  // 2015-08
            {125828524L, 1444003000L},  // 2015-10
            {143445125L, 1448928000L},  // 2015-12
            {148670295L, 1452211000L},  // 2016-01
            {152079341L, 1453420000L},  // 2016-01
            {171295414L, 1457481000L},  // 2016-03
            {181783990L, 1460246000L},  // 2016-04
            {222021233L, 1465344000L},  // 2016-06
            {278941742L, 1473465000L},  // 2016-09
            {285253072L, 1476835000L},  // 2016-10
            {294851037L, 1479600000L},  // 2016-11
            {328594461L, 1482969000L},  // 2016-12
            {337808429L, 1487707000L},  // 2017-02
            {369669043L, 1490918000L},  // 2017-03
            {400169472L, 1501459000L},  // 2017-07
            {616816630L, 1529625600L},  // 2018-06
            {681896077L, 1532821500L},  // 2018-07
            {727572658L, 1543708800L},  // 2018-12
            {925078064L, 1563290000L},  // 2019-07
            {1054883348L, 1585674420L}, // 2020-03
            {1145856008L, 1586342040L}, // 2020-04
            {1227964864L, 1596127860L}, // 2020-07
            {1382531194L, 1600188120L}, // 2020-09
            {1658586909L, 1613148540L}, // 2021-02
            {1719536397L, 1619293500L}, // 2021-04
            {1807942741L, 1625520300L}, // 2021-07
            {1972424006L, 1631669400L}, // 2021-09
            {2104178931L, 1638353220L}, // 2021-12
            {5162494923L, 1652449800L}, // 2022-05
            {5304951856L, 1656718440L}, // 2022-07
            {5387234031L, 1662137700L}, // 2022-09
            {5802242180L, 1671821040L}, // 2022-12
            {5853442730L, 1674866100L}, // 2023-01
            {6020888206L, 1675534800L}, // 2023-02
            {6132325730L, 1692033840L}, // 2023-08
            {6338817029L, 1705536000L}, // 2024-01
            {6739267230L, 1704067200L}, // 2024-01 (clamped)
            {6957108444L, 1713312000L}, // 2024-04
            {7100000000L, 1720224000L}, // 2024-07
            {7229898489L, 1723075200L}, // 2024-08
            {7600158321L, 1733356800L}, // 2024-12
            {7851389063L, 1733097600L}, // 2024-12 (clamped)
            {7857659678L, 1727222400L}, // 2024-09 (clamped)
            {7884373548L, 1732233600L}, // 2024-11 (clamped)
            {8060910775L, 1736294400L}, // 2025-01
            {8089817806L, 1736899200L}, // 2025-01
            {8454563873L, 1764979200L}, // 2025-12 (source also lists 8461412540 -> 2025-08; dropped, clamping would ignore it)
    };

    static {
        long max = 0;
        for (long[] p : REG_POINTS) {
            if (p[1] < max) {
                p[1] = max;
            } else {
                max = p[1];
            }
        }
    }

    public static final int REG_EXACT_POINT = 0;
    public static final int REG_APPROX = 1;
    public static final int REG_BEFORE = 2;
    public static final int REG_AFTER = 3;

    /**
     * Estimates registration time for a user id. Returns {kind, unixSeconds} where kind is one of
     * REG_*; or null for non-positive ids.
     */
    public static long[] estimateRegistration(long userId) {
        if (userId <= 0) {
            return null;
        }
        final int n = REG_POINTS.length;
        if (userId < REG_POINTS[0][0]) {
            return new long[]{REG_BEFORE, REG_POINTS[0][1]};
        }
        if (userId >= REG_POINTS[n - 1][0]) {
            return new long[]{userId == REG_POINTS[n - 1][0] ? REG_EXACT_POINT : REG_AFTER, REG_POINTS[n - 1][1]};
        }
        int lo = 0, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (REG_POINTS[mid][0] <= userId) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        long[] a = REG_POINTS[lo];
        long[] b = REG_POINTS[hi];
        if (a[0] == userId) {
            return new long[]{REG_EXACT_POINT, a[1]};
        }
        double ratio = (double) (userId - a[0]) / (double) (b[0] - a[0]);
        long ts = a[1] + (long) (ratio * (double) (b[1] - a[1]));
        return new long[]{REG_APPROX, ts};
    }

    /** "~ Mar 2017", "< Aug 2013", "> Dec 2025"; "" for ids that are not users. */
    public static String formatRegistration(long userId) {
        long[] est = estimateRegistration(userId);
        if (est == null) {
            return "";
        }
        String monthYear;
        try {
            monthYear = LocaleController.getInstance().getFormatterMonthYear().format(est[1] * 1000L);
        } catch (Exception e) {
            FileLog.e(e);
            return "";
        }
        switch ((int) est[0]) {
            case REG_BEFORE:
                return "< " + monthYear;
            case REG_AFTER:
                return "> " + monthYear;
            default:
                return "~ " + monthYear;
        }
    }

    // ---------------------------------------------------------------- profile row

    private static final String MASKED_ID = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";

    /**
     * Streamer mode (f10) hides this peer's name or title, so the ID row hides the ID, DC and
     * registration date as well (they identify the peer just as well). Your own profile stays visible.
     */
    public static boolean streamerMasked(int account, long userId, long chatId) {
        if (userId != 0) {
            return userId != UserConfig.getInstance(account).getClientUserId() && PlusStreamer.hidesDialog(userId);
        }
        return chatId != 0 && PlusStreamer.hidesDialog(-chatId);
    }

    /** First line of the profile ID row: the ID, or dots in streamer mode. */
    public static String idRowText(int account, long userId, long chatId, long displayId) {
        return streamerMasked(account, userId, chatId) ? MASKED_ID : String.valueOf(displayId);
    }

    /**
     * Second line of the profile ID row: "ID", optionally followed by " · DC 2 (AMS)" and
     * " · ~ Mar 2017" depending on the settings and on what is known for the peer.
     */
    public static String idRowSubtitle(int account, long userId, long chatId) {
        StringBuilder sb = new StringBuilder("ID");
        if (streamerMasked(account, userId, chatId)) {
            return sb.toString();
        }
        try {
            if (isShowDc()) {
                int dc = getDc(account, userId, chatId);
                if (dc > 0) {
                    sb.append(" · DC ").append(dc);
                    String code = dcShort(dc);
                    if (code.length() > 0) {
                        sb.append(" (").append(code).append(')');
                    }
                }
            }
            if (isShowRegDate() && userId > 0) {
                String reg = formatRegistration(userId);
                if (reg.length() > 0) {
                    sb.append(" · ").append(reg);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return sb.toString();
    }

    /**
     * Long-press on the profile ID row: a small menu to copy the ID, the Bot API style ID for
     * chats, the DC and the registration estimate. Always returns true (handled).
     */
    public static boolean showIdMenu(BaseFragment fragment, int account, long userId, long chatId, long displayId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return false;
        }
        if (streamerMasked(account, userId, chatId)) {
            return true; // the menu would show what streamer mode hides
        }
        final ArrayList<CharSequence> labels = new ArrayList<>();
        final ArrayList<String> values = new ArrayList<>();

        String idText = String.valueOf(displayId);
        labels.add(LocaleController.formatString(R.string.PlusF14CopyId, idText));
        values.add(idText);

        if (chatId != 0) {
            String raw = String.valueOf(chatId);
            if (!raw.equals(idText)) {
                labels.add(LocaleController.formatString(R.string.PlusF14CopyRawId, raw));
                values.add(raw);
            }
        }

        int dc = getDc(account, userId, chatId);
        String dcText = null;
        if (dc > 0) {
            String city = dcCity(dc);
            dcText = "DC " + dc + (city.length() > 0 ? ", " + city : "");
            labels.add(LocaleController.formatString(R.string.PlusF14CopyDc, dcText));
            values.add(dcText);
        }

        String reg = userId > 0 ? formatRegistration(userId) : "";
        if (reg.length() > 0) {
            labels.add(LocaleController.formatString(R.string.PlusF14CopyRegDate, reg));
            values.add(reg);
        }

        StringBuilder all = new StringBuilder();
        all.append("ID: ").append(idText);
        if (dcText != null) {
            all.append('\n').append(dcText);
        }
        if (reg.length() > 0) {
            all.append('\n').append(LocaleController.getString(R.string.PlusF14RegDate)).append(": ").append(reg);
        }
        if (labels.size() > 1) {
            labels.add(LocaleController.getString(R.string.PlusF14CopyAll));
            values.add(all.toString());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.PlusF14IdMenuTitle));
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        builder.setItems(items, (dialog, which) -> {
            if (which < 0 || which >= values.size()) {
                return;
            }
            try {
                AndroidUtilities.addToClipboard(values.get(which));
                BulletinFactory.of(fragment).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        fragment.showDialog(builder.create());
        return true;
    }
}
