package freenet.node.simulator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import freenet.client.ClientMetadata;
import freenet.client.FetchException;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.crypt.RandomSource;
import freenet.keys.FreenetURI;
import freenet.node.Node;
import freenet.node.NodeStarter;
import freenet.node.Version;
import freenet.support.Fields;
import freenet.support.Logger;
import freenet.support.PooledExecutor;
import freenet.support.Logger.LogLevel;
import freenet.support.api.Bucket;
import freenet.support.io.FileUtil;

/**
 * Push / Pull test over long period of time
 *
 * Pushes N CHK's and records both their insert time and their CHKs.
 * 
 * Pulls CHK's for (2^n)-1 days ago, from 0 to 8, but obviously only if
 * there is a CHK for the given date in the log.
 */
public class LongTermPushPullCHKTest {
	private static final int TEST_SIZE = 64 * 1024;

	private static final int EXIT_NO_SEEDNODES = 257;
	private static final int EXIT_FAILED_TARGET = 258;
	private static final int EXIT_THREW_SOMETHING = 261;

	private static final int DARKNET_PORT1 = 5010;
	private static final int OPENNET_PORT1 = 5011;
	private static final int DARKNET_PORT2 = 5012;
	private static final int OPENNET_PORT2 = 5013;

	private static final int MAX_N = 8;

	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy.MM.dd", Locale.US);
	static {
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	private static final Calendar today = Calendar.getInstance(TimeZone.getTimeZone("GMT"));

	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Usage: java freenet.node.simulator.LongTermPushPullTest <unique identifier>");
			System.exit(1);
		}
		String uid = args[0];

		List<String> csvLine = new ArrayList<String>(3 + 2 * MAX_N);
		System.out.println("DATE:" + dateFormat.format(today.getTime()));
		csvLine.add(dateFormat.format(today.getTime()));

		System.out.println("Version:" + Version.buildNumber());
		csvLine.add(String.valueOf(Version.buildNumber()));

