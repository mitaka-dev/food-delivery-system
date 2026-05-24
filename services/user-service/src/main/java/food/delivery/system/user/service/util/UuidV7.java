package food.delivery.system.user.service.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates UUID v7 (time-ordered). The top 48 bits carry the Unix epoch in ms,
 * the next 4 bits are the version (0x7), and the remaining bits are random.
 * Time-ordering keeps B-tree inserts at the end of the index, reducing write amplification.
 */
public final class UuidV7 {

    private UuidV7() {}

    public static UUID generate() {
        long now = System.currentTimeMillis();
        byte[] rand = new byte[10];
        ThreadLocalRandom.current().nextBytes(rand);

        // most-significant 64 bits: 48-bit timestamp | 4-bit version (7) | 12 random bits
        long msb = (now << 16)
                | 0x7000L
                | ((rand[0] & 0x0FL) << 8)
                | (rand[1] & 0xFFL);

        // least-significant 64 bits: 2-bit variant (10) | 62 random bits
        long lsb = (0x80L << 56)
                | ((rand[2] & 0x3FL) << 56)
                | ((rand[3] & 0xFFL) << 48)
                | ((rand[4] & 0xFFL) << 40)
                | ((rand[5] & 0xFFL) << 32)
                | ((rand[6] & 0xFFL) << 24)
                | ((rand[7] & 0xFFL) << 16)
                | ((rand[8] & 0xFFL) << 8)
                | (rand[9] & 0xFFL);

        return new UUID(msb, lsb);
    }
}
