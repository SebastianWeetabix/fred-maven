package freenet.client.async;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;

import com.db4o.ObjectContainer;

import freenet.keys.ClientCHK;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.support.Logger;

/** Contains the keys for a splitfile segment, in an efficient compressed form. These are 
 * not changed, so the object never needs to be stored once created; this is good as it is
 * fairly large. SplitFileFetcherSegment keeps the data on which keys have been used 
 * separately and passes it in.
 * 
 * We will auto-upgrade SplitFileFetcherSegment's to use SplitFileSegmentKeys in the caller.
 * 
 * @author toad
 */
public class SplitFileSegmentKeys implements Cloneable {
	
	public final int dataBlocks;
	public final int checkBlocks;
	/** Modern splitfiles have a common decrypt key */
	public final byte[] commonDecryptKey;
	/** Modern splitfiles have common extra bytes */
	public final byte[] commonExtraBytes;
	/** Routing keys. */
	public final byte[] routingKeys;
	/** Individual per-block decryption keys. Only on older splitfiles. */
	public final byte[] decryptKeys;
	/** Individual per-block extra bytes. */
	public final byte[] extraBytesForKeys;
	
	static final int EXTRA_BYTES_LENGTH = ClientCHK.EXTRA_LENGTH;

	// Bare constructor for Metadata. It will read the actual keys later.
	public SplitFileSegmentKeys(int blocksPerSegment, int checkBlocksPerSegment, byte[] splitfileSingleCryptoKey, byte splitfileSingleCryptoAlgorithm) {
		this.dataBlocks = blocksPerSegment;
		this.checkBlocks = checkBlocksPerSegment;
		routingKeys = new byte[NodeCHK.KEY_LENGTH * (dataBlocks + checkBlocks)];
		if(splitfileSingleCryptoKey != null) {
			commonDecryptKey = splitfileSingleCryptoKey;
			commonExtraBytes = ClientCHK.getExtra(splitfileSingleCryptoAlgorithm, (short)-1, false);
			decryptKeys = null;
			extraBytesForKeys = null;
		} else {
			commonDecryptKey = null;
			commonExtraBytes = null;
			decryptKeys = new byte[ClientCHK.CRYPTO_KEY_LENGTH * (dataBlocks + checkBlocks)];
			extraBytesForKeys = new byte[EXTRA_BYTES_LENGTH * (dataBlocks + checkBlocks)];
		}
	}

	public int getBlockNumber(ClientCHK key, boolean[] ignoreSlots) {
		byte[] rkey = key.getRoutingKey();
		byte[] ckey = null;
		byte[] extra = null;
		// Arrays.equals() doesn't do offsets, so implement ourselves
		int x = 0;
outer:	for(int i=0;i<(dataBlocks + checkBlocks);i++) {
			int oldX = x;
			x += NodeCHK.KEY_LENGTH;
			if(ignoreSlots != null && ignoreSlots[i]) {
				continue;
			}
			for(int j=0;j<NodeCHK.KEY_LENGTH;j++) {
				if(routingKeys[j + oldX] != rkey[j])
					continue outer;
			}
			if(ckey == null) ckey = key.getCryptoKey();
			assert(ClientCHK.CRYPTO_KEY_LENGTH == NodeCHK.KEY_LENGTH);
			// FIXME USE THE RIGHT CONSTANT, DONT ASSUME THE TWO LENGTHS ARE THE SAME
			if(commonDecryptKey != null) {
				if(!Arrays.equals(commonDecryptKey, ckey)) continue;
			} else {
				for(int j=0;j<NodeCHK.KEY_LENGTH;j++) {
					if(decryptKeys[j + oldX] != ckey[j])
						continue outer;
				}
			}
			if(extra == null) extra = key.getExtra();
			if(commonExtraBytes != null) {
				if(!Arrays.equals(commonExtraBytes, extra)) continue;
			} else {
				int offset = i * EXTRA_BYTES_LENGTH;
				for(int j=0;j<EXTRA_BYTES_LENGTH;j++) {
					if(extraBytesForKeys[j + offset] != extra[j]) continue outer;
				}
			}
			return i;
		}
		return -1;
	}
	
