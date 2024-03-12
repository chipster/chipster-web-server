package fi.csc.chipster.s3storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPCompressedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPOnePassSignatureList;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.bouncycastle.openpgp.operator.PGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;
import org.bouncycastle.util.io.Streams;

/**
 * Based on
 * https://github.com/bcgit/bc-java/blob/main/pg/src/main/java/org/bouncycastle/openpgp/examples/KeyBasedLargeFileProcessor.java,
 * MIT license
 */
public class EncryptDecryptTest {
    private static void decryptFile(
            String inputFileName,
            String keyFileName,
            char[] passwd,
            String defaultFileName)
            throws IOException, NoSuchProviderException {
        InputStream in = new BufferedInputStream(new FileInputStream(inputFileName));
        InputStream keyIn = new BufferedInputStream(new FileInputStream(keyFileName));
        decryptFile(in, keyIn, passwd, defaultFileName);
        keyIn.close();
        in.close();
    }

    /**
     * decrypt the passed in message stream
     */
    private static void decryptFile(
            InputStream in,
            InputStream keyIn,
            char[] passwd,
            String defaultFileName)
            throws IOException, NoSuchProviderException {
        in = PGPUtil.getDecoderStream(in);

        try {
            JcaPGPObjectFactory pgpF = new JcaPGPObjectFactory(in);
            PGPEncryptedDataList enc;

            Object o = pgpF.nextObject();
            //
            // the first object might be a PGP marker packet.
            //
            if (o instanceof PGPEncryptedDataList) {
                enc = (PGPEncryptedDataList) o;
            } else {
                enc = (PGPEncryptedDataList) pgpF.nextObject();
            }

            //
            // find the secret key
            //
            Iterator it = enc.getEncryptedDataObjects();
            PGPPrivateKey sKey = null;
            PGPPublicKeyEncryptedData pbe = null;
            PGPSecretKeyRingCollection pgpSec = new PGPSecretKeyRingCollection(
                    PGPUtil.getDecoderStream(keyIn), new JcaKeyFingerprintCalculator());

            while (sKey == null && it.hasNext()) {
                pbe = (PGPPublicKeyEncryptedData) it.next();

                sKey = PGPExampleUtil.findSecretKey(pgpSec, pbe.getKeyID(), passwd);
            }

            if (sKey == null) {
                throw new IllegalArgumentException("secret key for message not found.");
            }

            InputStream clear = pbe
                    .getDataStream(new JcePublicKeyDataDecryptorFactoryBuilder()
                            .build(sKey));

            JcaPGPObjectFactory plainFact = new JcaPGPObjectFactory(clear);

            PGPCompressedData cData = (PGPCompressedData) plainFact.nextObject();

            InputStream compressedStream = new BufferedInputStream(cData.getDataStream());

            JcaPGPObjectFactory pgpFact = new JcaPGPObjectFactory(compressedStream);

            Object message = pgpFact.nextObject();

            if (message instanceof PGPLiteralData) {
                PGPLiteralData ld = (PGPLiteralData) message;

                String outFileName = ld.getFileName();
                // if (outFileName.length() == 0) {
                outFileName = defaultFileName;
                // }

                InputStream unc = ld.getInputStream();
                OutputStream fOut = new FileOutputStream(outFileName);

                Streams.pipeAll(unc, fOut, 8192);

                // Streams.pipeAll(unc, fOut, 1 << 16);

                fOut.close();
            } else if (message instanceof PGPOnePassSignatureList) {
                throw new PGPException("encrypted message contains a signed message - not literal data.");
            } else {
                throw new PGPException("message is not a simple encrypted file - type unknown.");
            }

            if (pbe.isIntegrityProtected()) {
                if (!pbe.verify()) {
                    System.err.println("message failed integrity check");
                } else {
                    // System.err.println("message integrity check passed");
                }
            } else {
                System.err.println("no message integrity check");
            }
        } catch (PGPException e) {
            System.err.println(e);
            if (e.getUnderlyingException() != null) {
                e.getUnderlyingException().printStackTrace();
            }
        }
    }

    private static void encryptFile(
            String outputFileName,
            String inputFileName,
            PGPPublicKey pubKey,
            boolean withIntegrityCheck)
            throws IOException, NoSuchProviderException, PGPException {
        OutputStream out = new BufferedOutputStream(new FileOutputStream(outputFileName));
        // PGPPublicKey encKey = PGPExampleUtil.readPublicKey(pubKey);
        encryptFile(out, inputFileName, pubKey, withIntegrityCheck);
        out.close();
    }

