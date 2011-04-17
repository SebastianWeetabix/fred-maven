/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.zip.DeflaterOutputStream;

import net.i2p.util.NativeBigInteger;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Predicate;

import freenet.crypt.BlockCipher;
import freenet.crypt.DSA;
import freenet.crypt.DSAGroup;
import freenet.crypt.DSAPrivateKey;
import freenet.crypt.DSAPublicKey;
import freenet.crypt.DSASignature;
import freenet.crypt.Global;
import freenet.crypt.RandomSource;
import freenet.crypt.SHA256;
import freenet.crypt.UnsupportedCipherException;
import freenet.crypt.ciphers.Rijndael;
import freenet.io.AddressTracker.Status;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.IncomingPacketFilterImpl;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketHandler;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.support.Base64;
import freenet.support.Fields;
import freenet.support.IllegalBase64Exception;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.io.Closer;

/**
 * Cryptographic and transport level node identity.
 * @author toad
 */
public class NodeCrypto {

	/** Length of a node identity */
	public static final int IDENTITY_LENGTH = 32;
	final Node node;
	final boolean isOpennet;
	final RandomSource random;
	/** The object which handles our specific UDP port, pulls messages from it, feeds them to the packet mangler for decryption etc */
	final UdpSocketHandler socket;
	public FNPPacketMangler packetMangler;
	// FIXME: abstract out address stuff? Possibly to something like NodeReference?
	final int portNumber;
	byte[] myIdentity; // FIXME: simple identity block; should be unique
	/** Hash of identity. Used as setup key. */
	byte[] identityHash;
	/** Hash of hash of identity i.e. hash of setup key. */
	byte[] identityHashHash;
	/** Nonce used to generate ?secureid= for fproxy etc */
	byte[] clientNonce;
	/** My crypto group */
	private DSAGroup cryptoGroup;
	/** My private key */
	private DSAPrivateKey privKey;
	/** My public key */
	private DSAPublicKey pubKey;
	/** My ARK SSK private key */
	InsertableClientSSK myARK;
	/** My ARK sequence number */
	long myARKNumber;
	final NodeCryptoConfig config;
	final NodeIPPortDetector detector;
	final BlockCipher anonSetupCipher;

	// Noderef related
	/** An ordered version of the noderef FieldSet, without the signature */
	private String mySignedReference = null;
	/** The signature of the above fieldset */
	private DSASignature myReferenceSignature = null;
	/** A synchronization object used while signing the reference fieldset */
	private volatile Object referenceSync = new Object();

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	/**
	 * Get port number from a config, create socket and packet mangler
	 * @throws NodeInitException
	 */
	public NodeCrypto(final Node node, final boolean isOpennet, NodeCryptoConfig config, long startupTime, boolean enableARKs) throws NodeInitException {

		this.node = node;
		this.config = config;
		random = node.random;
		this.isOpennet = isOpennet;

		config.starting(this);

		try {

		int port = config.getPort();

		FreenetInetAddress bindto = config.getBindTo();

		UdpSocketHandler u = null;

		if(port > 65535) {
			throw new NodeInitException(NodeInitException.EXIT_IMPOSSIBLE_USM_PORT, "Impossible port number: "+port);
		} else if(port == -1) {
			// Pick a random port
			for(int i=0;i<200000;i++) {
				int portNo = 1024 + random.nextInt(65535-1024);
				try {
					u = new UdpSocketHandler(portNo, bindto.getAddress(), node, startupTime, getTitle(portNo), node.collector);
					port = u.getPortNumber();
					break;
				} catch (Exception e) {
					Logger.normal(this, "Could not use port: "+bindto+ ':' +portNo+": "+e, e);
					System.err.println("Could not use port: "+bindto+ ':' +portNo+": "+e);
					e.printStackTrace();
					continue;
				}
			}
			if(u == null)
				throw new NodeInitException(NodeInitException.EXIT_NO_AVAILABLE_UDP_PORTS, "Could not find an available UDP port number for FNP (none specified)");
		} else {
			try {
				u = new UdpSocketHandler(port, bindto.getAddress(), node, startupTime, getTitle(port), node.collector);
			} catch (Exception e) {
				Logger.error(this, "Caught "+e, e);
				System.err.println(e);
				e.printStackTrace();
				throw new NodeInitException(NodeInitException.EXIT_IMPOSSIBLE_USM_PORT, "Could not bind to port: "+port+" (node already running?)");
			}
		}
		socket = u;

		Logger.normal(this, "FNP port created on "+bindto+ ':' +port);
		System.out.println("FNP port created on "+bindto+ ':' +port);
		portNumber = port;
		config.setPort(port);

		socket.setDropProbability(config.getDropProbability());

		packetMangler = new FNPPacketMangler(node, this, socket);

		detector = new NodeIPPortDetector(node, node.ipDetector, this, enableARKs);

		anonSetupCipher = new Rijndael(256,256);

		} catch (NodeInitException e) {
			config.stopping(this);
			throw e;
		} catch (RuntimeException e) {
			config.stopping(this);
			throw e;
		} catch (Error e) {
			config.stopping(this);
			throw e;
		} catch (UnsupportedCipherException e) {
			config.stopping(this);
			throw new Error(e);
		} finally {
			config.maybeStarted(this);
		}
	}

