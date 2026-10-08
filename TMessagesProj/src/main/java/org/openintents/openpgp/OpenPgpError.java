package org.openintents.openpgp;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * plus f18: wire-compatible mirror of openpgp-api's OpenPgpError. The provider
 * puts it in result intents under "error"; the class name must match for the
 * extras Bundle to unparcel. Versioned parcel: version, size, fields; unknown
 * trailing fields from newer providers are skipped by size.
 */
public class OpenPgpError implements Parcelable {
    public static final int PARCELABLE_VERSION = 1;

    public static final int CLIENT_SIDE_ERROR = -1;
    public static final int GENERIC_ERROR = 0;
    public static final int INCOMPATIBLE_API_VERSIONS = 1;
    public static final int NO_OR_WRONG_PASSPHRASE = 2;
    public static final int NO_USER_IDS = 3;
    public static final int OPPORTUNISTIC_MISSING_KEYS = 4;

    public int errorId;
    public String message;

    public OpenPgpError() {
    }

    public OpenPgpError(int errorId, String message) {
        this.errorId = errorId;
        this.message = message;
    }

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
        dest.writeInt(errorId);
        dest.writeString(message);
        int size = dest.dataPosition() - startPosition;
        dest.setDataPosition(sizePosition);
        dest.writeInt(size);
        dest.setDataPosition(startPosition + size);
    }

    public static final Creator<OpenPgpError> CREATOR = new Creator<OpenPgpError>() {
        @Override
        public OpenPgpError createFromParcel(Parcel source) {
            source.readInt(); // version
            int size = source.readInt();
            int start = source.dataPosition();
            OpenPgpError e = new OpenPgpError();
            e.errorId = source.readInt();
            e.message = source.readString();
            source.setDataPosition(start + size);
            return e;
        }

        @Override
        public OpenPgpError[] newArray(int size) {
            return new OpenPgpError[size];
        }
    };
}
