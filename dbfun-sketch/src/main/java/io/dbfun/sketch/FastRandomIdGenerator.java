package io.dbfun.sketch;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * A random id generator based on sha1 that is 5 times faster than UUID#randomUUID().
 *
 * <p>see http://antirez.com/news/99
 */
public class FastRandomIdGenerator
{
  private HashFunction sha1 = Hashing.sha1();
  private long counter = 0;
  private ByteBuffer buffer;

  public FastRandomIdGenerator() {
    long seed = new Random().nextLong();
    buffer = ByteBuffer.allocate(16);
    buffer.putLong(8, seed);
  }

  /**
   * @return a 20 bytes random id with a very low collision rate
   */
  public byte[] generate() {
    buffer.putLong(0, counter++);
    return sha1.hashBytes(buffer.array()).asBytes();
  }
}
