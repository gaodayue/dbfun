package io.dbfun.sketch;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.UUID;

public class Main
{
  static class RandomIdGenerator {
    private HashFunction sha1 = Hashing.sha1();
    private long counter = 0;
    private ByteBuffer buffer;

    RandomIdGenerator() {
      long seed = new Random().nextLong();
      buffer = ByteBuffer.allocate(16);
      buffer.putLong(8, seed);
    }

    public byte[] generate() {
      buffer.putLong(0, counter++);
      return sha1.hashBytes(buffer.array()).asBytes();
    }
  }

  public static void main(String[] args)
  {
    CardinalityEstimator estimator = new HllRaw(14, Hashing.murmur3_128());
//    ByteBuffer buffer = ByteBuffer.allocate(16);
    RandomIdGenerator random = new RandomIdGenerator();

    long start = System.currentTimeMillis();
    for (int i = 0; i < 100_000_000; i++) {
//      UUID uuid = UUID.randomUUID();
//      buffer.position(0);
//      buffer.putLong(uuid.getMostSignificantBits());
//      buffer.putLong(uuid.getLeastSignificantBits());
//      estimator.add(buffer.array());
      estimator.add(random.generate());
    }
    System.out.println(estimator.cardinality());
    System.out.format("Time = %,d ms%n", System.currentTimeMillis() - start);
  }
}
