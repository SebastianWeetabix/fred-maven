/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.pluginmanager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Iterator;

import freenet.pluginmanager.PluginManager.PluginProgress;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

public class PluginDownLoaderOfficialHTTPS extends PluginDownLoaderURL {
	
	private static final String certurl = "freenet/clients/http/staticfiles/startssl.pem";
	private static final String certfile = "startssl.pem";

	@Override
	public URL checkSource(String source) throws PluginNotFoundException {
		return super.checkSource("https://checksums.freenetproject.org/latest/" +
		source + ".jar");
	}

	@Override
	String getPluginName(String source) throws PluginNotFoundException {
		return source + ".jar";
	}

	@Override
	String getSHA1sum() throws PluginNotFoundException {
		try {
			URL sha1url = new URL(getSource().toString()+".sha1");
			URLConnection urlConnection = sha1url.openConnection();
			urlConnection.setUseCaches(false);
			urlConnection.setAllowUserInteraction(false);
			
			InputStream is = openConnectionCheckRedirects(urlConnection);
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
		
			byte[] buffer = new byte[1024];
			int read;
		
			while ((read = is.read(buffer)) != -1) {
				bos.write(buffer, 0, read);
			}
			
			return new String(bos.toByteArray()).split(" ")[0];
	
		} catch (MalformedURLException e) {
			throw new PluginNotFoundException("impossible: "+e,e);
		} catch (IOException e) {
			throw new PluginNotFoundException("Error while fetching sha1 for plugin: "+e,e);
		}
	}

	@Override
	InputStream getInputStream(PluginProgress progress) throws IOException {
		File TMP_KEYSTORE = null;
		FileInputStream fis = null;
		InputStream is = null;
		try {
			TMP_KEYSTORE = File.createTempFile("keystore", ".tmp");
			TMP_KEYSTORE.deleteOnExit();
			
			KeyStore ks = KeyStore.getInstance("JKS");
			ks.load(null, new char[0]);

			is = getCert();

			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			Collection<? extends Certificate> c = cf.generateCertificates(is);
			Iterator<? extends Certificate> it = c.iterator();
			while(it.hasNext()) {
				Certificate cert = it.next();
				ks.setCertificateEntry(cert.getPublicKey().toString(), cert);
			}
			FileOutputStream tmpFOS = new FileOutputStream(TMP_KEYSTORE);
			try {
				ks.store(tmpFOS, new char[0]);
			} finally {
				Closer.close(tmpFOS);
			}
			System.out.println("The CA has been imported into the trustStore");
		} catch(Exception e) {
			System.err.println("Error while handling the CA :" + e.getMessage());
			throw new IOException("Error while handling the CA : "+e);
		} finally {
			Closer.close(fis);
		}

		System.setProperty("javax.net.ssl.trustStore", TMP_KEYSTORE.toString());
		
		return super.getInputStream(progress);
	}

	private InputStream getCert() throws IOException {
		
		// normal the file should be here,
		// left by installer or update script
		File certFile = new File(certfile).getAbsoluteFile();
		
		if (certFile.exists()) {
			return new FileInputStream(certFile);
		}
		
		// try to create pem file
		ClassLoader loader = ClassLoader.getSystemClassLoader();
		InputStream in = loader.getResourceAsStream(certurl);
		if(in != null) {
			FileUtil.writeTo(in, certFile);
			if (certFile.exists()) {
				System.err.println("Nodes certfile created, use it");
				return new FileInputStream(certFile);
			}
			System.err.println("Nodes certfile couldnt created, try direct");
			// couldnt write the file, maybe paranoid selinux or hardware node ;)
			return in;
		}	
		
		System.err.println("Certficate file '"+certfile+"' not found on disk nor buildin.");
		throw new IOException("Certficate file '"+certfile+"' not found on disk nor buildin.");
	}

}
