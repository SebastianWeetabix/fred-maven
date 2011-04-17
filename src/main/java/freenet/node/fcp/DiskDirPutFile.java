/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.client.DefaultMIMETypes;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;
import freenet.support.io.FileBucket;

public class DiskDirPutFile extends DirPutFile {

	final File file;
	
	// FIXME implement FileHash support
	public DiskDirPutFile(SimpleFieldSet subset, String identifier, boolean global) throws MessageInvalidException {
		super(subset, identifier, global);
		String s = subset.get("Filename");
		if(s == null)
			throw new MessageInvalidException(ProtocolErrorMessage.MISSING_FIELD, "Missing field: Filename on "+name, identifier, global);
		file = new File(s);
	}

	@Override
	protected String guessMIME() {
		String mime = super.guessMIME();
		if(mime == null) {
			mime = DefaultMIMETypes.guessMIMEType(file.getName(), false /* fixme? */);
		}
		return mime;
	}

	@Override
	public Bucket getData() {
		return new FileBucket(file, true, false, false, false, false);
	}

	public File getFile() {
		return file;
	}

	@Override
	public void removeFrom(ObjectContainer container) {
		container.delete(file);
		container.delete(this);
	}

}
