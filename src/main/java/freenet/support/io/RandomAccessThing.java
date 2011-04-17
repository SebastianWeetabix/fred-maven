/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.support.io;

import java.io.Closeable;
import java.io.IOException;

/**
 * Trivial random access file base interface.
 * @author toad
 */
public interface RandomAccessThing extends Closeable {

	public long size() throws IOException;
	
	public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException;
	
	public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException;

	public void close();
	
}
