/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.Fields;
import freenet.support.SimpleFieldSet;

public class ListPeersMessage extends FCPMessage {

	final boolean withMetadata;
	final boolean withVolatile;
	final String identifier;
	static final String NAME = "ListPeers";
	
	public ListPeersMessage(SimpleFieldSet fs) {
		withMetadata = Fields.stringToBool(fs.get("WithMetadata"), false);
		withVolatile = Fields.stringToBool(fs.get("WithVolatile"), false);
		this.identifier = fs.get("Identifier");
		fs.removeValue("Identifier");
	}
	
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}
	
	@Override
	public String getName() {
		return NAME;
	}
	
	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		if(!handler.hasFullAccess()) {
			throw new MessageInvalidException(ProtocolErrorMessage.ACCESS_DENIED, "ListPeers requires full access", identifier, false);
		}
		PeerNode[] nodes = node.getPeerNodes();
		for(int i = 0; i < nodes.length; i++) {
			PeerNode pn = nodes[i];
			handler.outputHandler.queue(new PeerMessage(pn, withMetadata, withVolatile, identifier));
		}
		
		handler.outputHandler.queue(new EndListPeersMessage(identifier));
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}
	
}