	private String getTitle(int port) {
		// FIXME l10n
		return "UDP " + (isOpennet ? "Opennet " : "Darknet ") + "port " + port;
	}

	/**
	 * Read the cryptographic keys etc from a SimpleFieldSet
	 * @param fs
	 * @throws IOException
	 */
	public void readCrypto(SimpleFieldSet fs) throws IOException {
		String identity = fs.get("identity");
		if(identity == null)
			throw new IOException();
		try {
			myIdentity = Base64.decode(identity);
		} catch (IllegalBase64Exception e2) {
			throw new IOException();
		}
		identityHash = SHA256.digest(myIdentity);
		anonSetupCipher.initialize(identityHash);
		identityHashHash = SHA256.digest(identityHash);

		try {
			cryptoGroup = DSAGroup.create(fs.subset("dsaGroup"));
			privKey = DSAPrivateKey.create(fs.subset("dsaPrivKey"), cryptoGroup);
			pubKey = DSAPublicKey.create(fs.subset("dsaPubKey"), cryptoGroup);
		} catch (IllegalBase64Exception e) {
			Logger.error(this, "Caught "+e, e);
			throw new IOException(e.toString());
		} catch (FSParseException e) {
			Logger.error(this, "Caught "+e, e);
			throw new IOException(e.toString());
		}
		InsertableClientSSK ark = null;

		// ARK

		String s = fs.get("ark.number");

		String privARK = fs.get("ark.privURI");
		try {
			if(privARK != null) {
				FreenetURI uri = new FreenetURI(privARK);
				ark = InsertableClientSSK.create(uri);
				if(s == null) {
					ark = null;
					myARKNumber = 0;
				} else {
					try {
						myARKNumber = Long.parseLong(s);
					} catch (NumberFormatException e) {
						myARKNumber = 0;
						ark = null;
					}
				}
			}
		} catch (MalformedURLException e) {
			Logger.minor(this, "Caught "+e, e);
			ark = null;
		}
		if(ark == null) {
			ark = InsertableClientSSK.createRandom(random, "ark");
			myARKNumber = 0;
		}
		myARK = ark;

		String cn = fs.get("clientNonce");
		if(cn != null) {
			try {
				clientNonce = Base64.decode(cn);
			} catch (IllegalBase64Exception e) {
				throw new IOException("Invalid clientNonce field: "+e);
			}
		} else {
			clientNonce = new byte[32];
			node.random.nextBytes(clientNonce);
		}

	}

	/**
	 * Create the cryptographic keys etc from scratch
	 */
	public void initCrypto() {
		myIdentity = new byte[32];
		random.nextBytes(myIdentity);
		MessageDigest md = SHA256.getMessageDigest();
		identityHash = md.digest(myIdentity);
		identityHashHash = md.digest(identityHash);
		cryptoGroup = Global.DSAgroupBigA;
		privKey = new DSAPrivateKey(cryptoGroup, random);
		pubKey = new DSAPublicKey(cryptoGroup, privKey);
		myARK = InsertableClientSSK.createRandom(random, "ark");
		myARKNumber = 0;
		SHA256.returnMessageDigest(md);
		anonSetupCipher.initialize(identityHash);
		clientNonce = new byte[32];
		node.random.nextBytes(clientNonce);
	}

