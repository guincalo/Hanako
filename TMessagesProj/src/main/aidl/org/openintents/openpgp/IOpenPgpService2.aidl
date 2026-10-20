// plus f18: mirror of the OpenPGP API service interface (org.openintents.openpgp,
// as published by OpenKeychain's openpgp-api). Only this AIDL plus the four
// result Parcelables in java/org/openintents/openpgp are mirrored; the
// openpgp-api library itself is not added. The package and method order must
// stay exactly as upstream: transaction codes are positional.
package org.openintents.openpgp;

interface IOpenPgpService2 {

    ParcelFileDescriptor createOutputPipe(in int pipeId);

    Intent execute(in Intent data, in ParcelFileDescriptor input, int pipeId);
}
