package fi.csc.chipster.tools.parsers;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;


/**
 * Simple data source for small non-binary files. These small files are stored in memory, so  only
 * simple initial line-by-line read through is needed.
 * 
 * @author Petri Klemela
 *
 */
public class LineDataSource extends DataSource {
	
	public LineDataSource(DataUrl indexUrl) throws URISyntaxException, IOException {
		super(indexUrl);
	}

	BufferedReader reader;
	
	public String readLine() throws IOException {
		if (file != null) {

			if (reader == null) {
				reader = new BufferedReader(new FileReader(file));
			}


		} else {

			if (reader == null) {

				HttpURLConnection connection = (HttpURLConnection)url.openConnection();
//				KeyAndTrustManager.configureForChipsterCertificate(connection);
				reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			}
		}

		String result = reader.readLine();

		if (result == null) {
			reader.close();
			reader = null;
		}

		return result;
	}
}