		int exitCode = 0;
		Node node = null;
		Node node2 = null;
		try {
			final File dir = new File("longterm-push-pull-test-" + uid);
			FileUtil.removeAll(dir);
			RandomSource random = NodeStarter.globalTestInit(dir.getPath(), false, LogLevel.ERROR, "", false);
			File seednodes = new File("seednodes.fref");
			if (!seednodes.exists() || seednodes.length() == 0 || !seednodes.canRead()) {
				System.err.println("Unable to read seednodes.fref, it doesn't exist, or is empty");
				System.exit(EXIT_NO_SEEDNODES);
			}

			final File innerDir = new File(dir, Integer.toString(DARKNET_PORT1));
			innerDir.mkdir();
			FileInputStream fis = new FileInputStream(seednodes);
			FileUtil.writeTo(fis, new File(innerDir, "seednodes.fref"));
			fis.close();

			// Create one node
			node = NodeStarter.createTestNode(DARKNET_PORT1, OPENNET_PORT1, dir.getPath(), false, Node.DEFAULT_MAX_HTL,
			        0, random, new PooledExecutor(), 1000, 4 * 1024 * 1024, true, true, true, true, true, true, true,
			        12 * 1024, true, true, false, false, null);
			Logger.getChain().setThreshold(LogLevel.ERROR);

			// Start it
			node.start(true);
			long t1 = System.currentTimeMillis();
			if (!TestUtil.waitForNodes(node)) {
				exitCode = EXIT_FAILED_TARGET;
				return;
			}
				
			long t2 = System.currentTimeMillis();
			System.out.println("SEED-TIME:" + (t2 - t1));
			csvLine.add(String.valueOf(t2 - t1));
			
			FreenetURI todaysInsert = null;

			// PUSH N+1 BLOCKS
			for (int i = 0; i <= MAX_N; i++) {
				Bucket data = randomData(node);
				HighLevelSimpleClient client = node.clientCore.makeClient((short) 0);
				System.out.println("PUSHING " + i);

				try {
					InsertBlock block = new InsertBlock(data, new ClientMetadata(), FreenetURI.EMPTY_CHK_URI);
					t1 = System.currentTimeMillis();
					FreenetURI uri = client.insert(block, false, null);
					if(i == 0) todaysInsert = uri;
					t2 = System.currentTimeMillis();

					System.out.println("PUSH-TIME-" + i + ":" + (t2 - t1)+" for "+uri);
					csvLine.add(String.valueOf(t2 - t1));
					csvLine.add(uri.toASCIIString());
				} catch (InsertException e) {
					e.printStackTrace();
					csvLine.add("N/A");
					csvLine.add("N/A");
				}

				data.free();
			}

			node.park();

			// Node 2
			File innerDir2 = new File(dir, Integer.toString(DARKNET_PORT2));
			innerDir2.mkdir();
			fis = new FileInputStream(seednodes);
			FileUtil.writeTo(fis, new File(innerDir2, "seednodes.fref"));
			fis.close();
			node2 = NodeStarter.createTestNode(DARKNET_PORT2, OPENNET_PORT2, dir.getPath(), false,
			        Node.DEFAULT_MAX_HTL, 0, random, new PooledExecutor(), 1000, 5 * 1024 * 1024, true, true, true,
			        true, true, true, true, 12 * 1024, false, true, false, false, null);
			node2.start(true);

			t1 = System.currentTimeMillis();
			if (!TestUtil.waitForNodes(node2)) {
				exitCode = EXIT_FAILED_TARGET;
				return;
			}
			t2 = System.currentTimeMillis();
			System.out.println("SEED-TIME:" + (t2 - t1));
			csvLine.add(String.valueOf(t2 - t1));

			// PULL N+1 BLOCKS
			for (int i = 0; i <= MAX_N; i++) {
				HighLevelSimpleClient client = node2.clientCore.makeClient((short) 0);
				Calendar targetDate = (Calendar) today.clone();
				targetDate.add(Calendar.DAY_OF_MONTH, -((1 << i) - 1));

				FreenetURI uri = null;
				
				if(i == 0) uri = todaysInsert;
				else {
					uri = getHistoricURI(uid, i, targetDate);
				}
				
				if(uri == null) {
					System.out.println("SKIPPING PULL FOR "+i);
					continue;
				}
				
				System.out.println("PULLING " + uri);

				try {
					t1 = System.currentTimeMillis();
					client.fetch(uri);
					t2 = System.currentTimeMillis();

					System.out.println("PULL-TIME-" + i + ":" + (t2 - t1));
					csvLine.add(String.valueOf(t2 - t1));
				} catch (FetchException e) {
					if (e.getMode() != FetchException.ALL_DATA_NOT_FOUND
					        && e.getMode() != FetchException.DATA_NOT_FOUND)
						e.printStackTrace();
					csvLine.add(FetchException.getShortMessage(e.getMode()));
				}
			}
		} catch (Throwable t) {
			t.printStackTrace();
			exitCode = EXIT_THREW_SOMETHING;
		} finally {
			try {
				if (node != null)
					node.park();
			} catch (Throwable t1) {
			}
			try {
				if (node2 != null)
					node2.park();
			} catch (Throwable t1) {
			}

			try {
				File file = new File(uid + ".csv");
				FileOutputStream fos = new FileOutputStream(file, true);
				PrintStream ps = new PrintStream(fos);

				ps.println(Fields.commaList(csvLine.toArray(),'!'));

				ps.close();
				fos.close();
			} catch (IOException e) {
				e.printStackTrace();
				exitCode = EXIT_THREW_SOMETHING;
			}
			
			System.exit(exitCode);
		}
	}

	private static FreenetURI getHistoricURI(String uid, int i, Calendar targetDate) throws IOException {
		// Quick and dirty, since we only have 1...8 it's not worth caching it.
		File file = new File(uid + ".csv");
		FileInputStream fis = new FileInputStream(file);
		try {
			InputStreamReader isr = new InputStreamReader(fis);
			BufferedReader br = new BufferedReader(isr);
			String line = null;
			String dateString = dateFormat.format(targetDate.getTime());
			while((line = br.readLine()) != null) {
				String[] split = line.split("!");
				if(split.length == 0) continue;
				if(!dateString.equals(split[0])) continue;
				int fieldnum = 3 + i * 2;
				if(line.length() >= fieldnum) continue; // Possible ran twice???
				return new FreenetURI(split[fieldnum]);
			}
			return null;
		} finally {
			fis.close();
		}
	}

	private static Bucket randomData(Node node) throws IOException {
		Bucket data = node.clientCore.tempBucketFactory.makeBucket(TEST_SIZE);
		OutputStream os = data.getOutputStream();
		byte[] buf = new byte[4096];
		for (long written = 0; written < TEST_SIZE;) {
			node.fastWeakRandom.nextBytes(buf);
			int toWrite = (int) Math.min(TEST_SIZE - written, buf.length);
			os.write(buf, 0, toWrite);
			written += toWrite;
		}
		os.close();
		return data;
	}
}
