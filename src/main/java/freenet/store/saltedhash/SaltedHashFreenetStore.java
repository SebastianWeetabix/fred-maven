/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.store.saltedhash;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import freenet.support.math.MersenneTwister;
import org.tanukisoftware.wrapper.WrapperManager;

import freenet.crypt.BlockCipher;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.keys.KeyVerifyException;
import freenet.keys.SSKBlock;
import freenet.l10n.NodeL10n;
import freenet.node.FastRunnable;
import freenet.node.SemiOrderedShutdownHook;
import freenet.node.stats.StoreAccessStats;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.node.useralerts.UserAlertManager;
import freenet.store.BlockMetadata;
import freenet.store.FreenetStore;
import freenet.store.KeyCollisionException;
import freenet.store.StorableBlock;
import freenet.store.StoreCallback;
import freenet.support.BloomFilter;
import freenet.support.Fields;
import freenet.support.HTMLNode;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.Ticker;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;
import freenet.support.io.NativeThread;

/**
 * Index-less data store based on salted hash.
 *
 * Provide a pseudo-random replacement based on a salt value generated on create. Keys are check
 * against a bloom filter before probing. Data are encrypted using the route key and the salt, so
 * there is no way to recover the data without holding the route key. (For debugging, you can set
 * OPTION_SAVE_PLAINKEY=true in source code)
 *
 * @author sdiz
 */
public class SaltedHashFreenetStore<T extends StorableBlock> implements FreenetStore<T> {
	/** Option for saving plainkey */
	private static final boolean OPTION_SAVE_PLAINKEY = false;
	private static final int OPTION_MAX_PROBE = 5;

	private static final byte FLAG_DIRTY = 0x1;
	private static final byte FLAG_REBUILD_BLOOM = 0x2;

	private boolean checkBloom = true;
	private int bloomFilterSize;
	private int bloomFilterK;
	private final BloomFilter bloomFilter;

	private static boolean logMINOR;
	private static boolean logDEBUG;

	private final File baseDir;
	private final String name;
	private final StoreCallback<T> callback;
	private final boolean collisionPossible;
	private final int headerBlockLength;
	private final int fullKeyLength;
	private final int dataBlockLength;
	private final Random random;
	private final File bloomFile;

	private long storeSize;
	private int generation;
	private int flags;

	private boolean preallocate = true;

	/** If we have no space in this store, try writing it to the alternate store,
	 * with the wrong store flag set. Note that we do not *read from* it, the caller
	 * must do that. IMPORTANT LOCKING NOTE: This must only happen in one direction!
	 * If two stores have altStore set to each other, deadlock is likely! (Infinite
	 * recursion is also possible). However, fortunately we don't need to do it
	 * bidirectionally - the cache needs more space from the store, but the store
	 * grows so slowly it will hardly ever need more space from the cache. */
	private SaltedHashFreenetStore<T> altStore;

	public void setAltStore(SaltedHashFreenetStore<T> store) {
		if(store.altStore != null) throw new IllegalStateException("Target must not have an altStore - deadlock can result");
		altStore = store;
	}

	public static <T extends StorableBlock> SaltedHashFreenetStore<T> construct(File baseDir, String name, StoreCallback<T> callback, Random random,
	        long maxKeys, int bloomFilterSize, boolean bloomCounting, SemiOrderedShutdownHook shutdownHook, boolean preallocate, boolean resizeOnStart, Ticker exec, byte[] masterKey)
	        throws IOException {
		return new SaltedHashFreenetStore<T>(baseDir, name, callback, random, maxKeys, bloomFilterSize, bloomCounting,
		        shutdownHook, preallocate, resizeOnStart, masterKey);
	}

	private SaltedHashFreenetStore(File baseDir, String name, StoreCallback<T> callback, Random random, long maxKeys,
	        int bloomFilterSize, boolean bloomCounting, SemiOrderedShutdownHook shutdownHook, boolean preallocate, boolean resizeOnStart, byte[] masterKey) throws IOException {
		logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);

		this.baseDir = baseDir;
		this.name = name;

		this.callback = callback;
		collisionPossible = callback.collisionPossible();
		headerBlockLength = callback.headerLength();
		fullKeyLength = callback.fullKeyLength();
		dataBlockLength = callback.dataLength();

		hdPadding = ((headerBlockLength + dataBlockLength) % 512 == 0 ? 0
		        : 512 - (headerBlockLength + dataBlockLength) % 512);

		this.random = random;
		storeSize = maxKeys;
		this.bloomFilterSize = bloomFilterSize;
		this.preallocate = preallocate;

		lockManager = new LockManager();

		// Create a directory it not exist
		this.baseDir.mkdirs();

		configFile = new File(this.baseDir, name + ".config");
		boolean newStore = loadConfigFile(masterKey);
		if(storeSize != 0 && storeSize != maxKeys && prevStoreSize == 0) {
			// If not already resizing, start resizing to the new store size.
			prevStoreSize = storeSize;
			storeSize = maxKeys;
			writeConfigFile();
		}

		newStore |= openStoreFiles(baseDir, name);

		bloomFile = new File(this.baseDir, name + ".bloom");
		bloomFilter = BloomFilter.createFilter(bloomFile, bloomFilterSize, bloomFilterK, bloomCounting);

		System.err.println("Bloomfilter (" + bloomFilter + ") for " + name + " is loaded.");

		if ((flags & FLAG_DIRTY) != 0)
			System.err.println("Datastore(" + name + ") is dirty.");

		flags |= FLAG_DIRTY; // datastore is now dirty until flushAndClose()
		writeConfigFile();

		callback.setStore(this);
		shutdownHook.addEarlyJob(new NativeThread(new ShutdownDB(), "Shutdown salted hash store", NativeThread.HIGH_PRIORITY, true));

		cleanerThread = new Cleaner();
		cleanerStatusUserAlert = new CleanerStatusUserAlert(cleanerThread);

		// finish all resizing before continue
		if (resizeOnStart && prevStoreSize != 0 && cleanerGlobalLock.tryLock()) {
			System.out.println("Resizing datastore (" + name + ")");
			try {
				cleanerThread.resizeStore(prevStoreSize, false);
			} finally {
				cleanerGlobalLock.unlock();
			}
			writeConfigFile();
		}
		if (bloomFilter.needRebuild() && !newStore) {
			// Bloom filter resized?
			flags |= FLAG_REBUILD_BLOOM;
			checkBloom = false;

			/*-
			if (cleanerGlobalLock.tryLock()) {
				System.out.println("Bloom filter for datastore (" + name + ") missing/mismatch, rebuilding.");
				try {
					cleanerThread.rebuildBloom(false);
				} finally {
					cleanerGlobalLock.unlock();
				}
				writeConfigFile();
			}
			*/
		}

