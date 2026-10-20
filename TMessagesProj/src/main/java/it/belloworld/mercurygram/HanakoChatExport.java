package it.belloworld.mercurygram;

import android.content.ContentResolver;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.JsonWriter;
import android.webkit.MimeTypeMap;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hanako: export one chat like Telegram Desktop does.
 *
 * <p>History is read oldest-first with messages.getHistory (offset_id + negative add_offset,
 * 100 per page), FLOOD_WAIT honoured; media is fetched with upload.getFile straight from the
 * file's DC (no CDN), so nothing lands in the app cache. Output follows Telegram Desktop's
 * single-chat export (tdesktop Telegram/SourceFiles/export, checked against dev @ d5b208cd):
 * a "ChatExport_YYYY-MM-DD" folder (" (N)" on a clash) inside the SAF tree the user picked,
 * result.json (https://core.telegram.org/import-export) and/or messages.html, messages2.html, ...
 * (1000 messages each) with tdesktop's css/, js/ and images/, media in photos/, video_files/,
 * animations/, stickers/, voice_messages/, round_video_messages/, files/ and contacts/, named
 * photo_N / audio_N / video_N / file_N @DD-MM-YYYY_HH-MM-SS per kind, document thumbnails as
 * "&lt;file&gt;_thumb.jpg", the same seven media types and per-file size limit.
 */
public final class HanakoChatExport {

    public static final class Options {
        public long dialogId;
        /** unix seconds, 0 = from the beginning */
        public int fromDate;
        /** unix seconds, 0 = until now */
        public int toDate;
        /** Export::MediaSettings::Type bits. */
        public static final int PHOTO = 0x01, VIDEO = 0x02, VOICE_MESSAGE = 0x04, VIDEO_MESSAGE = 0x08,
                STICKER = 0x10, GIF = 0x20, FILE = 0x40;
        /** Telegram Desktop's default: photos only. */
        public int types = PHOTO;
        /** bytes, 0 = no limit */
        public long sizeLimit = 8L * 1024 * 1024;
        public boolean html = true;
        public boolean json = true;
    }

    /** Snapshot for the progress UI / notification. */
    public static final class Progress {
        public int messages;
        /** messages expected in the range, 0 = unknown */
        public int total;
        public int media;
        public int failed;
        /** seconds left of a FLOOD_WAIT pause, 0 = not waiting */
        public int waitSeconds;
    }

    public interface Listener {
        void onProgress(Progress progress);

        /** @param exportDir the ChatExport_… folder (null if it was never created) */
        void onFinished(boolean cancelled, Throwable error, Progress progress, Uri exportDir);
    }

    private static final int PAGE = 100;
    private static final int CHUNK = 512 * 1024;
    private static final long REQUEST_TIMEOUT_SEC = 90;
    /** Timed out sends of one request before the export gives up. */
    private static final int MAX_ATTEMPTS = 3;
    /** A longer FLOOD_WAIT ends the export with an error instead of sleeping for hours. */
    public static final int MAX_FLOOD_WAIT_SEC = 10 * 60;
    private static final Pattern FLOOD = Pattern.compile("^FLOOD(?:_PREMIUM)?_WAIT_(\\d+)$");
    private static final Pattern MIGRATE = Pattern.compile("^FILE_MIGRATE_(\\d+)$");

    private static final String NOT_INCLUDED = "(File not included. Change data exporting settings to download.)";
    private static final String TOO_BIG = "(File exceeds maximum size. Change data exporting settings to download.)";
    private static final String FAILED = "(File unavailable, please try again later)";

    private final int account;
    private final Options options;
    private final Uri treeUri;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private ContentResolver resolver;
    private Uri exportDir;
    private final HashMap<String, Uri> subdirs = new HashMap<>();
    private final HashMap<Long, TLRPC.User> users = new HashMap<>();
    private final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
    private JsonWriter json;
    private Writer html;
    private int messageCount;
    private int mediaCount;
    private int failedCount;
    private int totalCount;
    private volatile int waitSeconds;
    private final SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    private final SimpleDateFormat fileFormat = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US);
    private final SimpleDateFormat zoneFormat = new SimpleDateFormat("xxx", Locale.US);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat fullFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US);

    public HanakoChatExport(int account, Options options, Uri treeUri, Listener listener) {
        this.account = account;
        this.options = options;
        this.treeUri = treeUri;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(this::run, "HanakoChatExport");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public long getDialogId() {
        return options.dialogId;
    }

    public int getAccount() {
        return account;
    }

    public Progress snapshot() {
        Progress p = new Progress();
        p.messages = messageCount;
        p.total = totalCount > 0 ? Math.max(totalCount, messageCount) : 0;
        p.media = mediaCount;
        p.failed = failedCount;
        p.waitSeconds = waitSeconds;
        return p;
    }

    public static boolean canExport(long dialogId) {
        return dialogId != 0 && !DialogObject.isEncryptedDialog(dialogId);
    }

    // =====================================================================================

    private void run() {
        Throwable error = null;
        try {
            Context ctx = ApplicationLoader.applicationContext;
            resolver = ctx.getContentResolver();
            MessagesController mc = MessagesController.getInstance(account);
            TLRPC.InputPeer peer = mc.getInputPeer(options.dialogId);
            if (peer == null || !canExport(options.dialogId)) {
                throw new IOException("chat not available");
            }
            Uri root = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            exportDir = DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR,
                    exportFolderName());
            if (exportDir == null) {
                throw new IOException("cannot create export folder");
            }
            openWriters(mc);
            countTotal(peer);
            progress();
            exportHistory(peer);
        } catch (Throwable t) {
            if (!cancelled.get()) {
                FileLog.e(t);
                error = t;
            }
        } finally {
            closeWriters();
        }
        final Throwable err = error;
        final boolean wasCancelled = cancelled.get();
        waitSeconds = 0;
        final Progress p = snapshot();
        final Uri dir = exportDir;
        AndroidUtilities.runOnUIThread(() -> listener.onFinished(wasCancelled, err, p, dir));
    }

    private void progress() {
        final Progress p = snapshot();
        AndroidUtilities.runOnUIThread(() -> listener.onProgress(p));
    }

    /**
     * Messages in the chosen range, for a determinate progress bar: the history count for the
     * whole chat, or a dated messages.search count for a range. 0 when unknown (never fatal).
     */
    private void countTotal(TLRPC.InputPeer peer) {
        try {
            TLRPC.messages_Messages res;
            if (options.fromDate <= 0 && options.toDate <= 0) {
                TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
                req.peer = peer;
                req.limit = 1;
                res = (TLRPC.messages_Messages) call(req, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric);
            } else {
                TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
                req.peer = peer;
                req.q = "";
                req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
                req.min_date = Math.max(0, options.fromDate);
                req.max_date = Math.max(0, options.toDate);
                req.limit = 1;
                res = (TLRPC.messages_Messages) call(req, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric);
            }
            if (res != null) {
                totalCount = res.count > 0 ? res.count : res.messages.size();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            totalCount = 0;
        }
    }

    // =====================================================================================
    // history paging

    private void exportHistory(TLRPC.InputPeer peer) throws Exception {
        int cursor = 1;
        if (options.fromDate > 0) {
            // newest message older than the start date; the export starts right after it
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.offset_date = options.fromDate;
            req.limit = 1;
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) call(req, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric);
            if (res != null && !res.messages.isEmpty()) {
                cursor = res.messages.get(0).id + 1;
            }
        }
        while (!cancelled.get()) {
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.offset_id = cursor;
            req.add_offset = -PAGE;
            req.limit = PAGE;
            TLRPC.messages_Messages res = (TLRPC.messages_Messages) call(req, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric);
            if (res == null) {
                break;
            }
            for (TLRPC.User u : res.users) {
                if (u != null) users.put(u.id, u);
            }
            for (TLRPC.Chat c : res.chats) {
                if (c != null) chats.put(c.id, c);
            }
            ArrayList<TLRPC.Message> page = new ArrayList<>();
            for (TLRPC.Message m : res.messages) {
                if (m != null && m.id >= cursor && !(m instanceof TLRPC.TL_messageEmpty)) page.add(m);
            }
            if (page.isEmpty()) {
                break;
            }
            Collections.sort(page, (a, b) -> Integer.compare(a.id, b.id));
            boolean pastEnd = false;
            for (TLRPC.Message m : page) {
                if (cancelled.get()) return;
                if (options.toDate > 0 && m.date > options.toDate) {
                    pastEnd = true;
                    break;
                }
                if (options.fromDate > 0 && m.date < options.fromDate) {
                    continue;
                }
                writeMessage(m);
                messageCount++;
            }
            progress();
            int maxId = page.get(page.size() - 1).id;
            if (pastEnd || maxId + 1 <= cursor) {
                break;
            }
            cursor = maxId + 1;
        }
    }

    /** Blocking RPC with FLOOD_WAIT handling. Returns null on a non-retryable error. */
    private TLObject call(TLObject req, int dcId, int connectionType) throws Exception {
        int attempts = 0; // timed out sends, each one waited REQUEST_TIMEOUT_SEC
        int migrations = 0;
        while (!cancelled.get()) {
            final CountDownLatch latch = new CountDownLatch(1);
            final TLObject[] response = new TLObject[1];
            final TLRPC.TL_error[] err = new TLRPC.TL_error[1];
            final byte[][] bytes = new byte[1][];
            int flags = connectionType == ConnectionsManager.ConnectionTypeDownload
                    ? ConnectionsManager.RequestFlagForceDownload | ConnectionsManager.RequestFlagFailOnServerErrors
                    : ConnectionsManager.RequestFlagFailOnServerErrors;
            int token = ConnectionsManager.getInstance(account).sendRequest(req, (res, error) -> {
                response[0] = res;
                err[0] = error;
                if (res instanceof TLRPC.TL_upload_file) {
                    // the native buffer is released once this callback returns: copy now
                    TLRPC.TL_upload_file file = (TLRPC.TL_upload_file) res;
                    if (file.bytes != null) {
                        ByteBuffer buf = file.bytes.buffer;
                        int limit = file.bytes.limit();
                        byte[] copy = new byte[limit];
                        ByteBuffer dup = buf.duplicate();
                        dup.position(0);
                        dup.limit(limit);
                        dup.get(copy);
                        bytes[0] = copy;
                    } else {
                        bytes[0] = new byte[0];
                    }
                }
                latch.countDown();
            }, null, null, flags, dcId, connectionType, true);
            boolean done = false;
            int waited = 0; // per attempt: every retry gets the full timeout again
            while (!done && !cancelled.get()) {
                done = latch.await(1, TimeUnit.SECONDS);
                if (!done && ++waited > REQUEST_TIMEOUT_SEC) break;
            }
            if (!done) {
                ConnectionsManager.getInstance(account).cancelRequest(token, true);
                if (cancelled.get()) return null;
                if (++attempts >= MAX_ATTEMPTS) throw new IOException("network timeout");
                continue;
            }
            if (err[0] == null) {
                if (bytes[0] != null) {
                    return new Chunk(bytes[0]);
                }
                return response[0];
            }
            String text = err[0].text != null ? err[0].text : "";
            Matcher flood = FLOOD.matcher(text);
            if (flood.matches()) {
                int seconds = Integer.parseInt(flood.group(1));
                if (seconds > MAX_FLOOD_WAIT_SEC) {
                    // waiting hours would burn the Android dataSync time budget; stop instead
                    throw new FloodWaitTooLongException(seconds);
                }
                floodWait(Math.max(1, seconds));
                continue;
            }
            if (err[0].code == 420) {
                floodWait(5);
                continue;
            }
            Matcher migrate = MIGRATE.matcher(text);
            if (migrate.matches() && req instanceof TLRPC.TL_upload_getFile && ++migrations <= 2) {
                dcId = Integer.parseInt(migrate.group(1));
                continue;
            }
            if (text.startsWith("FILE_REFERENCE_") && req instanceof TLRPC.TL_upload_getFile) {
                throw new FileReferenceExpiredException();
            }
            if (req instanceof TLRPC.TL_upload_getFile) {
                FileLog.d("hanako export: getFile failed " + err[0].code + " " + text);
                return null;
            }
            throw new IOException(text.isEmpty() ? ("error " + err[0].code) : text);
        }
        return null;
    }

    /** upload.getFile answered FILE_REFERENCE_*: the caller re-fetches the message and retries. */
    static final class FileReferenceExpiredException extends IOException {
        FileReferenceExpiredException() {
            super("file reference expired");
        }
    }

    /** Telegram asked to wait longer than MAX_FLOOD_WAIT_SEC. */
    public static final class FloodWaitTooLongException extends IOException {
        public final int seconds;

        FloodWaitTooLongException(int seconds) {
            super("flood wait " + seconds + " s");
            this.seconds = seconds;
        }
    }

    /** upload.getFile result, copied out of the native buffer. */
    private static final class Chunk extends TLObject {
        final byte[] data;

        Chunk(byte[] data) {
            this.data = data;
        }
    }

    /** Telegram asked to slow down: wait, counting the seconds down in the progress UI. */
    private void floodWait(int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L + 500;
        while (!cancelled.get() && System.currentTimeMillis() < end) {
            int left = (int) Math.max(1, (end - System.currentTimeMillis() + 999) / 1000);
            if (left != waitSeconds) {
                waitSeconds = left;
                progress();
            }
            Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
        }
        waitSeconds = 0;
        progress();
    }

    private void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (!cancelled.get() && System.currentTimeMillis() < end) {
            Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    // =====================================================================================
    // SAF output

    private Uri subdir(String name) throws IOException {
        Uri dir = subdirs.get(name);
        if (dir == null) {
            dir = DocumentsContract.createDocument(resolver, exportDir, DocumentsContract.Document.MIME_TYPE_DIR, name);
            if (dir == null) throw new IOException("cannot create " + name);
            subdirs.put(name, dir);
        }
        return dir;
    }

    /** Output::NormalizePath: "ChatExport_YYYY-MM-DD", then " (1)", " (2)", ... if taken. */
    private String exportFolderName() {
        String base = "ChatExport_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        HashSet<String> taken = new HashSet<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            try (Cursor c = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext()) taken.add(c.getString(0));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        String name = base;
        for (int i = 1; taken.contains(name); i++) name = base + " (" + i + ")";
        return name;
    }

    private String displayName(Uri uri, String fallback) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private static String safeName(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (s.length() > 120) {
            int dot = s.lastIndexOf('.');
            String ext = dot > 0 && s.length() - dot <= 10 ? s.substring(dot) : "";
            s = s.substring(0, 120 - ext.length()) + ext;
        }
        return s;
    }

    // =====================================================================================
    // output writers (Telegram Desktop layout: result.json, messages.html, messages2.html, ...)

    /** Telegram Desktop's kMessagesInFile (export_output_html.cpp). */
    private static final int MESSAGES_IN_FILE = 1000;
    /** export_output_html.cpp kHistoryUserpicSize. */
    private static final int USERPIC_SIZE = 42;
    /** export_output_html.cpp kJoinWithinSeconds. */
    private static final int JOIN_WITHIN_SECONDS = 900;
    private static final int PHOTO_MAX = 520, PHOTO_MIN = 80, STICKER_MAX = 384;
    private static final String ASSETS = "hanako/export_html";

    private String chatTitle;
    private int htmlFileIndex;
    private int htmlMessages;
    private final ArrayList<Integer> lastIdPerFile = new ArrayList<>();
    private int lastHtmlId;
    private int dateMessageId;
    private HtmlPrev prev;

    /** What messageNeedsWrap() in export_output_html.cpp compares against. */
    private static final class HtmlPrev {
        boolean service;
        String fromKey;
        int date;
        String fwdKey;
    }

    private void openWriters(MessagesController mc) throws IOException {
        chatTitle = chatName(mc);
        if (options.json) {
            Uri uri = DocumentsContract.createDocument(resolver, exportDir, "application/json", "result.json");
            if (uri == null) throw new IOException("cannot create result.json");
            OutputStream os = resolver.openOutputStream(uri, "w");
            if (os == null) throw new IOException("cannot open result.json");
            json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8)));
            json.setIndent(" ");
            json.beginObject();
            json.name("name").value(chatTitle);
            json.name("type").value(chatType(mc));
            json.name("id").value(Math.abs(rawId(options.dialogId)));
            json.name("messages").beginArray();
        }
        if (options.html) {
            copyHtmlAssets();
            openHtmlFile(0);
        }
    }

    /** HtmlWriter::start: css/style.css, js/script.js and images/*.png (+ @2x) next to the pages. */
    private void copyHtmlAssets() {
        try {
            AssetManager am = ApplicationLoader.applicationContext.getAssets();
            for (String sub : new String[]{"css", "js", "images"}) {
                String[] names = am.list(ASSETS + "/" + sub);
                if (names == null) continue;
                for (String n : names) {
                    String mime = n.endsWith(".png") ? "image/png" : n.endsWith(".css") ? "text/css" : "application/javascript";
                    Uri out = DocumentsContract.createDocument(resolver, subdir(sub), mime, n);
                    if (out == null) continue;
                    try (InputStream in = am.open(ASSETS + "/" + sub + "/" + n); OutputStream os = resolver.openOutputStream(out, "w")) {
                        if (os == null) continue;
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e); // pages still open without the styling
        }
    }

    private static String messagesFile(int index) {
        return "messages" + (index > 0 ? Integer.toString(index + 1) : "") + ".html";
    }

    private void openHtmlFile(int index) throws IOException {
        String file = messagesFile(index);
        Uri uri = DocumentsContract.createDocument(resolver, exportDir, "text/html", file);
        if (uri == null) throw new IOException("cannot create " + file);
        OutputStream os = resolver.openOutputStream(uri, "w");
        if (os == null) throw new IOException("cannot open " + file);
        html = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
        htmlFileIndex = index;
        html.write("<!DOCTYPE html>\n<html>\n <head>\n  <meta charset=\"utf-8\"/>\n  <title>Exported Data</title>\n"
                + "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"/>\n"
                + "  <link href=\"css/style.css\" rel=\"stylesheet\"/>\n"
                + "  <script src=\"js/script.js\" type=\"text/javascript\"></script>\n </head>\n"
                + " <body onload=\"CheckLocation();\">\n  <div class=\"page_wrap\">\n"
                + "   <div class=\"page_header\">\n    <div class=\"content\">\n     <div class=\"text bold\">\n");
        html.write(ser(chatTitle));
        html.write("\n     </div>\n    </div>\n   </div>\n   <div class=\"page_body chat_page\">\n    <div class=\"history\">\n");
        if (index > 0) {
            html.write("     <a class=\"pagination block_link\" href=\"" + messagesFile(index - 1) + "\">Previous messages</a>\n");
        }
        prev = null;
    }

    private void closeHtmlFile(boolean hasNext) throws IOException {
        if (hasNext) {
            html.write("     <a class=\"pagination block_link\" href=\"" + messagesFile(htmlFileIndex + 1) + "\">Next messages</a>\n");
        }
        html.write("    </div>\n   </div>\n  </div>\n </body>\n</html>\n");
        html.flush();
        html.close();
        html = null;
    }

    private void closeWriters() {
        if (json != null) {
            try {
                json.endArray();
                json.endObject();
                json.flush();
            } catch (Exception e) {
                FileLog.e(e);
            }
            try {
                json.close();
            } catch (Exception ignore) {
            }
            json = null;
        }
        if (html != null) {
            try {
                closeHtmlFile(false);
            } catch (Exception e) {
                FileLog.e(e);
            }
            try {
                if (html != null) html.close();
            } catch (Exception ignore) {
            }
            html = null;
        }
    }

    // =====================================================================================
    // naming

    private static long rawId(long dialogId) {
        return dialogId;
    }

    private String chatName(MessagesController mc) {
        long did = options.dialogId;
        if (DialogObject.isUserDialog(did)) {
            TLRPC.User u = mc.getUser(did);
            if (u != null) {
                if (UserObject.isUserSelf(u)) return "Saved Messages";
                return UserObject.getUserName(u);
            }
        } else {
            TLRPC.Chat c = mc.getChat(-did);
            if (c != null && c.title != null) return c.title;
        }
        return Long.toString(did);
    }

    private String chatType(MessagesController mc) {
        long did = options.dialogId;
        if (DialogObject.isUserDialog(did)) {
            TLRPC.User u = mc.getUser(did);
            if (u != null && UserObject.isUserSelf(u)) return "saved_messages";
            if (u != null && u.bot) return "bot_chat";
            return "personal_chat";
        }
        TLRPC.Chat c = mc.getChat(-did);
        if (c == null) return "private_group";
        boolean isPublic = !TextUtils.isEmpty(ChatObject.getPublicUsername(c));
        if (ChatObject.isChannel(c)) {
            if (c.megagroup) return isPublic ? "public_supergroup" : "private_supergroup";
            return isPublic ? "public_channel" : "private_channel";
        }
        return "private_group";
    }

    private String peerName(TLRPC.Peer peer) {
        if (peer == null) return null;
        if (peer.user_id != 0) {
            TLRPC.User u = users.get(peer.user_id);
            if (u == null) u = MessagesController.getInstance(account).getUser(peer.user_id);
            return u != null ? UserObject.getUserName(u) : null;
        }
        long id = peer.channel_id != 0 ? peer.channel_id : peer.chat_id;
        TLRPC.Chat c = chats.get(id);
        if (c == null) c = MessagesController.getInstance(account).getChat(id);
        return c != null ? c.title : null;
    }

    /** First and last name for the HTML initials (FillUserpicNames). */
    private String[] peerNames(TLRPC.Peer peer, String fallback) {
        if (peer != null && peer.user_id != 0) {
            TLRPC.User u = users.get(peer.user_id);
            if (u == null) u = MessagesController.getInstance(account).getUser(peer.user_id);
            if (u != null) return new String[]{u.first_name != null ? u.first_name : "", u.last_name != null ? u.last_name : ""};
        }
        String n = peer != null ? peerName(peer) : null;
        return new String[]{n != null ? n : (fallback != null ? fallback : ""), ""};
    }

    private static long bareId(TLRPC.Peer peer) {
        if (peer == null) return 0;
        if (peer.user_id != 0) return peer.user_id;
        if (peer.channel_id != 0) return peer.channel_id;
        return peer.chat_id;
    }

    private static String peerJsonId(TLRPC.Peer peer) {
        if (peer == null) return null;
        if (peer.user_id != 0) return "user" + peer.user_id;
        if (peer.channel_id != 0) return "channel" + peer.channel_id;
        if (peer.chat_id != 0) return "chat" + peer.chat_id;
        return null;
    }

    // =====================================================================================
    // messages

    /** Data::File::SkipReason. */
    private enum Skip { NONE, UNAVAILABLE, FILE_SIZE, FILE_TYPE }

    /** Data::Document classification plus where the file ended up. */
    private static final class MediaInfo {
        boolean photo;
        boolean isSticker, isAnimated, isVideoFile, isVideoMessage, isVoiceMessage, isAudioFile;
        String path;            // relative path when written
        Skip skip = Skip.NONE;
        long size;
        int width, height;
        double duration;
        String fileName, mime, performer, title, emoji;
        // document thumbnail (<file>_thumb.jpg)
        boolean hasThumb;
        String thumbPath;
        Skip thumbSkip = Skip.NONE;
        long thumbSize;
        // HTML-only scaled preview (WriteImageThumb)
        String previewPath;
        int previewW, previewH;
    }

    private void writeMessage(TLRPC.Message m) throws Exception {
        boolean service = m instanceof TLRPC.TL_messageService;
        TLRPC.Peer from = m.from_id != null ? m.from_id : m.peer_id;
        String fromName = peerName(from);
        if (fromName == null && m.post_author != null) fromName = m.post_author;
        if (fromName == null) fromName = "";
        String fromId = peerJsonId(from);
        String text = m.message != null ? m.message : "";
        ArrayList<Part> parts = parts(text, m.entities);
        MediaInfo media = service ? null : media(m);
        Contact contact = service ? null : contact(m.media);

        if (json != null) {
            json.beginObject();
            json.name("id").value(m.id);
            json.name("type").value(service ? "service" : "message");
            json.name("date").value(isoFormat.format(new Date(m.date * 1000L)));
            json.name("date_unixtime").value(Integer.toString(m.date));
            if (m.edit_date != 0 && !service) {
                json.name("edited").value(isoFormat.format(new Date(m.edit_date * 1000L)));
                json.name("edited_unixtime").value(Integer.toString(m.edit_date));
            }
            if (service) {
                json.name("actor").value(fromName);
                if (fromId != null) json.name("actor_id").value(fromId);
                json.name("action").value(actionName(m.action));
                if (m.action != null && m.action.title != null && !m.action.title.isEmpty()) {
                    json.name("title").value(m.action.title);
                }
            } else {
                json.name("from").value(fromName);
                if (fromId != null) json.name("from_id").value(fromId);
                if (m.fwd_from != null) {
                    String fwd = m.fwd_from.from_name;
                    if (fwd == null) fwd = peerName(m.fwd_from.from_id);
                    json.name("forwarded_from").value(fwd != null ? fwd : "");
                }
            }
            if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
                json.name("reply_to_message_id").value(m.reply_to.reply_to_msg_id);
            }
            if (media != null) writeJsonMedia(media);
            if (!service) writeJsonContactGeo(m.media, contact);
            writeJsonText(parts);
            json.endObject();
        }
        if (html != null) {
            writeHtml(m, service, fromName, parts, media, contact);
        }
    }

    private static String skipText(Skip s) {
        switch (s) {
            case UNAVAILABLE:
                return FAILED;
            case FILE_SIZE:
                return TOO_BIG;
            case FILE_TYPE:
                return NOT_INCLUDED;
            default:
                return "";
        }
    }

    private static String pathOrSkip(String path, Skip skip) {
        return skip == Skip.NONE && path != null ? path : skipText(skip == Skip.NONE ? Skip.UNAVAILABLE : skip);
    }

    /** export_output_json.cpp: pushPhoto / the Document branch. */
    private void writeJsonMedia(MediaInfo media) throws IOException {
        if (media.photo) {
            json.name("photo").value(pathOrSkip(media.path, media.skip));
            json.name("photo_file_size").value(media.size);
            if (media.width > 0) json.name("width").value(media.width);
            if (media.height > 0) json.name("height").value(media.height);
            return;
        }
        json.name("file").value(pathOrSkip(media.path, media.skip));
        if (!TextUtils.isEmpty(media.fileName)) json.name("file_name").value(media.fileName);
        json.name("file_size").value(media.size);
        if (media.hasThumb) {
            json.name("thumbnail").value(pathOrSkip(media.thumbPath, media.thumbSkip));
            json.name("thumbnail_file_size").value(media.thumbSize);
        }
        if (media.isSticker) {
            json.name("media_type").value("sticker");
            if (!TextUtils.isEmpty(media.emoji)) json.name("sticker_emoji").value(media.emoji);
        } else if (media.isVideoMessage) {
            json.name("media_type").value("video_message");
        } else if (media.isVoiceMessage) {
            json.name("media_type").value("voice_message");
        } else if (media.isAnimated) {
            json.name("media_type").value("animation");
        } else if (media.isVideoFile) {
            json.name("media_type").value("video_file");
        } else if (media.isAudioFile) {
            json.name("media_type").value("audio_file");
            if (!TextUtils.isEmpty(media.performer)) json.name("performer").value(media.performer);
            if (!TextUtils.isEmpty(media.title)) json.name("title").value(media.title);
        }
        if (!TextUtils.isEmpty(media.mime)) json.name("mime_type").value(media.mime);
        if (media.duration > 0) json.name("duration_seconds").value((long) Math.round(media.duration));
        if (media.width > 0 && media.height > 0) {
            json.name("width").value(media.width);
            json.name("height").value(media.height);
        }
    }

    /** Data::SharedContact: the vCard goes to contacts/contact_N.vcard. */
    private static final class Contact {
        String vcardPath;
        long vcardSize;
    }

    private Contact contact(TLRPC.MessageMedia mm) throws IOException {
        if (!(mm instanceof TLRPC.TL_messageMediaContact)) return null;
        Contact c = new Contact();
        if (!TextUtils.isEmpty(mm.vcard)) {
            byte[] data = mm.vcard.getBytes(StandardCharsets.UTF_8);
            c.vcardSize = data.length;
            String name = uniqueName("contacts", "contact_" + (++ctxContacts) + ".vcard");
            c.vcardPath = writeBytes("contacts", name, "text/vcard", data);
            if (c.vcardPath.startsWith("(")) c.vcardPath = null;
        }
        return c;
    }

    private void writeJsonContactGeo(TLRPC.MessageMedia mm, Contact contact) throws IOException {
        if (mm instanceof TLRPC.TL_messageMediaContact) {
            json.name("contact_information").beginObject();
            json.name("first_name").value(mm.first_name != null ? mm.first_name : "");
            json.name("last_name").value(mm.last_name != null ? mm.last_name : "");
            json.name("phone_number").value(mm.phone_number != null ? mm.phone_number : "");
            json.endObject();
            if (contact != null && contact.vcardSize > 0) {
                json.name("contact_vcard").value(contact.vcardPath != null ? contact.vcardPath : FAILED);
                json.name("contact_vcard_file_size").value(contact.vcardSize);
            }
        } else if (mm instanceof TLRPC.TL_messageMediaGeo && mm.geo != null) {
            json.name("location_information").beginObject();
            json.name("latitude").value(mm.geo.lat);
            json.name("longitude").value(mm.geo._long);
            json.endObject();
        }
    }
    private void writeJsonText(ArrayList<Part> parts) throws IOException {
        boolean plain = true;
        for (Part p : parts) {
            if (!"plain".equals(p.type)) {
                plain = false;
                break;
            }
        }
        if (plain) {
            StringBuilder sb = new StringBuilder();
            for (Part p : parts) sb.append(p.text);
            json.name("text").value(sb.toString());
        } else {
            json.name("text").beginArray();
            for (Part p : parts) {
                if ("plain".equals(p.type)) {
                    json.value(p.text);
                } else {
                    writeEntity(p);
                }
            }
            json.endArray();
        }
        json.name("text_entities").beginArray();
        for (Part p : parts) writeEntity(p);
        json.endArray();
    }

    private void writeEntity(Part p) throws IOException {
        json.beginObject();
        json.name("type").value(p.type);
        json.name("text").value(p.text);
        if (p.href != null) json.name("href").value(p.href);
        if (p.userId != 0) json.name("user_id").value(p.userId);
        if (p.language != null) json.name("language").value(p.language);
        json.endObject();
    }

    private static String actionName(TLRPC.MessageAction a) {
        if (a instanceof TLRPC.TL_messageActionChatCreate) return "create_group";
        if (a instanceof TLRPC.TL_messageActionChannelCreate) return "create_channel";
        if (a instanceof TLRPC.TL_messageActionChatAddUser) return "invite_members";
        if (a instanceof TLRPC.TL_messageActionChatDeleteUser) return "remove_members";
        if (a instanceof TLRPC.TL_messageActionChatJoinedByLink) return "join_group_by_link";
        if (a instanceof TLRPC.TL_messageActionChatJoinedByRequest) return "join_group_by_request";
        if (a instanceof TLRPC.TL_messageActionChatEditTitle) return "edit_group_title";
        if (a instanceof TLRPC.TL_messageActionChatEditPhoto) return "edit_group_photo";
        if (a instanceof TLRPC.TL_messageActionChatDeletePhoto) return "delete_group_photo";
        if (a instanceof TLRPC.TL_messageActionChatMigrateTo) return "migrate_to_supergroup";
        if (a instanceof TLRPC.TL_messageActionChannelMigrateFrom) return "migrate_from_group";
        if (a instanceof TLRPC.TL_messageActionPinMessage) return "pin_message";
        if (a instanceof TLRPC.TL_messageActionHistoryClear) return "clear_history";
        if (a instanceof TLRPC.TL_messageActionPhoneCall) return "phone_call";
        if (a instanceof TLRPC.TL_messageActionScreenshotTaken) return "take_screenshot";
        if (a instanceof TLRPC.TL_messageActionGroupCall) return "group_call";
        if (a instanceof TLRPC.TL_messageActionContactSignUp) return "joined_telegram";
        if (a instanceof TLRPC.TL_messageActionSetMessagesTTL) return "set_messages_ttl";
        if (a instanceof TLRPC.TL_messageActionCustomAction) return "custom_action";
        if (a == null) return "unknown";
        String n = a.getClass().getSimpleName();
        if (n.startsWith("TL_messageAction")) n = n.substring("TL_messageAction".length());
        return "unknown_" + n;
    }

    // =====================================================================================
    // text entities

    private static final class Part {
        String type;
        String text;
        String href;
        String language;
        long userId;

        Part(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private static ArrayList<Part> parts(String text, ArrayList<TLRPC.MessageEntity> entities) {
        ArrayList<Part> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        ArrayList<TLRPC.MessageEntity> sorted = new ArrayList<>();
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                if (e != null && e.length > 0 && e.offset >= 0 && e.offset < text.length()) sorted.add(e);
            }
        }
        Collections.sort(sorted, (a, b) -> Integer.compare(a.offset, b.offset));
        int pos = 0;
        for (TLRPC.MessageEntity e : sorted) {
            if (e.offset < pos) continue; // nested / overlapping: keep the outer one
            int end = Math.min(text.length(), e.offset + e.length);
            if (e.offset > pos) out.add(new Part("plain", text.substring(pos, e.offset)));
            String piece = text.substring(e.offset, end);
            Part p = new Part(entityType(e), piece);
            if (e instanceof TLRPC.TL_messageEntityTextUrl) p.href = e.url;
            if (e instanceof TLRPC.TL_messageEntityPre && !TextUtils.isEmpty(e.language)) p.language = e.language;
            if (e instanceof TLRPC.TL_messageEntityMentionName) p.userId = ((TLRPC.TL_messageEntityMentionName) e).user_id;
            out.add(p);
            pos = end;
        }
        if (pos < text.length()) {
            out.add(new Part("plain", text.substring(pos)));
        }
        return out;
    }

    private static String entityType(TLRPC.MessageEntity e) {
        if (e instanceof TLRPC.TL_messageEntityBold) return "bold";
        if (e instanceof TLRPC.TL_messageEntityItalic) return "italic";
        if (e instanceof TLRPC.TL_messageEntityUnderline) return "underline";
        if (e instanceof TLRPC.TL_messageEntityStrike) return "strikethrough";
        if (e instanceof TLRPC.TL_messageEntityCode) return "code";
        if (e instanceof TLRPC.TL_messageEntityPre) return "pre";
        if (e instanceof TLRPC.TL_messageEntityTextUrl) return "text_link";
        if (e instanceof TLRPC.TL_messageEntityUrl) return "link";
        if (e instanceof TLRPC.TL_messageEntityEmail) return "email";
        if (e instanceof TLRPC.TL_messageEntityMentionName) return "mention_name";
        if (e instanceof TLRPC.TL_messageEntityMention) return "mention";
        if (e instanceof TLRPC.TL_messageEntityHashtag) return "hashtag";
        if (e instanceof TLRPC.TL_messageEntityCashtag) return "cashtag";
        if (e instanceof TLRPC.TL_messageEntityBotCommand) return "bot_command";
        if (e instanceof TLRPC.TL_messageEntityPhone) return "phone";
        if (e instanceof TLRPC.TL_messageEntitySpoiler) return "spoiler";
        if (e instanceof TLRPC.TL_messageEntityBlockquote) return "blockquote";
        if (e instanceof TLRPC.TL_messageEntityCustomEmoji) return "custom_emoji";
        if (e instanceof TLRPC.TL_messageEntityBankCard) return "bank_card";
        return "unknown";
    }


    // =====================================================================================
    // media (export_data_types.cpp ParsePhoto / ParseDocument, export_api_wrap.cpp processFileLoad)

    /** ParseMediaContext counters: one sequence per kind, as Telegram Desktop numbers files. */
    private int ctxPhotos, ctxAudios, ctxVideos, ctxFiles, ctxContacts;
    private final HashMap<String, HashSet<String>> usedNames = new HashMap<>();

    /** Output::File::PrepareRelativePath: "name (1).ext", counting from the first dot. */
    private String uniqueName(String folder, String suggested) {
        HashSet<String> used = usedNames.get(folder);
        if (used == null) {
            used = new HashSet<>();
            usedNames.put(folder, used);
        }
        String result = suggested;
        if (used.contains(result)) {
            int dot = suggested.indexOf('.');
            String base = dot >= 0 ? suggested.substring(0, dot) : suggested;
            String ext = dot >= 0 ? suggested.substring(dot) : "";
            int attempt = 0;
            do {
                result = base + " (" + (++attempt) + ")" + ext;
            } while (used.contains(result));
        }
        used.add(result);
        return result;
    }

    /** "@DD-MM-YYYY_HH-MM-SS" (PrepareFileNameDatePart), empty without a date. */
    private String datePart(int date) {
        return date != 0 ? "@" + fileFormat.format(new Date(date * 1000L)) : "";
    }

    private static int area(TLRPC.PhotoSize s) {
        if (s == null || s instanceof TLRPC.TL_photoStrippedSize || s instanceof TLRPC.TL_photoPathSize
                || s instanceof TLRPC.TL_photoSizeEmpty) return 0;
        return s.w * s.h;
    }

    private static TLRPC.PhotoSize largest(ArrayList<TLRPC.PhotoSize> sizes) {
        TLRPC.PhotoSize best = null;
        if (sizes == null) return null;
        for (TLRPC.PhotoSize s : sizes) {
            if (area(s) > 0 && (best == null || area(s) > area(best))) best = s;
        }
        return best;
    }

    private Skip typeOrSizeSkip(int type, long fullSize) {
        if ((options.types & type) != type) return Skip.FILE_TYPE;
        if (options.sizeLimit > 0 && fullSize > options.sizeLimit) return Skip.FILE_SIZE;
        return Skip.NONE;
    }

    /** export_api_wrap.cpp DocumentMediaType. */
    private static int documentType(MediaInfo d) {
        if (d.isSticker) return Options.STICKER;
        if (d.isVideoMessage) return Options.VIDEO_MESSAGE;
        if (d.isVoiceMessage) return Options.VOICE_MESSAGE;
        if (d.isAnimated) return Options.GIF;
        if (d.isVideoFile) return Options.VIDEO;
        return Options.FILE;
    }

    /** export_data_types.cpp DocumentFolder. */
    private static String documentFolder(MediaInfo d) {
        if (d.isVideoFile) return "video_files";
        if (d.isAnimated) return "animations";
        if (d.isSticker) return "stickers";
        if (d.isVoiceMessage) return "voice_messages";
        if (d.isVideoMessage) return "round_video_messages";
        return "files";
    }

    /** ComputeDocumentName: the document's own name, else audio_N / video_N / file_N + date. */
    private String documentName(MediaInfo d, int date) {
        if (!TextUtils.isEmpty(d.fileName)) return d.fileName;
        String mime = d.mime != null ? d.mime : "";
        String ext = mime.isEmpty() ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.toLowerCase(Locale.US));
        if (d.isVoiceMessage) {
            return "audio_" + (++ctxAudios) + datePart(date) + ("audio/mp3".equalsIgnoreCase(mime) ? ".mp3" : ".ogg");
        } else if (d.isVideoFile) {
            return "video_" + (++ctxVideos) + datePart(date) + (TextUtils.isEmpty(ext) ? ".mov" : "." + ext);
        }
        return "file_" + (++ctxFiles) + datePart(date) + (TextUtils.isEmpty(ext) ? ".unknown" : "." + ext);
    }

    private MediaInfo media(TLRPC.Message m) throws Exception {
        TLRPC.MessageMedia mm = m.media;
        if (mm instanceof TLRPC.TL_messageMediaPhoto && mm.photo != null) {
            TLRPC.Photo photo = mm.photo;
            TLRPC.PhotoSize size = largest(photo.sizes);
            MediaInfo info = new MediaInfo();
            info.photo = true;
            if (size != null) {
                info.width = size.w;
                info.height = size.h;
                info.size = size.size > 0 ? size.size : (size.bytes != null ? size.bytes.length : 0);
            }
            String name = "photo_" + (++ctxPhotos) + datePart(m.date) + ".jpg";
            if (size == null) {
                info.skip = Skip.UNAVAILABLE;
                return info;
            }
            info.skip = typeOrSizeSkip(Options.PHOTO, info.size);
            if (info.skip != Skip.NONE) return info;
            name = uniqueName("photos", name);
            String out;
            if (size instanceof TLRPC.TL_photoCachedSize && size.bytes != null && size.bytes.length > 0) {
                out = writeBytes("photos", name, "image/jpeg", size.bytes);
            } else {
                TLRPC.TL_inputPhotoFileLocation loc = new TLRPC.TL_inputPhotoFileLocation();
                loc.id = photo.id;
                loc.access_hash = photo.access_hash;
                loc.file_reference = photo.file_reference != null ? photo.file_reference : new byte[0];
                loc.thumb_size = size.type != null ? size.type : "";
                final long photoId = photo.id;
                out = download(loc, photo.dc_id, info.size, "photos", name, "image/jpeg", () -> {
                    TLRPC.MessageMedia fresh = refetchMedia(m.id);
                    if (fresh != null && fresh.photo != null && fresh.photo.id == photoId) return fresh.photo.file_reference;
                    return null;
                });
            }
            if (out.startsWith("(")) {
                info.skip = Skip.UNAVAILABLE;
            } else {
                info.path = out;
                if (html != null) {
                    writePreview(info, lastWritten, "photos", out, PHOTO_MAX, false, Bitmap.CompressFormat.JPEG, "image/jpeg");
                }
            }
            return info;
        }
        if (mm instanceof TLRPC.TL_messageMediaDocument && mm.document != null) {
            TLRPC.Document doc = mm.document;
            MediaInfo info = new MediaInfo();
            info.size = doc.size;
            info.mime = doc.mime_type;
            for (TLRPC.DocumentAttribute a : doc.attributes) {
                if (a instanceof TLRPC.TL_documentAttributeImageSize) {
                    info.width = a.w;
                    info.height = a.h;
                } else if (a instanceof TLRPC.TL_documentAttributeAnimated) {
                    info.isAnimated = true;
                } else if (a instanceof TLRPC.TL_documentAttributeSticker || a instanceof TLRPC.TL_documentAttributeCustomEmoji) {
                    info.isSticker = true;
                    info.emoji = a.alt;
                } else if (a instanceof TLRPC.TL_documentAttributeVideo) {
                    if (a.round_message) {
                        info.isVideoMessage = true;
                    } else {
                        info.isVideoFile = true;
                    }
                    info.width = a.w;
                    info.height = a.h;
                    info.duration = (int) a.duration;
                } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                    if (a.voice) {
                        info.isVoiceMessage = true;
                    } else {
                        info.isAudioFile = true;
                    }
                    info.performer = a.performer;
                    info.title = a.title;
                    info.duration = (int) a.duration;
                } else if (a instanceof TLRPC.TL_documentAttributeFilename) {
                    info.fileName = a.file_name;
                }
            }
            String folder = documentFolder(info);
            String name = safeName(documentName(info, m.date));
            TLRPC.PhotoSize thumb = largest(doc.thumbs);
            info.hasThumb = thumb != null;
            if (thumb != null) {
                info.thumbSize = thumb.size > 0 ? thumb.size : (thumb.bytes != null ? thumb.bytes.length : 0);
            }
            info.skip = typeOrSizeSkip(documentType(info), doc.size);
            if (info.skip != Skip.NONE) {
                info.thumbSkip = info.skip; // "Don't load thumbs for large files that we skip."
                return info;
            }
            name = uniqueName(folder, name);
            TLRPC.TL_inputDocumentFileLocation loc = new TLRPC.TL_inputDocumentFileLocation();
            loc.id = doc.id;
            loc.access_hash = doc.access_hash;
            loc.file_reference = doc.file_reference != null ? doc.file_reference : new byte[0];
            loc.thumb_size = "";
            final long docId = doc.id;
            FileReferenceSource refs = () -> {
                TLRPC.MessageMedia fresh = refetchMedia(m.id);
                if (fresh != null && fresh.document != null && fresh.document.id == docId) return fresh.document.file_reference;
                return null;
            };
            String out = download(loc, doc.dc_id, doc.size, folder, name,
                    TextUtils.isEmpty(doc.mime_type) ? "application/octet-stream" : doc.mime_type, refs);
            Uri written = lastWritten;
            if (out.startsWith("(")) {
                info.skip = Skip.UNAVAILABLE;
            } else {
                info.path = out;
            }
            if (thumb != null) {
                // <document path>_thumb.jpg (ParseDocumentThumb)
                String thumbName = uniqueName(folder, displayLeaf(info.path != null ? info.path : folder + "/" + name) + "_thumb.jpg");
                String t;
                if (thumb instanceof TLRPC.TL_photoCachedSize && thumb.bytes != null && thumb.bytes.length > 0) {
                    t = writeBytes(folder, thumbName, "image/jpeg", thumb.bytes);
                } else {
                    TLRPC.TL_inputDocumentFileLocation tl = new TLRPC.TL_inputDocumentFileLocation();
                    tl.id = doc.id;
                    tl.access_hash = doc.access_hash;
                    tl.file_reference = loc.file_reference;
                    tl.thumb_size = thumb.type != null ? thumb.type : "";
                    t = download(tl, doc.dc_id, info.thumbSize, folder, thumbName, "image/jpeg", refs);
                }
                if (t.startsWith("(")) {
                    info.thumbSkip = Skip.UNAVAILABLE;
                } else {
                    info.thumbPath = t;
                }
            }
            if (html != null && info.isSticker && info.path != null && "image/webp".equals(doc.mime_type)) {
                writePreview(info, written, folder, info.path, STICKER_MAX, false, Bitmap.CompressFormat.PNG, "image/png");
            }
            return info;
        }
        return null;
    }

    private static String displayLeaf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * WriteImageThumb: a scaled copy next to the original ("photo_1@…_thumb.jpg"), sized by
     * CalculateThumbSize (fit max x max keeping the aspect ratio, even sides, at least 80 px),
     * shown at half size in the page.
     */
    private void writePreview(MediaInfo info, Uri source, String folder, String largePath, int max, boolean retina,
                              Bitmap.CompressFormat format, String mime) {
        if (source == null) return;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(source)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int w = bounds.outWidth, h = bounds.outHeight;
            if (w <= 0 || h <= 0 || w >= 10000 || h >= 10000) return;
            int[] size = thumbSize(w, h, max, retina);
            if (size == null) return;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (w / (opts.inSampleSize * 2) >= size[0] && h / (opts.inSampleSize * 2) >= size[1]) opts.inSampleSize *= 2;
            Bitmap bmp;
            try (InputStream in = resolver.openInputStream(source)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, size[0], size[1], true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(format, 75, bos); // Qt's default JPEG quality; PNG ignores it
            if (scaled != bmp) scaled.recycle();
            bmp.recycle();
            String leaf = displayLeaf(largePath);
            int dot = leaf.indexOf('.');
            String thumb = dot >= 0 ? leaf.substring(0, dot) + "_thumb" + leaf.substring(dot) : leaf + "_thumb";
            String out = writeBytes(folder, uniqueName(folder, thumb), mime, bos.toByteArray(), false);
            if (!out.startsWith("(")) {
                info.previewPath = out;
                info.previewW = size[0];
                info.previewH = size[1];
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** export_output_html.cpp CalculateThumbSize(max, max, 80, 80, expandForRetina). */
    private static int[] thumbSize(int w, int h, int max, boolean retina) {
        int mul = retina ? 2 : 1;
        int sw = w, sh = h;
        if (w * mul > max || h * mul > max) {
            // QSize::scaled(max, max, Qt::KeepAspectRatio)
            if (w >= h) {
                sw = max;
                sh = (int) ((long) h * max / w);
            } else {
                sh = max;
                sw = (int) ((long) w * max / h);
            }
        }
        sw &= ~1;
        sh &= ~1;
        if (sw < PHOTO_MIN || sh < PHOTO_MIN) return null;
        return new int[]{sw, sh};
    }

    private Uri lastWritten;

    private String writeBytes(String folder, String name, String mime, byte[] data) throws IOException {
        return writeBytes(folder, name, mime, data, true);
    }

    private String writeBytes(String folder, String name, String mime, byte[] data, boolean count) throws IOException {
        lastWritten = null;
        Uri dir = subdir(folder);
        Uri out = DocumentsContract.createDocument(resolver, dir, mime, name);
        if (out == null) {
            failedCount++;
            return FAILED;
        }
        try (OutputStream os = resolver.openOutputStream(out, "w")) {
            if (os == null) {
                DocumentsContract.deleteDocument(resolver, out); // no empty file left in the export
                failedCount++;
                return FAILED;
            }
            os.write(data);
        }
        if (count) mediaCount++;
        lastWritten = out;
        return folder + "/" + displayName(out, name);
    }

    /** Gives a fresh file_reference for a media file, or null. */
    private interface FileReferenceSource {
        byte[] fresh() throws Exception;
    }

    /*
     * File references from the history page expire after a while (long exports, FLOOD_WAITs).
     * Asking for the message again returns its media with a new reference.
     */
    private TLRPC.MessageMedia refetchMedia(int messageId) throws Exception {
        MessagesController mc = MessagesController.getInstance(account);
        TLObject req;
        if (DialogObject.isChatDialog(options.dialogId) && ChatObject.isChannel(mc.getChat(-options.dialogId))) {
            TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = mc.getInputChannel(-options.dialogId);
            r.id.add(messageId);
            req = r;
        } else {
            TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(messageId);
            req = r;
        }
        TLObject res = call(req, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric);
        if (!(res instanceof TLRPC.messages_Messages)) return null;
        for (TLRPC.Message msg : ((TLRPC.messages_Messages) res).messages) {
            if (msg.id == messageId) return msg.media;
        }
        return null;
    }

    /** Downloads a file into {@code folder}; returns its relative path, or a TD placeholder on failure. */
    private String download(TLRPC.InputFileLocation location, int dcId, long size, String folder, String name, String mime,
                            FileReferenceSource refSource) throws Exception {
        lastWritten = null;
        if (cancelled.get()) return FAILED;
        Uri dir = subdir(folder);
        Uri out = DocumentsContract.createDocument(resolver, dir, mime, name);
        if (out == null) {
            failedCount++;
            return FAILED;
        }
        boolean ok = false;
        try (OutputStream os = resolver.openOutputStream(out, "w")) {
            if (os == null) {
                DocumentsContract.deleteDocument(resolver, out); // no empty file left in the export
                failedCount++;
                return FAILED;
            }
            long offset = 0;
            boolean refreshed = false;
            while (!cancelled.get()) {
                TLRPC.TL_upload_getFile req = new TLRPC.TL_upload_getFile();
                req.location = location;
                req.offset = offset;
                req.limit = CHUNK;
                req.cdn_supported = false;
                TLObject res;
                try {
                    res = call(req, dcId > 0 ? dcId : ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeDownload);
                } catch (FileReferenceExpiredException e) {
                    // once per file: take a fresh reference and continue at the same offset
                    byte[] ref = refreshed || refSource == null ? null : refSource.fresh();
                    if (ref == null) break;
                    refreshed = true;
                    if (location instanceof TLRPC.TL_inputPhotoFileLocation) {
                        ((TLRPC.TL_inputPhotoFileLocation) location).file_reference = ref;
                    } else if (location instanceof TLRPC.TL_inputDocumentFileLocation) {
                        ((TLRPC.TL_inputDocumentFileLocation) location).file_reference = ref;
                    }
                    continue;
                }
                if (!(res instanceof Chunk)) {
                    break;
                }
                byte[] data = ((Chunk) res).data;
                if (data.length > 0) {
                    os.write(data);
                    offset += data.length;
                }
                if (data.length < CHUNK || (size > 0 && offset >= size)) {
                    ok = offset > 0;
                    break;
                }
            }
        }
        if (!ok) {
            try {
                DocumentsContract.deleteDocument(resolver, out);
            } catch (Exception ignore) {
            }
            if (!cancelled.get()) failedCount++;
            return FAILED;
        }
        mediaCount++;
        if (mediaCount % 5 == 0) progress();
        lastWritten = out;
        return folder + "/" + displayName(out, name);
    }


    // =====================================================================================
    // HTML (export_output_html.cpp HtmlWriter::Wrap::pushMessage and friends)

    private static String dateText(int date) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(date * 1000L);
        String[] months = {"January", "February", "March", "April", "May", "June", "July", "August",
                "September", "October", "November", "December"};
        return c.get(Calendar.DAY_OF_MONTH) + " " + months[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    private static boolean sameDay(int a, int b) {
        Calendar x = Calendar.getInstance();
        x.setTimeInMillis(a * 1000L);
        Calendar y = Calendar.getInstance();
        y.setTimeInMillis(b * 1000L);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    /** FormatDateTime(date, true): "20.10.2026 19:06:00 UTC+04:00". */
    private String fullDate(int date) {
        Date d = new Date(date * 1000L);
        return fullFormat.format(d) + " UTC" + zoneFormat.format(d);
    }

    /** Ui::FormatDurationText. */
    private static String duration(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Ui::FormatSizeText. */
    private static String sizeText(long size) {
        if (size >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024));
        if (size >= 1024L * 1024) return String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024));
        if (size >= 1024L) return String.format(Locale.US, "%.1f KB", size / 1024.0);
        return size + " B";
    }

    private static String noFileDescription(Skip s) {
        switch (s) {
            case UNAVAILABLE:
                return "Unavailable, please try again later.";
            case FILE_SIZE:
                return "Exceeds maximum size, change data exporting settings to download.";
            case FILE_TYPE:
                return "Not included, change data exporting settings to download.";
            default:
                return "";
        }
    }

    /** PeerColorIndex. */
    private static int colorIndex(long bareId) {
        int[] map = {0, 7, 4, 1, 6, 3, 5};
        return map[(int) (Math.abs(bareId) % map.length)];
    }

    private static String initial(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        return ser(s.substring(0, Character.charCount(s.codePointAt(0))));
    }

    private static String userpic(long bareId, String[] names) {
        String px = USERPIC_SIZE + "px";
        return "<div class=\"userpic userpic" + (colorIndex(bareId) + 1) + "\" style=\"width: " + px + "; height: " + px + "\">"
                + "<div class=\"initials\" style=\"line-height: " + px + "\">" + initial(names[0]) + initial(names[1]) + "</div></div>";
    }

    /** wrapMessageLink: a reply into an earlier page links to that page. */
    private String messageLink(int messageId, String text) {
        for (int i = 0; i < lastIdPerFile.size(); i++) {
            if (messageId <= lastIdPerFile.get(i)) {
                return "<a href=\"" + messagesFile(i) + "#go_to_message" + messageId + "\">" + text + "</a>";
            }
        }
        return "<a href=\"#go_to_message" + messageId + "\" onclick=\"return GoToMessage(" + messageId + ")\">" + text + "</a>";
    }

    private void writeHtml(TLRPC.Message m, boolean service, String fromName, ArrayList<Part> parts, MediaInfo media, Contact contact) throws IOException {
        if (htmlMessages > 0 && htmlMessages % MESSAGES_IN_FILE == 0) {
            lastIdPerFile.add(lastHtmlId);
            closeHtmlFile(true);
            openHtmlFile(htmlFileIndex + 1);
        }
        StringBuilder b = new StringBuilder();
        if (prev == null || !sameDay(prev.date, m.date)) {
            b.append("     <div class=\"message service\" id=\"message").append(--dateMessageId).append("\">\n")
                    .append("      <div class=\"body details\">\n       ").append(dateText(m.date)).append("\n      </div>\n     </div>\n");
        }
        if (service) {
            b.append("     <div class=\"message service\" id=\"message").append(m.id).append("\">\n      <div class=\"body details\">\n       ");
            b.append(ser(fromName)).append(' ').append(ser(actionName(m.action).replace('_', ' ')));
            if (m.action != null && !TextUtils.isEmpty(m.action.title)) b.append(" «").append(ser(m.action.title)).append('»');
            b.append("\n      </div>\n     </div>\n");
            HtmlPrev p = new HtmlPrev();
            p.service = true;
            p.date = m.date;
            prev = p;
            finishHtmlMessage(b, m.id);
            return;
        }
        TLRPC.Peer from = m.from_id != null ? m.from_id : m.peer_id;
        String fromKey = peerJsonId(from) + "|" + (m.via_bot_id);
        String fwdKey = null;
        String fwdName = null;
        if (m.fwd_from != null) {
            fwdName = m.fwd_from.from_name;
            if (fwdName == null) fwdName = peerName(m.fwd_from.from_id);
            if (fwdName == null) fwdName = "Deleted Account";
            fwdKey = peerJsonId(m.fwd_from.from_id) + "|" + fwdName;
        }
        boolean wrap = prev == null || prev.service || from == null || !fromKey.equals(prev.fromKey)
                || !sameDay(prev.date, m.date)
                || !TextUtils.equals(fwdKey, prev.fwdKey)
                || Math.abs(m.date - prev.date) > (fwdKey != null ? 1 : JOIN_WITHIN_SECONDS);
        b.append("     <div class=\"message default clearfix").append(wrap ? "" : " joined").append("\" id=\"message").append(m.id).append("\">\n");
        if (wrap) {
            b.append("      <div class=\"pull_left userpic_wrap\">").append(userpic(bareId(from), peerNames(from, fromName))).append("</div>\n");
        }
        b.append("      <div class=\"body\">\n");
        b.append("       <div class=\"pull_right date details\" title=\"").append(fullDate(m.date)).append("\">")
                .append(timeFormat.format(new Date(m.date * 1000L))).append("</div>\n");
        if (wrap) {
            b.append("       <div class=\"from_name\">").append(ser(fromName.isEmpty() ? "Deleted Account" : fromName)).append("</div>\n");
        }
        if (fwdKey != null) {
            b.append("       <div class=\"pull_left forwarded userpic_wrap\">")
                    .append(userpic(m.fwd_from.from_id != null ? bareId(m.fwd_from.from_id) : m.id, peerNames(m.fwd_from.from_id, fwdName))).append("</div>\n");
            b.append("       <div class=\"forwarded body\">\n");
            b.append("        <div class=\"from_name\">").append(ser(fwdName));
            if (m.fwd_from.date != 0) {
                b.append("<span class=\"date details\" title=\"").append(fullDate(m.fwd_from.date)).append("\"> ")
                        .append(fullFormat.format(new Date(m.fwd_from.date * 1000L))).append("</span>");
            }
            b.append("</div>\n");
        }
        if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
            b.append("       <div class=\"reply_to details\">");
            if (m.reply_to.reply_to_peer_id != null && peerJsonId(m.reply_to.reply_to_peer_id) != null
                    && !TextUtils.equals(peerJsonId(m.reply_to.reply_to_peer_id), peerJsonId(m.peer_id))) {
                b.append("In reply to a message in another chat");
            } else {
                b.append("In reply to ").append(messageLink(m.reply_to.reply_to_msg_id, "this message"));
            }
            b.append("</div>\n");
        }
        if (media != null) {
            b.append(htmlMedia(media));
        } else if (m.media instanceof TLRPC.TL_messageMediaContact) {
            TLRPC.MessageMedia mm = m.media;
            String status = mm.phone_number != null ? mm.phone_number : "";
            String link = null;
            if (contact != null && contact.vcardSize > 0) {
                status += " - vCard";
                link = contact.vcardPath;
            }
            b.append(generic("media_contact", link, null, (mm.first_name != null ? mm.first_name : "") + " " + (mm.last_name != null ? mm.last_name : ""), null, status));
        } else if (m.media instanceof TLRPC.TL_messageMediaGeo && m.media.geo != null) {
            String lat = Double.toString(m.media.geo.lat), lng = Double.toString(m.media.geo._long);
            String coords = lat + "," + lng;
            b.append(generic("media_location", "https://maps.google.com/maps?q=" + coords + "&ll=" + coords + "&z=16", null, "Location", null, lat + ", " + lng));
        }
        StringBuilder body = new StringBuilder();
        for (Part p : parts) body.append(htmlPart(p));
        if (body.length() > 0) {
            b.append("       <div class=\"text\">\n").append(body).append("\n       </div>\n");
        }
        if (!TextUtils.isEmpty(m.post_author)) {
            b.append("       <div class=\"signature details\">").append(ser(m.post_author)).append("</div>\n");
        }
        if (fwdKey != null) b.append("       </div>\n");
        b.append("      </div>\n     </div>\n");
        HtmlPrev p = new HtmlPrev();
        p.fromKey = fromKey;
        p.date = m.date;
        p.fwdKey = fwdKey;
        prev = p;
        finishHtmlMessage(b, m.id);
    }

    private void finishHtmlMessage(StringBuilder b, int id) throws IOException {
        html.write(b.toString());
        htmlMessages++;
        lastHtmlId = id;
    }

    /** pushGenericMedia. */
    private String generic(String classes, String link, String thumb, String title, String description, String status) {
        StringBuilder r = new StringBuilder("       <div class=\"media_wrap clearfix\">\n");
        if (TextUtils.isEmpty(link)) {
            r.append("        <div class=\"media clearfix pull_left ").append(classes).append("\">\n");
        } else {
            boolean global = link.startsWith("http://") || link.startsWith("https://");
            r.append("        <a class=\"media clearfix pull_left block_link ").append(classes).append("\" href=\"")
                    .append(escAttr(global ? link : encodePath(link))).append("\">\n");
        }
        if (TextUtils.isEmpty(thumb)) {
            r.append("         <div class=\"fill pull_left\"></div>\n");
        } else {
            r.append("         <img class=\"thumb pull_left\" src=\"").append(escAttr(encodePath(thumb))).append("\"/>\n");
        }
        r.append("         <div class=\"body\">\n");
        if (!TextUtils.isEmpty(title)) r.append("          <div class=\"title bold\">").append(ser(title)).append("</div>\n");
        if (!TextUtils.isEmpty(description)) r.append("          <div class=\"description\">").append(ser(description)).append("</div>\n");
        if (!TextUtils.isEmpty(status)) r.append("          <div class=\"status details\">").append(ser(status)).append("</div>\n");
        r.append("         </div>\n");
        r.append(TextUtils.isEmpty(link) ? "        </div>\n" : "        </a>\n");
        r.append("       </div>\n");
        return r.toString();
    }

    private String sized(String cls, String src, int w, int h) {
        return "<img class=\"" + cls + "\" src=\"" + escAttr(encodePath(src)) + "\" style=\"width: " + (w / 2) + "px; height: " + (h / 2) + "px\"/>";
    }

    private String htmlMedia(MediaInfo d) {
        boolean has = d.path != null;
        String desc = noFileDescription(d.skip);
        if (d.photo) {
            if (has && d.previewPath != null) {
                return "       <div class=\"media_wrap clearfix\">\n        <a class=\"photo_wrap clearfix pull_left\" href=\""
                        + escAttr(encodePath(d.path)) + "\">" + sized("photo", d.previewPath, d.previewW, d.previewH) + "</a>\n       </div>\n";
            }
            String status = d.width + "x" + d.height + (has ? "" : ", " + sizeText(d.size));
            return generic("media_photo", d.path, null, "Photo", desc, status);
        }
        if (d.isSticker) {
            if (has && d.previewPath != null) {
                return "       <div class=\"media_wrap clearfix\">\n        <a class=\"sticker_wrap clearfix pull_left\" href=\""
                        + escAttr(encodePath(d.path)) + "\">" + sized("sticker", d.previewPath, d.previewW, d.previewH) + "</a>\n       </div>\n";
            }
            String status = d.emoji != null ? d.emoji : "";
            if (!has) status += (status.isEmpty() ? "" : ", ") + sizeText(d.size);
            return generic("media_photo", d.path, null, "Sticker", desc, status);
        }
        if (d.isVideoMessage) {
            return generic("media_video", d.path, d.thumbPath, "Video message", desc, duration(d.duration) + (has ? "" : ", " + sizeText(d.size)));
        }
        if (d.isVoiceMessage) {
            return generic("media_voice_message", d.path, null, "Voice message", desc, duration(d.duration) + (has ? "" : ", " + sizeText(d.size)));
        }
        if (d.isAnimated || d.isVideoFile) {
            int[] ts = d.width > 0 && d.height > 0 ? thumbSize(d.width, d.height, PHOTO_MAX, true) : null;
            if (has && d.thumbPath != null && ts != null) {
                StringBuilder r = new StringBuilder("       <div class=\"media_wrap clearfix\">\n        <a class=\"")
                        .append(d.isAnimated ? "animated_wrap" : "video_file_wrap").append(" clearfix pull_left\" href=\"")
                        .append(escAttr(encodePath(d.path))).append("\">");
                if (d.isAnimated) {
                    r.append("<div class=\"video_play_bg\"><div class=\"gif_play\">GIF</div></div>");
                    r.append(sized("animated", d.thumbPath, ts[0], ts[1]));
                } else {
                    r.append("<div class=\"video_play_bg\"><div class=\"video_play\"></div></div><div class=\"video_duration\">")
                            .append(duration(d.duration)).append("</div>");
                    r.append(sized("video_file", d.thumbPath, ts[0], ts[1]));
                }
                return r.append("</a>\n       </div>\n").toString();
            }
            if (d.isAnimated) {
                return generic("media_video", d.path, null, "Animation", desc, sizeText(d.size));
            }
            return generic("media_video", d.path, null, "Video file", desc, duration(d.duration) + (has ? "" : ", " + sizeText(d.size)));
        }
        if (d.isAudioFile) {
            String title = !TextUtils.isEmpty(d.performer) && !TextUtils.isEmpty(d.title) ? d.performer + " \u2013 " + d.title
                    : !TextUtils.isEmpty(d.fileName) ? d.fileName : "Audio file";
            return generic("media_audio_file", d.path, null, title, desc, duration(d.duration) + (has ? "" : ", " + sizeText(d.size)));
        }
        return generic("media_file", d.path, null, TextUtils.isEmpty(d.fileName) ? "File" : d.fileName, desc, sizeText(d.size));
    }

    /** export_output_html.cpp FormatText. */
    private static String htmlPart(Part p) {
        String t = ser(p.text);
        switch (p.type) {
            case "plain":
            case "unknown":
            case "bank_card":
            case "custom_emoji":
                return t;
            case "mention":
                return "<a href=\"https://t.me/" + escAttr(p.text.length() > 1 ? p.text.substring(1) : "") + "\">" + t + "</a>";
            case "hashtag":
                return "<a href=\"\" onclick=\"return ShowHashtag(" + jsArg(p.text) + ")\">" + t + "</a>";
            case "cashtag":
                return "<a href=\"\" onclick=\"return ShowCashtag(" + jsArg(p.text) + ")\">" + t + "</a>";
            case "bot_command":
                return "<a href=\"\" onclick=\"return ShowBotCommand(" + jsArg(p.text) + ")\">" + t + "</a>";
            case "mention_name":
                return "<a href=\"\" onclick=\"return ShowMentionName()\">" + t + "</a>";
            case "phone":
                return "<a href=\"tel:" + escAttr(p.text) + "\">" + t + "</a>";
            case "bold":
                return "<strong>" + t + "</strong>";
            case "italic":
                return "<em>" + t + "</em>";
            case "underline":
                return "<u>" + t + "</u>";
            case "strikethrough":
                return "<s>" + t + "</s>";
            case "code":
                return "<code>" + t + "</code>";
            case "pre":
                return "<pre>" + t + "</pre>";
            case "spoiler":
                return "<span class=\"spoiler hidden\" onclick=\"ShowSpoiler(this)\"><span aria-hidden=\"true\">" + t + "</span></span>";
            case "blockquote":
                return "<blockquote>" + t + "</blockquote>";
            case "text_link":
                return link(p.href, t);
            case "link":
                return link(p.text, t);
            case "email":
                return "<a href=\"mailto:" + escAttr(p.text) + "\">" + t + "</a>";
            default:
                return t;
        }
    }

    /** SerializeString('"' + text.mid(1) + '"'). */
    private static String jsArg(String text) {
        return escAttr("\"" + (text.length() > 1 ? text.substring(1) : "") + "\"");
    }

    /** SerializeString: escaped, newlines as &lt;br&gt;. */
    private static String ser(String s) {
        return esc(s).replace("\n", "<br>");
    }

    private static String link(String url, String inner) {
        if (url == null) return inner;
        String u = url.trim();
        String lower = u.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("tg://") && !lower.startsWith("mailto:")) {
            if (lower.contains(":")) return inner; // javascript: and friends
            u = "https://" + u;
        }
        return "<a href=\"" + escAttr(u) + "\">" + inner + "</a>";
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0) sb.append('/');
            sb.append(Uri.encode(seg));
        }
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escAttr(String s) {
        return esc(s).replace("'", "&#39;");
    }
}
