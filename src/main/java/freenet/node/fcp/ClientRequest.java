package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.async.ClientContext;
import freenet.client.async.ClientRequester;
import freenet.client.async.DBJob;
import freenet.client.async.DatabaseDisabledException;
import freenet.keys.FreenetURI;
import freenet.node.PrioRunnable;
import freenet.node.RequestClient;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * A request process carried out by the node for an FCP client.
 * Examples: ClientGet, ClientPut, MultiGet.
 */
public abstract class ClientRequest {

	/** URI to fetch, or target URI to insert to */
	protected FreenetURI uri;
	/** Unique request identifier */
	protected final String identifier;
	/** Verbosity level. Relevant to all ClientRequests, although they interpret it
	 * differently. */
	protected final int verbosity;
	/** Original FCPConnectionHandler. Null if persistence != connection */
	protected transient final FCPConnectionHandler origHandler;
	/** Client */
	protected final FCPClient client;
	/** Priority class */
	protected short priorityClass;
	/** Persistence type */
	protected final short persistenceType;
	/** Charset of the request's contents */
	protected final String charset;
	/** Has the request finished? */
	protected boolean finished;
	/** Client token (string to feed back to the client on a Persistent* when he does a
	 * ListPersistentRequests). */
	protected String clientToken;
	/** Is the request on the global queue? */
	protected final boolean global;
	/** Timestamp : startup time */
	protected final long startupTime;
	/** Timestamp : completion time */
	protected long completionTime;

	/** Timestamp: last activity. */
	protected long lastActivity;

	protected final RequestClient lowLevelClient;
	private final int hashCode; // for debugging it is good to have a persistent id
	
	@Override
	public int hashCode() {
		return hashCode;
	}

