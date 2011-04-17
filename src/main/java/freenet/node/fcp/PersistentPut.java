/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.client.InsertContext;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class PersistentPut extends FCPMessage {

	static final String name = "PersistentPut";
	
	final String identifier;
	final FreenetURI uri;
	final int verbosity; 
	final short priorityClass;
	final short uploadFrom;
	final short persistenceType; 
	final File origFilename;
	final String mimeType;
	final boolean global;
	final FreenetURI targetURI;
	final long size;
	final String token;
	final boolean started;
	final int maxRetries;
	final String targetFilename;
	final boolean binaryBlob;
	final InsertContext.CompatibilityMode compatMode;
	
	public PersistentPut(String identifier, FreenetURI uri, int verbosity, 
			short priorityClass, short uploadFrom, FreenetURI targetURI, 
			short persistenceType, File origFilename, String mimeType, 
			boolean global, long size, String clientToken, boolean started, 
			int maxRetries, String targetFilename, boolean binaryBlob, InsertContext.CompatibilityMode compatMode) {
		this.identifier = identifier;
		this.uri = uri;
		this.verbosity = verbosity;
		this.priorityClass = priorityClass;
		this.uploadFrom = uploadFrom;
		this.targetURI = targetURI;
		this.persistenceType = persistenceType;
		this.origFilename = origFilename;
		this.mimeType = mimeType;
		this.global = global;
		this.size = size;
		this.token = clientToken;
		this.started = started;
		this.maxRetries = maxRetries;
		this.targetFilename = targetFilename;
		this.binaryBlob = binaryBlob;
		this.compatMode = compatMode;
	}

	@Override
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		fs.putSingle("Identifier", identifier);
		fs.putSingle("URI", uri.toString(false, false));
		fs.put("Verbosity", verbosity);
		fs.put("PriorityClass", priorityClass);
		fs.putSingle("UploadFrom", ClientPutMessage.uploadFromString(uploadFrom));
		fs.putSingle("Persistence", ClientRequest.persistenceTypeString(persistenceType));
		if(origFilename != null)
			fs.putSingle("Filename", origFilename.getAbsolutePath());
		if(targetURI != null)
			fs.putSingle("TargetURI", targetURI.toString());
		if(mimeType != null)
			fs.putSingle("Metadata.ContentType", mimeType);
		fs.put("Global", global);
		if(size != -1)
			fs.put("DataLength", size);
		if(token != null)
			fs.putSingle("ClientToken", token);
		fs.put("Started", started);
		fs.put("MaxRetries", maxRetries);
		if(targetFilename != null)
			fs.putSingle("TargetFilename", targetFilename);
		if(binaryBlob)
			fs.put("BinaryBlob", binaryBlob);
		fs.putOverwrite("CompatibilityMode", compatMode.name());
		return fs;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "PersistentPut goes from server to client not the other way around", identifier, global);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(uri, 5);
		uri.removeFrom(container);
		container.activate(origFilename, 5);
		container.delete(origFilename);
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		container.delete(this);
	}

}
