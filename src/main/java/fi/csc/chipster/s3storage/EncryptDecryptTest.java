package fi.csc.chipster.s3storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.apache.commons.io.IOUtils;

public class EncryptDecryptTest {

    public static void main(
            String[] args)
            throws Throwable {

        File tmpDir = new File("tmp");
        File largeTestFile = new File(tmpDir, "rand");
        File largeTestFileAes = new File(tmpDir, "rand.aes");
        File largeTestFileAesDec = new File(tmpDir, "rand.aes.dec");

        List<File> smallTestFiles = new ArrayList<File>();

        long fileSize = 1l * 1024 * 1024 * 1024;
        // long fileSize = 16l * 1024 * 1024;
        long smallFileCount = 1000;

        // generate test data

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

        // plain AES

        long t = System.currentTimeMillis();

        KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
        keyGenerator.init(256);
        SecretKey secretKey = keyGenerator.generateKey();

        System.out.println("encrypt aes " + largeTestFile);
        ChipsterEncryption.encrypt(secretKey, largeTestFile, largeTestFileAes);

        long dt = System.currentTimeMillis() - t;
        System.out.println(dt + "ms, " + largeTestFile.length() / 1024.0 / 1024.0 / (dt / 1000.0) + "MB/s");

        System.out.println("decrypt aes " + largeTestFileAes);
        t = System.currentTimeMillis();
        ChipsterEncryption.decrypt(secretKey, largeTestFileAes, largeTestFileAesDec);

        dt = System.currentTimeMillis() - t;
        System.out.println(dt + "ms, " + largeTestFileAes.length() / 1024.0 / 1024.0 / (dt / 1000.0) + "MB/s");

        // small files

        System.out.println("encrypt 1000 4k files");
        t = System.currentTimeMillis();

        for (File testFile : smallTestFiles) {

            File outFile = new File(testFile.getPath() + ".enc");
            // System.out.println("encrypt " + testFile.getPath() + " to " +
            // outFile.getPath());
            ChipsterEncryption.encrypt(secretKey, testFile, outFile);
        }

        dt = System.currentTimeMillis() - t;
        System.out.println(1000.0 / (dt / 1000.0) + " ops/s");

        System.out.println("decrypt 1000 4k files");
        t = System.currentTimeMillis();

        for (File origFile : smallTestFiles) {

            File encFile = new File(origFile.getPath() + ".enc");
            File decFile = new File(origFile.getPath() + ".dec");

            ChipsterEncryption.decrypt(secretKey, encFile, decFile);
        }

        dt = System.currentTimeMillis() - t;
        System.out.println(1000.0 / (dt / 1000.0) + " ops/s");
    }
}