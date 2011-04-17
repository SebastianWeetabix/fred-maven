package freenet.node.fcp;

import java.net.MalformedURLException;

import com.db4o.ObjectContainer;

import freenet.client.async.ManifestElement;
import freenet.keys.FreenetURI;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

public class RedirectDirPutFile extends DirPutFile {

	final FreenetURI targetURI;

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}
	
	public RedirectDirPutFile(SimpleFieldSet subset, String identifier, boolean global) throws MessageInvalidException {
		super(subset, identifier, global);
		String target = subset.get("TargetURI");
		if(target == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "TargetURI missing but UploadFrom=redirect", identifier, global);
		try {
			targetURI = new FreenetURI(target);
		} catch (MalformedURLException e) {
			throw new MessageInvalidException(ProtocolErrorMessage.INVALID_FIELD, "Invalid TargetURI: "+e, identifier, global);
		}
        if(logMINOR)
        	Logger.minor(this, "targetURI = "+targetURI);
	}

	@Override
	public Bucket getData() {
		return null;
	}

	@Override
	public ManifestElement getElement() {
		return new ManifestElement(name, targetURI, getMIMEType());
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.activate(targetURI, 5);
		targetURI.removeFrom(container);
		container.delete(this);
	}
}
