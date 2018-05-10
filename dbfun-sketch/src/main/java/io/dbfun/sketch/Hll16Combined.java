package io.dbfun.sketch;

import com.google.common.hash.HashFunction;

public class Hll16Combined implements CardinalityEstimator<Hll16Combined>
{
  private static final int TO_HLL_THRESHOLD = 1 << 13;

  private Object state;
  private final HashFunction hashFunction;

  public Hll16Combined(HashFunction hashFunction)
  {
    this.state = new HashTable();
    this.hashFunction = hashFunction;
  }

  @Override
  public void add(byte[] value)
  {
    addHash(hashFunction.hashBytes(value).asInt());
  }

  @Override
  public void add(long value)
  {
    addHash(hashFunction.hashLong(value).asInt());
  }

  public void addHash(int hash)
  {
    if (state instanceof HashTable) {
      HashTable table = (HashTable) state;
      if (table.cardinality() < TO_HLL_THRESHOLD) {
        table.addHash(hash);
        return;
      }
      state = table.toHll16();
    }
    ((Hll16) state).addHash(hash);
  }

  @Override
  public void merge(Hll16Combined that)
  {
    if (state instanceof HashTable && that.state instanceof Hll16) {
      state = ((HashTable) state).toHll16();
    }
    if (state instanceof Hll16 && that.state instanceof Hll16) {
      // hashtable/hll merge hll
      ((Hll16) state).merge((Hll16) that.state);
    } else {
      // hashtable/hll merge hashtable
      ((HashTable) that.state).drainTo(this);
    }
  }

  @Override
  public long cardinality()
  {
    if (state instanceof HashTable) {
      return ((HashTable) state).cardinality();
    }
    return ((Hll16) state).cardinality();
  }

  @Override
  public long memoryFootprint()
  {
    if (state instanceof HashTable) {
      return ((HashTable) state).memoryFootprint();
    }
    return ((Hll16) state).memoryFootprint();
  }

  @Override
  public String name()
  {
    return "combined";
  }

  private static final class HashTable
  {
    private static final int INITIAL_SIZE = 16;
    int[] buf; // buf.length should always be power of 2
    int count;
    boolean hasZero;

    HashTable()
    {
      this.buf = new int[INITIAL_SIZE];
    }

    public void addHash(int hash)
    {
      if (hash == 0) {
        if (!hasZero) {
          count++;
        }
        hasZero = true;
        return;
      }

      int index = hash & (buf.length - 1);
      while (buf[index] != 0 && buf[index] != hash) {
        index = (index + 1) & (buf.length - 1);
      }
      if (buf[index] == 0) {
        buf[index] = hash;
        count++;
      }

      // resize if half-full
      if (count > (buf.length >>> 1)) {
        resize(buf.length << 1);
      }
    }

    private void resize(final int newSize)
    {
      int[] newBuf = new int[newSize];
      for (int hash : buf) {
        if (hash != 0) {
          int index = hash & (newSize - 1);
          while (newBuf[index] != 0 && newBuf[index] != hash) {
            index = (index + 1) & (newSize - 1);
          }
          newBuf[index] = hash;
        }
      }
      buf = newBuf;
    }

    public long cardinality()
    {
      return count;
    }

    public long memoryFootprint()
    {
      return Integer.BYTES * buf.length;
    }

    public void drainTo(Hll16Combined that)
    {
      for (int hash : buf) {
        if (hash != 0) {
          that.addHash(hash);
        }
      }
      if (hasZero) {
        that.addHash(0);
      }
    }

    public Hll16 toHll16()
    {
      Hll16 hll = new Hll16();
      for (int hash : buf) {
        hll.addHash(hash);
      }
      if (hasZero) {
        hll.addHash(0);
      }
      return hll;
    }
  }

  private static final class Hll16
  {
    private static final int p = 16;
    private static final int m = 1 << p;
    private static final double alpha = 0.7213 / (1 + 1.079 / m);
    private static final double TWO_TO_THE_THIRTY_TWO = Math.pow(2, 32);
    private static final double HIGH_CORRECTION_THRESHOLD = TWO_TO_THE_THIRTY_TWO / 30.0d;
    private static final double SMALL_CORRECTION_THRESHOLD = 2.5d * m;

    private byte[] registers;

    Hll16()
    {
      this.registers = new byte[m];
    }

    public void addHash(int hash)
    {
      int bucket = hash >>> 16;
      byte positionOfOne = (byte) (Integer.numberOfLeadingZeros((hash << 16) | 0x8000) + 1);
      if (registers[bucket] < positionOfOne) {
        registers[bucket] = positionOfOne;
      }
    }

    public long cardinality()
    {
      double registerSum = 0.0;
      int zeros = 0;
      for (int i = 0; i < m; i++) {
        registerSum += 1.0 / (1 << registers[i]);
        if (registers[i] == 0) {
          zeros++;
        }
      }

      final double e = alpha * m * m * (1 / registerSum);
      return Math.round(makeCorrection(e, zeros));
    }

    private double makeCorrection(double e, int zeros)
    {
      if (e <= SMALL_CORRECTION_THRESHOLD) {
        return zeros == 0 ? e : m * Math.log(m / (double) zeros);
      }

      if (e > HIGH_CORRECTION_THRESHOLD) {
        return -TWO_TO_THE_THIRTY_TWO * Math.log(1 - e / TWO_TO_THE_THIRTY_TWO);
      }

      return e;
    }

    public long memoryFootprint()
    {
      return m;
    }

    public void merge(Hll16 that)
    {
      for (int i = 0; i < registers.length; i++) {
        if (registers[i] < that.registers[i]) {
          registers[i] = that.registers[i];
        }
      }
    }
  }
}
