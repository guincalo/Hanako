package it.belloworld.mercurygram;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * plus f17: custom DNS-over-HTTPS resolver.
 *
 * Speaks RFC 8484 wire format (GET ?dns=base64url, application/dns-message), which every public
 * DoH server supports, unlike the JSON API the stock code uses (Cloudflare/Google only).
 * Used by two ConnectionsManager paths:
 *  - MozillaDnsLoadTask: the TXT lookup of the DC config fallback (dcDomainName), and
 *  - ResolveHostByNameTask: native getHostByName (proxy host names etc.), instead of the system resolver.
 * Every method returns null when the option is off, Tor owns networking, or the lookup failed, so the
 * caller falls back to its stock path. Only the resolver is contacted, never Telegram (ghost-safe).
 */
public final class PlusDoh {

    private static final String PREFS = "plus_f17";
    private static final String KEY_ENABLED = "doh_enabled";
    private static final String KEY_URL = "doh_url";

    public static final String DEFAULT_URL = "https://cloudflare-dns.com/dns-query";
    // HttpURLConnection speaks HTTP/1.1 only, so HTTP/2-only servers (Quad9, Mullvad) can't be listed.
    // The 1.1.1.1 entry needs no system DNS lookup to reach the resolver itself.
    public static final String[] PRESET_NAMES = {"Cloudflare", "Cloudflare (1.1.1.1)", "Google", "AdGuard (unfiltered)", "NextDNS"};
    public static final String[] PRESET_URLS = {
            "https://cloudflare-dns.com/dns-query",
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "https://unfiltered.adguard-dns.com/dns-query",
            "https://dns.nextdns.io/dns-query",
    };

    private static final int TYPE_A = 1;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_AAAA = 28;
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE = 64 * 1024;

    private PlusDoh() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String getUrl() {
        String url = prefs().getString(KEY_URL, null);
        return TextUtils.isEmpty(url) ? DEFAULT_URL : url;
    }

    /** @return false if the url is not a usable https url (nothing saved then). */
    public static boolean setUrl(String url) {
        String normalized = normalizeUrl(url);
        if (normalized == null) {
            return false;
        }
        prefs().edit().putString(KEY_URL, normalized).apply();
        return true;
    }

