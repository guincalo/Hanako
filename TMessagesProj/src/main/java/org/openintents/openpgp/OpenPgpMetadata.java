package org.openintents.openpgp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * plus f18: mirror of openpgp-api's OpenPgpMetadata (result extra "metadata"
 * of DECRYPT_VERIFY). Present only so the result Bundle unparcels; the
 * charset (version 2) is used to decode the plaintext.
 */
public class OpenPgpMetadata implements Parcelable {
    public static final int PARCELABLE_VERSION = 2;

    public String filename;
    public String mimeType;
    public String charset;
    public long modificationTime;
    public long originalSize;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(PARCELABLE_VERSION);
        int sizePosition = dest.dataPosition();
        dest.writeInt(0);
        int startPosition = dest.dataPosition();
        dest.writeString(filename);
        dest.writeString(mimeType);
        dest.writeLong(modificationTime);
        dest.writeLong(originalSize);
        dest.writeString(charset);
        int size = dest.dataPosition() - startPosition;
        dest.setDataPosition(sizePosition);
        dest.writeInt(size);
        dest.setDataPosition(startPosition + size);
    }

    public static final Creator<OpenPgpMetadata> CREATOR = new Creator<OpenPgpMetadata>() {
        @Override
        public OpenPgpMetadata createFromParcel(Parcel source) {
            int version = source.readInt();
            int size = source.readInt();
            int start = source.dataPosition();
            OpenPgpMetadata m = new OpenPgpMetadata();
            m.filename = source.readString();
            m.mimeType = source.readString();
            m.modificationTime = source.readLong();
            m.originalSize = source.readLong();
            if (version >= 2) {
                m.charset = source.readString();
            }
            source.setDataPosition(start + size);
            return m;
        }

        @Override
        public OpenPgpMetadata[] newArray(int size) {
            return new OpenPgpMetadata[size];
        }
    };
}
