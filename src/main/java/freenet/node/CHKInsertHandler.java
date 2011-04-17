/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.RetrievalException;
import freenet.io.comm.SlowAsyncMessageFilterCallback;
import freenet.io.xfer.AbortedException;
import freenet.io.xfer.BlockReceiver;
import freenet.io.xfer.BlockReceiver.BlockReceiverCompletion;
import freenet.io.xfer.BlockReceiver.BlockReceiverTimeoutHandler;
import freenet.io.xfer.PartiallyReceivedBlock;
import freenet.keys.CHKBlock;
import freenet.keys.CHKVerifyException;
import freenet.keys.NodeCHK;
import freenet.store.KeyCollisionException;
import freenet.support.HexUtil;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.ShortBuffer;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/**
 * @author amphibian
 * 
 * Handle an incoming insert request.
 * This corresponds to RequestHandler.
 */
public class CHKInsertHandler implements PrioRunnable, ByteCounter {
	private static volatile boolean logMINOR;

	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

    static final int DATA_INSERT_TIMEOUT = 10000;
    
    final Message req;
    final Node node;
    final long uid;
    final PeerNode source;
    final NodeCHK key;
    final long startTime;
    private short htl;
    private CHKInsertSender sender;
    private byte[] headers;
    private BlockReceiver br;
    private Thread runThread;
    PartiallyReceivedBlock prb;
    final InsertTag tag;
    private boolean canWriteDatastore;
	private final boolean forkOnCacheable;
	private final boolean preferInsert;
	private final boolean ignoreLowBackoff;
	private final boolean realTimeFlag;

    CHKInsertHandler(Message req, PeerNode source, long id, Node node, long startTime, InsertTag tag, boolean forkOnCacheable, boolean preferInsert, boolean ignoreLowBackoff, boolean realTimeFlag) {
        this.req = req;
        this.node = node;
        this.uid = id;
        this.source = source;
        this.startTime = startTime;
        this.tag = tag;
        key = (NodeCHK) req.getObject(DMT.FREENET_ROUTING_KEY);
        htl = req.getShort(DMT.HTL);
        if(htl <= 0) htl = 1;
        canWriteDatastore = node.canWriteDatastoreInsert(htl);
        receivedBytes(req.receivedByteCount());
        this.forkOnCacheable = forkOnCacheable;
        this.preferInsert = preferInsert;
        this.ignoreLowBackoff = ignoreLowBackoff;
        this.realTimeFlag = realTimeFlag;
    }
    
    @Override
	public String toString() {
        return super.toString()+" for "+uid;
    }
    
    public void run() {
	    freenet.support.Logger.OSThread.logPID(this);
        try {
        	realRun();
		} catch (OutOfMemoryError e) {
			OOMHandler.handleOOM(e);
			tag.handlerThrew(e);
        } catch (Throwable t) {
            Logger.error(this, "Caught in run() "+t, t);
            tag.handlerThrew(t);
        } finally {
        	if(logMINOR) Logger.minor(this, "Exiting CHKInsertHandler.run() for "+uid);
        	tag.unlockHandler();
        }
    }