	public int getBlockNumber(NodeCHK key, boolean[] ignoreSlots) {
		byte[] rkey = key.getRoutingKey();
		// Arrays.equals() doesn't do offsets, so implement ourselves
		int x = 0;
outer:	for(int i=0;i<(dataBlocks + checkBlocks);i++) {
			int oldX = x;
			x += NodeCHK.KEY_LENGTH;
			if(ignoreSlots != null && ignoreSlots[i]) {
				continue;
			}
			for(int j=0;j<NodeCHK.KEY_LENGTH;j++) {
				if(routingKeys[j + oldX] != rkey[j])
					continue outer;
			}
			return i;
		}
		return -1;
	}
	
	public int[] getBlockNumbers(NodeCHK key, boolean[] ignoreSlots) {
		ArrayList<Integer> results = null;
		byte[] rkey = key.getRoutingKey();
		// Arrays.equals() doesn't do offsets, so implement ourselves
		int x = 0;
outer:	for(int i=0;i<(dataBlocks + checkBlocks);i++) {
			int oldX = x;
			x += NodeCHK.KEY_LENGTH;
			if(ignoreSlots != null && ignoreSlots[i]) {
				continue;
			}
			for(int j=0;j<NodeCHK.KEY_LENGTH;j++) {
				if(routingKeys[j + oldX] != rkey[j])
					continue outer;
			}
			if(results == null) results = new ArrayList<Integer>();
			results.add(i);
		}
		if(results == null) return new int[0];
		int[] ret = new int[results.size()];
		for(int i=0;i<ret.length;i++) ret[i] = results.get(i);
		return ret;
	}
	
	public NodeCHK getNodeKey(int x, boolean[] ignoreSlots, boolean copy) {
		if(ignoreSlots != null) {
			if(ignoreSlots[x]) return null;
		}
		return getNodeKey(x, copy);
	}
	
	public ClientCHK getKey(int x, boolean[] ignoreSlots, boolean copy) {
		if(ignoreSlots != null) {
			if(ignoreSlots[x]) return null;
		}
		return getKey(x, copy);
	}

	private ClientCHK getKey(int x, boolean copy) {
		byte[] routingKey = new byte[32];
		System.arraycopy(routingKeys, x * NodeCHK.KEY_LENGTH, routingKey, 0, NodeCHK.KEY_LENGTH);
		byte[] decryptKey;
		if(commonDecryptKey != null) {
			if(copy) {
				decryptKey = new byte[ClientCHK.CRYPTO_KEY_LENGTH];
				System.arraycopy(commonDecryptKey, 0, decryptKey, 0, ClientCHK.CRYPTO_KEY_LENGTH);
			} else {
				decryptKey = commonDecryptKey;
			}
		} else {
			decryptKey = new byte[ClientCHK.CRYPTO_KEY_LENGTH];
			System.arraycopy(decryptKeys, x * ClientCHK.CRYPTO_KEY_LENGTH, decryptKey, 0, ClientCHK.CRYPTO_KEY_LENGTH);
		}
		byte[] extra;
		if(commonExtraBytes != null) {
			if(copy) {
				extra = new byte[EXTRA_BYTES_LENGTH];
				System.arraycopy(commonExtraBytes, 0, extra, 0, EXTRA_BYTES_LENGTH);
			} else {
				extra = commonExtraBytes;
			}
		} else {
			extra = new byte[EXTRA_BYTES_LENGTH];
			System.arraycopy(extraBytesForKeys, x * EXTRA_BYTES_LENGTH, extra, 0, EXTRA_BYTES_LENGTH);
		}
		try {
			return new ClientCHK(routingKey, decryptKey, extra);
		} catch (MalformedURLException e) {
			Logger.error(this, "Impossible: "+e);
			throw new IllegalStateException(e);
		}
	}

	private NodeCHK getNodeKey(int x, boolean copy) {
		byte[] routingKey = new byte[32];
		System.arraycopy(routingKeys, x * NodeCHK.KEY_LENGTH, routingKey, 0, NodeCHK.KEY_LENGTH);
		byte[] extra;
		if(commonExtraBytes != null) {
			extra = commonExtraBytes;
		} else {
			extra = new byte[EXTRA_BYTES_LENGTH];
			System.arraycopy(extraBytesForKeys, x * EXTRA_BYTES_LENGTH, extra, 0, EXTRA_BYTES_LENGTH);
		}
		
		byte cryptoAlgorithm = ClientCHK.getCryptoAlgorithmFromExtra(extra);
		
		return new NodeCHK(routingKey, cryptoAlgorithm);
	}

