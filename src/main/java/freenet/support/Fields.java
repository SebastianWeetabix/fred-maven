package freenet.support;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.StringTokenizer;
import java.util.TimeZone;

import freenet.support.Logger.LogLevel;

/**
 * This class contains static methods used for parsing boolean and unsigned
 * long fields in Freenet messages. Also some general utility methods for
 * dealing with string and numeric data.
 *
 * @author oskar
 */
public abstract class Fields {

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/**
	 * All possible chars for representing a number as a String. Used to
	 * optimize numberList().
	 */
	private final static char[] digits = {
		'0',
		'1',
		'2',
		'3',
		'4',
		'5',
		'6',
		'7',
		'8',
		'9',
		'a',
		'b',
		'c',
		'd',
		'e',
		'f',
		'g',
		'h',
		'i',
		'j',
		'k',
		'l',
		'm',
		'n',
		'o',
		'p',
		'q',
		'r',
		's',
		't',
		'u',
		'v',
		'w',
		'x',
		'y',
		'z'
	};
	private static final long[] MULTIPLES = {
		1000, 1l << 10,
		1000 * 1000, 1l << 20,
		1000l * 1000l * 1000l, 1l << 30,
		1000l * 1000l * 1000l * 1000l, 1l << 40,
		1000l * 1000l * 1000l * 1000l * 1000, 1l << 50,
		1000l * 1000l * 1000l * 1000l * 1000l * 1000l, 1l << 60
	};
	private static final String[] MULTIPLES_2 = {
		"k", "K", "m", "M", "g", "G", "t", "T", "p", "P", "e", "E"
	};

	/**
	 * Converts a hex string into a long. Long.parseLong(hex, 16) assumes the
	 * input is nonnegative unless there is a preceding minus sign. This method
	 * reads the input as twos complement instead, so if the input is 8 bytes
	 * long, it will correctly restore a negative long produced by
	 * Long.toHexString() but not necessarily one produced by
	 * Long.toString(x,16) since that method will produce a string like '-FF'
	 * for negative longs values.
	 *
	 * @param hex
	 *            A string in capital or lower case hex, of no more then 16
	 *            characters.
	 * @throws NumberFormatException
	 *             if the string is more than 16 characters long, or if any
	 *             character is not in the set [0-9a-fA-f]
	 */
	public static final long hexToLong(String hex)
		throws NumberFormatException {
		int len = hex.length();
		if(len > 16)
			throw new NumberFormatException();

		long l = 0;
		for(int i = 0; i < len; i++) {
			l <<= 4;
			int c = Character.digit(hex.charAt(i), 16);
			if(c < 0)
				throw new NumberFormatException();
			l |= c;
		}
		return l;
	}

	/**
	 * Converts a hex string into an int. Integer.parseInt(hex, 16) assumes the
	 * input is nonnegative unless there is a preceding minus sign. This method
	 * reads the input as twos complement instead, so if the input is 8 bytes
	 * long, it will correctly restore a negative int produced by
	 * Integer.toHexString() but not necessarily one produced by
	 * Integer.toString(x,16) since that method will produce a string like
	 * '-FF' for negative integer values.
	 *
	 * @param hex
	 *            A string in capital or lower case hex, of no more then 16
	 *            characters.
	 * @throws NumberFormatException
	 *             if the string is more than 16 characters long, or if any
	 *             character is not in the set [0-9a-fA-f]
	 */
	public static final int hexToInt(String hex) throws NumberFormatException {
		int len = hex.length();
		if(len > 16)
			throw new NumberFormatException();

		int l = 0;
		for(int i = 0; i < len; i++) {
			l <<= 4;
			int c = Character.digit(hex.charAt(i), 16);
			if(c < 0)
				throw new NumberFormatException();
			l |= c;
		}
		return l;
	}

