package it.belloworld.mercurygram;

import org.telegram.tgnet.TLRPC;

/**
 * Mercurygram saved-history media: a photo / thumbnail location that points at
 * a copy kept in app-private storage by {@link MgHistoryMedia}. Keeps every
 * field of the original location (so file names, keys and serialization stay
 * the same) and adds the local {@link #path}, which
 * {@code FileLoader.getPathToAttach} and {@code ImageLocation} resolve first.
 * Only ever set on messages rebuilt from the history DB, never on live ones.
 */
public class MgFileLocation extends TLRPC.TL_fileLocationToBeDeprecated {

    public final String path;

    public MgFileLocation(TLRPC.FileLocation src, String path) {
        this.path = path;
        if (src != null) {
            dc_id = src.dc_id;
            volume_id = src.volume_id;
            local_id = src.local_id;
            secret = src.secret;
            file_reference = src.file_reference;
            key = src.key;
            iv = src.iv;
        }
    }
}
