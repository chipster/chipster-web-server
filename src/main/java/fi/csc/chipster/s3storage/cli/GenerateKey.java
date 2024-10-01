package fi.csc.chipster.s3storage.cli;

import javax.crypto.SecretKey;

import fi.csc.chipster.s3storage.encryption.FileEncryption;

/**
 * CLI utility program to generate a encryption key for Chipster encryption
 * format
 */
public class GenerateKey {

    public static void main(String[] args) throws Throwable {

        FileEncryption enc = new FileEncryption();

        SecretKey secretKey = enc.generateKey();

        System.out.println(new FileEncryption().keyToString(secretKey));
    }
}