	/**
	 * Finds the boolean value of the field, by doing a caseless match with the
	 * strings "true" and "false".
	 *
	 * @param s
	 *            The string
	 * @param def
	 *            The default value if the string can't be parsed. If the
	 *            default is true, it checks that the string is not "false"; if
	 *            it is false, it checks whether the string is "true".
	 * @return the boolean field value or the default value if the field value
	 *         couldn't be parsed.
	 */
	/* wooo, rocket science! (this is purely abstraction people) */
	public static final boolean stringToBool(String s, boolean def) {
		if(s == null)
			return def;
		return (def ? !s.equalsIgnoreCase("false") : s.equalsIgnoreCase("true"));
	}

	/**
	 * Find the boolean value of the field. Throw if the string is neither "yes"/"true" nor "no"/"false".
	 * @param s
	 * @return
	 */
	public static boolean stringToBool(String s) throws NumberFormatException {
		if(s == null)
			throw new NumberFormatException("Null");
		if(s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no"))
			return false;
		if(s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes"))
			return true;
		throw new NumberFormatException("Invalid boolean: " + s);
	}

	/**
	 * Converts a boolean to a String of either "true" or "false".
	 *
	 * @param b
	 *            the boolean value to convert.
	 * @return A "true" or "false" String.
	 */
	public static final String boolToString(boolean b) {
		return b ? "true" : "false";
	}

	public static final String[] commaList(String ls) {
		if(ls == null)
			return null;
		StringTokenizer st = new StringTokenizer(ls, ",");
		String[] r = new String[st.countTokens()];
		for(int i = 0; i < r.length; i++) {
			r[i] = st.nextToken().trim();
		}
		return r;
	}

	public static final String commaList(String[] ls) {
		return textList(ls, ',');
	}

	public static final String textList(String[] ls, char ch) {
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < ls.length; i++) {
			sb.append(ls[i]);
			if(i != ls.length - 1)
				sb.append(ch);
		}
		return sb.toString();
	}

	public static final long[] numberList(String ls)
		throws NumberFormatException {
		StringTokenizer st = new StringTokenizer(ls, ",");
		long[] r = new long[st.countTokens()];
		for(int i = 0; i < r.length; i++) {
			r[i] = hexToLong(st.nextToken());
		}
		return r;
	}

	public static final String numberList(long[] ls) {
		char[] numberBuf = new char[64];
		StringBuilder listBuf = new StringBuilder(ls.length * 18);
		for(int i = 0; i < ls.length; i++) {

			// Convert the number into a string in a fixed size buffer.
			long l = ls[i];
			int charPos = 64;
			do {
				numberBuf[--charPos] = digits[(int) (l & 0x0F)];
				l >>>= 4;
			} while(l != 0);

			listBuf.append(numberBuf, charPos, (64 - charPos));
			if(i != ls.length - 1)
				listBuf.append(',');
		}
		return listBuf.toString();
	}

	/**
	 * Parses a time and date value, using a very strict format. The value has
	 * to be of the form YYYYMMDD-HH:MM:SS (where seconds may include a
	 * decimal) or YYYYMMDD (in which case 00:00:00 is assumed for time).
	 * Another accepted format is +/-{integer}{day|month|year|minute|second}
	 *
	 * @return millis of the epoch of at the time described.
	 */
	public static final long dateTime(String date)
		throws NumberFormatException {

		if(date.length() == 0)
			throw new NumberFormatException("Date time empty");

		if((date.charAt(0) == '-') || (date.charAt(0) == '+')) {
			// Relative date
			StringBuilder sb = new StringBuilder(10);
			for(int x = 1; x < date.length(); x++) {
				char c = date.charAt(x);
				if(Character.isDigit(c))
					sb.append(c);
				else
					break;
			}
			int num = Integer.parseInt(sb.toString());
			int chop = 1 + sb.length();
			int deltaType = 0;
			if(date.length() == chop)
				deltaType = Calendar.DAY_OF_YEAR;
			else {
				String deltaTypeString = date.substring(chop).toLowerCase();
				if(deltaTypeString.equals("y") || deltaTypeString.equals("year"))
					deltaType = Calendar.YEAR;
				else if(deltaTypeString.equals("month") || deltaTypeString.equals("mo"))
					deltaType = Calendar.MONTH;
				else if(deltaTypeString.equals("week") || deltaTypeString.equals("w"))
					deltaType = Calendar.WEEK_OF_YEAR;
				else if(deltaTypeString.equals("day") || deltaTypeString.equals("d"))
					deltaType = Calendar.DAY_OF_YEAR;
				else if(deltaTypeString.equals("hour") || deltaTypeString.equals("h"))
					deltaType = Calendar.HOUR;
				else if(deltaTypeString.equals("minute") || deltaTypeString.equals("min"))
					deltaType = Calendar.MINUTE;
				else if(deltaTypeString.equals("second") || deltaTypeString.equals("s") || deltaTypeString.equals("sec"))
					deltaType = Calendar.SECOND;
				else
					throw new NumberFormatException(
						"unknown time/date delta type: " + deltaTypeString);
				GregorianCalendar gc = new GregorianCalendar();
				gc.add(deltaType, (date.charAt(0) == '+') ? num : -num);
				return gc.getTime().getTime();
			}
		}

		int dash = date.indexOf('-');

		if(!((dash == -1) && (date.length() == 8)) && !((dash == 8) && (date.length() == 17)))
			throw new NumberFormatException(
				"Date time: " + date + " not correct.");
		int year = Integer.parseInt(date.substring(0, 4));
		int month = Integer.parseInt(date.substring(4, 6));
		int day = Integer.parseInt(date.substring(6, 8));

		int hour = dash == -1 ? 0 : Integer.parseInt(date.substring(9, 11));
		int minute = dash == -1 ? 0 : Integer.parseInt(date.substring(12, 14));
		int second = dash == -1 ? 0 : Integer.parseInt(date.substring(15, 17));

		// Note that month is zero based in GregorianCalender!
		try {
			return (new GregorianCalendar(
				year,
				month - 1,
				day,
				hour,
				minute,
				second)).getTime().getTime();
		} catch(Exception e) {
			e.printStackTrace();
			// The API docs don't say which exception is thrown on bad numbers!
			throw new NumberFormatException("Invalid date " + date + ": " + e);
		}

	}

	public static final String secToDateTime(long time) {
		//Calendar c = Calendar.getInstance();
		//c.setTime(new Date(time));
		//gc.setTimeInMillis(time*1000);

		DateFormat f = new SimpleDateFormat("yyyyMMdd-HH:mm:ss");
		f.setTimeZone(TimeZone.getTimeZone("GMT"));
		//String dateString = f.format(c.getTime());
		String dateString = f.format(new Date(time * 1000));

		if(dateString.endsWith("-00:00:00"))
			dateString = dateString.substring(0, 8);

		return dateString;
	}

	public static final int compareBytes(byte[] b1, byte[] b2) {
		int len = Math.max(b1.length, b2.length);
		for(int i = 0; i < len; ++i) {
			if(i == b1.length)
				return i == b2.length ? 0 : -1;
			else if(i == b2.length)
				return 1;
			else if((0xff & b1[i]) > (0xff & b2[i]))
				return 1;
			else if((0xff & b1[i]) < (0xff & b2[i]))
				return -1;
		}
		return 0;
	}

	public static final int compareBytes(
		byte[] a,
		byte[] b,
		int aoff,
		int boff,
		int len) {
		for(int i = 0; i < len; ++i) {
			if(i + aoff == a.length)
				return i + boff == b.length ? 0 : -1;
			else if(i + boff == b.length)
				return 1;
			else if((0xff & a[i + aoff]) > (0xff & b[i + boff]))
				return 1;
			else if((0xff & a[i + aoff]) < (0xff & b[i + boff]))
				return -1;
		}
		return 0;
	}

	public static final boolean byteArrayEqual(byte[] a, byte[] b) {
		if(a.length != b.length)
			return false;
		for(int i = 0; i < a.length; ++i)
			if(a[i] != b[i])
				return false;
		return true;
	}

	public static final boolean byteArrayEqual(
		byte[] a,
		byte[] b,
		int aoff,
		int boff,
		int len) {
		if((a.length < aoff + len) || (b.length < boff + len))
			return false;
		for(int i = 0; i < len; ++i)
			if(a[i + aoff] != b[i + boff])
				return false;
		return true;
	}

	/**
	 * Compares byte arrays lexicographically.
	 */
	public static final class ByteArrayComparator implements Comparator<byte[]> {
		public final int compare(byte[] o1, byte[] o2) {
			return compareBytes(o1, o2);
		}
	}

	// could add stuff like IntegerComparator, LongComparator etc.
	// if we need it
	public static final int hashCode(byte[] b) {
		return hashCode(b, 0, b.length);
	}

	/**
	 * A generic hashcode suited for byte arrays that are more or less random.
	 */
	public static final int hashCode(byte[] b, int ptr, int length) {
		int h = 0;
		for(int i = length - 1; i >= 0; --i) {
			int x = b[ptr + i] & 0xff;
			h ^= x << ((i & 3) << 3);
		}
		return h;
	}

	/**
	 * Long version of above Not believed to be secure in any sense of the word :)
	 */
	public static final long longHashCode(byte[] b) {
		return longHashCode(b, 0, b.length);
	}

	/**
	 * Long version of above Not believed to be secure in any sense of the word :)
	 */
	public static final long longHashCode(byte[] b, int offset, int length) {
		long h = 0;
		for(int i = length - 1; i >= 0; --i) {
			int x = b[i + offset] & 0xff;
			h ^= ((long) x) << ((i & 7) << 3);
		}
		return h;
	}

	public static String commaList(Object[] addr) {
		return commaList(addr, ',');
	}

	/**
	 * @param addr
	 * @return
	 */
	public static String commaList(Object[] addr, char comma) {
		StringBuilder sb = new StringBuilder();
		for(int i = 0; i < addr.length; i++) {
			sb.append(addr[i]);
			if(i != addr.length - 1)
				sb.append(comma);
		}
		return sb.toString();
	}

	/**
	 * Convert an array of longs to an array of bytes, using a
	 * consistent endianness.
	 */
	public static byte[] longsToBytes(long[] longs) {
		byte[] buf = new byte[longs.length * 8];
		for(int i = 0; i < longs.length; i++) {
			long x = longs[i];
			for(int j = 0; j < 8; j++) {
				buf[i * 8 + j] = (byte) x;
				x >>>= 8;
			}
		}
		return buf;
	}

	/**
	 * Convert an array of bytes to an array of longs.
	 */
	public static long[] bytesToLongs(byte[] buf) {
		return bytesToLongs(buf, 0, buf.length);
	}

	/**
	 * Convert an array of bytes to an array of longs.
	 * @param buf
	 * @param length
	 * @param offset
	 * @return
	 */
	public static long[] bytesToLongs(byte[] buf, int offset, int length) {
		if(length % 8 != 0)
			throw new IllegalArgumentException();
		long[] longs = new long[length / 8];
		for(int i = 0; i < longs.length; i++) {
			long x = 0;
			for(int j = 7; j >= 0; j--) {
				long y = (buf[offset + i * 8 + j] & 0xff);
				x = (x << 8) | y;
			}
			longs[i] = x;
		}
		return longs;
	}

	/**
	 * Convert an array of bytes to a single long.
	 */
	public static long bytesToLong(byte[] buf) {
		return bytesToLong(buf, 0);
	}

	/**
	 * Convert an array of bytes to a single long.
	 */
	public static long bytesToLong(byte[] buf, int offset) {
		if(buf.length < 8 + offset)
			throw new IllegalArgumentException();
		long x = 0;
		for(int j = 7; j >= 0; j--) {
			long y = (buf[j + offset] & 0xff);
			x = (x << 8) | y;
		}
		return x;
	}

	/**
	 * Convert an array of bytes to a single int.
	 */
	public static int bytesToInt(byte[] buf, int offset) {
		if(buf.length < 4)
			throw new IllegalArgumentException();
		int x = 0;
		for(int j = 3; j >= 0; j--) {
			int y = (buf[j + offset] & 0xff);
			x = (x << 8) | y;
		}
		return x;
	}

	/**
	 * Convert an array of bytes to a single int.
	 */
	public static short bytesToShort(byte[] buf, int offset) {
		if(buf.length < 2)
			throw new IllegalArgumentException();
		short x = 0;
		for(int j = 1; j >= 0; j--) {
			short y = (short)(buf[j + offset] & 0xff);
			x = (short)((x << 8) | y);
		}
		return x;
	}

	public static int[] bytesToInts(byte[] buf, int offset, int length) {
		if(length % 4 != 0)
			throw new IllegalArgumentException();
		int[] ints = new int[length / 4];
		for(int i = 0; i < ints.length; i++) {
			int x = 0;
			for(int j = 3; j >= 0; j--) {
				int y = (buf[j + offset + i * 4] & 0xff);
				x = (x << 8) | y;
			}
			ints[i] = x;
		}
		return ints;
	}

	public static int[] bytesToInts(byte[] buf) {
		return bytesToInts(buf, 0, buf.length);
	}

	public static byte[] longToBytes(long x) {
		byte[] buf = new byte[8];
		for(int j = 0; j < 8; j++) {
			buf[j] = (byte) x;
			x >>>= 8;
		}
		return buf;
	}

	public static byte[] intsToBytes(int[] ints) {
		byte[] buf = new byte[ints.length * 4];
		for(int i = 0; i < ints.length; i++) {
			long x = ints[i];
			for(int j = 0; j < 4; j++) {
				buf[i * 4 + j] = (byte) x;
				x >>>= 8;
			}
		}
		return buf;
	}

	public static byte[] intToBytes(int x) {
		byte[] buf = new byte[4];
			for(int j = 0; j < 4; j++) {
				buf[j] = (byte) x;
				x >>>= 8;
			}
		return buf;
	}

	public static byte[] shortToBytes(short x) {
		byte[] buf = new byte[2];
			for(int j = 0; j < 2; j++) {
				buf[j] = (byte) x;
				x >>>= 8;
			}
		return buf;
	}

	public static long parseLong(String s, long defaultValue) {
		try {
			return Long.parseLong(s);
		} catch(NumberFormatException e) {
			Logger.error(Fields.class, "Failed to parse value as long: " + s + " : " + e, e);
			return defaultValue;
		}
	}

	public static int parseInt(String s, int defaultValue) {
		try {
			return Integer.parseInt(s);
		} catch(NumberFormatException e) {
			Logger.error(Fields.class, "Failed to parse value as int: " + s + " : " + e, e);
			return defaultValue;
		}
	}

	public static long parseShort(String s, short defaultValue) {
		try {
			return Short.parseShort(s);
		} catch(NumberFormatException e) {
			Logger.error(Fields.class, "Failed to parse value as short: " + s + " : " + e, e);
			return defaultValue;
		}
	}

	/**
	 * Parse a human-readable string possibly including SI and ICE units into a short.
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	public static short parseShort(String s) throws NumberFormatException {
		s = s.replaceFirst("(i)*B$", "");
		short res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			while((x >= 0) && ((idx = "kK".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= MULTIPLES[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch(ArithmeticException e) {
			res = Short.MAX_VALUE;
			throw new NumberFormatException(e.getMessage());
		}
		return res;
	}

	/**
	 * Parse a human-readable string possibly including SI and ICE units into an integer.
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	public static int parseInt(String s) throws NumberFormatException {
		s = s.replaceFirst("(i)*B$", "");
		int res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			while((x >= 0) && ((idx = "kKmMgG".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= MULTIPLES[idx];
			}
			res *= Double.parseDouble(s.substring(0, x + 1));
		} catch(ArithmeticException e) {
			res = Integer.MAX_VALUE;
			throw new NumberFormatException(e.getMessage());
		}
		return res;
	}

	/**
	 * Parse a human-readable string possibly including SI and ICE units into a long.
	 * @throws NumberFormatException
	 *             if the string is not parseable
	 */
	public static long parseLong(String s) throws NumberFormatException {
		s = s.replaceFirst("(i)*B$", "");
		long res = 1;
		int x = s.length() - 1;
		int idx;
		try {
			while((x >= 0) && ((idx = "kKmMgGtTpPeE".indexOf(s.charAt(x))) != -1)) {
				x--;
				res *= MULTIPLES[idx];
			}
			String multiplier = s.substring(0, x + 1).trim();
			if(multiplier.indexOf('.') > -1 || multiplier.indexOf('E') > -1) {
				res *= Double.parseDouble(multiplier);
				if(logMINOR)
					Logger.minor(Fields.class, "Parsed " + multiplier + " of " + s + " as double: " + res);
			} else {
				res *= Long.parseLong(multiplier);
				if(logMINOR)
					Logger.minor(Fields.class, "Parsed " + multiplier + " of " + s + " as long: " + res);
			}
		} catch(ArithmeticException e) {
			res = Long.MAX_VALUE;
			throw new NumberFormatException(e.getMessage());
		}
		return res;
	}

	public static String longToString(long val, boolean isSize) {
		String ret = Long.toString(val);

		if(val <= 0)
			return ret;

		for(int i = MULTIPLES.length - 1; i >= 0; i--) {
			if(val > MULTIPLES[i] && val % MULTIPLES[i] == 0 && (isSize || MULTIPLES[i] % 1000 == 0)) {
				ret = (val / MULTIPLES[i]) + MULTIPLES_2[i];
				if(!MULTIPLES_2[i].toLowerCase().equals(MULTIPLES_2[i]))
					ret += "iB";
				break;
			}
		}
		return ret;
	}

	public static String intToString(int val, boolean isSize) {
		String ret = Integer.toString(val);

		if(val <= 0)
			return ret;

		for(int i = MULTIPLES.length - 1; i >= 0; i--) {
			if(val > MULTIPLES[i] && val % MULTIPLES[i] == 0 && (isSize || MULTIPLES[i] % 1000 == 0)) {
				ret = (val / MULTIPLES[i]) + MULTIPLES_2[i];
				if(!MULTIPLES_2[i].toLowerCase().equals(MULTIPLES_2[i]))
					ret += "iB";
				break;
			}
		}
		return ret;
	}

	public static String shortToString(short val, boolean isSize) {
		String ret = Short.toString(val);

		if(val <= 0)
			return ret;

		for(int i = MULTIPLES.length - 1; i >= 0; i--) {
			if(val > MULTIPLES[i] && val % MULTIPLES[i] == 0 && (isSize || MULTIPLES[i] % 1000 == 0)) {
				ret = (val / MULTIPLES[i]) + MULTIPLES_2[i];
				if(!MULTIPLES_2[i].toLowerCase().equals(MULTIPLES_2[i]))
					ret += "iB";
				break;
			}
		}
		return ret;
	}

	public static double[] bytesToDoubles(byte[] data, int offset, int length) {
		long[] longs = bytesToLongs(data, offset, length);
		double[] doubles = new double[longs.length];
		for(int i = 0; i < longs.length; i++)
			doubles[i] = Double.longBitsToDouble(longs[i]);
		return doubles;
	}

	public static byte[] doublesToBytes(double[] doubles) {
		long[] longs = new long[doubles.length];
		for(int i = 0; i < longs.length; i++)
			longs[i] = Double.doubleToLongBits(doubles[i]);
		return longsToBytes(longs);
	}

	public static double[] bytesToDoubles(byte[] data) {
		return bytesToDoubles(data, 0, data.length);
	}

	/**
	 * Assumes the array is sorted in ascending order, [begin] is lowest and [end] is highest.
	 */
	public static int binarySearch(long[] values, long key, int origBegin, int origEnd) {
		int begin = origBegin;
		int end = origEnd;
		while(true) {
			if(end < begin)	// so we can use origEnd=length-1 without worrying length=0
				return -begin - 1;

			int middle = (begin + end) >>> 1;
			if(values[middle] == key)
				return middle;

			if(values[middle] > key)
				end = middle - 1;
			else if(values[middle] < key)
				begin = middle + 1;
		}
	}

	/**
	 * Assumes the array is sorted in ascending order, [begin] is lowest and [end] is highest.
	 */
	public static int binarySearch(int[] values, int key, int origBegin, int origEnd) {
		int begin = origBegin;
		int end = origEnd;
		while(true) {
			if(end < begin)	// so we can use origEnd=length-1 without worrying length=0
				return -begin - 1;

			int middle = (begin + end) >>> 1;
			if(values[middle] == key)
				return middle;

			if(values[middle] > key)
				end = middle - 1;
			else if(values[middle] < key)
				begin = middle + 1;
		}
	}

	/**
	** Search a range of the given array using binary search. We use this
	** because the corresponding method in java.util.Arrays is only available
	** in JDK6 or later.
	**
	** Note that this implementation behaves exactly the same way as the one
	** from Arrays, as opposed to {@link binarySearch(long[], long, int, int)}.
	** In particular, the right endpoint here is <b>exclusive</b>.
	**
	** TODO JDK6: make this @deprecated when we move to JDK6.
	**
	** @throws ClassCastException if the comparator is {@code null} and the
	**         array contains elements that are not {@link Comparable}, or the
	**         comparator cannot handle any elements of the array.
	** @throws IllegalArgumentException if {@code li} > {@code ri}
	** @throws ArrayIndexOutOfBoundsException if {@code li} < 0 or {@code ri} >
	**         {@code arr.length}.
	*/
	public static <T> int binarySearch(T[] arr, int li, int ri, T key, Comparator<? super T> cmp) {
		int l = li, r = ri, m = 0;

		if (li > ri) {
			throw new IllegalArgumentException("L-index must not be greater than R-index");
		}
		if (li < 0 || ri > arr.length) {
			throw new ArrayIndexOutOfBoundsException();
		}

		if (cmp == null) {
			// natural ordering
			while (l<=r) {
				m = (l+r)>>>1;
				@SuppressWarnings("unchecked") int c = ((Comparable<T>)arr[m]).compareTo(key);

				if (c == 0) {
					return m;
				} else if (c > 0) {
					r = m-1;
				} else {
					l = ++m; // gets the insertion point right on the last loop
				}
			}
			return ~m;

		} else {
			// comparator
			while (l<=r) {
				m = (l+r)>>>1;
				int c = cmp.compare(arr[m], key);

				if (c == 0) {
					return m;
				} else if (c > 0) {
					r = m-1;
				} else {
					l = ++m; // gets the insertion point right on the last loop
				}
			}
			return ~m;

		}

	}

	/**
	 * Remove empty lines and trim head/trailing space
	 *
	 * @param str string to be trimmed
	 * @return result string
	 */
	public static String trimLines(String str) {
		StringBuilder r = new StringBuilder(str.length());
		for (String line : str.split("\n")) {
			line = line.trim();
			if (line.length() == 0) continue;

			r.append(line);
			r.append('\n');
		}
		return r.toString();
	}
}
