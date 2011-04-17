package freenet.support.io;

import java.io.File;

import com.db4o.ObjectContainer;

import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;

/*
 *  This code is part of FProxy, an HTTP proxy server for Freenet.
 *  It is distributed under the GNU Public Licence (GPL) version 2.  See
 *  http://www.gnu.org/ for further details of the GPL.
 */
/**
 * Temporary file handling. TempFileBuckets start empty.
 *
 * @author     giannij
 */
public class TempFileBucket extends BaseFileBucket implements Bucket {
	long filenameID;
	final FilenameGenerator generator;
	private boolean readOnly;
	private final boolean deleteOnFree;

        private static volatile boolean logMINOR;
        private static volatile boolean logDEBUG;

        static {
            Logger.registerLogThresholdCallback(new LogThresholdCallback() {

                @Override
                public void shouldUpdate() {
                    logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
                    logDEBUG = Logger.shouldLog(LogLevel.DEBUG, this);
                }
            });
        }
	
	public TempFileBucket(long id, FilenameGenerator generator) {
		// deleteOnExit -> files get stuck in a big HashSet, whether or not
		// they are deleted. This grows without bound, it's a major memory
		// leak.
		this(id, generator, true);
	}
	
	/**
	 * Constructor for the TempFileBucket object
	 * Subclasses can call this constructor.
	 * @param deleteOnExit Set if you want the bucket deleted on shutdown. Passed to 
	 * the parent BaseFileBucket. You must also override deleteOnExit() and 
	 * implement your own createShadow()!
	 * @param deleteOnFree True for a normal temp bucket, false for a shadow.
	 */
	protected TempFileBucket(
		long id,
		FilenameGenerator generator, boolean deleteOnFree) {
		super(generator.getFilename(id), false);
		this.filenameID = id;
		this.generator = generator;
		this.deleteOnFree = deleteOnFree;

            if (logDEBUG) {
                Logger.debug(this,"Initializing TempFileBucket(" + getFile());
            }
	}

	@Override
	protected boolean deleteOnFinalize() {
		// Make sure finalize wacks temp file 
		// if it is not explictly freed.
		return deleteOnFree; // not if shadow
	}
	
	@Override
	protected boolean createFileOnly() {
		return false;
	}

	@Override
	protected boolean deleteOnFree() {
		return deleteOnFree;
	}

	@Override
	public File getFile() {
		return generator.getFilename(filenameID);
	}

	public boolean isReadOnly() {
		return readOnly;
	}

	public void setReadOnly() {
		readOnly = true;
	}

	@Override
	protected boolean deleteOnExit() {
		// Temp files will be cleaned up on next restart.
		// File.deleteOnExit() is a hideous memory leak.
		// It should NOT be used for temp files.
		return false;
	}

	public void storeTo(ObjectContainer container) {
		container.store(generator);
		container.store(this);
	}

	public void removeFrom(ObjectContainer container) {
		if(logMINOR)
			Logger.minor(this, "Removing from database: "+this);
		// filenameGenerator is a global, we don't need to worry about it.
		container.delete(this);
	}

	public Bucket createShadow() {
		TempFileBucket ret = new TempFileBucket(filenameID, generator, false);
		ret.setReadOnly();
		if(!getFile().exists()) Logger.error(this, "File does not exist when creating shadow: "+getFile());
		return ret;
	}
}