	public void start() {
		socket.calculateMaxPacketSize();
		socket.setLowLevelFilter(new IncomingPacketFilterImpl(packetMangler, node, this));
		packetMangler.start();
		socket.start();
	}

	public SimpleFieldSet exportPrivateFieldSet() {
		SimpleFieldSet fs = exportPublicFieldSet(false, false, false);
		addPrivateFields(fs);
		return fs;
	}

	/**
	 * Export my node reference so that another node can connect to me.
	 * Public version, includes everything apart from private keys.
	 * @see exportPublicFieldSet(boolean forSetup).
	 */
	public SimpleFieldSet exportPublicFieldSet() {
		return exportPublicFieldSet(false, false, false);
	}

	/**
	 * Export my reference so that another node can connect to me.
	 * @param forSetup If true, strip out everything that isn't needed for the references
	 * exchanged immediately after connection setup. I.e. strip out everything that is invariant,
	 * or that can safely be exchanged later.
	 * @param forAnonInitiator If true, we are adding a node from an anonymous initiator noderef
	 * exchange. Minimal noderef which we can construct a PeerNode from. Short lived so no ARK etc.
	 * Already signed so dump the signature.
	 */
	SimpleFieldSet exportPublicFieldSet(boolean forSetup, boolean forAnonInitiator, boolean forARK) {
		SimpleFieldSet fs = exportPublicCryptoFieldSet(forSetup || forARK, forAnonInitiator);
		if((!forAnonInitiator) && (!forSetup)) {
			// IP addresses
			Peer[] ips = detector.detectPrimaryPeers();
			if(ips != null) {
				for(int i=0;i<ips.length;i++)
					fs.putAppend("physical.udp", ips[i].toString()); // Keep; important that node know all our IPs
			}
		} // Don't include IPs for anonymous initiator.
		// Negotiation types
		fs.putSingle("version", Version.getVersionString()); // Keep, vital that peer know our version. For example, some types may be sent in different formats to different node versions (e.g. Peer).
		if(!forAnonInitiator)
			fs.putSingle("lastGoodVersion", Version.getLastGoodVersionString()); // Also vital
		if(node.isTestnetEnabled()) {
			fs.put("testnet", true);
			//fs.put("testnetPort", node.testnetHandler.getPort()); // Useful, saves a lot of complexity
		}
		if((!isOpennet) && (!forSetup) && (!forARK))
			fs.putSingle("myName", node.getMyName());

		if(!forAnonInitiator) {
			// Anonymous initiator setup type specifies whether the node is opennet or not.
			fs.put("opennet", isOpennet);
			synchronized (referenceSync) {
				if(myReferenceSignature == null || mySignedReference == null || !mySignedReference.equals(fs.toOrderedString())){
					mySignedReference = fs.toOrderedString();
					try {
						myReferenceSignature = signRef(mySignedReference);
					} catch (NodeInitException e) {
						node.exit(e.exitCode);
					}
				}
				fs.putSingle("sig", myReferenceSignature.toLongString());
			}
		}

		if(logMINOR) Logger.minor(this, "My reference: "+fs.toOrderedString());
		return fs;
	}

	SimpleFieldSet exportPublicCryptoFieldSet(boolean forSetup, boolean forAnonInitiator) {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		int[] negTypes = packetMangler.supportedNegTypes(true);
		if(!(forSetup || forAnonInitiator))
			// Can't change on setup.
			// Anonymous initiator doesn't need identity as we don't use it.
			fs.putSingle("identity", Base64.encode(myIdentity));
		if(!forSetup) {
			// These are invariant. They cannot change on connection setup. They can safely be excluded.
			fs.put("dsaGroup", cryptoGroup.asFieldSet());
			fs.put("dsaPubKey", pubKey.asFieldSet());
		}
		if(!forAnonInitiator) {
			// Short-lived connections don't need ARK and don't need negTypes either.
			fs.put("auth.negTypes", negTypes);
			if(!forSetup) {
				fs.put("ark.number", myARKNumber); // Can be changed on setup
				fs.putSingle("ark.pubURI", myARK.getURI().toString(false, false)); // Can be changed on setup
			}
		}
		return fs;
	}

