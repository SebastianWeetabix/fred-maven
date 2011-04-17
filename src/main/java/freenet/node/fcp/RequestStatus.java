package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.client.events.SplitfileProgressEvent;
import freenet.keys.FreenetURI;

/** The status of a request. Cached copy i.e. can be accessed outside the database thread
 * even for a persistent request.
 * @author toad 
 */
public abstract class RequestStatus {
	
	private final String identifier;
	private boolean hasStarted;
	private boolean hasFinished;
	private boolean hasSucceeded;
	private short priority;
	private int totalBlocks;
	private int minBlocks;
	private int fetchedBlocks;
	private int fatallyFailedBlocks;
	private int failedBlocks;
	private boolean isTotalFinalized;
	private long lastActivity;
	private final short persistenceType;
	
	/** The download or upload has finished.
	 * @param success Did it succeed? */
	synchronized void setFinished(boolean success) {
		this.lastActivity = System.currentTimeMillis();
		this.hasFinished = true;
		this.hasSucceeded = success;
		this.hasStarted = true;
		this.isTotalFinalized = true;
	}
	
	synchronized void restart(boolean started) {
		this.lastActivity = System.currentTimeMillis();
		this.hasFinished = false;
		this.hasSucceeded = false;
		this.hasStarted = started;
		this.isTotalFinalized = false;
	}
	
	/** Constructor for creating a status from a request that has already started, e.g. on
	 * startup. We will also create status when a request is created. */
	RequestStatus(String identifier, short persistence, boolean started, boolean finished, 
			boolean success, int total, int min, int fetched, int fatal, int failed,
			boolean totalFinalized,
			long last, short prio) {
		this.identifier = identifier;
		this.hasStarted = started;
		this.hasFinished = finished;
		this.hasSucceeded = success;
		this.priority = prio;
		this.totalBlocks = total;
		this.minBlocks = min;
		this.fetchedBlocks = fetched;
		this.fatallyFailedBlocks = fatal;
		this.failedBlocks = failed;
		this.isTotalFinalized = totalFinalized;
		this.lastActivity = last;
		this.persistenceType = persistence;
	}
	
	public boolean hasSucceeded() {
		return hasSucceeded;
	}

	public boolean hasFinished() {
		return hasFinished;
	}

	public short getPriority() {
		return priority;
	}

	public String getIdentifier() {
		return identifier;
	}

	public int getTotalBlocks() {
		return totalBlocks;
	}

	public boolean isTotalFinalized() {
		return isTotalFinalized;
	}

	public int getMinBlocks() {
		return minBlocks;
	}

	public int getFetchedBlocks() {
		return fetchedBlocks;
	}

	public long getLastActivity() {
		return lastActivity;
	}

	/** Get the original URI for a fetch or the final URI for an insert. */
	public abstract FreenetURI getURI();

	public abstract long getDataSize();

	public boolean isPersistentForever() {
		return persistenceType == ClientRequest.PERSIST_FOREVER;
	}

	public boolean isPersistent() {
		return persistenceType != ClientRequest.PERSIST_CONNECTION;
	}

	public int getFatalyFailedBlocks() {
		return fatallyFailedBlocks;
	}

	public int getFailedBlocks() {
		return failedBlocks;
	}

	public boolean isStarted() {
		return hasStarted;
	}

	public abstract String getFailureReason(boolean longDescription);

	public synchronized void updateStatus(SplitfileProgressEvent event) {
		this.failedBlocks = event.failedBlocks;
		this.fatallyFailedBlocks = event.fatallyFailedBlocks;
		this.fetchedBlocks = event.succeedBlocks;
		this.isTotalFinalized = event.finalizedTotal;
		this.minBlocks = event.minSuccessfulBlocks;
		this.totalBlocks = event.totalBlocks;
		updateLastActivity();
	}
	
	protected synchronized void updateLastActivity() {
		lastActivity = System.currentTimeMillis();
	}

	public synchronized void setPriority(short newPriority) {
		this.priority = newPriority;
	}

	public synchronized void setStarted(boolean started) {
		this.hasStarted = started;
	}

}