    private void realRun() {
        runThread = Thread.currentThread();
        
        // FIXME implement rate limiting or something!
        // Send Accepted
        Message accepted = DMT.createFNPAccepted(uid);
        try {
			//Using sendSync here will help the next message filter not timeout... wait here or at the message filter.
			source.sendSync(accepted, this, realTimeFlag);
		} catch (NotConnectedException e1) {
			if(logMINOR) Logger.minor(this, "Lost connection to source");
			return;
		} catch (SyncSendWaitedTooLongException e) {
			Logger.error(this, "Unable to send "+accepted+" in a reasonable time to "+source);
			return;
		}
        
        // Source will send us a DataInsert
        
        MessageFilter mf;
        mf = makeDataInsertFilter(DATA_INSERT_TIMEOUT);
        
        Message msg;
        try {
            msg = node.usm.waitFor(mf, this);
        } catch (DisconnectedException e) {
            Logger.normal(this, "Disconnected while waiting for DataInsert on "+uid);
            return;
        }
        
        if(logMINOR) Logger.minor(this, "Received "+msg);
        
        if(msg == null) {
        	handleNoDataInsert();
        	return;
        }
        
        if(msg.getSpec() == DMT.FNPDataInsertRejected) {
        	// Not caused by the immediately upstream node.
        	return;
        }
        
        // We have a DataInsert
        headers = ((ShortBuffer)msg.getObject(DMT.BLOCK_HEADERS)).getData();
        // FIXME check the headers
        
        // Now create an CHKInsertSender, or use an existing one, or
        // discover that the data is in the store.

        // From this point onwards, if we return cleanly we must go through finish().
        
        prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
        if(htl > 0)
            sender = node.makeInsertSender(key, htl, uid, tag, source, headers, prb, false, false, forkOnCacheable, preferInsert, ignoreLowBackoff, realTimeFlag);
        br = new BlockReceiver(node.usm, source, uid, prb, this, node.getTicker(), false, realTimeFlag, myTimeoutHandler);
        
        // Receive the data, off thread
        Runnable dataReceiver = new DataReceiver();
		receiveStarted = true;
        node.executor.execute(dataReceiver, "CHKInsertHandler$DataReceiver for UID "+uid);

        // Wait...
        // What do we want to wait for?
        // If the data receive completes, that's very nice,
        // but doesn't really matter. What matters is what
        // happens to the CHKInsertSender. If the data receive
        // fails, that does matter...
        
        // We are waiting for a terminal status on the CHKInsertSender,
        // including SUCCESS.
        // If we get transfer failed, we can check whether the receive
        // failed first. If it did it's not our fault.
        // If the receive failed, and we haven't started transferring
        // yet, we probably want to kill the sender.
        // So we call the wait method on the CHKInsertSender, but we
        // also have a flag locally to indicate the receive failed.
        // And if it does, we interrupt.
        
        boolean receivedRejectedOverload = false;
        
        while(true) {
            synchronized(sender) {
                try {
                	if(sender.getStatus() == CHKInsertSender.NOT_FINISHED)
                		sender.wait(5000);
                } catch (InterruptedException e) {
                    // Cool, probably this is because the receive failed...
                }
            }
            if(receiveFailed()) {
                // Nothing else we can do
                finish(CHKInsertSender.RECEIVE_FAILED);
                return;
            }
            
            if((!receivedRejectedOverload) && sender.receivedRejectedOverload()) {
            	receivedRejectedOverload = true;
            	// Forward it
            	// Does not need to be sent synchronously since is non-terminal.
            	Message m = DMT.createFNPRejectedOverload(uid, false, true, realTimeFlag);
            	try {
					source.sendAsync(m, null, this);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				}
            }
            
            int status = sender.getStatus();
            
            if(status == CHKInsertSender.NOT_FINISHED) {
                continue;
            }

            // Local RejectedOverload's (fatal).
            // Internal error counts as overload. It'd only create a timeout otherwise, which is the same thing anyway.
            if((status == CHKInsertSender.TIMED_OUT) ||
            		(status == CHKInsertSender.GENERATED_REJECTED_OVERLOAD) ||
            		(status == CHKInsertSender.INTERNAL_ERROR)) {
                msg = DMT.createFNPRejectedOverload(uid, true, true, realTimeFlag);
                try {
					source.sendSync(msg, this, realTimeFlag);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				} catch (SyncSendWaitedTooLongException e) {
					Logger.error(this, "Took too long to send "+msg+" to "+source);
					return;
				}
                // Might as well store it anyway.
                if((status == CHKInsertSender.TIMED_OUT) ||
                		(status == CHKInsertSender.GENERATED_REJECTED_OVERLOAD))
                	canCommit = true;
                finish(status);
                return;
            }
            
            if((status == CHKInsertSender.ROUTE_NOT_FOUND) || (status == CHKInsertSender.ROUTE_REALLY_NOT_FOUND)) {
                msg = DMT.createFNPRouteNotFound(uid, sender.getHTL());
                try {
					source.sendSync(msg, this, realTimeFlag);
				} catch (NotConnectedException e) {
					if(logMINOR) Logger.minor(this, "Lost connection to source");
					return;
				} catch (SyncSendWaitedTooLongException e) {
					Logger.error(this, "Took too long to send "+msg+" to "+source);
					return;
				}
                canCommit = true;
                finish(status);
                return;
            }
            
            if(status == CHKInsertSender.RECEIVE_FAILED) {
            	// Probably source's fault.
            	finish(status);
            	return;
            }
            
            if(status == CHKInsertSender.SUCCESS) {
            	msg = DMT.createFNPInsertReply(uid);
            	try {
					source.sendSync(msg, this, realTimeFlag);
				} catch (NotConnectedException e) {
					Logger.minor(this, "Lost connection to source");
					return;
				} catch (SyncSendWaitedTooLongException e) {
					Logger.error(this, "Took too long to send "+msg+" to "+source);
					return;
				}
                canCommit = true;
                finish(status);
                return;
            }
            
            // Otherwise...?
            Logger.error(this, "Unknown status code: "+sender.getStatusString());
            msg = DMT.createFNPRejectedOverload(uid, true, true, realTimeFlag);
            try {
				source.sendSync(msg, this, realTimeFlag);
			} catch (NotConnectedException e) {
				// Ignore
			} catch (SyncSendWaitedTooLongException e) {
				// Ignore
			}
            finish(CHKInsertSender.INTERNAL_ERROR);
            return;
        }
	}