	DSASignature signRef(String mySignedReference) throws NodeInitException {
		if(logMINOR) Logger.minor(this, "Signing reference:\n"+mySignedReference);

		try{
			byte[] ref = mySignedReference.getBytes("UTF-8");
			BigInteger m = new BigInteger(1, SHA256.digest(ref));
			if(logMINOR) Logger.minor(this, "m = "+m.toString(16));
			DSASignature _signature = DSA.sign(cryptoGroup, privKey, m, random);
			if(logMINOR && !DSA.verify(pubKey, _signature, m, false))
				throw new NodeInitException(NodeInitException.EXIT_EXCEPTION_TO_DEBUG, mySignedReference);
			return _signature;
		} catch(UnsupportedEncodingException e){
			//duh ?
			Logger.error(this, "Error while signing the node identity!" + e, e);
			System.err.println("Error while signing the node identity!"+e);
			e.printStackTrace();
			throw new NodeInitException(NodeInitException.EXIT_CRAPPY_JVM, "Impossible: JVM doesn't support UTF-8");
		}
	}

	private byte[] myCompressedRef(boolean setup, boolean heavySetup, boolean forARK) {
		SimpleFieldSet fs = exportPublicFieldSet(setup, heavySetup, forARK);
		boolean shouldStripGroup = heavySetup && Global.DSAgroupBigA.equals(cryptoGroup);
		if(shouldStripGroup)
			fs.removeSubset("dsaGroup");

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DeflaterOutputStream gis;
		gis = new DeflaterOutputStream(baos);
		try {
			fs.writeTo(gis);
                } catch (IOException e) {
                    Logger.error(this, "IOE :"+e.getMessage(), e);
		} finally {
			Closer.close(gis);
                        Closer.close(baos);
		}

		byte[] buf = baos.toByteArray();
		if(buf.length >= 4096)
			throw new IllegalStateException("We are attempting to send a "+buf.length+" bytes big reference!");
		byte[] obuf = new byte[buf.length + 1 + (shouldStripGroup ? 1 : 0)];
		int offset = 0;
		if(shouldStripGroup) {
			obuf[offset++] = 0x3; // compressed noderef - group
			int dsaGroupIndex = Global.GROUP_INDEX_BIG_A;
			if(logMINOR)
				Logger.minor(this, "We are stripping the group from the reference as it's a known group (groupIndex="+dsaGroupIndex+')');
			obuf[offset++] = (byte)(dsaGroupIndex & 0xff);
		} else
			obuf[offset++] = 0x01; // compressed noderef
		System.arraycopy(buf, 0, obuf, offset, buf.length);
		if(logMINOR)
			Logger.minor(this, "myCompressedRef("+setup+","+heavySetup+") returning "+obuf.length+" bytes");
		return obuf;
	}

	/**
	 * The part of our node reference which is exchanged in the connection setup, compressed.
	 * @see exportSetupFieldSet()
	 */
	public byte[] myCompressedSetupRef() {
		return myCompressedRef(true, false, false);
	}

	/**
	 * The part of our node reference which is exchanged in the connection setup, if we don't
	 * already have the node, compressed.
	 * @see exportSetupFieldSet()
	 */
	public byte[] myCompressedHeavySetupRef() {
		return myCompressedRef(false, true, false);
	}

	/**
	 * Our full node reference, compressed.
	 * @see exportSetupFieldSet()
	 */
	public byte[] myCompressedFullRef() {
		return myCompressedRef(false, false, false);
	}

	void addPrivateFields(SimpleFieldSet fs) {
		fs.put("dsaPrivKey", privKey.asFieldSet());
		fs.putSingle("ark.privURI", myARK.getInsertURI().toString(false, false));
		// FIXME remove the conditional after we've removed it from exportPublic...
		// We must save the location!
		if(fs.get("location") == null)
			fs.put("location", node.lm.getLocation());
		fs.putSingle("clientNonce", Base64.encode(clientNonce));

	}

	public int getIdentityHash(){
		return Fields.hashCode(identityHash);
	}

