/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import com.db4o.ObjectContainer;

import freenet.node.Node;
import freenet.support.SimpleFieldSet;

public class EndListPersistentRequestsMessage extends FCPMessage {

	static final String name = "EndListPersistentRequests";
	
	@Override
	public SimpleFieldSet getFieldSet() {
		return new SimpleFieldSet(true);
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "EndListPersistentRequests goes from server to client not the other way around", null, false);
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(this);
	}

}