	public void readKeys(DataInputStream dis, boolean check) throws IOException {
		int count = check ? checkBlocks : dataBlocks;
		int offset = check ? dataBlocks : 0;
		if(commonDecryptKey != null) {
			int rkOffset = offset * NodeCHK.KEY_LENGTH;
			for(int i=0;i<count;i++) {
				dis.readFully(routingKeys, rkOffset, NodeCHK.KEY_LENGTH);
				rkOffset += NodeCHK.KEY_LENGTH;
			}
		} else {
			int rkOffset = offset * NodeCHK.KEY_LENGTH;
			int extraOffset = offset * EXTRA_BYTES_LENGTH;
			assert(NodeCHK.KEY_LENGTH == ClientCHK.CRYPTO_KEY_LENGTH);
			for(int i=0;i<count;i++) {
				ClientCHK key = ClientCHK.readRawBinaryKey(dis);
				byte[] r = key.getRoutingKey();
				System.arraycopy(r, 0, routingKeys, rkOffset, NodeCHK.KEY_LENGTH);
				byte[] c = key.getCryptoKey();
				System.arraycopy(c, 0, decryptKeys, rkOffset, NodeCHK.KEY_LENGTH);
				rkOffset += NodeCHK.KEY_LENGTH;
				byte[] e = key.getExtra();
				System.arraycopy(e, 0, extraBytesForKeys, extraOffset, EXTRA_BYTES_LENGTH);
				extraOffset += EXTRA_BYTES_LENGTH;
			}
		}
	}

	public void writeKeys(DataOutputStream dos, boolean check) throws IOException {
		int count = check ? checkBlocks : dataBlocks;
		int offset = check ? dataBlocks : 0;
		if(commonDecryptKey != null) {
			int rkOffset = offset * NodeCHK.KEY_LENGTH;
			for(int i=0;i<count;i++) {
				dos.write(routingKeys, rkOffset, NodeCHK.KEY_LENGTH);
				rkOffset += NodeCHK.KEY_LENGTH;
			}
		} else {
			int rkOffset = offset * NodeCHK.KEY_LENGTH;
			int extraOffset = offset * EXTRA_BYTES_LENGTH;
			assert(NodeCHK.KEY_LENGTH == ClientCHK.CRYPTO_KEY_LENGTH);
			for(int i=0;i<count;i++) {
				dos.write(extraBytesForKeys, extraOffset, EXTRA_BYTES_LENGTH);
				extraOffset += EXTRA_BYTES_LENGTH;
				dos.write(routingKeys, rkOffset, NodeCHK.KEY_LENGTH);
				dos.write(decryptKeys, rkOffset, NodeCHK.KEY_LENGTH);
				rkOffset += NodeCHK.KEY_LENGTH;
			}
		}
	}

	public int getDataBlocks() {
		return dataBlocks;
	}

	public int getCheckBlocks() {
		return checkBlocks;
	}

	public void setKey(int i, ClientCHK key) {
		byte[] r = key.getRoutingKey();
		System.arraycopy(r, 0, routingKeys, i * NodeCHK.KEY_LENGTH, NodeCHK.KEY_LENGTH);
		if(decryptKeys != null) {
			byte[] c = key.getCryptoKey();
			System.arraycopy(c, 0, decryptKeys, i * ClientCHK.CRYPTO_KEY_LENGTH, ClientCHK.CRYPTO_KEY_LENGTH);
		}
		if(extraBytesForKeys != null) {
			byte[] e = key.getExtra();
			System.arraycopy(e, 0, extraBytesForKeys, i * EXTRA_BYTES_LENGTH, EXTRA_BYTES_LENGTH);
		}
	}

	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

	public NodeCHK[] listNodeKeys(boolean[] foundKeys, boolean copy) {
		ArrayList<NodeCHK> list = new ArrayList<NodeCHK>();
		for(int i=0;i<dataBlocks+checkBlocks;i++) {
			NodeCHK k = getNodeKey(i, foundKeys, copy);
			if(k == null) continue;
			list.add(k);
		}
		return list.toArray(new NodeCHK[list.size()]);
	}
	
	@Override
	public SplitFileSegmentKeys clone() {
		try {
			return (SplitFileSegmentKeys) super.clone();
		} catch (CloneNotSupportedException e) {
			throw new Error("Yes it is!");
		}
	}

}