		System.err.println(" checkBloom=" + checkBloom + ", flags=" + flags+" bloom size = "+bloomFilterSize+" keys = "+maxKeys);
	}

	private boolean started = false;

	/** If start can be completed quickly, or longStart is true, then do it.
	 * If longStart is false and start cannot be completed quickly, return
	 * true. Don't start twice.
	 * @throws IOException */
	public boolean start(Ticker ticker, boolean longStart) throws IOException {

		if(started) return true;

		long curStoreFileSize = hdRAF.length();

		long curMetaFileSize = metaRAF.length();

		// If prevStoreSize is nonzero, that means that we are either shrinking or
		// growing. Either way, the file size should be between the old size and the
		// new size. If it is not, we should pad it until it is.

		long smallerSize = storeSize;
		if(prevStoreSize < storeSize && prevStoreSize > 0)
			smallerSize = prevStoreSize;

		if((smallerSize * (headerBlockLength + dataBlockLength + hdPadding) > curStoreFileSize) ||
				(smallerSize * Entry.METADATA_LENGTH > curMetaFileSize)) {
			// Pad it up to the minimum size before proceeding.
			if(longStart) {
				setStoreFileSize(storeSize, true);
				curStoreFileSize = hdRAF.length();
				curMetaFileSize = metaRAF.length();
			} else
				return true;
		}

		// Otherwise the resize will be completed by the Cleaner thread.
		// However, we do still need to set storeFileOffsetReady

		storeFileOffsetReady = Math.min(curStoreFileSize / (headerBlockLength + dataBlockLength + hdPadding), curMetaFileSize / Entry.METADATA_LENGTH);

		if(ticker == null) {
			cleanerThread.start();
		} else
			ticker.queueTimedJob(new FastRunnable() {

				public void run() {
					cleanerThread.start();
				}

			}, "Start cleaner thread", 0, true, false);

		started = true;

		return false;
	}

	public T fetch(byte[] routingKey, byte[] fullKey, boolean dontPromote, boolean canReadClientCache, boolean canReadSlashdotCache, boolean ignoreOldBlocks, BlockMetadata meta) throws IOException {
		if (logMINOR)
			Logger.minor(this, "Fetch " + HexUtil.bytesToHex(routingKey) + " for " + callback);

		try {
			int retry = 0;
			while (!configLock.readLock().tryLock(2, TimeUnit.SECONDS)) {
				if (shutdown)
					return null;
				if (retry++ > 10)
					throw new IOException("lock timeout (20s)");
			}
		} catch(InterruptedException e) {
			throw new IOException("interrupted: " +e);
		}
		try {
			Map<Long, Condition> lockMap = lockPlainKey(routingKey, true);
			if (lockMap == null) {
				if (logDEBUG)
					Logger.debug(this, "cannot lock key: " + HexUtil.bytesToHex(routingKey) + ", shutting down?");
				return null;
			}
			try {
				Entry entry = probeEntry(routingKey, true);
				if (entry == null) {
					misses.incrementAndGet();
					return null;
				}

				if((entry.flag & Entry.ENTRY_NEW_BLOCK) == 0) {
					if(ignoreOldBlocks) {
						Logger.normal(this, "Ignoring old block");
						return null;
					}
					if(meta != null)
						meta.setOldBlock();
				}

				try {
					T block = entry.getStorableBlock(routingKey, fullKey, canReadClientCache, canReadSlashdotCache, meta, null);
					if (block == null) {
						misses.incrementAndGet();
						return null;
					}
					hits.incrementAndGet();
					return block;
				} catch (KeyVerifyException e) {
					Logger.minor(this, "key verification exception", e);
					misses.incrementAndGet();
					return null;
				}
			} finally {
				unlockPlainKey(routingKey, true, lockMap);
			}
		} finally {
			configLock.readLock().unlock();
		}
	}

	/**
	 * Find and lock an entry with a specific routing key. This function would <strong>not</strong>
	 * lock the entries.
	 *
	 * @param routingKey
	 * @param withData
	 * @return <code>Entry</code> object
	 * @throws IOException
	 */
	private Entry probeEntry(byte[] routingKey, boolean withData) throws IOException {
		if (checkBloom)
			if (!bloomFilter.checkFilter(cipherManager.getDigestedKey(routingKey)))
				return null;

		Entry entry = probeEntry0(routingKey, storeSize, withData);

		if (entry == null && prevStoreSize != 0)
			entry = probeEntry0(routingKey, prevStoreSize, withData);
		if (checkBloom && entry == null)
			bloomFalsePos.incrementAndGet();

		return entry;
	}

	private Entry probeEntry0(byte[] routingKey, long probeStoreSize, boolean withData) throws IOException {
		Entry entry = null;
		long[] offset = getOffsetFromPlainKey(routingKey, probeStoreSize);

		for (int i = 0; i < offset.length; i++) {
			if (logDEBUG)
				Logger.debug(this, "probing for i=" + i + ", offset=" + offset[i]);

			try {
				if(storeFileOffsetReady == -1 || offset[i] < this.storeFileOffsetReady) {
					entry = readEntry(offset[i], routingKey, withData);
					if (entry != null)
						return entry;
				}
			} catch (EOFException e) {
				if (prevStoreSize == 0) // may occur on store shrinking
					Logger.error(this, "EOFException on probeEntry", e);
				continue;
			}
		}
		return null;
	}

	public void put(T block, byte[] data, byte[] header, boolean overwrite, boolean isOldBlock) throws IOException, KeyCollisionException {
		put(block, data, header, overwrite, isOldBlock, false);
	}

	public boolean put(T block, byte[] data, byte[] header, boolean overwrite, boolean isOldBlock, boolean wrongStore) throws IOException, KeyCollisionException {
		byte[] routingKey = block.getRoutingKey();
		byte[] fullKey = block.getFullKey();

		if (logMINOR)
			Logger.minor(this, "Putting " + HexUtil.bytesToHex(routingKey) + " (" + name + ")");

		boolean rebuildBloom = false;
		
		try {
			int retry = 0;
			while (!configLock.readLock().tryLock(2, TimeUnit.SECONDS)) {
				if (shutdown)
					return true;
				if (retry++ > 10)
					throw new IOException("lock timeout (20s)");
			}
		} catch(InterruptedException e) {
			throw new IOException("interrupted: " +e);
		}
		try {
			Map<Long, Condition> lockMap = lockPlainKey(routingKey, false);
			if (lockMap == null) {
				if (logDEBUG)
					Logger.debug(this, "cannot lock key: " + HexUtil.bytesToHex(routingKey) + ", shutting down?");
				return false;
			}
			try {
				/*
				 * Use lazy loading here. This may lost data if digestedRoutingKey collide but
				 * collisionPossible is false. Should be very rare as digestedRoutingKey is a
				 * SHA-256 hash.
				 */
				Entry oldEntry = probeEntry(routingKey, false);
				if (oldEntry != null && !oldEntry.isFree()) {
					long oldOffset = oldEntry.curOffset;
					try {
						if (!collisionPossible) {
							if((oldEntry.flag & Entry.ENTRY_NEW_BLOCK) == 0 && !isOldBlock) {
								oldEntry = readEntry(oldEntry.curOffset, routingKey, true);
								// Currently flagged as an old block
								oldEntry.flag |= Entry.ENTRY_NEW_BLOCK;
								if(logMINOR) Logger.minor(this, "Setting old block to new block");
								oldEntry.storeSize = storeSize;
								writeEntry(oldEntry, oldOffset);
							}
							return true;
						}
						oldEntry.setHD(readHD(oldOffset)); // read from disk
						T oldBlock = oldEntry.getStorableBlock(routingKey, fullKey, false, false, null, (block instanceof SSKBlock) ? ((SSKBlock)block).getPubKey() : null);
						if (block.equals(oldBlock)) {
							if(logDEBUG) Logger.debug(this, "Block already stored");
							if((oldEntry.flag & Entry.ENTRY_NEW_BLOCK) == 0 && !isOldBlock) {
								// Currently flagged as an old block
								oldEntry.flag |= Entry.ENTRY_NEW_BLOCK;
								if(logMINOR) Logger.minor(this, "Setting old block to new block");
								oldEntry.storeSize = storeSize;
								writeEntry(oldEntry, oldOffset);
							}
							return false; // already in store
						} else if (!overwrite) {
							throw new KeyCollisionException();
						}
					} catch (KeyVerifyException e) {
						// ignore
					}

					// Overwrite old offset with same key
					Entry entry = new Entry(routingKey, header, data, !isOldBlock, wrongStore);
					writeEntry(entry, oldOffset);
					rebuildBloom = onWrite();
					if (oldEntry.generation != generation)
						keyCount.incrementAndGet();
					return true;
				}

				Entry entry = new Entry(routingKey, header, data, !isOldBlock, wrongStore);
				long[] offset = entry.getOffset();

				int firstWrongStoreIndex = -1;
				int wrongStoreCount = 0;

				for (int i = 0; i < offset.length; i++) {
					if(offset[i] < storeFileOffsetReady) {
						long flag = getFlag(offset[i]);
						if((flag & Entry.ENTRY_FLAG_OCCUPIED) == 0) {
							// write to free block
							if (logDEBUG)
								Logger.debug(this, "probing, write to i=" + i + ", offset=" + offset[i]);
							bloomFilter.addKey(cipherManager.getDigestedKey(routingKey));
							writeEntry(entry, offset[i]);
							rebuildBloom = onWrite();
							keyCount.incrementAndGet();
							return true;
						} else if(((flag & Entry.ENTRY_WRONG_STORE) == Entry.ENTRY_WRONG_STORE)) {
							if (wrongStoreCount == 0)
								firstWrongStoreIndex = i;
							wrongStoreCount++;
						}
					}
				}

				if((!wrongStore) && altStore != null) {
					if(altStore.put(block, data, header, overwrite, isOldBlock, true)) {
						if(logMINOR) Logger.minor(this, "Successfully wrote block to wrong store "+altStore+" on "+this);
						return true;
					} else {
						if(logMINOR) Logger.minor(this, "Writing to wrong store "+altStore+" on "+this+" failed");
					}
				}

				// There are no free slots for this Entry, so some slot will have to get overwritten.
				int indexToOverwrite = -1;

				if(wrongStore) {
					// Distribute overwrites evenly between the right store and the wrong store.
					int a = OPTION_MAX_PROBE;
					int b = wrongStoreCount;
					if(random.nextInt(a+b) < b)
						// Allow the overwrite to happen in the wrong store.
						indexToOverwrite = firstWrongStoreIndex;
					else
						// Force the overwrite to happen in the right store.
						return false;
				}
				else {
					// By default, overwrite offset[0] when not writing to wrong store.
					indexToOverwrite = 0;
				}

				// Do the overwriting.
				if (logDEBUG)
					Logger.debug(this, "collision, write to i=" + indexToOverwrite + ", offset=" + offset[indexToOverwrite]);
				bloomFilter.addKey(cipherManager.getDigestedKey(routingKey));
				oldEntry = readEntry(offset[indexToOverwrite], null, false);
				writeEntry(entry, offset[indexToOverwrite]);
				rebuildBloom = onWrite();
				if (oldEntry.generation == generation)
					bloomFilter.removeKey(oldEntry.getDigestedRoutingKey());
				else
					keyCount.incrementAndGet();
				return true;
			} finally {
				unlockPlainKey(routingKey, false, lockMap);
			}
		} finally {
			configLock.readLock().unlock();
			if(rebuildBloom)
				rebuildBloom();
		}
	}

	private boolean onWrite() {
		return (writes.incrementAndGet() % (storeSize*2) == 0);
	}
	
	private void rebuildBloom() {
		try {
			configLock.writeLock().lock();
			// Rebuild bloom filter.
			flags |= FLAG_REBUILD_BLOOM;
			checkBloom = false;
		} finally {
			configLock.writeLock().unlock();
		}
	}

	// ------------- Entry I/O
	// meta-data file
	private File metaFile;
	private RandomAccessFile metaRAF;
	private FileChannel metaFC;
	// header+data file
	private File hdFile;
	private RandomAccessFile hdRAF;
	private FileChannel hdFC;
	private final int hdPadding;

	/**
	 * Data entry
	 *
	 * <pre>
	 *  META-DATA BLOCK
	 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *       |0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|
	 *  +----+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *  |0000|                               |
	 *  +----+     Digested Routing Key      |
	 *  |0010|                               |
	 *  +----+-------------------------------+
	 *  |0020|       Data Encrypt IV         |
	 *  +----+---------------+---------------+
	 *  |0030|     Flag      |  Store Size   |
	 *  +----+---------------+---------------+
	 *  |0040|       Plain Routing Key       |
	 *  |0050| (Only if ENTRY_FLAG_PLAINKEY) |
	 *  +----+-------+-----------------------+
	 *  |0060|  Gen  |    Reserved           |
	 *  +----+-------+-----------------------+
	 *  |0070|            Reserved           |
	 *  +----+-------------------------------+
	 *
	 *  Gen = Generation
	 * </pre>
	 */
	class Entry {
		/** Flag for occupied space */
		private final static long ENTRY_FLAG_OCCUPIED = 0x00000001L;
		/** Flag for plain key available */
		private final static long ENTRY_FLAG_PLAINKEY = 0x00000002L;
		/** Flag for block added after we stopped caching local (and high htl) requests */
		private final static long ENTRY_NEW_BLOCK = 0x00000004L;
		/** Flag set if the block was stored in the wrong datastore i.e. store instead of cache */
		private final static long ENTRY_WRONG_STORE = 0x00000008L;

		/** Control block length */
		private static final int METADATA_LENGTH = 0x80;

		byte[] plainRoutingKey;
		byte[] digestedRoutingKey;
		byte[] dataEncryptIV;
		private long flag;
		private long storeSize;
		private int generation;
		byte[] header;
		byte[] data;

		boolean isEncrypted;
		private long curOffset = -1;

		private Entry() {
		}

		private Entry(ByteBuffer metaDataBuf, ByteBuffer hdBuf) {
			assert metaDataBuf.remaining() == METADATA_LENGTH;

			digestedRoutingKey = new byte[0x20];
			metaDataBuf.get(digestedRoutingKey);

			dataEncryptIV = new byte[0x10];
			metaDataBuf.get(dataEncryptIV);

			flag = metaDataBuf.getLong();
			storeSize = metaDataBuf.getLong();

			if ((flag & ENTRY_FLAG_PLAINKEY) != 0) {
				plainRoutingKey = new byte[0x20];
				metaDataBuf.get(plainRoutingKey);
			}

			metaDataBuf.position(0x60);
			generation = metaDataBuf.getInt();

			isEncrypted = true;

			if (hdBuf != null)
				setHD(hdBuf);
		}

		/**
		 * Set header/data after construction.
		 *
		 * @param storeBuf
		 * @param store
		 */
		private void setHD(ByteBuffer hdBuf) {
			assert hdBuf.remaining() == headerBlockLength + dataBlockLength + hdPadding;
			assert isEncrypted;

			header = new byte[headerBlockLength];
			hdBuf.get(header);

			data = new byte[dataBlockLength];
			hdBuf.get(data);
		}

		/**
		 * Create a new entry
		 *
		 * @param plainRoutingKey
		 * @param header
		 * @param data
		 */
		private Entry(byte[] plainRoutingKey, byte[] header, byte[] data, boolean newBlock, boolean wrongStore) {
			this.plainRoutingKey = plainRoutingKey;

			flag = ENTRY_FLAG_OCCUPIED;
			if(newBlock)
				flag |= ENTRY_NEW_BLOCK;
			if(wrongStore)
				flag |= ENTRY_WRONG_STORE;
			this.storeSize = SaltedHashFreenetStore.this.storeSize;
			this.generation = SaltedHashFreenetStore.this.generation;

			// header/data will be overwritten in encrypt()/decrypt(),
			// let's make a copy here
			this.header = new byte[headerBlockLength];
			System.arraycopy(header, 0, this.header, 0, headerBlockLength);
			this.data = new byte[dataBlockLength];
			System.arraycopy(data, 0, this.data, 0, dataBlockLength);

			if (OPTION_SAVE_PLAINKEY) {
				flag |= ENTRY_FLAG_PLAINKEY;
			}

			isEncrypted = false;
		}

		private ByteBuffer toMetaDataBuffer() {
			ByteBuffer out = ByteBuffer.allocate(METADATA_LENGTH);
			cipherManager.encrypt(this, random);

			out.put(getDigestedRoutingKey());
			out.put(dataEncryptIV);
			out.putLong(flag);
			out.putLong(storeSize);

			if ((flag & ENTRY_FLAG_PLAINKEY) != 0 && plainRoutingKey != null) {
				assert plainRoutingKey.length == 0x20;
				out.put(plainRoutingKey);
			}

			out.position(0x60);
			out.putInt(generation);

			out.position(0);
			return out;
		}

		private ByteBuffer toHDBuffer() {
			assert isEncrypted; // should have encrypted to get dataEncryptIV in control buffer
			assert header.length == headerBlockLength;
			assert data.length == dataBlockLength;

			if (header == null || data == null)
				return null;

			ByteBuffer out = ByteBuffer.allocate(headerBlockLength + dataBlockLength + hdPadding);
			out.put(header);
			out.put(data);

			out.position(0);
			return out;
		}

		private T getStorableBlock(byte[] routingKey, byte[] fullKey, boolean canReadClientCache, boolean canReadSlashdotCache, BlockMetadata meta, DSAPublicKey knownKey) throws KeyVerifyException {
			if (isFree() || header == null || data == null)
				return null; // this is a free block
			if (!cipherManager.decrypt(this, routingKey))
				return null;

			T block = callback.construct(data, header, routingKey, fullKey, canReadClientCache, canReadSlashdotCache, meta, knownKey);
			byte[] blockRoutingKey = block.getRoutingKey();

			if (!Arrays.equals(blockRoutingKey, routingKey)) {
				// can't recover, as decrypt() depends on a correct route key
				return null;
			}

			return block;
		}

		private long[] getOffset() {
			if (digestedRoutingKey != null)
				return getOffsetFromDigestedKey(digestedRoutingKey, storeSize);
			else
				return getOffsetFromPlainKey(plainRoutingKey, storeSize);
		}

		private boolean isFree() {
			return (flag & ENTRY_FLAG_OCCUPIED) == 0;
		}

		byte[] getDigestedRoutingKey() {
			if (digestedRoutingKey == null)
				if (plainRoutingKey == null)
					return null;
				else
					digestedRoutingKey = cipherManager.getDigestedKey(plainRoutingKey);
			return digestedRoutingKey;
		}
	}

	private volatile long storeFileOffsetReady = -1;

	/**
	 * Open all store files
	 *
	 * @param baseDir
	 * @param name
	 * @throws IOException
	 * @return <code>true</code> iff this is a new datastore
	 */
	private boolean openStoreFiles(File baseDir, String name) throws IOException {
		metaFile = new File(baseDir, name + ".metadata");
		hdFile = new File(baseDir, name + ".hd");

		boolean newStore = !metaFile.exists() || !hdFile.exists();

		metaRAF = new RandomAccessFile(metaFile, "rw");
		metaFC = metaRAF.getChannel();
		metaFC.lock();

		hdRAF = new RandomAccessFile(hdFile, "rw");
		hdFC = hdRAF.getChannel();
		hdFC.lock();

		return newStore;
	}

	/**
	 * Read entry from disk. Before calling this function, you should acquire all required locks.
	 *
	 * @return <code>null</code> if and only if <code>routingKey</code> is not <code>null</code> and
	 *         the key does not match the entry.
	 */
	private Entry readEntry(long offset, byte[] routingKey, boolean withData) throws IOException {
		ByteBuffer mbf = ByteBuffer.allocate(Entry.METADATA_LENGTH);

		do {
			int status = metaFC.read(mbf, Entry.METADATA_LENGTH * offset + mbf.position());
			if (status == -1) {
				Logger.error(this, "Failed to access offset "+offset, new Exception("error"));
				throw new EOFException();
			}
		} while (mbf.hasRemaining());
		mbf.flip();

		Entry entry = new Entry(mbf, null);
		entry.curOffset = offset;

		if (routingKey != null) {
			if (entry.isFree())
				return null;
			if (!Arrays.equals(cipherManager.getDigestedKey(routingKey), entry.digestedRoutingKey))
				return null;

			if (withData) {
				ByteBuffer hdBuf = readHD(offset);
				entry.setHD(hdBuf);
				boolean decrypted = cipherManager.decrypt(entry, routingKey);
				if (!decrypted)
					return null;
			}
		}

		return entry;
	}

	/**
	 * Read header + data from disk
	 *
	 * @param offset
	 * @throws IOException
	 */
	private ByteBuffer readHD(long offset) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(headerBlockLength + dataBlockLength + hdPadding);

		long pos = (headerBlockLength + dataBlockLength + hdPadding) * offset;
		do {
			int status = hdFC.read(buf, pos + buf.position());
			if (status == -1)
				throw new EOFException();
		} while (buf.hasRemaining());
		buf.flip();

		return buf;
	}

	private long getFlag(long offset) throws IOException {
		Entry entry = readEntry(offset, null, false);
		return entry.flag;
	}

	private boolean isFree(long offset) throws IOException {
		Entry entry = readEntry(offset, null, false);
		return entry.isFree();
	}

	private byte[] getDigestedKeyFromOffset(long offset) throws IOException {
		Entry entry = readEntry(offset, null, false);
		return entry.getDigestedRoutingKey();
	}

	/**
	 * Write entry to disk.
	 *
	 * Before calling this function, you should:
	 * <ul>
	 * <li>acquire all required locks</li>
	 * <li>update the entry with latest store size</li>
	 * </ul>
	 */
	private void writeEntry(Entry entry, long offset) throws IOException {
		cipherManager.encrypt(entry, random);

		ByteBuffer bf = entry.toMetaDataBuffer();
		do {
			int status = metaFC.write(bf, Entry.METADATA_LENGTH * offset + bf.position());
			if (status == -1)
				throw new EOFException();
		} while (bf.hasRemaining());

		bf = entry.toHDBuffer();
		if (bf != null) {
			long pos = (headerBlockLength + dataBlockLength + hdPadding) * offset;
			do {
				int status = hdFC.write(bf, pos + bf.position());
				if (status == -1)
					throw new EOFException();
			} while (bf.hasRemaining());
		}

		entry.curOffset = offset;
	}

	private void flushAndClose() {
		Logger.normal(this, "Flush and closing this store: " + name);
		try {
			metaFC.force(true);
			metaFC.close();
		} catch (Exception e) {
			Logger.error(this, "error flusing store", e);
		}
		try {
			hdFC.force(true);
			hdFC.close();
		} catch (Exception e) {
			Logger.error(this, "error flusing store", e);
		}

		bloomFilter.force();
	}

	/**
	 * Set preallocate storage space
	 * @param preallocate
	 */
	public void setPreallocate(boolean preallocate) {
		this.preallocate = preallocate;
	}

	/**
	 * Change on disk store file size
	 *
	 * @param storeMaxEntries
	 */
	private void setStoreFileSize(long storeMaxEntries, boolean starting) {
		try {
			long oldMetaLen = metaRAF.length();
			long currentHdLen = hdRAF.length();

			final long newMetaLen = Entry.METADATA_LENGTH * storeMaxEntries;
			final long newHdLen = (headerBlockLength + dataBlockLength + hdPadding) * storeMaxEntries;

			if (preallocate && (oldMetaLen < newMetaLen || currentHdLen < newHdLen)) {
				/*
				 * Fill the store file with random data. This won't be compressed, unlike filling it with zeros.
				 * So the disk space usage of the node will not change (apart from temp files).
				 *
				 * Note that MersenneTwister is *not* cryptographically secure, in fact from 2.4KB of output you
				 * can predict the rest of the stream! This is okay because an attacker knows which blocks are
				 * occupied anyway; it is essential to label them to get good data retention on resizing etc.
				 *
				 * On my test system (phenom 2.2GHz), this does approx 80MB/sec. If I reseed every 2kB from an
				 * AES CTR, which is pointless as I just explained, it does 40MB/sec.
				 */
				byte[] b = new byte[4096];
				ByteBuffer bf = ByteBuffer.wrap(b);

				// start from next 4KB boundary => align to x86 page size
				if (oldMetaLen % 4096 != 0)
					oldMetaLen += 4096 - (oldMetaLen % 4096);
				if (currentHdLen % 4096 != 0)
					currentHdLen += 4096 - (currentHdLen % 4096);

				storeFileOffsetReady = -1;

				// this may write excess the size, the setLength() would fix it
				while (oldMetaLen < newMetaLen) {
					// never write random byte to meta data!
					// this would screw up the isFree() function
					bf.rewind();
					metaFC.write(bf, oldMetaLen);
					oldMetaLen += 4096;
				}
				byte[] seed = new byte[64];
				random.nextBytes(seed);
				Random mt = new MersenneTwister(seed);
				int x = 0;
				while (currentHdLen < newHdLen) {
					mt.nextBytes(b);
					bf.rewind();
					hdFC.write(bf, currentHdLen);
					currentHdLen += 4096;
					if(currentHdLen % (1024*1024*1024L) == 0) {
						random.nextBytes(seed);
						mt = new MersenneTwister(seed);
						if (starting) {
							WrapperManager.signalStarting(5*60*1000);
							if ( x++ % 32 == 0 )
								System.err.println("Preallocating space for " + name + ": " + currentHdLen + "/" + newHdLen);
						}
					}
					storeFileOffsetReady = currentHdLen / (headerBlockLength + dataBlockLength + hdPadding);
				}
			}
			storeFileOffsetReady = 1 + storeMaxEntries;

			metaRAF.setLength(newMetaLen);
			hdRAF.setLength(newHdLen);
		} catch (IOException e) {
			Logger.error(this, "error resizing store file", e);
		}
	}

	// ------------- Configuration
	/**
	 * Configuration File
	 *
	 * <pre>
	 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *       |0|1|2|3|4|5|6|7|8|9|A|B|C|D|E|F|
	 *  +----+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
	 *  |0000|             Salt              |
	 *  +----+---------------+---------------+
	 *  |0010|   Store Size  | prevStoreSize |
	 *  +----+---------------+-------+-------+
	 *  |0020| Est Key Count |  Gen  | Flags |
	 *  +----+-------+-------+-------+-------+
	 *  |0030|   K   |      (reserved)       |
	 *  +----+-------+-------+---------------+
	 *  |0040|    writes     |     hits      |
	 *  +----+---------------+---------------+
	 *  |0050|    misses     | bloomFalsePos |
	 *  +----+---------------+---------------+
	 *
	 *  Gen = Generation
	 *    K = K for bloom filter
	 * </pre>
	 */
	private final File configFile;

	/**
	 * Load config file
	 * @param masterKey
	 *
	 * @return <code>true</code> iff this is a new datastore
	 */
	private boolean loadConfigFile(byte[] masterKey) throws IOException {
		assert cipherManager == null; // never load the configuration twice

		if (!configFile.exists()) {
			// create new
			byte[] newsalt = new byte[0x10];
			random.nextBytes(newsalt);
			byte[] diskSalt = newsalt;
			if(masterKey != null) {
				BlockCipher cipher;
				try {
					cipher = new Rijndael(256, 128);
				} catch (UnsupportedCipherException e) {
					throw new Error("Impossible: no Rijndael(256,128): "+e, e);
				}
				cipher.initialize(masterKey);
				diskSalt = new byte[0x10];
				cipher.encipher(newsalt, diskSalt);
				if(logDEBUG)
					Logger.debug(this, "Encrypting with "+HexUtil.bytesToHex(newsalt)+" from "+HexUtil.bytesToHex(diskSalt));
			}
			cipherManager = new CipherManager(newsalt, diskSalt);
			bloomFilterK = BloomFilter.optimialK(bloomFilterSize, storeSize);

			writeConfigFile();
			return true;
		} else {
			try {
				// try to load
				RandomAccessFile raf = new RandomAccessFile(configFile, "r");
				try {
					byte[] salt = new byte[0x10];
					raf.readFully(salt);

					byte[] diskSalt = salt;
					if(masterKey != null) {
						BlockCipher cipher;
						try {
							cipher = new Rijndael(256, 128);
						} catch (UnsupportedCipherException e) {
							throw new Error("Impossible: no Rijndael(256,128): "+e, e);
						}
						cipher.initialize(masterKey);
						salt = new byte[0x10];
						cipher.decipher(diskSalt, salt);
						if(logDEBUG)
							Logger.debug(this, "Encrypting (new) with "+HexUtil.bytesToHex(salt)+" from "+HexUtil.bytesToHex(diskSalt));
					}

					cipherManager = new CipherManager(salt, diskSalt);

					storeSize = raf.readLong();
					if(storeSize <= 0) throw new IOException("Bogus datastore size");
					prevStoreSize = raf.readLong();
					keyCount.set(raf.readLong());
					generation = raf.readInt();
					flags = raf.readInt();

					if ((flags & FLAG_DIRTY) != 0)
						flags |= FLAG_REBUILD_BLOOM;

					try {
						bloomFilterK = raf.readInt();
						if (bloomFilterK == 0) {
							bloomFilterK = BloomFilter.optimialK(bloomFilterSize, storeSize);
							flags |= FLAG_REBUILD_BLOOM;
							checkBloom = false;
						}
					} catch (IOException e) {
						flags |= FLAG_REBUILD_BLOOM;
					}
					try {
						raf.readInt(); // reserved
						raf.readLong(); // reserved
						long w = raf.readLong();
						writes.set(w);
						initialWrites = w;
						Logger.normal(this, "Set writes to saved value "+w);
						hits.set(raf.readLong());
						initialHits = hits.get();
						misses.set(raf.readLong());
						initialMisses = misses.get();
						bloomFalsePos.set(raf.readLong());
						initialBloomFalsePos = bloomFalsePos.get();
					} catch (EOFException e) {
						// Ignore, back compatibility.
					}

					return false;
				} finally {
					Closer.close(raf);
				}
			} catch (IOException e) {
				// corrupted? delete it and try again
				Logger.error(this, "config file corrupted, trying to create a new store: " + name, e);
				System.err.println("config file corrupted, trying to create a new store: " + name);
				if (configFile.exists() && configFile.delete()) {
					File metaFile = new File(baseDir, name + ".metadata");
					metaFile.delete();
					return loadConfigFile(masterKey);
				}

				// last restore
				Logger.error(this, "can't delete config file, please delete the store manually: " + name, e);
				System.err.println( "can't delete config file, please delete the store manually: " + name);
				throw e;
			}
		}
	}

	/**
	 * Write config file
	 */
	private void writeConfigFile() {
		configLock.writeLock().lock();
		try {
			File tempConfig = new File(configFile.getPath() + ".tmp");
			RandomAccessFile raf = new RandomAccessFile(tempConfig, "rw");
			raf.seek(0);
			raf.write(cipherManager.getDiskSalt());

			raf.writeLong(storeSize);
			raf.writeLong(prevStoreSize);
			raf.writeLong(keyCount.get());
			raf.writeInt(generation);
			raf.writeInt(flags);
			raf.writeInt(bloomFilterK);
			raf.writeInt(0);
			raf.writeLong(0);
			raf.writeLong(writes.get());
			raf.writeLong(hits.get());
			raf.writeLong(misses.get());
			raf.writeLong(bloomFalsePos.get());

			raf.getFD().sync();
			raf.close();

			FileUtil.renameTo(tempConfig, configFile);
		} catch (IOException ioe) {
			Logger.error(this, "error writing config file for " + name, ioe);
		} finally {
			configLock.writeLock().unlock();
		}
	}

	// ------------- Store resizing
	private long prevStoreSize = 0;
	private Lock cleanerLock = new ReentrantLock(); // local to this datastore
	private Condition cleanerCondition = cleanerLock.newCondition();
	private static Lock cleanerGlobalLock = new ReentrantLock(); // global across all datastore
	private Cleaner cleanerThread;
	private CleanerStatusUserAlert cleanerStatusUserAlert;

	private final Entry NOT_MODIFIED = new Entry();

	private interface BatchProcessor<T extends StorableBlock> {
		// initialize
		void init();

		// call this after reading RESIZE_MEMORY_ENTRIES entries
		// return false to abort
		boolean batch(long entriesLeft);

		// call this on abort (e.g. node shutdown)
		void abort();

		void finish();

		// return <code>null</code> to free the entry
		// return NOT_MODIFIED to keep the old entry
		SaltedHashFreenetStore<T>.Entry process(SaltedHashFreenetStore<T>.Entry entry);
	}

	private class Cleaner extends NativeThread {
		/**
		 * How often the clean should run
		 */
		private static final int CLEANER_PERIOD = 5 * 60 * 1000; // 5 minutes

		private volatile boolean isRebuilding;
		private volatile boolean isResizing;

		public Cleaner() {
			super("Store-" + name + "-Cleaner", NativeThread.LOW_PRIORITY, false);
			setPriority(MIN_PRIORITY);
			setDaemon(true);
		}

		@Override
		public void realRun() {

			try {
				Thread.sleep((int)(CLEANER_PERIOD / 2 + CLEANER_PERIOD * Math.random()));
			} catch (InterruptedException e){}

			if (shutdown)
				return;

			int loop = 0;
			while (!shutdown) {
				loop++;

				cleanerLock.lock();
				try {
					long _prevStoreSize;
					configLock.readLock().lock();
					try {
						_prevStoreSize = prevStoreSize;
					} finally {
						configLock.readLock().unlock();
					}

					if (_prevStoreSize != 0 && cleanerGlobalLock.tryLock()) {
						try {
							isResizing = true;
							resizeStore(_prevStoreSize, true);
						} finally {
							isResizing = false;
							cleanerGlobalLock.unlock();
						}
					}

					boolean _rebuildBloom;
					configLock.readLock().lock();
					try {
						_rebuildBloom = ((flags & FLAG_REBUILD_BLOOM) != 0);
					} finally {
						configLock.readLock().unlock();
					}
					if (_rebuildBloom && prevStoreSize == 0 && cleanerGlobalLock.tryLock()) {
						try {
							isRebuilding = true;
							rebuildBloom(false);
						} finally {
							isRebuilding = false;
							cleanerGlobalLock.unlock();
						}
					}

					try {
						if (loop % 6 == 0)
							bloomFilter.force();
					} catch (Exception e) { // may throw IOException (even if it is not defined)
						Logger.error(this, "Can't force bloom filter", e);
					}
					writeConfigFile();

					try {
						cleanerCondition.await(CLEANER_PERIOD, TimeUnit.MILLISECONDS);
					} catch (InterruptedException e) {
						Logger.debug(this, "interrupted", e);
					}
				} finally {
					cleanerLock.unlock();
				}
			}
		}

		private static final int RESIZE_MEMORY_ENTRIES = 128; // temporary memory store size (in # of entries)

		/**
		 * Move old entries to new location and resize store
		 */
		private void resizeStore(final long _prevStoreSize, final boolean sleep) {
			Logger.normal(this, "Starting datastore resize");
			System.out.println("Resizing datastore "+name);

			BatchProcessor<T> resizeProcesser = new BatchProcessor<T>() {
				List<Entry> oldEntryList = new LinkedList<Entry>();
				int optimialK;

				public void init() {
					if (storeSize > _prevStoreSize)
						setStoreFileSize(storeSize, false);

					optimialK = BloomFilter.optimialK(bloomFilterSize, storeSize);
					configLock.writeLock().lock();
					try {
						generation++;
						bloomFilter.fork(optimialK);
						keyCount.set(0);
					} finally {
						configLock.writeLock().unlock();
					}

					WrapperManager.signalStarting(RESIZE_MEMORY_ENTRIES * 30 * 1000 + 1000);
				}

				public Entry process(Entry entry) {
					int oldGeneration = entry.generation;
					if (oldGeneration != generation) {
						entry.generation = generation;
						keyCount.incrementAndGet();
					}

					if (entry.storeSize == storeSize) {
						// new size, don't have to relocate
						if (entry.generation != generation) {
							// update filter
							bloomFilter.addKeyForked(entry.getDigestedRoutingKey());
							return entry;
						} else {
							return NOT_MODIFIED;
						}
					}

					// remove from store, prepare for relocation
					if (oldGeneration == generation) {
						// should be impossible
						Logger.error(this, //
						        "new generation object with wrong storeSize. DigestedRoutingKey=" //
						                + HexUtil.bytesToHex(entry.getDigestedRoutingKey()) //
						                + ", Offset=" + entry.curOffset);
						bloomFilter.removeKey(entry.getDigestedRoutingKey());
					}
					try {
						entry.setHD(readHD(entry.curOffset));
						oldEntryList.add(entry);
						if (oldEntryList.size() > RESIZE_MEMORY_ENTRIES)
							oldEntryList.remove(0);
					} catch (IOException e) {
						Logger.error(this, "error reading entry (offset=" + entry.curOffset + ")", e);
					}
					return null;
				}

				int i = 0;
				public boolean batch(long entriesLeft) {
					WrapperManager.signalStarting(RESIZE_MEMORY_ENTRIES * 30 * 1000 + 1000);

					if (i++ % 16 == 0)
						writeConfigFile();

					// shrink data file to current size
					if (storeSize < _prevStoreSize)
						setStoreFileSize(Math.max(storeSize, entriesLeft), false);

					// try to resolve the list
					ListIterator<Entry> it = oldEntryList.listIterator();
					while (it.hasNext())
						if (resolveOldEntry(it.next()))
							it.remove();

					return _prevStoreSize == prevStoreSize;
				}

				public void abort() {
					bloomFilter.discard();
				}

				public void finish() {
					configLock.writeLock().lock();
					try {
						if (_prevStoreSize != prevStoreSize)
							return;
						bloomFilter.merge();
						prevStoreSize = 0;

						flags &= ~FLAG_REBUILD_BLOOM;
						checkBloom = true;
						bloomFilterK = optimialK;
					} finally {
						configLock.writeLock().unlock();
					}

					Logger.normal(this, "Finish resizing (" + name + ")");
				}
			};

			batchProcessEntries(resizeProcesser, _prevStoreSize, true, sleep);
		}

		/**
		 * Rebuild bloom filter
		 */
		private void rebuildBloom(boolean sleep) {
			if (bloomFilter == null)
				return;
			Logger.normal(this, "Start rebuilding bloom filter (" + name + ")");

			BatchProcessor<T> rebuildBloomProcessor = new BatchProcessor<T>() {
				int optimialK;

				public void init() {
					optimialK = BloomFilter.optimialK(bloomFilterSize, storeSize);

					configLock.writeLock().lock();
					try {
						generation++;
						bloomFilter.fork(bloomFilterK);
						keyCount.set(0);
					} finally {
						configLock.writeLock().unlock();
					}

					WrapperManager.signalStarting(RESIZE_MEMORY_ENTRIES * 5 * 1000 + 1000);
				}

				public Entry process(Entry entry) {
					if (entry.generation != generation) {
						bloomFilter.addKeyForked(entry.getDigestedRoutingKey());
						keyCount.incrementAndGet();

						entry.generation = generation;
						return entry;
					}
					return NOT_MODIFIED;
				}

				int i = 0;
				public boolean batch(long entriesLeft) {
					WrapperManager.signalStarting(RESIZE_MEMORY_ENTRIES * 5 * 1000 + 1000);

					if (i++ % 16 == 0)
						writeConfigFile();

					return prevStoreSize == 0;
				}

				public void abort() {
					bloomFilter.discard();
				}

				public void finish() {
					bloomFilter.merge();
					configLock.writeLock().lock();
					try {
						flags &= ~FLAG_REBUILD_BLOOM;
						checkBloom = true;
						bloomFilterK = optimialK;
					} finally {
						configLock.writeLock().unlock();
					}

					Logger.normal(this, "Finish rebuilding bloom filter (" + name + ")");
				}
			};

			batchProcessEntries(rebuildBloomProcessor, storeSize, false, sleep);
		}

		private volatile long entriesLeft;
		private volatile long entriesTotal;

		private void batchProcessEntries(BatchProcessor<T> processor, long storeSize, boolean reverse, boolean sleep) {
			entriesLeft = entriesTotal = storeSize;

			long startOffset, step;
			if (!reverse) {
				startOffset = 0;
				step = RESIZE_MEMORY_ENTRIES;
			} else {
				startOffset = ((storeSize - 1) / RESIZE_MEMORY_ENTRIES) * RESIZE_MEMORY_ENTRIES;
				step = -RESIZE_MEMORY_ENTRIES;
			}

			int i = 0;
			processor.init();
			try {
				for (long curOffset = startOffset; curOffset >= 0 && curOffset < storeSize; curOffset += step) {
					if (shutdown) {
						processor.abort();
						return;
					}

					if (i++ % 64 == 0)
						System.err.println(name + " cleaner in progress: " + (entriesTotal - entriesLeft) + "/"
						        + entriesTotal);

					batchProcessEntries(curOffset, RESIZE_MEMORY_ENTRIES, processor);
					entriesLeft = reverse ? curOffset : Math.max(storeSize - curOffset - RESIZE_MEMORY_ENTRIES, 0);
					if (!processor.batch(entriesLeft)) {
						processor.abort();
						return;
					}

					try {
						if (sleep)
							Thread.sleep(100);
					} catch (InterruptedException e) {
						processor.abort();
						return;
					}
				}
				processor.finish();
			} catch (Exception e) {
				processor.abort();
			}
		}

		/**
		 * Read a list of items from store.
		 *
		 * @param offset
		 *            start offset, must be multiple of {@link FILE_SPLIT}
		 * @param length
		 *            number of items to read, must be multiple of {@link FILE_SPLIT}. If this
		 *            excess store size, read as much as possible.
		 * @param processor
		 *            batch processor
		 * @return <code>true</code> if operation complete successfully; <code>false</code>
		 *         otherwise (e.g. can't acquire locks, node shutting down)
		 */
		private boolean batchProcessEntries(long offset, int length, BatchProcessor<T> processor) {
			Condition[] locked = new Condition[length];
			try {
				// acquire all locks in the region, will unlock in the finally block
				for (int i = 0; i < length; i++) {
					locked[i] = lockManager.lockEntry(offset + i);
					if (locked[i] == null)
						return false;
				}

				long startFileOffset = offset * Entry.METADATA_LENGTH;
				long entriesToRead = length;
				long bufLen = Entry.METADATA_LENGTH * entriesToRead;

				ByteBuffer buf = ByteBuffer.allocate((int) bufLen);
				boolean dirty = false;
				try {
					while (buf.hasRemaining()) {
						int status = metaFC.read(buf, startFileOffset + buf.position());
						if (status == -1)
							break;
					}
				} catch (IOException ioe) {
					if (shutdown)
						return false;
					Logger.error(this, "unexpected IOException", ioe);
				}
				buf.flip();

				try {
					for (int j = 0; !shutdown && buf.limit() > j * Entry.METADATA_LENGTH; j++) {
						buf.position(j * Entry.METADATA_LENGTH);
						if (buf.remaining() < Entry.METADATA_LENGTH) // EOF
							break;

						ByteBuffer enBuf = buf.slice();
						enBuf.limit(Entry.METADATA_LENGTH);

						Entry entry = new Entry(enBuf, null);
						entry.curOffset = offset + j;

						if (entry.isFree())
							continue; // not occupied

						Entry newEntry = processor.process(entry);
						if (newEntry == null) {// free the offset
							buf.position(j * Entry.METADATA_LENGTH);
							buf.put(ByteBuffer.allocate(Entry.METADATA_LENGTH));
							keyCount.decrementAndGet();

							dirty = true;
						} else if (newEntry == NOT_MODIFIED) {
						} else {
							// write back
							buf.position(j * Entry.METADATA_LENGTH);
							buf.put(newEntry.toMetaDataBuffer());

							assert newEntry.header == null; // not supported
							assert newEntry.data == null; // not supported

							dirty = true;
						}
					}
				} finally {
					// write back.
					if (dirty) {
						buf.flip();

						try {
							while (buf.hasRemaining()) {
								metaFC.write(buf, startFileOffset + buf.position());
							}
						} catch (IOException ioe) {
							Logger.error(this, "unexpected IOException", ioe);
						}
					}
				}

				return true;
			} finally {
				// unlock
				for (int i = 0; i < length; i++)
					if (locked[i] != null)
						lockManager.unlockEntry(offset + i, locked[i]);
			}
		}

		/**
		 * Put back an old entry to store file
		 *
		 * @param entry
		 * @return <code>true</code> if the entry have put back successfully.
		 */
		private boolean resolveOldEntry(Entry entry) {
			Map<Long, Condition> lockMap = lockDigestedKey(entry.getDigestedRoutingKey(), false);
			if (lockMap == null)
				return false;
			try {
				entry.storeSize = storeSize;
				long[] offsets = entry.getOffset();

				// Check for occupied entry with same key
				for (long offset : offsets) {
					try {
						if (!isFree(offset)
						        && Arrays.equals(getDigestedKeyFromOffset(offset), entry.getDigestedRoutingKey())) {
							// do nothing
							return true;
						}
					} catch (IOException e) {
						Logger.debug(this, "IOExcception on resolveOldEntry", e);
					}
				}

				// Check for free entry
				for (long offset : offsets) {
					try {
						if (isFree(offset)) {
							writeEntry(entry, offset);
							bloomFilter.addKeyForked(entry.getDigestedRoutingKey());
							keyCount.incrementAndGet();
							return true;
						}
					} catch (IOException e) {
						Logger.debug(this, "IOExcception on resolveOldEntry", e);
					}
				}
				return false;
			} finally {
				unlockDigestedKey(entry.getDigestedRoutingKey(), false, lockMap);
			}
		}
	}

	private final class CleanerStatusUserAlert extends AbstractUserAlert {
		private Cleaner cleaner;

		private CleanerStatusUserAlert(Cleaner cleaner) {
			this.cleaner = cleaner;
		}

		public String anchor() {
			return "store-cleaner-" + name;
		}

		public String dismissButtonText() {
			return NodeL10n.getBase().getString("UserAlert.hide");
		}

		public HTMLNode getHTMLText() {
			return new HTMLNode("#", getText());
		}

		public short getPriorityClass() {
			return UserAlert.MINOR;
		}

		public String getShortText() {
			if (cleaner.isResizing)
				return NodeL10n.getBase().getString("SaltedHashFreenetStore.shortResizeProgress", //
				        new String[] { "name", "processed", "total" },//
				        new String[] { name, (cleaner.entriesTotal - cleaner.entriesLeft) + "",
				                cleaner.entriesTotal + "" });
			else
				return NodeL10n.getBase().getString("SaltedHashFreenetStore.shortRebuildProgress", //
				        new String[] { "name", "processed", "total" },//
				        new String[] { name, (cleaner.entriesTotal - cleaner.entriesLeft) + "",
				                cleaner.entriesTotal + "" });
		}

		public String getText() {
			if (cleaner.isResizing)
				return NodeL10n.getBase().getString("SaltedHashFreenetStore.longResizeProgress", //
				        new String[] { "name", "processed", "total" },//
				        new String[] { name, (cleaner.entriesTotal - cleaner.entriesLeft) + "",
				                cleaner.entriesTotal + "" });
			else
				return NodeL10n.getBase().getString("SaltedHashFreenetStore.longRebuildProgress", //
				        new String[] { "name", "processed", "total" },//
				        new String[] { name, (cleaner.entriesTotal - cleaner.entriesLeft) + "",
				                cleaner.entriesTotal + "" });
		}

		public String getTitle() {
			return NodeL10n.getBase().getString("SaltedHashFreenetStore.cleanerAlertTitle", //
			        new String[] { "name" }, //
			        new String[] { name });
		}

		public Object getUserIdentifier() {
			return null;
		}

		public boolean isValid() {
			return cleaner.isRebuilding || cleaner.isResizing;
		}

		public void isValid(boolean validity) {
			// Ignore
		}

		public void onDismiss() {
			// Ignore
		}

		public boolean shouldUnregisterOnDismiss() {
			return true;
		}

		public boolean userCanDismiss() {
			return false;
		}

		public boolean isEventNotification() {
			return false;
		}
	}

	public void setUserAlertManager(UserAlertManager userAlertManager) {
		if (cleanerStatusUserAlert != null)
			userAlertManager.register(cleanerStatusUserAlert);
	}

	public void setMaxKeys(long newStoreSize, boolean shrinkNow) throws IOException {
		Logger.normal(this, "[" + name + "] Resize newStoreSize=" + newStoreSize + ", shinkNow=" + shrinkNow);

		configLock.writeLock().lock();
		try {
			if (newStoreSize == this.storeSize)
				return;

			if (prevStoreSize != 0) {
				Logger.normal(this, "[" + name + "] resize already in progress, ignore resize request");
				return;
			}

			prevStoreSize = storeSize;
			storeSize = newStoreSize;
			writeConfigFile();
		} finally {
			configLock.writeLock().unlock();
		}

		if (cleanerLock.tryLock()) {
			cleanerCondition.signal();
			cleanerLock.unlock();
		}
	}

	// ------------- Locking
	volatile boolean shutdown = false;
	private LockManager lockManager;
	private ReadWriteLock configLock = new ReentrantReadWriteLock();

	/**
	 * Lock all possible offsets of a key. This method would release the locks if any locking
	 * operation failed.
	 *
	 * @param plainKey
	 * @return <code>true</code> if all the offsets are locked.
	 */
	private Map<Long, Condition> lockPlainKey(byte[] plainKey, boolean usePrevStoreSize) {
		return lockDigestedKey(cipherManager.getDigestedKey(plainKey), usePrevStoreSize);
	}

	private void unlockPlainKey(byte[] plainKey, boolean usePrevStoreSize, Map<Long, Condition> lockMap) {
		unlockDigestedKey(cipherManager.getDigestedKey(plainKey), usePrevStoreSize, lockMap);
	}

	/**
	 * Lock all possible offsets of a key. This method would release the locks if any locking
	 * operation failed.
	 *
	 * @param digestedKey
	 * @return <code>true</code> if all the offsets are locked.
	 */
	private Map<Long, Condition> lockDigestedKey(byte[] digestedKey, boolean usePrevStoreSize) {
		// use a set to prevent duplicated offsets,
		// a sorted set to prevent deadlocks
		SortedSet<Long> offsets = new TreeSet<Long>();
		long[] offsetArray = getOffsetFromDigestedKey(digestedKey, storeSize);
		for (long offset : offsetArray)
			offsets.add(offset);
		if (usePrevStoreSize && prevStoreSize != 0) {
			offsetArray = getOffsetFromDigestedKey(digestedKey, prevStoreSize);
			for (long offset : offsetArray)
				offsets.add(offset);
		}

		Map<Long, Condition> locked = new TreeMap<Long, Condition>();
		for (long offset : offsets) {
			Condition condition = lockManager.lockEntry(offset);
			if (condition == null)
				break;
			locked.put(offset, condition);
		}

		if (locked.size() == offsets.size()) {
			return locked;
		} else {
			// failed, remove the locks
			for (Map.Entry<Long, Condition> e : locked.entrySet())
				lockManager.unlockEntry(e.getKey(), e.getValue());
			return null;
		}
	}

	private void unlockDigestedKey(byte[] digestedKey, boolean usePrevStoreSize, Map<Long, Condition> lockMap) {
		// use a set to prevent duplicated offsets
		SortedSet<Long> offsets = new TreeSet<Long>();
		long[] offsetArray = getOffsetFromDigestedKey(digestedKey, storeSize);
		for (long offset : offsetArray)
			offsets.add(offset);
		if (usePrevStoreSize && prevStoreSize != 0) {
			offsetArray = getOffsetFromDigestedKey(digestedKey, prevStoreSize);
			for (long offset : offsetArray)
				offsets.add(offset);
		}

		for (long offset : offsets) {
			lockManager.unlockEntry(offset, lockMap.get(offset));
			lockMap.remove(offset);
		}
	}

	public class ShutdownDB implements Runnable {
		public void run() {
			close();
		}
	}

	// ------------- Hashing
	private CipherManager cipherManager;

	/**
	 * Get offset in the hash table, given a plain routing key.
	 *
	 * @param plainKey
	 * @param storeSize
	 * @return
	 */
	private long[] getOffsetFromPlainKey(byte[] plainKey, long storeSize) {
		return getOffsetFromDigestedKey(cipherManager.getDigestedKey(plainKey), storeSize);
	}

	public void close() {
		shutdown = true;
		lockManager.shutdown();

		cleanerLock.lock();
		try {
			cleanerCondition.signalAll();
			cleanerThread.interrupt();
		} finally {
			cleanerLock.unlock();
		}

		configLock.writeLock().lock();
		try {
			flushAndClose();
			flags &= ~FLAG_DIRTY; // clean shutdown
			writeConfigFile();
		} finally {
			configLock.writeLock().unlock();
		}
		cipherManager.shutdown();
		System.out.println("Successfully closed store "+name);
	}

	/**
	 * Get offset in the hash table, given a digested routing key.
	 *
	 * @param digestedKey
	 * @param storeSize
	 * @return
	 */
	private long[] getOffsetFromDigestedKey(byte[] digestedKey, long storeSize) {
		long keyValue = Fields.bytesToLong(digestedKey);
		long[] offsets = new long[OPTION_MAX_PROBE];

		for (int i = 0; i < OPTION_MAX_PROBE; i++) {
			// h + 141 i^2 + 13 i
			offsets[i] = ((keyValue + 141 * (i * i) + 13 * i) & Long.MAX_VALUE) % storeSize;
			// Make sure the slots are all unique.
			// Important for very small stores e.g. in unit tests.
			while(true) {
				boolean clear = true;
				for(int j=0;j<i;j++) {
					if(offsets[i] == offsets[j]) {
						offsets[i] = (offsets[i] + 1) % storeSize;
						clear = false;
					}
				}
				if(clear || OPTION_MAX_PROBE > storeSize) break;
			}
		}

		return offsets;
	}

	// ------------- Statistics (a.k.a. lies)
	private AtomicLong hits = new AtomicLong();
	private AtomicLong misses = new AtomicLong();
	private AtomicLong writes = new AtomicLong();
	private AtomicLong keyCount = new AtomicLong();
	private AtomicLong bloomFalsePos = new AtomicLong();
	
	private long initialHits;
	private long initialMisses;
	private long initialWrites;
	private long initialBloomFalsePos;

	public long hits() {
		return hits.get();
	}

	public long misses() {
		return misses.get();
	}

	public long writes() {
		return writes.get();
	}

	public long keyCount() {
		return keyCount.get();
	}

	public long getMaxKeys() {
		configLock.readLock().lock();
		long _storeSize = storeSize;
		configLock.readLock().unlock();
		return _storeSize;
	}

	public long getBloomFalsePositive() {
		return bloomFalsePos.get();
	}

	// ------------- Migration
	public void migrationFrom(File storeFile, File keyFile) {
		try {
			System.out.println("Migrating from " + storeFile);

			RandomAccessFile storeRAF = new RandomAccessFile(storeFile, "r");
			RandomAccessFile keyRAF = keyFile.exists() ? new RandomAccessFile(keyFile, "r") : null;

			byte[] header = new byte[headerBlockLength];
			byte[] data = new byte[dataBlockLength];
			byte[] key = new byte[fullKeyLength];

			long maxKey = storeRAF.length() / (headerBlockLength + dataBlockLength);

			for (int l = 0; l < maxKey; l++) {
				if (l % 1024 == 0) {
					System.out.println(" migrating key " + l + "/" + maxKey);
					WrapperManager.signalStarting(10 * 60 * 1000); // max 10 minutes for every 1024 keys
				}

				boolean keyRead = false;
				storeRAF.readFully(header);
				storeRAF.readFully(data);
				try {
					if (keyRAF != null) {
						keyRAF.readFully(key);
						keyRead = true;
					}
				} catch (IOException e) {
				}

				try {
					T b = callback.construct(data, header, null, keyRead ? key : null, false, false, null, null);
					put(b, data, header, true, true);
				} catch (KeyVerifyException e) {
					System.out.println("kve at block " + l);
				} catch (KeyCollisionException e) {
					System.out.println("kce at block " + l);
				}
			}
		} catch (EOFException eof) {
			// done
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public boolean probablyInStore(byte[] routingKey) {
		configLock.readLock().lock();
		try {
			if (!checkBloom)
				return true;
			return bloomFilter.checkFilter(cipherManager.getDigestedKey(routingKey));
		} finally {
			configLock.readLock().unlock();
		}
	}

	public void destruct() {
		metaFile.delete();
		hdFile.delete();
		configFile.delete();
		bloomFile.delete();
	}

	public String toString() {
		return super.toString()+":"+name;
	}
	
	public StoreAccessStats getSessionAccessStats() {
		return new StoreAccessStats() {

			@Override
			public long hits() {
				return hits.get() - initialHits;
			}

			@Override
			public long misses() {
				return misses.get() - initialMisses;
			}

			@Override
			public long falsePos() {
				return bloomFalsePos.get() - initialBloomFalsePos;
			}

			@Override
			public long writes() {
				return writes.get() - initialWrites;
			}
			
		};
	}

	public StoreAccessStats getTotalAccessStats() {
		return new StoreAccessStats() {

			@Override
			public long hits() {
				return hits.get();
			}

			@Override
			public long misses() {
				return misses.get();
			}

			@Override
			public long falsePos() {
				return bloomFalsePos.get();
			}

			@Override
			public long writes() {
				return writes.get();
			}
			
		};
	}


}