	private static volatile boolean logMINOR;
	
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback() {
			
			@Override
			public void shouldUpdate() {
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public ClientRequest(FreenetURI uri2, String identifier2, int verbosity2, String charset, 
			FCPConnectionHandler handler, FCPClient client, short priorityClass2, short persistenceType2, boolean realTime, String clientToken2, boolean global, ObjectContainer container) {
		int hash = super.hashCode();
		if(hash == 0) hash = 1;
		hashCode = hash;
		this.uri = uri2;
		this.identifier = identifier2;
		if(global)
			this.verbosity = Integer.MAX_VALUE;
		else
			this.verbosity = verbosity2;
		this.finished = false;
		this.priorityClass = priorityClass2;
		this.persistenceType = persistenceType2;
		this.charset = charset;
		this.clientToken = clientToken2;
		this.global = global;
		if(persistenceType == PERSIST_CONNECTION) {
			this.origHandler = handler;
			lowLevelClient = origHandler.connectionRequestClient(realTime);
			this.client = null;
		} else {
			origHandler = null;
			if(persistenceType == PERSIST_FOREVER) {
				container.activate(client, 1);
				client.init(container);
			}
			this.client = client;
			assert client != null;
			assert(client.persistenceType == persistenceType);
			lowLevelClient = client.lowLevelClient(realTime);
		}
		assert lowLevelClient != null;
		this.startupTime = System.currentTimeMillis();
	}

	public ClientRequest(FreenetURI uri2, String identifier2, int verbosity2, String charset, 
			FCPConnectionHandler handler, short priorityClass2, short persistenceType2, final boolean realTime, String clientToken2, boolean global, ObjectContainer container) {
		int hash = super.hashCode();
		if(hash == 0) hash = 1;
		hashCode = hash;
		this.uri = uri2;
		
		this.identifier = identifier2;
		if(global)
			this.verbosity = Integer.MAX_VALUE;
		else
			this.verbosity = verbosity2;
		this.finished = false;
		this.priorityClass = priorityClass2;
		this.persistenceType = persistenceType2;
		this.clientToken = clientToken2;
		this.charset = charset;
		this.global = global;
		if(persistenceType == PERSIST_CONNECTION) {
			this.origHandler = handler;
			client = null;
			lowLevelClient = new RequestClient() {

				public boolean persistent() {
					return false;
				}

				public void removeFrom(ObjectContainer container) {
					throw new UnsupportedOperationException();
				}

				public boolean realTimeFlag() {
					return realTime;
				}
				
			};
		} else {
			origHandler = null;
		if(global) {
			client = persistenceType == PERSIST_FOREVER ? handler.server.globalForeverClient : handler.server.globalRebootClient;
		} else {
			client = persistenceType == PERSIST_FOREVER ? handler.getForeverClient(container) : handler.getRebootClient();
		}
		if(persistenceType == PERSIST_FOREVER) {
			container.activate(client, 1);
			client.init(container);
		}
		lowLevelClient = client.lowLevelClient(realTime);
		if(lowLevelClient == null)
			throw new NullPointerException("No lowLevelClient from client: "+client+" global = "+global+" persistence = "+persistenceType);
		}
		if(lowLevelClient.persistent() != (persistenceType == PERSIST_FOREVER))
			throw new IllegalStateException("Low level client.persistent="+lowLevelClient.persistent()+" but persistence type = "+persistenceType);
		if(client != null)
			assert(client.persistenceType == persistenceType);
		this.startupTime = System.currentTimeMillis();
	}

	/** Lost connection */
	public abstract void onLostConnection(ObjectContainer container, ClientContext context);

	/** Send any pending messages for a persistent request e.g. after reconnecting */
	public abstract void sendPendingMessages(FCPConnectionOutputHandler handler, boolean includePersistentRequest, boolean includeData, boolean onlyData, ObjectContainer container);

	// Persistence

	public static final short PERSIST_CONNECTION = 0;
	public static final short PERSIST_REBOOT = 1;
	public static final short PERSIST_FOREVER = 2;

	public static String persistenceTypeString(short type) {
		switch(type) {
		case PERSIST_CONNECTION:
			return "connection";
		case PERSIST_REBOOT:
			return "reboot";
		case PERSIST_FOREVER:
			return "forever";
		default:
			return Short.toString(type);
		}
	}

	public static short parsePersistence(String string) {
		if((string == null) || string.equalsIgnoreCase("connection"))
			return PERSIST_CONNECTION;
		if(string.equalsIgnoreCase("reboot"))
			return PERSIST_REBOOT;
		if(string.equalsIgnoreCase("forever"))
			return PERSIST_FOREVER;
		return Short.parseShort(string);
	}

	abstract void register(ObjectContainer container, boolean noTags) throws IdentifierCollisionException;

	public void cancel(ObjectContainer container, ClientContext context) {
		ClientRequester cr = getClientRequest();
		// It might have been finished on startup.
		if(persistenceType == PERSIST_FOREVER)
			container.activate(cr, 1);
		if(logMINOR) Logger.minor(this, "Cancelling "+cr+" for "+this+" persistenceType = "+persistenceType);
		if(cr != null) cr.cancel(container, context);
		freeData(container);
		if(persistenceType == PERSIST_FOREVER)
			container.store(this);
	}

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

	/** Is the request persistent? False = we can drop the request if we lose the connection */
	public boolean isPersistent() {
		return persistenceType != ClientRequest.PERSIST_CONNECTION;
	}

	public boolean hasFinished() {
		return finished;
	}

	/** Get identifier string for request */
	public String getIdentifier() {
		return identifier;
	}

	protected abstract ClientRequester getClientRequest();

	/** Completed request dropped off the end without being acknowledged */
	public void dropped(ObjectContainer container, ClientContext context) {
		cancel(container, context);
		freeData(container);
	}

	/** Return the priority class */
	public short getPriority(){
		return priorityClass;
	}

	/** Free cached data bucket(s) */
	protected abstract void freeData(ObjectContainer container); 

	/** Request completed. But we may have to stick around until we are acked. */
	protected void finish(ObjectContainer container) {
		completionTime = System.currentTimeMillis();
		if(persistenceType == ClientRequest.PERSIST_CONNECTION)
			origHandler.finishedClientRequest(this);
		else
			client.finishedClientRequest(this, container);
		if(persistenceType == ClientRequest.PERSIST_FOREVER)
			container.store(this);
	}

	public abstract double getSuccessFraction(ObjectContainer container);

	public abstract double getTotalBlocks(ObjectContainer container);
	public abstract double getMinBlocks(ObjectContainer container);
	public abstract double getFetchedBlocks(ObjectContainer container);
	public abstract double getFailedBlocks(ObjectContainer container);
	public abstract double getFatalyFailedBlocks(ObjectContainer container);

	public abstract String getFailureReason(boolean longDescription, ObjectContainer container);

	/**
	 * Has the total number of blocks to insert been determined yet?
	 */
	public abstract boolean isTotalFinalized(ObjectContainer container);

	public void onMajorProgress(ObjectContainer container) {
		// Ignore
	}

	/** Start the request, if it has not already been started. */
	public abstract void start(ObjectContainer container, ClientContext context);

	protected boolean started;

	public boolean isStarted() {
		return started;
	}

	public abstract boolean hasSucceeded();

	/**
	 * Returns the time of the request’s last activity, or {@code 0} if there is
	 * no known last activity.
	 *
	 * @return The time of the request’s last activity, or {@code 0}
	 */
	public long getLastActivity() {
		return lastActivity;
	}

	public abstract boolean canRestart();

	public abstract boolean restart(ObjectContainer container, ClientContext context, boolean disableFilterData) throws DatabaseDisabledException;

	protected abstract FCPMessage persistentTagMessage(ObjectContainer container);

	/**
	 * Called after a ModifyPersistentRequest.
	 * Sends a PersistentRequestModified message to clients if any value changed. 
	 * Commits before sending the messages.
	 */
	public void modifyRequest(String newClientToken, short newPriorityClass, FCPServer server, ObjectContainer container) {

		boolean clientTokenChanged = false;
		boolean priorityClassChanged = false;

		if(newClientToken != null) {
			if( clientToken != null ) {
				if( !newClientToken.equals(clientToken) ) {
					this.clientToken = newClientToken; // token changed
					clientTokenChanged = true;
				}
			} else {
				this.clientToken = newClientToken; // first time the token is set
				clientTokenChanged = true;
			}
		}
		
		if(newPriorityClass >= 0 && newPriorityClass != priorityClass) {
			this.priorityClass = newPriorityClass;
			ClientRequester r = getClientRequest();
			if(persistenceType == PERSIST_FOREVER) container.activate(r, 1);
			if(r.checkForBrokenClient(container, server.node.clientCore.clientContext)) return;
			r.setPriorityClass(priorityClass, server.core.clientContext, container);
			if(persistenceType == PERSIST_FOREVER) container.deactivate(r, 1);
			priorityClassChanged = true;
			if(client != null) {
				RequestStatusCache cache = client.getRequestStatusCache();
				if(cache != null) {
					cache.setPriority(identifier, newPriorityClass);
				}
			}
		}

		if(! ( clientTokenChanged || priorityClassChanged ) ) {
			return; // quick return, nothing was changed
		}
		
		if(persistenceType == PERSIST_FOREVER) {
			container.store(this);
			container.commit(); // commit before we send the message
			if(logMINOR) Logger.minor(this, "COMMITTED");
		}

		// this could become too complex with more parameters, but for now its ok
		final PersistentRequestModifiedMessage modifiedMsg;
		if( clientTokenChanged && priorityClassChanged ) {
			modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, priorityClass, clientToken);
		} else if( priorityClassChanged ) {
			modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, priorityClass);
		} else if( clientTokenChanged ) {
			modifiedMsg = new PersistentRequestModifiedMessage(identifier, global, clientToken);
		} else {
			return; // paranoia, we should not be here if nothing was changed!
		}
		client.queueClientRequestMessage(modifiedMsg, 0, container);
	}

