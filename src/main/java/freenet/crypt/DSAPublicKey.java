/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.crypt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

import net.i2p.util.NativeBigInteger;

import com.db4o.ObjectContainer;

import freenet.store.StorableBlock;
import freenet.support.Base64;
import freenet.support.HexUtil;
import freenet.support.IllegalBase64Exception;
import freenet.support.SimpleFieldSet;

public class DSAPublicKey extends CryptoKey implements StorableBlock {

	private static final long serialVersionUID = -1;
	private final BigInteger y;
	public static final int PADDED_SIZE = 1024;
	public static final int HASH_LENGTH = 32;
	private final DSAGroup group;
	private byte[] fingerprint = null;

	public DSAPublicKey(DSAGroup g, BigInteger y) {
		if(y.signum() != 1)
			throw new IllegalArgumentException();
		this.y = y;
		this.group = g;
		if(y.compareTo(g.getP()) > 0)
			throw new IllegalArgumentException("y must be < p but y=" + y + " p=" + g.getP());
	}

	/**
	 * Use this constructor if you have a Hex:ed version of y already
	 * available, will save some conversions and string allocations.
	 */
	public DSAPublicKey(DSAGroup g, String yAsHexString) throws NumberFormatException {
		this.y = new NativeBigInteger(yAsHexString, 16);
		if(y.signum() != 1)
			throw new IllegalArgumentException();
		this.group = g;
		if(g == null)
			throw new NullPointerException();
	}

	public DSAPublicKey(DSAGroup g, DSAPrivateKey p) {
		this(g, g.getG().modPow(p.getX(), g.getP()));
	}

	public DSAPublicKey(InputStream is) throws IOException, CryptFormatException {
		group = (DSAGroup) DSAGroup.read(is);
		y = Util.readMPI(is);
		if(y.compareTo(group.getP()) > 0)
			throw new IllegalArgumentException("y must be < p but y=" + y + " p=" + group.getP());
	}

	public DSAPublicKey(byte[] pubkeyBytes) throws IOException, CryptFormatException {
		this(new ByteArrayInputStream(pubkeyBytes));
	}

	private DSAPublicKey(DSAPublicKey key) {
		fingerprint = null; // regen when needed
		this.y = new NativeBigInteger(1, key.y.toByteArray());
		this.group = key.group.cloneKey();
	}

	public static DSAPublicKey create(byte[] pubkeyAsBytes) throws CryptFormatException {
		try {
			return new DSAPublicKey(new ByteArrayInputStream(pubkeyAsBytes));
		} catch(IOException e) {
			throw new CryptFormatException(e);
		}
	}

	public BigInteger getY() {
		return y;
	}

	public BigInteger getP() {
		return group.getP();
	}

	public BigInteger getQ() {
		return group.getQ();
	}

	public BigInteger getG() {
		return group.getG();
	}

	@Override
	public String keyType() {
		return "DSA.p";
	}

	// Nope, this is fine
	public DSAGroup getGroup() {
		return group;
	}

//    public void writeForWireWithoutGroup(OutputStream out) throws IOException {
//		Util.writeMPI(y, out);
//    }
//
//    public void writeForWire(OutputStream out) throws IOException {
//		Util.writeMPI(y, out);
//		group.writeForWire(out);
//    }
//
//    public void writeWithoutGroup(OutputStream out) 
//	throws IOException {
//		write(out, getClass().getName());
//		Util.writeMPI(y, out);
//    }
//
	public static CryptoKey read(InputStream i) throws IOException, CryptFormatException {
		return new DSAPublicKey(i);
	}

	public int keyId() {
		return y.intValue();
	}

	public String toLongString() {
		return "y=" + HexUtil.biToHex(y);
	}

	// this won't correctly read the output from writeAsField
	//public static CryptoKey readFromField(DSAGroup group, String field) {
	//    BigInteger y=Util.byteArrayToMPI(Util.hexToBytes(field));
	//    return new DSAPublicKey(group, y);
	//}
	@Override
	public byte[] asBytes() {
		byte[] groupBytes = group.asBytes();
		byte[] ybytes = Util.MPIbytes(y);
		byte[] bytes = new byte[groupBytes.length + ybytes.length];
		System.arraycopy(groupBytes, 0, bytes, 0, groupBytes.length);
		System.arraycopy(ybytes, 0, bytes, groupBytes.length, ybytes.length);
		return bytes;
	}

	public byte[] asBytesHash() {
		byte[] hash = SHA256.digest(asBytes());
		return hash;
	}

	public byte[] asPaddedBytes() {
		byte[] asBytes = asBytes();
		if(asBytes.length == PADDED_SIZE)
			return asBytes;
		if(asBytes.length > PADDED_SIZE)
			throw new Error("Cannot fit key in " + PADDED_SIZE + " - real size is " + asBytes.length);
		byte[] padded = new byte[PADDED_SIZE];
		System.arraycopy(asBytes, 0, padded, 0, asBytes.length);
		return padded;
	}

	@Override
	public byte[] fingerprint() {
		synchronized(this) {
			if(fingerprint == null)
				fingerprint = fingerprint(new BigInteger[]{y});
			return fingerprint;
		}
	}

	public boolean equals(DSAPublicKey o) {
		if(this == o) // Not necessary, but a very cheap optimization
			return true;
		return y.equals(o.y) && group.equals(o.group);
	}

	@Override
	public int hashCode() {
		return y.hashCode() ^ group.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if(this == o) // Not necessary, but a very cheap optimization
			return true;
		else if((o == null) || (o.getClass() != this.getClass()))
			return false;
		return y.equals(((DSAPublicKey) o).y) && group.equals(((DSAPublicKey) o).group);
	}

	public int compareTo(Object other) {
		if(other instanceof DSAPublicKey)
			return getY().compareTo(((DSAPublicKey) other).getY());
		else
			return -1;
	}

	public SimpleFieldSet asFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("y", Base64.encode(y.toByteArray()));
		return fs;
	}

	public static DSAPublicKey create(SimpleFieldSet set, DSAGroup group) throws IllegalBase64Exception {
		NativeBigInteger x =
			new NativeBigInteger(1, Base64.decode(set.get("y")));
		return new DSAPublicKey(group, x);
	}

	public byte[] getFullKey() {
		return asBytesHash();
	}

	public byte[] getRoutingKey() {
		return asBytesHash();
	}

	public DSAPublicKey cloneKey() {
		return new DSAPublicKey(this);
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(y);
		group.removeFrom(container);
		container.delete(this);
	}
}
