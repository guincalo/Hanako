package org.openintents.openpgp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * plus f18: mirror of openpgp-api's OpenPgpDecryptionResult (result extra
 * "decryption"). Session keys (version 2+) are skipped by size, never read.
 */
public class OpenPgpDecryptionResult implements Parcelable {
    public static final int PARCELABLE_VERSION = 1;

    public static final int RESULT_NOT_ENCRYPTED = -1;
    public static final int RESULT_INSECURE = 0;
    public static final int RESULT_ENCRYPTED = 1;

    public int result = RESULT_NOT_ENCRYPTED;

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
        dest.writeInt(result);
        int size = dest.dataPosition() - startPosition;
        dest.setDataPosition(sizePosition);
        dest.writeInt(size);
        dest.setDataPosition(startPosition + size);
    }

    public static final Creator<OpenPgpDecryptionResult> CREATOR = new Creator<OpenPgpDecryptionResult>() {
        @Override
        public OpenPgpDecryptionResult createFromParcel(Parcel source) {
            source.readInt(); // version
            int size = source.readInt();
            int start = source.dataPosition();
            OpenPgpDecryptionResult r = new OpenPgpDecryptionResult();
            r.result = source.readInt();
            source.setDataPosition(start + size);
            return r;
        }

        @Override
        public OpenPgpDecryptionResult[] newArray(int size) {
            return new OpenPgpDecryptionResult[size];
        }
    };
}
