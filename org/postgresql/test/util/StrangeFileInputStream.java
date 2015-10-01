package org.postgresql.test.util;

import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.Date;

/**
 * Emulator of strange conditions when FileInputStream returns 0 bytes read
 * Created by amozhenin on 30.09.2015.
 */
public class StrangeFileInputStream extends InputStream {

    private FileInputStream in;
    private Random rand; //generator of fun events

    public StrangeFileInputStream(String filename) throws FileNotFoundException {
        in = new FileInputStream(filename);
        rand = new Random();
        rand.setSeed(new Date().getTime());
    }

    public StrangeFileInputStream(File file) throws FileNotFoundException {
        in = new FileInputStream(file);
        rand = new Random();
        rand.setSeed(new Date().getTime());
    }

    @Override
    public int read() throws IOException {
        return in.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        int x = rand.nextInt(100);
        if (x == 0) // ~1% chance of returning 0 bytes
            return 0;
        else
            return super.read(b);
    }

}