	private MessageFilter makeDataInsertFilter(int timeout) {
    	MessageFilter mfDataInsert = MessageFilter.create().setType(DMT.FNPDataInsert).setField(DMT.UID, uid).setSource(source).setTimeout(timeout);
    	// DataInsertRejected means the transfer failed upstream so a DataInsert will not be sent.
    	MessageFilter mfDataInsertRejected = MessageFilter.create().setType(DMT.FNPDataInsertRejected).setField(DMT.UID, uid).setSource(source).setTimeout(timeout);
    	return mfDataInsert.or(mfDataInsertRejected);
	}

	private void handleNoDataInsert() {
    	try {
    		// Nodes wait until they have the DataInsert before forwarding, so there is absolutely no excuse: There is a local problem here!
    		if(source.isConnected() && (startTime > (source.timeLastConnectionCompleted()+Node.HANDSHAKE_TIMEOUT*4)))
    			Logger.warning(this, "Did not receive DataInsert on "+uid+" from "+source+" !");
    		Message tooSlow = DMT.createFNPRejectedTimeout(uid);
    		source.sendAsync(tooSlow, null, this);
    		Message m = DMT.createFNPInsertTransfersCompleted(uid, true);
    		source.sendAsync(m, null, this);
    		prb = new PartiallyReceivedBlock(Node.PACKETS_IN_BLOCK, Node.PACKET_SIZE);
    		br = new BlockReceiver(node.usm, source, uid, prb, this, node.getTicker(), false, realTimeFlag, null);
    		prb.abort(RetrievalException.NO_DATAINSERT, "No DataInsert", true);
    		source.localRejectedOverload("TimedOutAwaitingDataInsert", realTimeFlag);
    		
    		// Two stage timeout. Don't go fatal unless no response in 60 seconds.
    		// Yes it's ugly everywhere but since we have a longish connection timeout it's necessary everywhere. :|
    		// FIXME review two stage timeout everywhere with some low level networking guru.
    		MessageFilter mf = makeDataInsertFilter(60*1000);
    		node.usm.addAsyncFilter(mf, new SlowAsyncMessageFilterCallback() {

    			public void onMatched(Message m) {
    				// Okay, great.
    				// Either we got a DataInsert, in which case the transfer was aborted above, or we got a DataInsertRejected, which means it never started.
    				// FIXME arguably we should wait until we have the message before sending the transfer cancel in case the message gets lost? Or maybe not?
    			}

    			public boolean shouldTimeout() {
    				return false;
    			}

    			public void onTimeout() {
    				Logger.error(this, "No DataInsert for "+CHKInsertHandler.this+" from "+source+" ("+source.getVersionNumber()+")");
    				// Fatal timeout. Something is seriously busted.
    				// We've waited long enough that we know it's not just a connectivity problem - if it was we'd have disconnected by now.
    	    		source.fatalTimeout();
    			}

    			public void onDisconnect(PeerContext ctx) {
    				// Okay. Somewhat expected, it was having problems.
    			}

    			public void onRestarted(PeerContext ctx) {
    				// Okay.
    			}

    			public int getPriority() {
    				return NativeThread.NORM_PRIORITY;
    			}
    			
    		}, this);
    		return;
    	} catch (NotConnectedException e) {
    		if(logMINOR) Logger.minor(this, "Lost connection to source");
			return;
    	} catch (DisconnectedException e) {
    		if(logMINOR) Logger.minor(this, "Lost connection to source");
			return;
		}
	}

