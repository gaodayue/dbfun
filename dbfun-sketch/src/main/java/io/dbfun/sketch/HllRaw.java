package io.dbfun.sketch;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashFunction;

/**
 * Implements HyperLogLog described in http://algo.inria.fr/flajolet/Publications/FlFuGaMe07.pdf.
 *
 * <p>Uses a 32-bits hash function with a parameter p.
 *
 * <p>Run this code to see a simple indication of expected errors based on different p values:
 * <pre>
 * for (int p = 7; p &lt; 31; ++p) {
 * System.out.printf("p[%,d], m[%,d] =&gt; error[%f%%]%n", p, 1 &lt;&lt; p, 104 / Math.sqrt(1 &lt;&lt; p));
 * }
 * </pre>
 *
 * <p> Differences from paper
 * <ul>
 *   <li>each register takes 8-bits instead of 5-bits
 *   <li>bucket index and position of 1 are took from the least-significant bit instead of most-significant bit
 * </ul>
 */
public class HllRaw implements CardinalityEstimator<HllRaw>
{
  private static final double TWO_TO_THE_THIRTY_TWO = Math.pow(2, 32);
  private static final double HIGH_CORRECTION_THRESHOLD = TWO_TO_THE_THIRTY_TWO / 30.0d;

  private final int p;
  private final HashFunction hashFunction;

  // each register actually only needs 5-bits,
  // we use `byte` here to simplify implementation
  private final byte[] registers;

  public HllRaw(int precision, HashFunction hashFunction)
  {
    Preconditions.checkArgument(
        precision >= 7 && precision < 31,
        "invalid precision [%d] : should be in [7, 32)"
    );
    this.p = precision;
    this.hashFunction = hashFunction;
    this.registers = new byte[1 << p];
  }

  public void add(byte[] value)
  {
    add32BitsHash(hashFunction.hashBytes(value).asInt());
  }

  public void add(long value)
  {
    add32BitsHash(hashFunction.hashLong(value).asInt());
  }

  private void add32BitsHash(int hash)
  {
    final int bucket = hash & ((1 << p) - 1);
    hash >>>= p;

    byte positionOfOne;
    if (hash == 0) { // very unlikely
      positionOfOne = (byte) (Integer.SIZE - p + 1);
    } else {
      positionOfOne = (byte) (Integer.numberOfTrailingZeros(hash) + 1);
    }

    // note that both operands can never be negative, so we don't need to use unsigned comparison
    if (registers[bucket] < positionOfOne) {
      registers[bucket] = positionOfOne;
    }
  }

  public void merge(HllRaw that)
  {
    assert this.p == that.p;
    for (int i = 0; i < registers.length; i++) {
      if (registers[i] < that.registers[i]) {
        registers[i] = that.registers[i];
      }
    }
  }

  public long cardinality()
  {
    final int m = 1 << p;

    double registerSum = 0.0;
    int zeros = 0;
    for (int i = 0; i < m; i++) {
      registerSum += 1.0 / (1 << registers[i]);
      if (registers[i] == 0) {
        zeros++;
      }
    }

    final double alpha = 0.7213 / (1 + 1.079 / m);
    final double e = alpha * m * m * (1 / registerSum);
    return Math.round(makeCorrection(e, zeros, m));
  }

  private double makeCorrection(double e, int zeros, int m)
  {
    if (e <= (2.5d * m)) { // small range correction
      return zeros == 0 ? e : m * Math.log(m / (double) zeros);
    }

    if (e > HIGH_CORRECTION_THRESHOLD) { // high range correction
      return -TWO_TO_THE_THIRTY_TWO * Math.log(1 - e / TWO_TO_THE_THIRTY_TWO);
    }

    return e;
  }

  public long memoryFootprint()
  {
    return registers.length; // not counting object headers, `p`, `hashFunction` reference
  }

  @Override
  public String name()
  {
    return "hllraw" + p;
  }

  public static void main(String[] args)
  {
    for (int p = 4; p <= 16; p++) {
      System.out.printf("p[%,d], m[%,d] => error[%f%%]%n", p, 1 << p, 104 / Math.sqrt(1 << p));
    }
  }
}
