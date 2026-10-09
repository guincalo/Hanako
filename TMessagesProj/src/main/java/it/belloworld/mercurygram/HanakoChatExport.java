package it.belloworld.mercurygram;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.JsonWriter;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
 * file's DC (no CDN), so nothing lands in the app cache. Output goes into a new
 * "ChatExport_&lt;date&gt;" folder inside a SAF tree the user picked: result.json (Telegram
 * Desktop's single-chat export schema, https://core.telegram.org/import-export) and/or a simple
 * self-contained messages.html, plus photos/, video_files/, voice_messages/,
 * round_video_messages/, stickers/ and files/ folders.
 */
public final class HanakoChatExport {

    public static final class Options {
        public long dialogId;
        /** unix seconds, 0 = from the beginning */
        public int fromDate;
        /** unix seconds, 0 = until now */
        public int toDate;
        public boolean photos = true;
        public boolean videos = true;
        public boolean voice = true;
        public boolean files = true;
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
    private String lastHtmlDay;
    private final SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    private final SimpleDateFormat fileFormat = new SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
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

    public Uri getExportDir() {
        return exportDir;
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
                    "ChatExport_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()));
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
        int timeouts = 0;
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
            while (!done && !cancelled.get()) {
                done = latch.await(1, TimeUnit.SECONDS);
                if (!done && ++timeouts > REQUEST_TIMEOUT_SEC) break;
            }
            if (!done) {
                ConnectionsManager.getInstance(account).cancelRequest(token, true);
                if (cancelled.get()) return null;
                if (timeouts > REQUEST_TIMEOUT_SEC * 3) throw new IOException("network timeout");
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
                floodWait(Math.max(1, seconds));
                continue;
            }
            if (err[0].code == 420) {
                floodWait(5);
                continue;
            }
            Matcher migrate = MIGRATE.matcher(text);
            if (migrate.matches() && req instanceof TLRPC.TL_upload_getFile) {
                dcId = Integer.parseInt(migrate.group(1));
                continue;
            }
            if (req instanceof TLRPC.TL_upload_getFile) {
                FileLog.d("hanako export: getFile failed " + err[0].code + " " + text);
                return null;
            }
            throw new IOException(text.isEmpty() ? ("error " + err[0].code) : text);
        }
        return null;
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

    private void openWriters(MessagesController mc) throws IOException {
        String name = chatName(mc);
        if (options.json) {
            Uri uri = DocumentsContract.createDocument(resolver, exportDir, "application/json", "result.json");
            if (uri == null) throw new IOException("cannot create result.json");
            OutputStream os = resolver.openOutputStream(uri, "w");
            if (os == null) throw new IOException("cannot open result.json");
            json = new JsonWriter(new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8)));
            json.setIndent(" ");
            json.beginObject();
            json.name("name").value(name);
            json.name("type").value(chatType(mc));
            json.name("id").value(Math.abs(rawId(options.dialogId)));
            json.name("messages").beginArray();
        }
        if (options.html) {
            Uri uri = DocumentsContract.createDocument(resolver, exportDir, "text/html", "messages.html");
            if (uri == null) throw new IOException("cannot create messages.html");
            OutputStream os = resolver.openOutputStream(uri, "w");
            if (os == null) throw new IOException("cannot open messages.html");
            html = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
            html.write("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>");
            html.write(esc(name));
            html.write("</title>\n<style>\n" + CSS + "</style></head><body>\n<div class=\"page_header\"><h1>");
            html.write(esc(name));
            html.write("</h1><div class=\"sub\">Exported by Hanako · ");
            html.write(esc(fullFormat.format(new Date())));
            html.write("</div></div>\n<div class=\"history\">\n");
        }
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
                html.write("</div>\n</body></html>\n");
                html.flush();
            } catch (Exception e) {
                FileLog.e(e);
            }
            try {
                html.close();
            } catch (Exception ignore) {
            }
            html = null;
        }
    }

    private static final String CSS =
            "body{margin:0;background:#e7ebf0;font:14px/1.45 -apple-system,Roboto,'Segoe UI',sans-serif;color:#000}\n"
            + ".page_header{background:#fff;padding:14px 20px;border-bottom:1px solid #d6dbe1;position:sticky;top:0}\n"
            + ".page_header h1{font-size:18px;margin:0}.sub{color:#70777b;font-size:12px}\n"
            + ".history{max-width:760px;margin:0 auto;padding:10px}\n"
            + ".day{text-align:center;margin:14px 0 8px}.day span{background:rgba(0,0,0,.25);color:#fff;border-radius:12px;padding:3px 10px;font-size:13px}\n"
            + ".message{background:#fff;border-radius:10px;padding:8px 12px;margin:6px 0;box-shadow:0 1px 1px rgba(0,0,0,.08)}\n"
            + ".message.out{background:#effdde}\n"
            + ".service{text-align:center;color:#70777b;font-size:13px;margin:8px 0}\n"
            + ".from{font-weight:600;color:#3e88c7}.date{float:right;color:#a0acb6;font-size:12px}\n"
            + ".fwd,.reply{color:#70777b;font-size:13px;border-left:2px solid #3e88c7;padding-left:6px;margin:3px 0}\n"
            + ".text{white-space:pre-wrap;word-wrap:break-word}\n"
            + ".media{margin:4px 0}.media img{max-width:100%;max-height:420px;border-radius:6px}\n"
            + ".media video{max-width:100%;max-height:420px}.missing{color:#a0acb6;font-style:italic}\n"
            + ".spoiler{background:#ccc;color:#ccc}.spoiler:hover{color:inherit}\n"
            + "pre,code{background:#f2f4f6;border-radius:4px;padding:1px 4px;font-family:monospace}\n"
            + "blockquote{margin:4px 0;padding-left:8px;border-left:3px solid #c3cbd2}\n"
            + "a{color:#168acd;text-decoration:none}\n";

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

    private static String peerJsonId(TLRPC.Peer peer) {
        if (peer == null) return null;
        if (peer.user_id != 0) return "user" + peer.user_id;
        if (peer.channel_id != 0) return "channel" + peer.channel_id;
        if (peer.chat_id != 0) return "chat" + peer.chat_id;
        return null;
    }

    // =====================================================================================
    // messages

    private static final class MediaInfo {
        String jsonKey;         // "photo" or "file"
        String mediaType;       // TD media_type, null for photos
        String path;            // relative path or a TD placeholder
        boolean downloaded;
        long size;
        int width, height;
        double duration;
        String fileName, mime, performer, title, emoji;
        String folder;
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
            if (!service) writeJsonContactGeo(m.media);
            writeJsonText(parts);
            json.endObject();
        }
        if (html != null) {
            writeHtml(m, service, fromName, parts, media);
        }
    }

    private void writeJsonMedia(MediaInfo media) throws IOException {
        if ("photo".equals(media.jsonKey)) {
            json.name("photo").value(media.path);
            if (media.size > 0) json.name("photo_file_size").value(media.size);
            if (media.width > 0) json.name("width").value(media.width);
            if (media.height > 0) json.name("height").value(media.height);
            return;
        }
        json.name("file").value(media.path);
        if (!TextUtils.isEmpty(media.fileName)) json.name("file_name").value(media.fileName);
        if (media.size > 0) json.name("file_size").value(media.size);
        if (media.mediaType != null) json.name("media_type").value(media.mediaType);
        if (!TextUtils.isEmpty(media.emoji)) json.name("sticker_emoji").value(media.emoji);
        if (!TextUtils.isEmpty(media.performer)) json.name("performer").value(media.performer);
        if (!TextUtils.isEmpty(media.title)) json.name("title").value(media.title);
        if (!TextUtils.isEmpty(media.mime)) json.name("mime_type").value(media.mime);
        if (media.duration > 0) json.name("duration_seconds").value((long) Math.round(media.duration));
        if (media.width > 0) json.name("width").value(media.width);
        if (media.height > 0) json.name("height").value(media.height);
    }

    private void writeJsonContactGeo(TLRPC.MessageMedia mm) throws IOException {
        if (mm instanceof TLRPC.TL_messageMediaContact) {
            json.name("contact_information").beginObject();
            json.name("first_name").value(mm.first_name != null ? mm.first_name : "");
            json.name("last_name").value(mm.last_name != null ? mm.last_name : "");
            json.name("phone_number").value(mm.phone_number != null ? mm.phone_number : "");
            json.endObject();
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
    // media

    private MediaInfo media(TLRPC.Message m) throws Exception {
        TLRPC.MessageMedia mm = m.media;
        if (mm instanceof TLRPC.TL_messageMediaPhoto && mm.photo != null) {
            TLRPC.Photo photo = mm.photo;
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 2560);
            MediaInfo info = new MediaInfo();
            info.jsonKey = "photo";
            info.folder = "photos";
            if (size != null) {
                info.width = size.w;
                info.height = size.h;
                info.size = size.size;
            }
            if (!options.photos || size == null) {
                info.path = NOT_INCLUDED;
            } else if (options.sizeLimit > 0 && info.size > options.sizeLimit) {
                info.path = TOO_BIG;
            } else {
                String name = "photo_" + m.id + "@" + fileFormat.format(new Date(m.date * 1000L)) + ".jpg";
                if (size instanceof TLRPC.TL_photoCachedSize && size.bytes != null && size.bytes.length > 0) {
                    info.path = writeBytes(info.folder, name, "image/jpeg", size.bytes);
                } else if (size instanceof TLRPC.TL_photoStrippedSize) {
                    info.path = NOT_INCLUDED;
                } else {
                    TLRPC.TL_inputPhotoFileLocation loc = new TLRPC.TL_inputPhotoFileLocation();
                    loc.id = photo.id;
                    loc.access_hash = photo.access_hash;
                    loc.file_reference = photo.file_reference != null ? photo.file_reference : new byte[0];
                    loc.thumb_size = size.type != null ? size.type : "";
                    info.path = download(loc, photo.dc_id, info.size, info.folder, name, "image/jpeg");
                }
                info.downloaded = info.path != null && !info.path.startsWith("(");
            }
            return info;
        }
        if (mm instanceof TLRPC.TL_messageMediaDocument && mm.document != null) {
            TLRPC.Document doc = mm.document;
            MediaInfo info = new MediaInfo();
            info.jsonKey = "file";
            info.size = doc.size;
            info.mime = doc.mime_type;
            info.fileName = FileLoader.getDocumentFileName(doc);
            boolean wanted;
            if (MessageObject.isRoundVideoDocument(doc)) {
                info.mediaType = "video_message";
                info.folder = "round_video_messages";
                wanted = options.voice;
            } else if (MessageObject.isVoiceDocument(doc)) {
                info.mediaType = "voice_message";
                info.folder = "voice_messages";
                wanted = options.voice;
            } else if (MessageObject.isStickerDocument(doc) || MessageObject.isAnimatedStickerDocument(doc, true)) {
                info.mediaType = "sticker";
                info.folder = "stickers";
                wanted = options.files;
            } else if (MessageObject.isGifDocument(doc)) {
                info.mediaType = "animation";
                info.folder = "video_files";
                wanted = options.videos;
            } else if (MessageObject.isVideoDocument(doc)) {
                info.mediaType = "video_file";
                info.folder = "video_files";
                wanted = options.videos;
            } else if (MessageObject.isMusicDocument(doc)) {
                info.mediaType = "audio_file";
                info.folder = "files";
                wanted = options.files;
            } else {
                info.folder = "files";
                wanted = options.files;
            }
            for (TLRPC.DocumentAttribute a : doc.attributes) {
                if (a instanceof TLRPC.TL_documentAttributeVideo || a instanceof TLRPC.TL_documentAttributeImageSize) {
                    if (a.w > 0) info.width = a.w;
                    if (a.h > 0) info.height = a.h;
                } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                    info.performer = a.performer;
                    info.title = a.title;
                } else if (a instanceof TLRPC.TL_documentAttributeSticker) {
                    info.emoji = a.alt;
                }
            }
            try {
                info.duration = MessageObject.getDocumentDuration(doc);
            } catch (Throwable ignore) {
            }
            if (!wanted) {
                info.path = NOT_INCLUDED;
            } else if (options.sizeLimit > 0 && doc.size > options.sizeLimit) {
                info.path = TOO_BIG;
            } else {
                String name = safeName(info.fileName);
                if (TextUtils.isEmpty(name)) {
                    String ext = extensionFor(doc.mime_type, info.mediaType);
                    String prefix = info.mediaType != null ? info.mediaType.replace("_message", "") : "file";
                    name = prefix + "_" + m.id + "@" + fileFormat.format(new Date(m.date * 1000L)) + ext;
                }
                TLRPC.TL_inputDocumentFileLocation loc = new TLRPC.TL_inputDocumentFileLocation();
                loc.id = doc.id;
                loc.access_hash = doc.access_hash;
                loc.file_reference = doc.file_reference != null ? doc.file_reference : new byte[0];
                loc.thumb_size = "";
                info.path = download(loc, doc.dc_id, doc.size, info.folder, name,
                        TextUtils.isEmpty(doc.mime_type) ? "application/octet-stream" : doc.mime_type);
                info.downloaded = info.path != null && !info.path.startsWith("(");
            }
            return info;
        }
        return null;
    }

    private static String extensionFor(String mime, String mediaType) {
        if ("voice_message".equals(mediaType)) return ".ogg";
        if ("video_message".equals(mediaType)) return ".mp4";
        if ("sticker".equals(mediaType)) {
            if ("application/x-tgsticker".equals(mime)) return ".tgs";
            if ("video/webm".equals(mime)) return ".webm";
            return ".webp";
        }
        if (mime != null) {
            switch (mime) {
                case "video/mp4":
                    return ".mp4";
                case "image/jpeg":
                    return ".jpg";
                case "image/png":
                    return ".png";
                case "image/gif":
                    return ".gif";
                case "audio/mpeg":
                    return ".mp3";
                case "audio/ogg":
                    return ".ogg";
                case "application/pdf":
                    return ".pdf";
            }
        }
        return "";
    }

    private String writeBytes(String folder, String name, String mime, byte[] data) throws IOException {
        Uri dir = subdir(folder);
        Uri out = DocumentsContract.createDocument(resolver, dir, mime, name);
        if (out == null) {
            failedCount++;
            return FAILED;
        }
        try (OutputStream os = resolver.openOutputStream(out, "w")) {
            if (os == null) {
                failedCount++;
                return FAILED;
            }
            os.write(data);
        }
        mediaCount++;
        return folder + "/" + displayName(out, name);
    }

    /** Downloads a file into {@code folder}; returns its relative path, or a TD placeholder on failure. */
    private String download(TLRPC.InputFileLocation location, int dcId, long size, String folder, String name, String mime) throws Exception {
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
                failedCount++;
                return FAILED;
            }
            long offset = 0;
            while (!cancelled.get()) {
                TLRPC.TL_upload_getFile req = new TLRPC.TL_upload_getFile();
                req.location = location;
                req.offset = offset;
                req.limit = CHUNK;
                req.cdn_supported = false;
                TLObject res = call(req, dcId > 0 ? dcId : ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeDownload);
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
        return folder + "/" + displayName(out, name);
    }

    // =====================================================================================
    // HTML

    private void writeHtml(TLRPC.Message m, boolean service, String fromName, ArrayList<Part> parts, MediaInfo media) throws IOException {
        Date date = new Date(m.date * 1000L);
        String day = dayFormat.format(date);
        if (!day.equals(lastHtmlDay)) {
            lastHtmlDay = day;
            html.write("<div class=\"day\"><span>" + esc(day) + "</span></div>\n");
        }
        if (service) {
            html.write("<div class=\"service\" id=\"message" + m.id + "\">");
            html.write(esc(fromName));
            html.write(" · ");
            html.write(esc(actionName(m.action).replace('_', ' ')));
            if (m.action != null && !TextUtils.isEmpty(m.action.title)) {
                html.write(" «" + esc(m.action.title) + "»");
            }
            html.write("</div>\n");
            return;
        }
        html.write("<div class=\"message" + (m.out ? " out" : "") + "\" id=\"message" + m.id + "\">");
        html.write("<span class=\"date\" title=\"" + esc(fullFormat.format(date)) + "\">" + esc(timeFormat.format(date))
                + (m.edit_date != 0 ? " ✎" : "") + "</span>");
        html.write("<div class=\"from\">" + esc(fromName) + "</div>");
        if (m.fwd_from != null) {
            String fwd = m.fwd_from.from_name;
            if (fwd == null) fwd = peerName(m.fwd_from.from_id);
            html.write("<div class=\"fwd\">Forwarded from " + esc(fwd != null ? fwd : "?") + "</div>");
        }
        if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
            html.write("<div class=\"reply\">In reply to <a href=\"#message" + m.reply_to.reply_to_msg_id + "\">this message</a></div>");
        }
        if (media != null) {
            html.write("<div class=\"media\">");
            if (!media.downloaded) {
                String what = media.mediaType != null ? media.mediaType.replace('_', ' ') : ("photo".equals(media.jsonKey) ? "photo" : "file");
                html.write("<span class=\"missing\">[" + esc(what) + (media.fileName != null ? ": " + esc(media.fileName) : "") + "] " + esc(media.path) + "</span>");
            } else {
                String href = escAttr(encodePath(media.path));
                if ("photo".equals(media.jsonKey)) {
                    html.write("<a href=\"" + href + "\"><img src=\"" + href + "\" loading=\"lazy\" alt=\"photo\"></a>");
                } else if ("video_file".equals(media.mediaType) || "animation".equals(media.mediaType) || "video_message".equals(media.mediaType)) {
                    html.write("<video controls preload=\"none\" src=\"" + href + "\"" + ("animation".equals(media.mediaType) ? " loop muted" : "") + "></video>");
                } else if ("voice_message".equals(media.mediaType) || "audio_file".equals(media.mediaType)) {
                    html.write("<audio controls preload=\"none\" src=\"" + href + "\"></audio>");
                    if ("audio_file".equals(media.mediaType)) {
                        html.write("<div><a href=\"" + href + "\">" + esc(media.fileName != null ? media.fileName : media.path) + "</a></div>");
                    }
                } else if ("sticker".equals(media.mediaType) && media.path.endsWith(".webp")) {
                    html.write("<img src=\"" + href + "\" style=\"max-width:160px\" alt=\"sticker\">");
                } else {
                    html.write("<a href=\"" + href + "\">📎 " + esc(media.fileName != null ? media.fileName : media.path) + "</a>");
                    if (media.size > 0) html.write(" <span class=\"missing\">" + esc(AndroidUtilities.formatFileSize(media.size)) + "</span>");
                }
            }
            html.write("</div>");
        }
        TLRPC.MessageMedia mm = m.media;
        if (mm instanceof TLRPC.TL_messageMediaContact) {
            html.write("<div class=\"media\">👤 " + esc((mm.first_name != null ? mm.first_name : "") + " " + (mm.last_name != null ? mm.last_name : "")) + " " + esc(mm.phone_number != null ? mm.phone_number : "") + "</div>");
        } else if (mm instanceof TLRPC.TL_messageMediaGeo && mm.geo != null) {
            String q = mm.geo.lat + "," + mm.geo._long;
            html.write("<div class=\"media\"><a href=\"https://maps.google.com/maps?q=" + escAttr(q) + "\">📍 " + esc(q) + "</a></div>");
        }
        StringBuilder body = new StringBuilder();
        for (Part p : parts) body.append(htmlPart(p));
        if (body.length() > 0) {
            html.write("<div class=\"text\">");
            html.write(body.toString());
            html.write("</div>");
        }
        html.write("</div>\n");
    }

    private static String htmlPart(Part p) {
        String t = esc(p.text);
        switch (p.type) {
            case "plain":
                return t;
            case "bold":
                return "<b>" + t + "</b>";
            case "italic":
                return "<i>" + t + "</i>";
            case "underline":
                return "<u>" + t + "</u>";
            case "strikethrough":
                return "<s>" + t + "</s>";
            case "code":
                return "<code>" + t + "</code>";
            case "pre":
                return "<pre>" + t + "</pre>";
            case "spoiler":
                return "<span class=\"spoiler\">" + t + "</span>";
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

    /** Account to export from: the one the user is looking at. */
    public static int defaultAccount() {
        return UserConfig.selectedAccount;
    }
}