	private boolean canCommit = false;
    private boolean sentCompletion = false;
    private Object sentCompletionLock = new Object();
    
    /**
     * If canCommit, and we have received all the data, and it
     * verifies, then commit it.
     */
    private void finish(int code) {
    	if(logMINOR) Logger.minor(this, "Waiting for receive");
		synchronized(this) {
			while(receiveStarted && !receiveCompleted) {
				try {
					wait(100*1000);
				} catch (InterruptedException e) {
					// Ignore
				}
			}
    	}
		
		CHKBlock block = verify();
		// If we wanted to reduce latency at the cost of security (bug 3338), we'd commit here, or even on the receiver thread.
		
        // Wait for completion
        boolean sentCompletionWasSet;
        synchronized(sentCompletionLock) {
        	sentCompletionWasSet = sentCompletion;
        	sentCompletion = true;
        }
        
		Message m=null;
		
        if((sender != null) && (!sentCompletionWasSet)) {
            if(logMINOR) Logger.minor(this, "Waiting for completion");
			//If there are downstream senders, our final success report depends on there being no timeouts in the chain.
        	while(true) {
        		synchronized(sender) {
        			if(sender.completed()) {
        				break;
        			}
        			try {
        				sender.wait(10*1000);
        			} catch (InterruptedException e) {
        				// Loop
        			}
        		}
        	}
        	boolean failed = sender.anyTransfersFailed();
        	m = DMT.createFNPInsertTransfersCompleted(uid, failed);
		}
		
		if((sender == null) && (!sentCompletionWasSet) && (canCommit)) {
			//There are no downstream senders, but we stored the data locally, report successful transfer.
			//Note that this is done even if the verify fails.
			m = DMT.createFNPInsertTransfersCompleted(uid, false /* no timeouts */);
		}		
		
		// Don't commit until after we have received all the downstream transfer completion notifications.
		// We don't want an attacker to see a ULPR notice from the inserter before he sees it from the end of the chain (bug 3338).
		if(block != null) {
			commit(block);
			block = null;
		}
        
        	try {
        		source.sendSync(m, this, realTimeFlag);
        		if(logMINOR) Logger.minor(this, "Sent completion: "+m+" for "+this);
        	} catch (NotConnectedException e1) {
        		if(logMINOR) Logger.minor(this, "Not connected: "+source+" for "+this);
        		// May need to commit anyway...
        	} catch (SyncSendWaitedTooLongException e) {
        		Logger.error(this, "Took too long to send "+m+" to "+source);
        		// May need to commit anyway...
			}
		        
        if(code != CHKInsertSender.TIMED_OUT && code != CHKInsertSender.GENERATED_REJECTED_OVERLOAD && 
        		code != CHKInsertSender.INTERNAL_ERROR && code != CHKInsertSender.ROUTE_REALLY_NOT_FOUND &&
        		code != CHKInsertSender.RECEIVE_FAILED && !receiveFailed()) {
        	int totalSent = getTotalSentBytes();
        	int totalReceived = getTotalReceivedBytes();
        	if(sender != null) {
        		totalSent += sender.getTotalSentBytes();
        		totalReceived += sender.getTotalReceivedBytes();
        	}
        	if(logMINOR) Logger.minor(this, "Remote CHK insert cost "+totalSent+ '/' +totalReceived+" bytes ("+code+ ") receive failed = "+receiveFailed());
        	node.nodeStats.remoteChkInsertBytesSentAverage.report(totalSent);
        	node.nodeStats.remoteChkInsertBytesReceivedAverage.report(totalReceived);
        	if(code == CHKInsertSender.SUCCESS) {
        		// Report both sent and received because we have both a Handler and a Sender
        		if(sender != null && sender.startedSendingData())
        			node.nodeStats.successfulChkInsertBytesSentAverage.report(totalSent);
        		node.nodeStats.successfulChkInsertBytesReceivedAverage.report(totalReceived);
        	}
        }
    }
    
