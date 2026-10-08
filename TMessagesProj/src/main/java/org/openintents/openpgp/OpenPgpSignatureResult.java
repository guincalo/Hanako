package org.openintents.openpgp;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

/**
 * plus f18: read-mostly mirror of openpgp-api's OpenPgpSignatureResult
 * (result extra "signature" of DECRYPT_VERIFY). Only the version 1-2 fields
 * are read; anything newer (sender status, timestamps, autocrypt) is skipped
 * by the parcel size the provider writes.
 */
public class OpenPgpSignatureResult implements Parcelable {
    public static final int PARCELABLE_VERSION = 2;

    public static final int RESULT_NO_SIGNATURE = -1;
    public static final int RESULT_INVALID_SIGNATURE = 0;
    public static final int RESULT_VALID_KEY_CONFIRMED = 1;
    public static final int RESULT_KEY_MISSING = 2;
    public static final int RESULT_VALID_KEY_UNCONFIRMED = 3;
    public static final int RESULT_INVALID_KEY_REVOKED = 4;
    public static final int RESULT_INVALID_KEY_EXPIRED = 5;
    public static final int RESULT_INVALID_KEY_INSECURE = 6;
    public static final int RESULT_INVALID_NOT_INTENDED_RECIPIENT = 7;

    public int result = RESULT_NO_SIGNATURE;
    public String primaryUserId;
    public long keyId;
    public ArrayList<String> userIds;

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
        dest.writeByte((byte) 0); // signatureOnly, deprecated upstream
        dest.writeString(primaryUserId);
        dest.writeLong(keyId);
        dest.writeStringList(userIds);
        int size = dest.dataPosition() - startPosition;
        dest.setDataPosition(sizePosition);
        dest.writeInt(size);
        dest.setDataPosition(startPosition + size);
    }

    public static final Creator<OpenPgpSignatureResult> CREATOR = new Creator<OpenPgpSignatureResult>() {
        @Override
        public OpenPgpSignatureResult createFromParcel(Parcel source) {
            int version = source.readInt();
            int size = source.readInt();
            int start = source.dataPosition();
            OpenPgpSignatureResult r = new OpenPgpSignatureResult();
            r.result = source.readInt();
            source.readByte();
            r.primaryUserId = source.readString();
            r.keyId = source.readLong();
            if (version > 1) {
                r.userIds = source.createStringArrayList();
            }
            source.setDataPosition(start + size);
            return r;
        }

        @Override
        public OpenPgpSignatureResult[] newArray(int size) {
            return new OpenPgpSignatureResult[size];
        }
    };
}