    private static void encryptFile(
            OutputStream out,
            String fileName,
            PGPPublicKey encKey,
            boolean withIntegrityCheck)
            throws IOException, NoSuchProviderException {

        try {
            PGPDataEncryptorBuilder encryptorBuilder = new JcePGPDataEncryptorBuilder(
                    PGPEncryptedData.AES_256)
                    .setSecureRandom(new SecureRandom())
                    .setWithIntegrityPacket(withIntegrityCheck);

            PGPEncryptedDataGenerator cPk = new PGPEncryptedDataGenerator(encryptorBuilder);

            cPk.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encKey));

            OutputStream cOut = cPk.open(out, new byte[1 << 16]);

            PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(
                    PGPCompressedData.UNCOMPRESSED);

            PGPUtil.writeFileToLiteralData(comData.open(cOut), PGPLiteralData.BINARY, new File(fileName),
                    new byte[1 << 16]);

            comData.close();

            cOut.close();

        } catch (PGPException e) {
            System.err.println(e);
            if (e.getUnderlyingException() != null) {
                e.getUnderlyingException().printStackTrace();
            }
        }
    }

    public static void main(
            String[] args)
            throws Exception {

        File tmpDir = new File("tmp");
        File largeTestFile = new File(tmpDir, "rand");
        File largeTestFileEnc = new File(tmpDir, "rand.enc");
        File largeTestFileDec = new File(tmpDir, "rand.dec");

        List<File> smallTestFiles = new ArrayList<File>();

        long fileSize = 4l * 1024 * 1024 * 1024;
        long smallFileCount = 1000;

        for (int i = 0; i < smallFileCount; i++) {
            File testFile = new File(tmpDir, "rand_4k_" + i);
            smallTestFiles.add(testFile);
        }

        // if dir does not exists already
        if (tmpDir.mkdirs()) {

            System.out.println("copy " + fileSize / 1024 / 1024 + " MB to " + largeTestFile);
            try (InputStream is = new FileInputStream("/dev/urandom");
                    OutputStream os = new FileOutputStream(new File(tmpDir, "rand"))) {

                IOUtils.copyLarge(is, os, 0, fileSize, new byte[64 * 1024]);
            }

            System.out.println("copy " + smallFileCount + " 4k files");

            for (File testFile : smallTestFiles) {
                try (InputStream is = new FileInputStream("/dev/urandom");
                        OutputStream os = new FileOutputStream(testFile)) {

                    IOUtils.copyLarge(is, os, 0, 4 * 1024, new byte[4 * 1024]);
                }
            }

        } else {
            System.out.println(tmpDir + " found already");

        }

        PGPPublicKey pubKey = PGPExampleUtil.readPublicKey("pub.bpg");

        long t = System.currentTimeMillis();

        System.out.println("encrypt " + largeTestFile);
        encryptFile(largeTestFileEnc.getPath(), largeTestFile.getPath(), pubKey, true);

        long dt = System.currentTimeMillis() - t;
        System.out.println(dt + "ms, " + largeTestFile.length() / 1024.0 / 1024.0 / (dt / 1000.0) + "MB/s");

        System.out.println("decrypt " + largeTestFileEnc);
        t = System.currentTimeMillis();
        decryptFile(largeTestFileEnc.getPath(), "secret.bpg", "".toCharArray(),
                largeTestFileDec.getPath());

        dt = System.currentTimeMillis() - t;
        System.out.println(dt + "ms, " + largeTestFileEnc.length() / 1024.0 / 1024.0 / (dt / 1000.0) + "MB/s");

        System.out.println("encrypt 1000 4k files");
        t = System.currentTimeMillis();

        for (File testFile : smallTestFiles) {

            File outFile = new File(testFile.getPath() + ".enc");
            // System.out.println("encrypt " + testFile.getPath() + " to " +
            // outFile.getPath());
            encryptFile(outFile.getPath(), testFile.getPath(), pubKey, true);
        }

        dt = System.currentTimeMillis() - t;
        System.out.println(1000.0 / (dt / 1000.0) + " ops/s");

        System.out.println("decrypt 1000 4k files");
        t = System.currentTimeMillis();

        for (File origFile : smallTestFiles) {

            File encFile = new File(origFile.getPath() + ".enc");
            File decFile = new File(origFile.getPath() + ".dec");

            decryptFile(encFile.getPath(), "secret.bpg", "".toCharArray(), decFile.getPath());
        }

        dt = System.currentTimeMillis() - t;
        System.out.println(1000.0 / (dt / 1000.0) + " ops/s");

    }
}