	/** Sign a hash */
	DSASignature sign(byte[] hash) {
		return DSA.sign(cryptoGroup, privKey, new NativeBigInteger(1, hash), random);
	}

	public void onSetDropProbability(int val) {
		synchronized(this) {
			if(socket == null) return;
		}
		socket.setDropProbability(val);
	}

	public void stop() {
		config.stopping(this);
		socket.close();
	}

	public PeerNode[] getPeerNodes() {
		if(node.peers == null) return null;
		if(isOpennet)
			return node.peers.getOpennetAndSeedServerPeers();
		else
			return node.peers.getDarknetPeers();
	}

	public boolean allowConnection(PeerNode pn, FreenetInetAddress addr) {
    	if(config.oneConnectionPerAddress()) {
    		// Disallow multiple connections to the same address
    		if(node.peers.anyConnectedPeerHasAddress(addr, pn) && !detector.includes(addr)) {
    			Logger.normal(this, "Not sending handshake packets to "+addr+" for "+pn+" : Same IP address as another node");
    			return false;
    		}
		}
    	return true;
	}

	DSAGroup getCryptoGroup() {
		return cryptoGroup;
	}

	/**
	 * Get the cipher for connection attempts for e.g. seednode connections from nodes we don't know.
	 */
	public BlockCipher getAnonSetupCipher() {
		return anonSetupCipher;
	}

	public PeerNode[] getAnonSetupPeerNodes() {
		ArrayList<PeerNode> v = new ArrayList<PeerNode>();
		PeerNode[] peers = node.peers.myPeers;
		for(int i=0;i<peers.length;i++) {
			PeerNode pn = peers[i];
			if(pn.handshakeUnknownInitiator() && pn.getOutgoingMangler() == packetMangler)
				v.add(pn);
		}
		return v.toArray(new PeerNode[v.size()]);
	}

	void setPortForwardingBroken() {
		this.socket.getAddressTracker().setBroken();
	}

	/**
	 * Get my identity.
	 * @param unknownInitiator Unknown-initiator connections use the hash of the pubkey as the identity to save space
	 * in packets 3 and 4.
	 */
	public byte[] getIdentity(boolean unknownInitiator) {
		if(unknownInitiator)
			return this.pubKey.asBytesHash();
		else
			return myIdentity;
	}

	public boolean definitelyPortForwarded() {
		return socket.getDetectedConnectivityStatus() == Status.DEFINITELY_PORT_FORWARDED;
	}

	public Status getDetectedConnectivityStatus() {
		return socket.getDetectedConnectivityStatus();
	}

	public FreenetInetAddress getBindTo() {
		return config.getBindTo();
	}

	public long getNodeHandle(ObjectContainer setupContainer) {
		if(setupContainer == null) return random.nextLong();
		// Ignore warnings, this is db4o magic.
		ObjectSet<HandlePortTuple> result = setupContainer.query(new Predicate<HandlePortTuple>() {
			final private static long serialVersionUID = -5442250371745036389L;
			@Override
			public boolean match(HandlePortTuple tuple) {
				return tuple.portNumber == portNumber;
			}
		});
		long handle;
		if(result.hasNext()) {
			handle = result.next().handle;
			System.err.println("Retrieved database handle for node on port "+portNumber+": "+handle);
			return handle;
		} else {
			while(true) {
				handle = random.nextLong();
				HandlePortTuple tuple = new HandlePortTuple();
				tuple.handle = handle;
				// Double-check with QBE, just in case the RNG is broken (similar things have happened before!)
				ObjectSet os = setupContainer.get(tuple);
				if(os.hasNext()) {
					System.err.println("Generating database handle for node: already taken: "+handle);
					continue;
				}
				tuple.portNumber = portNumber;
				setupContainer.store(tuple);
				setupContainer.commit();
				if(logMINOR) Logger.minor(this, "COMMITTED");
				System.err.println("Generated and stored database handle for node on port "+portNumber+": "+handle);
				return handle;
			}
		}
	}

	public boolean wantAnonAuth() {
		return node.wantAnonAuth(isOpennet);
	}
	
	public boolean wantAnonAuthChangeIP() {
		return node.wantAnonAuthChangeIP(isOpennet);
	}
}