    public static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        url = url.trim();
        if (url.isEmpty()) {
            return null;
        }
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        try {
            URL u = new URL(url);
            if (!"https".equalsIgnoreCase(u.getProtocol()) || TextUtils.isEmpty(u.getHost())) {
                return null;
            }
            if (TextUtils.isEmpty(u.getPath()) || "/".equals(u.getPath())) {
                // bare host given: RFC 8484 servers conventionally answer on /dns-query
                url = url.endsWith("/") ? url + "dns-query" : url + "/dns-query";
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    /** Short label for the settings row. */
    public static String getLabel() {
        String url = getUrl();
        for (int i = 0; i < PRESET_URLS.length; i++) {
            if (PRESET_URLS[i].equals(url)) {
                return PRESET_NAMES[i];
            }
        }
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean active() {
        return isEnabled() && !SharedConfig.mg_useTor;
    }

    // ---- hooks -------------------------------------------------------------------------------

    /** Decoded DC config from the TXT records of {@code domain}, plus the server date. */
    public static final class TxtConfig {
        public final byte[] bytes;
        public final int date;

        TxtConfig(byte[] bytes, int date) {
            this.bytes = bytes;
            this.date = date;
        }
    }

    /** Replacement for the body of MozillaDnsLoadTask; null = use the stock lookup. */
    public static TxtConfig loadDcConfig(String domain) {
        if (!active() || TextUtils.isEmpty(domain)) {
            return null;
        }
        try {
            Answer answer = query(getUrl(), domain, TYPE_TXT);
            if (answer == null || answer.records.isEmpty()) {
                return null;
            }
            ArrayList<String> parts = new ArrayList<>(answer.records);
            // same order as the stock code: longest chunk first
            Collections.sort(parts, (o1, o2) -> Integer.compare(o2.length(), o1.length()));
            StringBuilder builder = new StringBuilder();
            for (String p : parts) {
                builder.append(p.replace("\"", ""));
            }
            byte[] bytes = Base64.decode(builder.toString(), Base64.DEFAULT);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            int date = answer.date > 0 ? answer.date : (int) (System.currentTimeMillis() / 1000);
            return new TxtConfig(bytes, date);
        } catch (Throwable e) {
            FileLog.e(e, false);
            return null;
        }
    }

    /** Replacement for InetAddress.getAllByName in ResolveHostByNameTask; null = use the system resolver. */
    public static ArrayList<String> resolveHost(String host) {
        if (!active() || TextUtils.isEmpty(host) || isIpLiteral(host) || "localhost".equalsIgnoreCase(host)) {
            return null;
        }
        try {
            String url = getUrl();
            Answer answer = query(url, host, TYPE_A);
            if (answer == null || answer.records.isEmpty()) {
                answer = query(url, host, TYPE_AAAA);
            }
            if (answer == null || answer.records.isEmpty()) {
                return null;
            }
            return new ArrayList<>(answer.records);
        } catch (Throwable e) {
            FileLog.e(e, false);
            return null;
        }
    }

    /** Blocking test of a resolver url (call off the UI thread). Returns an address or null. */
    public static String test(String url) {
        try {
            Answer answer = query(url, "telegram.org", TYPE_A);
            return answer == null || answer.records.isEmpty() ? null : answer.records.get(0);
        } catch (Throwable e) {
            FileLog.e(e, false);
            return null;
        }
    }

    // ---- wire format -------------------------------------------------------------------------

    private static final class Answer {
        final ArrayList<String> records = new ArrayList<>();
        int date;
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    private static Answer query(String url, String name, int type) throws Exception {
        byte[] msg = buildQuery(name, type);
        String dns = Base64.encodeToString(msg, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        URL u = new URL(url + (url.indexOf('?') >= 0 ? "&" : "?") + "dns=" + dns);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        InputStream in = null;
        try {
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.addRequestProperty("Accept", "application/dns-message");
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return null;
            }
            in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                if (out.size() > MAX_RESPONSE) {
                    return null;
                }
            }
            Answer answer = parse(out.toByteArray(), type);
            if (answer != null) {
                answer.date = (int) (conn.getDate() / 1000);
            }
            return answer;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignore) {
            }
            conn.disconnect();
        }
    }

    static byte[] buildQuery(String name, int type) {
        ByteArrayOutputStream q = new ByteArrayOutputStream(128);
        // header: id 0 (RFC 8484 §4.1), RD, 1 question, 1 additional (EDNS0 OPT for padding)
        q.write(0); q.write(0);
        q.write(0x01); q.write(0x00);
        q.write(0); q.write(1);
        q.write(0); q.write(0);
        q.write(0); q.write(0);
        q.write(0); q.write(1);
        String n = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        for (String label : n.split("\\.")) {
            byte[] l = label.toLowerCase(Locale.US).getBytes(StandardCharsets.UTF_8);
            if (l.length == 0 || l.length > 63) {
                throw new IllegalArgumentException("bad label");
            }
            q.write(l.length);
            q.write(l, 0, l.length);
        }
        q.write(0);
        q.write(type >> 8); q.write(type & 0xff);
        q.write(0); q.write(1); // class IN
        // OPT RR: root name, type 41, udp 4096, ext-rcode/version/flags 0, then a padding option
        // sized so the whole message is a multiple of 128 bytes (RFC 8467 block padding).
        int base = q.size() + 11 + 4;
        int pad = (128 - base % 128) % 128;
        q.write(0);
        q.write(0); q.write(41);
        q.write(0x10); q.write(0x00);
        q.write(0); q.write(0); q.write(0); q.write(0);
        int rdlen = 4 + pad;
        q.write(rdlen >> 8); q.write(rdlen & 0xff);
        q.write(0); q.write(12); // option code 12 = padding
        q.write(pad >> 8); q.write(pad & 0xff);
        for (int i = 0; i < pad; i++) {
            q.write(0);
        }
        return q.toByteArray();
    }

    static Answer parse(byte[] r, int wantType) throws Exception {
        if (r.length < 12) {
            return null;
        }
        if ((r[3] & 0x0f) != 0) {
            return null; // rcode != NOERROR
        }
        int qd = u16(r, 4);
        int an = u16(r, 6);
        int pos = 12;
        for (int i = 0; i < qd; i++) {
            pos = skipName(r, pos) + 4;
        }
        Answer answer = new Answer();
        for (int i = 0; i < an; i++) {
            pos = skipName(r, pos);
            if (pos + 10 > r.length) {
                break;
            }
            int type = u16(r, pos);
            int rdlen = u16(r, pos + 8);
            int rd = pos + 10;
            pos = rd + rdlen;
            if (pos > r.length) {
                break;
            }
            if (type != wantType) {
                continue; // CNAMEs etc.
            }
            if (type == TYPE_A && rdlen == 4 || type == TYPE_AAAA && rdlen == 16) {
                byte[] addr = new byte[rdlen];
                System.arraycopy(r, rd, addr, 0, rdlen);
                answer.records.add(InetAddress.getByAddress(addr).getHostAddress());
            } else if (type == TYPE_TXT) {
                StringBuilder sb = new StringBuilder();
                int p = rd;
                while (p < rd + rdlen) {
                    int len = r[p] & 0xff;
                    if (p + 1 + len > rd + rdlen) {
                        break;
                    }
                    sb.append(new String(r, p + 1, len, StandardCharsets.US_ASCII));
                    p += 1 + len;
                }
                answer.records.add(sb.toString());
            }
        }
        return answer;
    }

    private static int u16(byte[] b, int off) {
        if (off + 2 > b.length) {
            throw new IndexOutOfBoundsException();
        }
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static int skipName(byte[] b, int pos) {
        while (true) {
            if (pos >= b.length) {
                throw new IndexOutOfBoundsException();
            }
            int len = b[pos] & 0xff;
            if ((len & 0xc0) == 0xc0) {
                return pos + 2;
            }
            if (len == 0) {
                return pos + 1;
            }
            pos += 1 + len;
        }
    }
}
