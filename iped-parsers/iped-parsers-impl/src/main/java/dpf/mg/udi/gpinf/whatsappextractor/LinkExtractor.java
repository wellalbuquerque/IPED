package dpf.mg.udi.gpinf.whatsappextractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

import com.whatsapp.MediaData;

/**
 *
 * @author PCF HAUCK
 */
public class LinkExtractor implements Closeable {

    private static final Logger logger = Logger.getLogger(LinkExtractor.class.getName());

    private File dbFile;
    private Connection con;
    private HashSet<String> hashes;
    private ArrayList<LinkDownloader> links;
    private int connTimeout;
    private int readTimeout;

    public LinkExtractor(File dbFile, HashSet<String> hashes, int connTimeout, int readTimeout) {
        this.dbFile = dbFile;
        this.hashes = hashes;
        this.con = createConnection(dbFile.getAbsolutePath());
        this.connTimeout = connTimeout;
        this.readTimeout = readTimeout;
        links = new ArrayList<>();
    }

    public Connection createConnection(String dbname) {
        try {
            return DriverManager.getConnection("jdbc:sqlite:" + dbname);
        } catch (Exception ex) {
            String msg = "Error getting connection when processing " + dbFile.getAbsolutePath();
            logger.log(Level.FINE, msg, ex);
        }

        return null;
    }

    public void getKeyFromMediaKey(byte[] mediaKey) {

    }

    public static int tot = 0;

    private class HKDF {
        private HMac hMacHash = new HMac(new SHA256Digest());

        public byte[] extract(byte[] salt, byte[] inputKeyMaterial) {
            hMacHash.init(new KeyParameter(salt));
            byte[] output = new byte[32];

            hMacHash.update(inputKeyMaterial, 0, inputKeyMaterial.length);
            hMacHash.doFinal(output, 0);

            return output;
        }

        public byte[] expand(byte[] prk, byte[] info, int outputSize) {
            int iterations = (int) Math.ceil((double) outputSize / (double) 32);
            byte[] mixin = new byte[0];
            ByteArrayOutputStream results = new ByteArrayOutputStream();
            int remainingBytes = outputSize;

            for (int i = 1; i <= iterations; i++) {

                hMacHash.init(new KeyParameter(prk));

                byte[] stepResult = new byte[32];

                hMacHash.update(mixin, 0, mixin.length);

                hMacHash.update(info, 0, info.length);

                hMacHash.update((byte) i);

                hMacHash.doFinal(stepResult, 0);

                int stepSize = Math.min(remainingBytes, stepResult.length);

                results.write(stepResult, 0, stepSize);

                mixin = stepResult;
                remainingBytes -= stepSize;
            }

            return results.toByteArray();
        }
    }

    public byte[] getCipherKey(byte[] rawData) {

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(rawData);
            ObjectInput in = new ObjectInputStream(bis);
            MediaData media = (MediaData) in.readObject();

            if (media.cipherKey != null)
                return media.cipherKey;
            else if (media.mediaKey != null) {
                HKDF hkg = new HKDF();

                byte[] key = hkg.expand(hkg.extract(new byte[32], media.mediaKey), mediaType.getBytes("UTF-8"), 112);

                byte[] cpk = Arrays.copyOfRange(key, 16, 48);

                return cpk;
            }
        } catch (Exception ex) {
            String msg = "Error getting cipher key when processing " + dbFile.getAbsolutePath();
            logger.log(Level.FINE, msg, ex);
        }
        return null;

    }

    public byte[] getIV(byte[] rawData) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(rawData);
            ObjectInput in = new ObjectInputStream(bis);
            MediaData media = (MediaData) in.readObject();
            if (media.iv != null) {
                return media.iv;
            } else if (media.mediaKey != null) {
                HKDF hkg = new HKDF();

                byte[] key = hkg.expand(hkg.extract(new byte[32], media.mediaKey), mediaType.getBytes("UTF-8"), 112);

                byte[] iv = Arrays.copyOfRange(key, 0, 16);

                return iv;
            }
        } catch (Exception ex) {
            String msg = "Error getting IV when processing " + dbFile.getAbsolutePath();
            logger.log(Level.FINE, msg, ex);
        }
        return null;
    }

    private String mediaType = "";

    public static String capitalize(String aux) {
        if (aux != null && !aux.isEmpty()) {
            aux = aux.substring(0, 1).toUpperCase() + aux.substring(1);

        }
        return aux;
    }

    public void extractLinks() throws SQLException, DecoderException {

        if (con == null) {
            return;
        }

        StringBuilder base64Hashes = null;
        for (String hash : hashes) {
            hash = Base64.getEncoder().encodeToString(Hex.decodeHex(hash));
            if (base64Hashes == null) {
                base64Hashes = new StringBuilder();
            } else {
                base64Hashes.append(",");
            }
            base64Hashes.append('"').append(hash).append('"');
        }
        try (PreparedStatement stmt = con.prepareStatement(sql_android.replaceAll("\\?", base64Hashes.toString()))) {
            // stmt.setCharacterStream(1, new StringReader(base64Hashes.toString()));
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                String link = rs.getString("url");
                String hash = rs.getString("hash");

                String decoded = new String(Hex.encodeHex(Base64.getDecoder().decode(hash), false));
                if (!hashes.contains(decoded)) {
                    continue;
                }
                String tipo = rs.getString("tipo");
                if (tipo == null) {
                    continue;
                }
                mediaType = tipo;
                if (tipo.indexOf("/") >= 0) {
                    mediaType = tipo.substring(0, tipo.indexOf("/"));
                }
                mediaType = capitalize(mediaType).trim();
                mediaType = "WhatsApp " + mediaType + " Keys";

                tipo = tipo.substring(tipo.indexOf("/") + 1);

                byte[] rawData = rs.getBytes("data");
                byte[] cipherkey = getCipherKey(rawData);
                byte[] iv = getIV(rawData);
                if (cipherkey == null || iv == null) {
                    tot++;
                }

                LinkDownloader ld = new LinkDownloader(link, hash, connTimeout, readTimeout, cipherkey, iv);
                if (ld.getHash() != null && ld.getFileName() != null) {
                    links.add(ld);
                }
            }
        }

    }

    @Override
    public void close() throws IOException {
        try {
            if (con != null && !con.isClosed()) {
                this.con.close();
            }
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }

    public ArrayList<LinkDownloader> getLinks() {
        return links;
    }

    public static final String sql_android = "SELECT media_url as url,media_hash as hash ,media_mime_type as tipo,thumb_image as data,_id from messages "
            + "where media_url like '%whatsapp%.enc' and media_hash is not null and media_hash in (?) group by url";

}
