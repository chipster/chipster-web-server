package fi.csc.chipster.s3storage.tests;

import java.io.File;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import org.apache.commons.io.FileUtils;

import fi.csc.chipster.s3storage.FileEncryption;
import fi.csc.chipster.s3storage.IllegalFileException;

public class EncryptDecryptTest {

    public static void main(String[] args) throws Throwable {

        long fileSize = 1l * 1024 * 1024 * 1024;
        // long fileSize = 16l * 1024 * 1024;
        long smallFileCount = 1000;

        List<File> smallTestFiles = new ArrayList<File>();

        File tmpDir = TestData.generateTestFiles(fileSize, smallFileCount, smallTestFiles);

        File largeTestFile = new File(tmpDir, "rand");
        File largeTestFileAes = new File(tmpDir, "rand.aes");
        File largeTestFileAesDec = new File(tmpDir, "rand.aes.dec");

        FileEncryption enc = new FileEncryption();

        SecretKey secretKey = enc.generateKey();

        testLarge(true, largeTestFile, largeTestFileAes, secretKey, enc);
        testLarge(false, largeTestFileAes, largeTestFileAesDec, secretKey, enc);

        testSmall(true, 1000, smallTestFiles, secretKey, enc);
        testSmall(false, 1000, smallTestFiles, secretKey, enc);

        FileUtils.deleteDirectory(tmpDir);
    }

    public static void testLarge(boolean isEncrypt, File input, File output, SecretKey secretKey, FileEncryption enc)
            throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidAlgorithmParameterException, IOException, IllegalFileException {

        long t = System.currentTimeMillis();

        if (isEncrypt) {
            System.out.println("encrypt aes " + input);
            enc.encrypt(secretKey, input, output);
        } else {
            System.out.println("decrypt aes " + input);
            enc.decrypt(secretKey, input, output);
        }

        long dt = System.currentTimeMillis() - t;

        System.out.println(dt + "ms, " + input.length() / 1024.0 / 1024.0 / (dt / 1000.0) + "MB/s");
    }

    private static void testSmall(boolean isEncrypt, int fileCount, List<File> smallTestFiles, SecretKey secretKey,
            FileEncryption enc) throws InvalidKeyException, NoSuchAlgorithmException, NoSuchPaddingException,
            InvalidAlgorithmParameterException, IOException, IllegalFileException {
        // small files

        if (isEncrypt) {
            System.out.println("encrypt 1000 4k files");
        } else {
            System.out.println("decrypt 1000 4k files");
        }

        long t = System.currentTimeMillis();

        for (File testFile : smallTestFiles) {

            if (isEncrypt) {
                File outFile = new File(testFile.getPath() + ".enc");
                // System.out.println("encrypt " + testFile.getPath() + " to " +
                // outFile.getPath());
                enc.encrypt(secretKey, testFile, outFile);
            } else {
                File encFile = new File(testFile.getPath() + ".enc");
                File decFile = new File(testFile.getPath() + ".dec");

                enc.decrypt(secretKey, encFile, decFile);
            }
        }

        long dt = System.currentTimeMillis() - t;
        System.out.println(1000.0 / (dt / 1000.0) + " ops/s");
    }
}