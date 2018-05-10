package io.dbfun.sketch;

import com.google.common.base.Preconditions;
import com.google.common.hash.HashFunction;

public class Hll64WithBiasCorrection implements CardinalityEstimator<Hll64WithBiasCorrection>
{
  private final int p;
  private final HashFunction hashFunction;

  // each register actually only needs 5-bits,
  // we use `byte` here to simplify implementation
  private final byte[] registers;

  public Hll64WithBiasCorrection(int precision, HashFunction hashFunction)
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
    add64BitsHash(hashFunction.hashBytes(value).asLong());
  }

  public void add(long value)
  {
    add64BitsHash(hashFunction.hashLong(value).asLong());
  }

  private void add64BitsHash(long hash)
  {
    final int bucket = (int) (hash >>> (Long.SIZE - p));
    byte positionOfOne = (byte) (Long.numberOfLeadingZeros((hash << p) | (1 << (p - 1))) + 1);
    // note that both operands can never be negative, so we don't need to use unsigned comparison
    if (registers[bucket] < positionOfOne) {
      registers[bucket] = positionOfOne;
    }
  }

  public void merge(Hll64WithBiasCorrection that)
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
    double ePrime = e;
    if (e <= 5 * m) {
      ePrime = e - HllPlusBiasTable.getEstimateBias(e, p);
    }

    double H = zeros > 0 ? m * Math.log(m / (double) zeros) : ePrime;
    // when p is large the threshold is just 5*m
    if (((p <= 18) && (H < HllPlusBiasTable.thresholdData[p - 4])) || ((p > 18) && (e <= (5 * m)))) {
      return Math.round(H);
    } else {
      return Math.round(ePrime);
    }
  }

  public long memoryFootprint()
  {
    return registers.length; // not counting object headers, `p`, `hashFunction` reference;
  }

  @Override
  public String name()
  {
    return "hllnobias" + p;
  }
}
