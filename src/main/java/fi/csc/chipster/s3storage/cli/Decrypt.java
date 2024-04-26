package fi.csc.chipster.s3storage.cli;

import java.io.File;

import javax.crypto.SecretKey;

import fi.csc.chipster.s3storage.benchmark.EncryptDecryptBenchmark;
import fi.csc.chipster.s3storage.encryption.FileEncryption;

public class Decrypt {

    public static void main(String[] args) throws Throwable {

        if (args.length != 3) {
            System.out.println("Usgae: Decrypt INPUT_FILE OUTPUT_FILE SECRET_KEY");
            System.exit(1);
        }

        FileEncryption enc = new FileEncryption();

        File input = new File(args[0]);
        File output = new File(args[1]);
        SecretKey secretKey = enc.parseKey(args[2]);

        EncryptDecryptBenchmark.testLarge(false, input, output, secretKey, enc);
    }
}