	public void restartAsync(final FCPServer server, final boolean disableFilterData) throws DatabaseDisabledException {
		synchronized(this) {
			this.started = false;
		}
		if(client != null) {
			RequestStatusCache cache = client.getRequestStatusCache();
			if(cache != null) {
				cache.updateStarted(identifier, false);
			}
		}
		if(persistenceType == PERSIST_FOREVER) {
		server.core.clientContext.jobRunner.queue(new DBJob() {

			public boolean run(ObjectContainer container, ClientContext context) {
				container.activate(ClientRequest.this, 1);
				try {
					restart(container, context, disableFilterData);
				} catch (DatabaseDisabledException e) {
					// Impossible
				}
				container.deactivate(ClientRequest.this, 1);
				return true;
			}
			
		}, NativeThread.HIGH_PRIORITY, false);
		} else {
			server.core.getExecutor().execute(new PrioRunnable() {

				public int getPriority() {
					return NativeThread.NORM_PRIORITY;
				}

				public void run() {
					try {
						restart(null, server.core.clientContext, disableFilterData);
					} catch (DatabaseDisabledException e) {
						// Impossible
					}
				}
				
			}, "Restart request");
		}
	}

	/**
	 * Called after a RemovePersistentRequest. Send a PersistentRequestRemoved to the clients.
	 * If the request is in the database, delete it.
	 */
	public void requestWasRemoved(ObjectContainer container, ClientContext context) {
		if(persistenceType != PERSIST_FOREVER) return;
		if(uri != null) uri.removeFrom(container);
		container.delete(this);
	}

	protected boolean isGlobalQueue() {
		if(client == null) return false;
		return client.isGlobalQueue;
	}

	public boolean objectCanUpdate(ObjectContainer container) {
		if(hashCode == 0) {
			Logger.error(this, "Trying to update with hash 0 => already deleted!", new Exception("error"));
			return false;
		}
		return true;
	}
	
	public boolean objectCanNew(ObjectContainer container) {
		if(persistenceType != PERSIST_FOREVER) {
			Logger.error(this, "Not storing non-persistent request in database", new Exception("error"));
			return false;
		}
		if(hashCode == 0) {
			Logger.error(this, "Trying to write with hash 0 => already deleted!", new Exception("error"));
			return false;
		}
		return true;
	}

	public void storeTo(ObjectContainer container) {
		container.store(this);
	}
	
	public FCPClient getClient(){
		return client;
	}

	abstract RequestStatus getStatus(ObjectContainer container);
	
}