    /**
     * Verify data, or send DataInsertRejected.
     */
    private CHKBlock verify() {
        Message toSend = null;
        
        CHKBlock block = null;
        
        synchronized(this) {
        	if((prb == null) || prb.isAborted()) return null;
            try {
                if(!canCommit) return null;
                if(!prb.allReceived()) return null;
                block = new CHKBlock(prb.getBlock(), headers, key);
            } catch (CHKVerifyException e) {
            	Logger.error(this, "Verify failed in CHKInsertHandler: "+e+" - headers: "+HexUtil.bytesToHex(headers), e);
                toSend = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_VERIFY_FAILED);
            } catch (AbortedException e) {
            	Logger.error(this, "Receive failed: "+e);
            	// Receiver thread (below) will handle sending the failure notice
            }
        }
        if(toSend != null) {
            try {
                source.sendAsync(toSend, null, this);
            } catch (NotConnectedException e) {
                // :(
            	if(logMINOR) Logger.minor(this, "Lost connection in "+this+" when sending FNPDataInsertRejected");
            }
        }
        return block;
	}
    
    private void commit(CHKBlock block) {
        try {
			node.store(block, node.shouldStoreDeep(key, source, sender == null ? new PeerNode[0] : sender.getRoutedTo()), false, canWriteDatastore, false);
		} catch (KeyCollisionException e) {
			// Impossible with CHKs.
		}
        if(logMINOR) Logger.minor(this, "Committed");
    }

	/** Has the receive failed? If so, there's not much more that can be done... */
    private boolean receiveFailed;
    
    private boolean receiveStarted;
    private boolean receiveCompleted;

    public class DataReceiver implements PrioRunnable {

        public void run() {
		    freenet.support.Logger.OSThread.logPID(this);
        	if(logMINOR) Logger.minor(this, "Receiving data for "+CHKInsertHandler.this);
        	// Don't log whether the transfer succeeded or failed as the transfer was initiated by the source therefore could be unreliable evidence.
        	br.receive(new BlockReceiverCompletion() {
        		
        		public void blockReceived(byte[] buf) {
        			if(logMINOR) Logger.minor(this, "Received data for "+CHKInsertHandler.this);
        			synchronized(CHKInsertHandler.this) {
        				receiveCompleted = true;
        				CHKInsertHandler.this.notifyAll();
        			}
   					node.nodeStats.successfulBlockReceive(realTimeFlag, false);
        		}

        		public void blockReceiveFailed(RetrievalException e) {
        			synchronized(CHKInsertHandler.this) {
        				receiveCompleted = true;
        				receiveFailed = true;
        				CHKInsertHandler.this.notifyAll();
        			}
        			// Cancel the sender
        			if(sender != null)
        				sender.onReceiveFailed(); // tell it to stop if it hasn't already failed... unless it's sending from store
        			runThread.interrupt();
        			tag.reassignToSelf(); // sender is finished, or will be very soon; we may however be waiting for the sendAborted downstream.
        			Message msg = DMT.createFNPDataInsertRejected(uid, DMT.DATA_INSERT_REJECTED_RECEIVE_FAILED);
        			try {
        				source.sendSync(msg, CHKInsertHandler.this, realTimeFlag);
        			} catch (NotConnectedException ex) {
        				//If they are not connected, that's probably why the receive failed!
        				if (logMINOR) Logger.minor(this, "Can't send "+msg+" to "+source+": "+ex);
        			} catch (SyncSendWaitedTooLongException ex) {
        				Logger.error(this, "Took too long to send "+msg+" to "+source);
					}
        			if (e.getReason()==RetrievalException.SENDER_DISCONNECTED)
        				Logger.normal(this, "Failed to retrieve (disconnect): "+e+" for "+CHKInsertHandler.this, e);
        			else
        				// Annoying, but we have stats for this; no need to call attention to it, it's unlikely to be a bug.
        				Logger.normal(this, "Failed to retrieve ("+e.getReason()+"/"+RetrievalException.getErrString(e.getReason())+"): "+e+" for "+CHKInsertHandler.this, e);
        			
        			if(!prb.abortedLocally())
        				node.nodeStats.failedBlockReceive(false, false, realTimeFlag, false);
        			return;
        		}
        		
        	});
        }

        @Override
		public String toString() {
        	return super.toString()+" for "+uid;
        }

		public int getPriority() {
			return NativeThread.HIGH_PRIORITY;
		}
        
    }

    private synchronized boolean receiveFailed() {
    	return receiveFailed;
    }
    
    private final Object totalSync = new Object();
    private int totalSentBytes;
    private int totalReceivedBytes;
    
	public void sentBytes(int x) {
		synchronized(totalSync) {
			totalSentBytes += x;
		}
		node.nodeStats.insertSentBytes(false, x);
	}

	public void receivedBytes(int x) {
		synchronized(totalSync) {
			totalReceivedBytes += x;
		}
		node.nodeStats.insertReceivedBytes(false, x);
	}

	public int getTotalSentBytes() {
		return totalSentBytes;
	}
	
	public int getTotalReceivedBytes() {
		return totalReceivedBytes;
	}

	public void sentPayload(int x) {
		node.sentPayload(x);
		node.nodeStats.insertSentBytes(false, -x);
	}

	public int getPriority() {
		return NativeThread.HIGH_PRIORITY;
	}
	
	private BlockReceiverTimeoutHandler myTimeoutHandler = new BlockReceiverTimeoutHandler() {

		/** We timed out waiting for a block from the request sender. We do not know 
		 * whether it is the fault of the request sender or that of some previous node.
		 * The PRB will be cancelled, resulting in all outgoing transfers for this insert
		 * being cancelled quickly. If the problem occurred on a previous node, we will
		 * receive a cancel. So we are consistent with the nodes we routed to, and it is
		 * safe to wait for the node that routed to us to send an explicit cancel. We do
		 * not need to do anything yet. */
		public void onFirstTimeout() {
			// Do nothing.
		}

		/** We timed out, and the sender did not send us a timeout message, even after we
		 * told it we were cancelling. Hence, we know that it was at fault. We need to 
		 * take action against it.
		 */
		public void onFatalTimeout(PeerContext receivingFrom) {
			Logger.error(this, "Fatal timeout receiving insert "+CHKInsertHandler.this+" from "+receivingFrom);
			((PeerNode)receivingFrom).fatalTimeout();
		}
		
	};
    
}
