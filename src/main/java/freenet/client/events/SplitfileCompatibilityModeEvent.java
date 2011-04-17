package freenet.client.events;

import freenet.client.InsertContext.CompatibilityMode;

public class SplitfileCompatibilityModeEvent implements ClientEvent {

	public final long minCompatibilityMode;
	public final long maxCompatibilityMode;
	public final byte[] splitfileCryptoKey;
	public final boolean dontCompress;
	public final boolean bottomLayer;
	
	public final static int CODE = 0x0D;
	
	public int getCode() {
		return CODE;
	}

	public String getDescription() {
		if(minCompatibilityMode == -1)
			return "Unknown CompatibilityMode";
		else
			return "CompatibilityMode between "+minCompatibilityMode+" and "+maxCompatibilityMode;
	}
	
	public SplitfileCompatibilityModeEvent(CompatibilityMode min, CompatibilityMode max, byte[] splitfileCryptoKey, boolean dontCompress, boolean bottomLayer) {
		this.minCompatibilityMode = min.ordinal();
		this.maxCompatibilityMode = max.ordinal();
		this.splitfileCryptoKey = splitfileCryptoKey;
		this.dontCompress = dontCompress;
		this.bottomLayer = bottomLayer;
	}
	
}
