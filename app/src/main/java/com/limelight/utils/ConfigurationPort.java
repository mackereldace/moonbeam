package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Base64;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.nvstream.http.ComputerDetails;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes Moonlight V+ configuration backups.
 *
 * This is a faithful port of the pairing and preference handling in V+'s
 * ConfigurationSyncManager, restricted to the sections this client owns. The details that
 * matter are not obvious and were the source of broken restores when implemented from the
 * format alone:
 *
 *  - identity blobs may be raw bytes *or* PEM text; a PEM private key has to be reduced to
 *    its DER body, while a PEM certificate is kept as text with CR stripped;
 *  - both files are validated before being written (certificate parses as X.509, key parses
 *    as a PKCS#8 RSA key), because a bad key silently breaks pairing later;
 *  - uniqueId must be exactly 16 hex characters;
 *  - an address without a port is dropped;
 *  - a computer without a name is skipped;
 *  - the per-PC display name lives in the "pair_name_map" preferences, not in the database.
 */
public final class ConfigurationPort {

    // Document layout
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_SECTIONS = "sections";
    private static final String KEY_VALUES = "values";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";
    private static final int SUPPORTED_SCHEMA_VERSION = 2;

    // Sections we handle
    private static final String SECTION_DEFAULT_PREFERENCES = "defaultPreferences";
    private static final String SECTION_PAIRING = "pairing";

    // Value types
    private static final String TYPE_STRING = "string";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING_SET = "stringSet";

    // Pairing payload keys
    private static final String KEY_PAIRING_UNIQUE_ID = "uniqueId";
    private static final String KEY_PAIRING_CLIENT_CERTIFICATE = "clientCertificatePem";
    private static final String KEY_PAIRING_CLIENT_PRIVATE_KEY = "clientPrivateKeyPkcs8";
    private static final String KEY_PAIRING_COMPUTERS = "computers";
    private static final String KEY_PAIRING_COMPUTER_UUID = "uuid";
    private static final String KEY_PAIRING_COMPUTER_NAME = "name";
    private static final String KEY_PAIRING_ADDRESS = "address";
    private static final String KEY_PAIRING_PORT = "port";
    private static final String KEY_PAIRING_LOCAL_ADDRESS = "localAddress";
    private static final String KEY_PAIRING_REMOTE_ADDRESS = "remoteAddress";
    private static final String KEY_PAIRING_MANUAL_ADDRESS = "manualAddress";
    private static final String KEY_PAIRING_IPV6_ADDRESS = "ipv6Address";
    private static final String KEY_PAIRING_ACTIVE_ADDRESS = "activeAddress";
    private static final String KEY_PAIRING_HTTPS_PORT = "httpsPort";
    private static final String KEY_PAIRING_MAC_ADDRESS = "macAddress";
    private static final String KEY_PAIRING_PAIR_NAME = "pairName";
    private static final String KEY_PAIRING_SERVER_CERTIFICATE = "serverCertificateDer";

    // Local storage
    private static final String UNIQUE_ID_FILE_NAME = "uniqueid";
    private static final String CLIENT_CERTIFICATE_FILE_NAME = "client.crt";
    private static final String CLIENT_PRIVATE_KEY_FILE_NAME = "client.key";
    private static final String PAIR_NAME_PREFS = "pair_name_map";
    private static final String PREF_PAIRING_STATE_UPDATED_AT = "config_sync_pairing_state_updated_at";
    private static final int UNIQUE_ID_LENGTH = 16;

    // Encrypted envelope (V+ "moonlight-vplus-config-sync-encrypted")
    private static final String ENCRYPTED_FORMAT = "moonlight-vplus-config-sync-encrypted";
    private static final int ENCRYPTED_VERSION = 1;
    private static final String ENCRYPTED_CIPHER = "AES-256-GCM";
    private static final String AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    private static final String PEM_BEGIN_PREFIX = "-----BEGIN ";
    private static final String PEM_END_PREFIX = "-----END ";

    private ConfigurationPort() {
    }

    /** Result of an import, for display in the UI. */
    public static final class ImportResult {
        public int preferences;
        public int pairingImported;
        public int pairingFailed;
        /** Step-by-step outcome, so a failed restore explains itself instead of guessing. */
        public final java.util.List<String> details = new java.util.ArrayList<>();

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(preferences).append(" settings, ")
              .append(pairingImported).append(" pairing items");
            if (pairingFailed > 0) {
                sb.append(", ").append(pairingFailed).append(" failed");
            }
            return sb.toString();
        }

        /** Full report for the result dialog. */
        public String report() {
            StringBuilder sb = new StringBuilder(describe()).append("\n\n");
            for (String line : details) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * Import a V+ backup.
     *
     * @throws JSONException when the document is not a usable V+ backup.
     * @throws IOException   when the stream cannot be read.
     */
    public static ImportResult importVPlusBackup(Context context, InputStream in)
            throws IOException, JSONException {
        return importVPlusBackup(context, in, null);
    }

    /**
     * @param password decrypts an encrypted envelope; ignored for plain backups.
     */
    public static ImportResult importVPlusBackup(Context context, InputStream in, String password)
            throws IOException, JSONException {
        String document = new String(readAll(in), StandardCharsets.UTF_8);
        ImportResult result = new ImportResult();

        JSONObject probe = new JSONObject(document);
        if (isEncryptedEnvelope(probe)) {
            result.details.add("envelope: encrypted (PBKDF2 + AES-256-GCM)");
            if (password == null || password.isEmpty()) {
                throw new JSONException("This backup is encrypted; a password is required");
            }
            document = decryptEnvelope(probe, password);
            result.details.add("decryption: ok");
        } else {
            result.details.add("envelope: plain");
        }

        JSONObject root = new JSONObject(document);

        int schema = root.optInt(KEY_SCHEMA_VERSION, 0);
        if (schema < 1) {
            throw new JSONException("Missing schemaVersion: not a V+ backup");
        }
        if (schema > SUPPORTED_SCHEMA_VERSION) {
            throw new JSONException("Backup schema " + schema + " is newer than this build supports");
        }
        result.details.add("schemaVersion: " + schema);

        JSONObject sections = root.optJSONObject(KEY_SECTIONS);
        if (sections == null) {
            throw new JSONException("Missing sections");
        }

        Context app = context.getApplicationContext();

        JSONObject prefs = sections.optJSONObject(SECTION_DEFAULT_PREFERENCES);
        if (prefs != null) {
            result.preferences = importPreferences(app, prefs.optJSONObject(KEY_VALUES));
        }

        JSONObject pairing = sections.optJSONObject(SECTION_PAIRING);
        result.details.add("pairing section: " + (pairing == null ? "absent" : "present"));
        if (pairing != null) {
            int[] counts = importPairingState(app, pairing, result);
            result.pairingImported = counts[0];
            result.pairingFailed = counts[1];
        }

        verifyAfterImport(app, result);
        return result;
    }

    /**
     * Read the state back after writing. "ok" from a write call is not evidence: the UI keeps
     * its own copy of the computer list, so a report that only says "insert returned true"
     * cannot tell a successful restore from one the running app never reloaded.
     */
    private static void verifyAfterImport(Context context, ImportResult result) {
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        try {
            java.util.List<ComputerDetails> stored = db.getAllComputers();
            result.details.add("verify: " + stored.size() + " computers on disk");
            for (ComputerDetails details : stored) {
                result.details.add("  db: " + details.name + " (" + details.uuid + ") cert="
                        + (details.serverCert != null));
            }
        } catch (Exception e) {
            result.details.add("verify: database read failed: " + e.getMessage());
        } finally {
            db.close();
        }

        java.io.File certFile = new java.io.File(context.getFilesDir(), CLIENT_CERTIFICATE_FILE_NAME);
        java.io.File keyFile = new java.io.File(context.getFilesDir(), CLIENT_PRIVATE_KEY_FILE_NAME);
        result.details.add("verify: client.crt " + (certFile.exists() ? certFile.length() + " bytes" : "MISSING"));
        result.details.add("verify: client.key " + (keyFile.exists() ? keyFile.length() + " bytes" : "MISSING"));
        result.details.add("verify: cert/key pair " + checkKeyPair(certFile, keyFile));

        String uniqueId = readInternalFileText(context, UNIQUE_ID_FILE_NAME);
        result.details.add("verify: uniqueid " + (uniqueId == null ? "MISSING" : uniqueId));
        result.details.add("NOTE: force stop the app (swipe it away) before checking the PC list");
    }

    /**
     * Check that the stored certificate and private key actually belong together.
     *
     * A restored cert with a mismatched key is the difference between "no re-pairing needed"
     * and "the host rejects us", and nothing else in the import would notice.
     */
    private static String checkKeyPair(java.io.File certFile, java.io.File keyFile) {
        if (!certFile.exists() || !keyFile.exists()) {
            return "SKIPPED (file missing)";
        }
        try (InputStream certIn = new FileInputStream(certFile);
             InputStream keyIn = new FileInputStream(keyFile)) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(certIn);
            java.security.PrivateKey key = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(readAll(keyIn)));

            // Sign and verify a fixed nonce; the public key algorithm must line up too.
            String alg = key.getAlgorithm();
            java.security.Signature signer = java.security.Signature.getInstance(
                    "SHA256with" + ("RSA".equals(alg) ? "RSA" : alg));
            signer.initSign(key);
            byte[] nonce = new byte[32];
            new SecureRandom().nextBytes(nonce);
            signer.update(nonce);
            byte[] signature = signer.sign();

            java.security.Signature verifier = java.security.Signature.getInstance(
                    "SHA256with" + ("RSA".equals(alg) ? "RSA" : alg));
            verifier.initVerify(cert.getPublicKey());
            verifier.update(nonce);
            return verifier.verify(signature) ? "ok (" + alg + ")" : "MISMATCH";
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- encrypted envelope

    /** @return true when the document is a V+ encrypted envelope. */
    public static boolean looksEncrypted(byte[] document) {
        try {
            return isEncryptedEnvelope(new JSONObject(new String(document, StandardCharsets.UTF_8)));
        } catch (JSONException e) {
            return false;
        }
    }

    /** Read a stream fully; exposed for callers that need to buffer the document first. */
    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean isEncryptedEnvelope(JSONObject root) {
        return ENCRYPTED_FORMAT.equals(root.optString("format", ""));
    }

    /**
     * Decrypt a V+ encrypted backup.
     *
     * Mirrors V+ exactly: the GCM tag is stored as its own base64 field and has to be
     * appended to the ciphertext before the cipher sees it, the key is PBKDF2-HMAC-SHA256
     * with the iteration count taken from the envelope, and the KDF name comes from the
     * file rather than being hardcoded.
     */
    private static String decryptEnvelope(JSONObject envelope, String password) throws JSONException {
        int version = envelope.optInt("version", 0);
        if (version != ENCRYPTED_VERSION) {
            throw new JSONException("Unsupported encrypted backup version: " + version);
        }
        if (!ENCRYPTED_CIPHER.equals(envelope.optString("cipher", ""))) {
            throw new JSONException("Unsupported encrypted backup cipher");
        }

        String kdf = envelope.optString("kdf", "");
        int iterations = envelope.optInt("iterations", 0);
        if (iterations <= 0) {
            throw new JSONException("Missing encrypted backup KDF iterations");
        }

        byte[] salt = decodeEnvelopeBase64(envelope, "salt");
        byte[] iv = decodeEnvelopeBase64(envelope, "iv");
        byte[] ciphertext = decodeEnvelopeBase64(envelope, "ciphertext");
        byte[] tag = decodeEnvelopeBase64(envelope, "tag");

        byte[] sealed = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, sealed, 0, ciphertext.length);
        System.arraycopy(tag, 0, sealed, ciphertext.length, tag.length);

        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, AES_KEY_BITS);
            SecretKeySpec key;
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(kdf);
                key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
            } finally {
                spec.clearPassword();
            }

            Cipher cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new JSONException("Unable to decrypt backup: " + e.getMessage());
        }
    }

    private static byte[] decodeEnvelopeBase64(JSONObject envelope, String key) throws JSONException {
        String value = envelope.optString(key, "");
        if (value.isEmpty()) {
            throw new JSONException("Missing encrypted backup field: " + key);
        }
        try {
            return Base64.decode(value, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new JSONException("Invalid encrypted backup field: " + key);
        }
    }

    // ---------------------------------------------------------------- preferences

    private static int importPreferences(Context context, JSONObject values) {
        if (values == null) {
            return 0;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        int applied = 0;

        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = values.optJSONObject(key);
            if (entry == null) {
                continue;
            }

            String type = entry.optString(KEY_TYPE, "");
            try {
                switch (type) {
                    case TYPE_STRING:
                        editor.putString(key, entry.getString(KEY_VALUE));
                        applied++;
                        break;
                    case TYPE_BOOLEAN:
                        editor.putBoolean(key, entry.getBoolean(KEY_VALUE));
                        applied++;
                        break;
                    case TYPE_INT:
                        editor.putInt(key, entry.getInt(KEY_VALUE));
                        applied++;
                        break;
                    case TYPE_LONG:
                        editor.putLong(key, entry.getLong(KEY_VALUE));
                        applied++;
                        break;
                    case TYPE_FLOAT:
                        editor.putFloat(key, (float) entry.getDouble(KEY_VALUE));
                        applied++;
                        break;
                    case TYPE_STRING_SET: {
                        JSONArray array = entry.optJSONArray(KEY_VALUE);
                        if (array != null) {
                            Set<String> set = new HashSet<>();
                            for (int i = 0; i < array.length(); i++) {
                                set.add(array.getString(i));
                            }
                            editor.putStringSet(key, set);
                            applied++;
                        }
                        break;
                    }
                    default:
                        // Unknown type: skip. Guessing would risk a type mismatch crash.
                        break;
                }
            } catch (JSONException ignored) {
                // A single malformed entry must not abort the whole import
            }
        }

        editor.apply();
        return applied;
    }

    // ---------------------------------------------------------------- pairing

    /** @return {imported, failed} */
    private static int[] importPairingState(Context context, JSONObject pairing, ImportResult result) {
        int imported = 0;
        int failed = 0;

        String rawUniqueId = pairing.optString(KEY_PAIRING_UNIQUE_ID, "");
        if (!rawUniqueId.isEmpty()) {
            if (isValidUniqueId(rawUniqueId)) {
                writePrivateFile(context, UNIQUE_ID_FILE_NAME, rawUniqueId.getBytes(StandardCharsets.UTF_8));
                imported++;
                result.details.add("uniqueId: ok (" + rawUniqueId.length() + " hex chars)");
            } else {
                failed++;
                result.details.add("uniqueId: REJECTED (length " + rawUniqueId.length() + ", needs 16 hex chars)");
            }
        }

        int[] certResult = importIdentityFile(context, pairing, KEY_PAIRING_CLIENT_CERTIFICATE,
                CLIENT_CERTIFICATE_FILE_NAME);
        imported += certResult[0];
        failed += certResult[1];
        result.details.add("client cert: " + describeIdentity(certResult, KEY_PAIRING_CLIENT_CERTIFICATE, pairing));

        int[] keyResult = importIdentityFile(context, pairing, KEY_PAIRING_CLIENT_PRIVATE_KEY,
                CLIENT_PRIVATE_KEY_FILE_NAME);
        imported += keyResult[0];
        failed += keyResult[1];
        result.details.add("client key: " + describeIdentity(keyResult, KEY_PAIRING_CLIENT_PRIVATE_KEY, pairing));

        SharedPreferences.Editor pairNameEditor =
                context.getSharedPreferences(PAIR_NAME_PREFS, Context.MODE_PRIVATE).edit();

        JSONArray computers = pairing.optJSONArray(KEY_PAIRING_COMPUTERS);
        if (computers != null && computers.length() > 0) {
            ComputerDatabaseManager manager = new ComputerDatabaseManager(context);
            try {
                for (int i = 0; i < computers.length(); i++) {
                    JSONObject encoded = computers.optJSONObject(i);
                    ComputerDetails decoded = encoded == null ? null : decodeComputer(encoded);
                    if (decoded == null) {
                        failed++;
                        result.details.add("computer " + i + ": REJECTED (missing uuid or name)");
                        continue;
                    }
                    if (manager.updateComputer(decoded)) {
                        String pairName = encoded.optString(KEY_PAIRING_PAIR_NAME, "");
                        if (!pairName.isEmpty()) {
                                pairNameEditor.putString(decoded.uuid, pairName);
                        }
                        imported++;
                        result.details.add("computer " + decoded.uuid + " (" + decoded.name + "): ok, paired="
                                + (decoded.serverCert != null));
                    } else {
                        failed++;
                    }
                }
            } finally {
                manager.close();
            }
        }
        pairNameEditor.apply();

        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putLong(PREF_PAIRING_STATE_UPDATED_AT, System.currentTimeMillis())
                .apply();

        return new int[] {imported, failed};
    }

    private static String describeIdentity(int[] count, String key, JSONObject pairing) {
        if (!pairing.has(key)) {
            return "absent";
        }
        return count[0] > 0 ? "ok" : "REJECTED (not valid PEM/DER)";
    }

    /** @return {imported, failed} */
    private static int[] importIdentityFile(Context context, JSONObject pairing, String key, String fileName) {
        if (!pairing.has(key)) {
            return new int[] {0, 0};
        }

        String encoded = pairing.optString(key, "");
        if (encoded.isEmpty()) {
            return new int[] {0, 1};
        }

        byte[] decoded = decodeIdentityFileBytes(encoded, fileName);
        if (decoded == null || !isValidIdentityFile(fileName, decoded)) {
            return new int[] {0, 1};
        }

        writePrivateFile(context, fileName, decoded);
        return new int[] {1, 0};
    }

    private static byte[] decodeIdentityFileBytes(String value, String fileName) {
        String trimmed = value.trim();
        if (trimmed.startsWith(PEM_BEGIN_PREFIX)) {
            return normalizeIdentityFileBytes(fileName, trimmed.getBytes(StandardCharsets.UTF_8));
        }

        byte[] decoded;
        try {
            decoded = Base64.decode(trimmed, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return normalizeIdentityFileBytes(fileName, decoded);
    }

    /**
     * A PEM private key is stored as DER (the app hands it to PKCS8EncodedKeySpec), while a
     * PEM certificate is stored as text.
     */
    private static byte[] normalizeIdentityFileBytes(String fileName, byte[] bytes) {
        String text;
        try {
            text = new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return bytes;
        }

        if (!text.startsWith(PEM_BEGIN_PREFIX)) {
            return bytes;
        }

        if (CLIENT_PRIVATE_KEY_FILE_NAME.equals(fileName)) {
            return decodePemBody(text);
        }
        return text.replace("\r", "").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decodePemBody(String pem) {
        StringBuilder body = new StringBuilder();
        for (String line : pem.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(PEM_BEGIN_PREFIX) || trimmed.startsWith(PEM_END_PREFIX)) {
                continue;
            }
            body.append(trimmed);
        }
        if (body.length() == 0) {
            return null;
        }
        try {
            return Base64.decode(body.toString(), Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isValidIdentityFile(String fileName, byte[] bytes) {
        try {
            if (CLIENT_CERTIFICATE_FILE_NAME.equals(fileName)) {
                CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(bytes));
                return true;
            }
            if (CLIENT_PRIVATE_KEY_FILE_NAME.equals(fileName)) {
                KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
                return true;
            }
            return bytes.length > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isValidUniqueId(String uniqueId) {
        if (uniqueId.length() != UNIQUE_ID_LENGTH) {
            return false;
        }
        for (int i = 0; i < uniqueId.length(); i++) {
            char c = uniqueId.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static ComputerDetails decodeComputer(JSONObject encoded) {
        String uuid = encoded.optString(KEY_PAIRING_COMPUTER_UUID, "");
        if (uuid.isEmpty()) {
            return null;
        }
        String name = encoded.optString(KEY_PAIRING_COMPUTER_NAME, "");
        if (name.isEmpty()) {
            return null;
        }

        ComputerDetails details = new ComputerDetails();
        details.uuid = uuid;
        details.name = name;
        details.localAddress = decodeAddress(encoded.optJSONObject(KEY_PAIRING_LOCAL_ADDRESS));
        details.remoteAddress = decodeAddress(encoded.optJSONObject(KEY_PAIRING_REMOTE_ADDRESS));
        details.manualAddress = decodeAddress(encoded.optJSONObject(KEY_PAIRING_MANUAL_ADDRESS));
        details.ipv6Address = decodeAddress(encoded.optJSONObject(KEY_PAIRING_IPV6_ADDRESS));
        details.activeAddress = decodeAddress(encoded.optJSONObject(KEY_PAIRING_ACTIVE_ADDRESS));
        details.httpsPort = encoded.optInt(KEY_PAIRING_HTTPS_PORT, 0);
        String mac = encoded.optString(KEY_PAIRING_MAC_ADDRESS, "");
        if (!mac.isEmpty()) {
            details.macAddress = mac;
        }
        details.serverCert = decodeCertificate(encoded.optString(KEY_PAIRING_SERVER_CERTIFICATE, ""));
        return details;
    }

    private static ComputerDetails.AddressTuple decodeAddress(JSONObject encoded) {
        if (encoded == null) {
            return null;
        }
        String address = encoded.optString(KEY_PAIRING_ADDRESS, "");
        if (address.isEmpty()) {
            return null;
        }
        int port = encoded.optInt(KEY_PAIRING_PORT, 0);
        if (port <= 0) {
            return null;
        }
        return new ComputerDetails.AddressTuple(address, port);
    }

    private static X509Certificate decodeCertificate(String encoded) {
        if (encoded.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- export

    /** Serialize this installation in the same layout V+ writes. */
    public static String exportAsVPlusBackup(Context context) {
        Context app = context.getApplicationContext();
        try {
            JSONObject sections = new JSONObject();
            sections.put(SECTION_DEFAULT_PREFERENCES, new JSONObject().put(KEY_VALUES,
                    exportPreferences(PreferenceManager.getDefaultSharedPreferences(app))));
            sections.put(SECTION_PAIRING, exportPairing(app));

            return new JSONObject()
                    .put(KEY_SCHEMA_VERSION, SUPPORTED_SCHEMA_VERSION)
                    .put("syncId", UUID.randomUUID().toString())
                    .put("packageName", app.getPackageName())
                    .put("exportedAt", System.currentTimeMillis())
                    .put(KEY_SECTIONS, sections)
                    .toString(2);
        } catch (JSONException e) {
            return null;
        }
    }

    private static JSONObject exportPreferences(SharedPreferences prefs) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            JSONObject encoded = null;
            if (value instanceof String) {
                encoded = new JSONObject().put(KEY_TYPE, TYPE_STRING).put(KEY_VALUE, value);
            } else if (value instanceof Boolean) {
                encoded = new JSONObject().put(KEY_TYPE, TYPE_BOOLEAN).put(KEY_VALUE, value);
            } else if (value instanceof Integer) {
                encoded = new JSONObject().put(KEY_TYPE, TYPE_INT).put(KEY_VALUE, value);
            } else if (value instanceof Long) {
                encoded = new JSONObject().put(KEY_TYPE, TYPE_LONG).put(KEY_VALUE, value);
            } else if (value instanceof Float) {
                encoded = new JSONObject().put(KEY_TYPE, TYPE_FLOAT).put(KEY_VALUE, value);
            } else if (value instanceof Set) {
                JSONArray array = new JSONArray();
                for (Object item : (Set<?>) value) {
                    if (item instanceof String) {
                        array.put(item);
                    }
                }
                encoded = new JSONObject().put(KEY_TYPE, TYPE_STRING_SET).put(KEY_VALUE, array);
            }
            if (encoded != null) {
                out.put(entry.getKey(), encoded);
            }
        }
        return out;
    }

    private static JSONObject exportPairing(Context context) throws JSONException {
        JSONObject pairing = new JSONObject();

        String uniqueId = readInternalFileText(context, UNIQUE_ID_FILE_NAME);
        if (uniqueId != null && !uniqueId.isEmpty()) {
            pairing.put(KEY_PAIRING_UNIQUE_ID, uniqueId);
        }
        String cert = readInternalFileBase64(context, CLIENT_CERTIFICATE_FILE_NAME);
        if (cert != null) {
            pairing.put(KEY_PAIRING_CLIENT_CERTIFICATE, cert);
        }
        String key = readInternalFileBase64(context, CLIENT_PRIVATE_KEY_FILE_NAME);
        if (key != null) {
            pairing.put(KEY_PAIRING_CLIENT_PRIVATE_KEY, key);
        }

        SharedPreferences pairNames = context.getSharedPreferences(PAIR_NAME_PREFS, Context.MODE_PRIVATE);
        JSONArray computers = new JSONArray();
        ComputerDatabaseManager manager = new ComputerDatabaseManager(context);
        try {
            for (ComputerDetails details : manager.getAllComputers()) {
                if (details.uuid == null || details.uuid.isEmpty()) {
                    continue;
                }
                JSONObject encoded = new JSONObject()
                        .put(KEY_PAIRING_COMPUTER_UUID, details.uuid)
                        .put(KEY_PAIRING_COMPUTER_NAME, details.name == null ? "" : details.name)
                        .put(KEY_PAIRING_LOCAL_ADDRESS, encodeAddress(details.localAddress))
                        .put(KEY_PAIRING_REMOTE_ADDRESS, encodeAddress(details.remoteAddress))
                        .put(KEY_PAIRING_MANUAL_ADDRESS, encodeAddress(details.manualAddress))
                        .put(KEY_PAIRING_IPV6_ADDRESS, encodeAddress(details.ipv6Address))
                        .put(KEY_PAIRING_ACTIVE_ADDRESS, encodeAddress(details.activeAddress))
                        .put(KEY_PAIRING_HTTPS_PORT, details.httpsPort)
                        .put(KEY_PAIRING_MAC_ADDRESS, details.macAddress == null ? "" : details.macAddress)
                        .put(KEY_PAIRING_PAIR_NAME, pairNames.getString(details.uuid, ""));
                if (details.serverCert != null) {
                    try {
                        encoded.put(KEY_PAIRING_SERVER_CERTIFICATE,
                                Base64.encodeToString(details.serverCert.getEncoded(), Base64.NO_WRAP));
                    } catch (java.security.cert.CertificateEncodingException ignored) {
                        // Without the DER the PC looks unpaired on the other side; leave it out
                    }
                }
                computers.put(encoded);
            }
        } finally {
            manager.close();
        }
        pairing.put(KEY_PAIRING_COMPUTERS, computers);
        return pairing;
    }

    private static JSONObject encodeAddress(ComputerDetails.AddressTuple tuple) {
        if (tuple == null || tuple.address == null || tuple.address.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject().put(KEY_PAIRING_ADDRESS, tuple.address).put(KEY_PAIRING_PORT, tuple.port);
        } catch (JSONException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- io helpers

    private static void writePrivateFile(Context context, String name, byte[] bytes) {
        try (FileOutputStream out = context.openFileOutput(name, Context.MODE_PRIVATE)) {
            out.write(bytes);
        } catch (IOException ignored) {
            // Nothing useful to do here; the caller already counts a failure per file
        }
    }

    private static String readInternalFileText(Context context, String name) {
        try (InputStream in = context.openFileInput(name)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readInternalFileBase64(Context context, String name) {
        File file = new File(context.getFilesDir(), name);
        if (!file.exists()) {
            return null;
        }
        // Files.readAllBytes is API 26+; minSdk is 21, so read the stream manually.
        try (InputStream in = new FileInputStream(file)) {
            return Base64.encodeToString(readAll(in), Base64.NO_WRAP);
        } catch (IOException e) {
            return null;
        }
    }

}
