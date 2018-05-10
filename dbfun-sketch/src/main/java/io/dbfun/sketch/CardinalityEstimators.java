package io.dbfun.sketch;

import com.google.common.base.Supplier;
import com.google.common.hash.Hashing;

public final class CardinalityEstimators
{
  private static final int DEFAULT_PRECISION = 14;

  public static CardinalityEstimator get(String name)
  {
    if (name.startsWith("hllraw")) {
      String pStr = name.substring("hllraw".length());
      int precision = pStr.isEmpty() ? DEFAULT_PRECISION : Integer.parseInt(pStr);
      return new HllRaw(precision, Hashing.murmur3_128());
    }
    if (name.startsWith("hllnobias")) {
      String pStr = name.substring("hllnobias".length());
      int precision = pStr.isEmpty() ? DEFAULT_PRECISION : Integer.parseInt(pStr);
      return new Hll64WithBiasCorrection(precision, Hashing.murmur3_128());
    }
    if (name.equals("uniq")) {
      return new UniqCounter();
    }
    if (name.equals("combined")) {
      return new Hll16Combined(Hashing.murmur3_128());
    }
    throw new IllegalArgumentException("Unknown estimator : " + name);
  }

  public static Supplier<CardinalityEstimator> lazyGet(String name)
  {
    return () -> get(name);
  }
}
