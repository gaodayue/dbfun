package io.dbfun.sketch;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

public class UniqCounter implements CardinalityEstimator<UniqCounter>
{
  private static final short INITIAL_SIZE_DEGREE = 4;
  private static final short MAX_SIZE_DEGREE = 17;
  private static final int MAX_SIZE = 1 << (MAX_SIZE_DEGREE - 1);

  // The number of least significant bits used for thinning.
  // The remaining high-order bits are used to determine the position in the hash table.
  // (high-order bits are taken because the younger bits will be constant after dropping some of the values)
  private static final short BITS_FOR_SKIP = 32 - MAX_SIZE_DEGREE;

  private static final HashFunction HASH_FUNCTION = Hashing.murmur3_128();

  private int count;
  private int[] buf; // TODO use Unsafe byte array?
  private short sizeDegree;
  private short skipDegree;
  private boolean hasZero;

  public UniqCounter()
  {
    this.sizeDegree = INITIAL_SIZE_DEGREE;
    this.buf = new int[1 << sizeDegree];
  }

  // MurmurHash3 64-bit finalizer
  private static long intHash64(long k)
  {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return k;
  }

  private static int crc32(long x)
  {
    final ByteBuffer scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    scratch.putLong(x);
    Checksum checksum = new CRC32();
    checksum.update(scratch.array(), 0, Long.BYTES);
    return (int) checksum.getValue();
  }

  private boolean good(int hash)
  { // hash can be divided by 2^skipDegree
    return hash == ((hash >> skipDegree) << skipDegree);
  }

  // TODO all code use "index & mask()" to get index
  // why not just using index % buf.length?
  // is there any performance different?
  private int mask()
  {
    return (1 << sizeDegree) - 1;
  }

  private int place(int hash)
  {
    return (hash >>> BITS_FOR_SKIP) & mask();
  }

  private void add32BitsHash(int hash)
  {
    if (!good(hash)) {
      return;
    }

    if (hash == 0) {
      if (!hasZero) {
        count += 1;
      }
      hasZero = true;
      return;
    }

    int index = place(hash);
    // linear probe until search hit or miss
    while (buf[index] != 0 && buf[index] != hash) {
      index = (index + 1) & mask();
    }

    if (buf[index] == hash) {
      return; // search hit
    }
    // search miss
    buf[index] = hash;
    count++;

    shrinkIfNeeded();
  }

  // If the hash table is half-full, then do resize.
  // If there are too many items, then throw half the pieces until they are small enough.
  private void shrinkIfNeeded()
  {
    if (count > (1 << (sizeDegree - 1))) {
      if (count > MAX_SIZE) {
        while (count > MAX_SIZE) {
          skipDegree++;
          removeAccordingToSkipDegree();
        }
      } else {
        resize();
      }
    }
  }

  private void removeAccordingToSkipDegree()
  {
    // remove all elements that is not divided by 2^skipDegree
    for (int i = 0; i < buf.length; i++) {
      if (buf[i] != 0 && !good(buf[i])) {
        buf[i] = 0;
        count--;
      }
    }
    // reinsert all remaining elements
    for (int i = 0; i < buf.length; i++) {
      int hash = buf[i];
      if (hash != 0) {
        int index = place(hash);
        if (i == index) {
          continue;
        }
        buf[i] = 0;
        while (buf[index] != 0) {
          index = (index + 1) & mask();
        }
        buf[index] = hash;
      }
    }
  }

  private void resize()
  {
    sizeDegree++;
    int[] newBuf = new int[1 << sizeDegree];

    // add values to newBuf
    for (int i = 0; i < buf.length; i++) {
      int hash = buf[i];
      if (hash == 0) {
        continue;
      }
      int index = place(hash);
      while (newBuf[index] != 0) {
        index = (index + 1) & mask();
      }
      newBuf[index] = hash;
    }

    this.buf = newBuf;
  }

  @Override
  public void add(byte[] value)
  {
    add32BitsHash(HASH_FUNCTION.hashBytes(value).asInt());
  }

  @Override
  public void add(long value)
  {
    add32BitsHash((int) intHash64(value));
  }

  @Override
  public void merge(UniqCounter that)
  {
    if (skipDegree < that.skipDegree) {
      skipDegree = that.skipDegree;
      removeAccordingToSkipDegree();
    }

    if (!hasZero && that.hasZero) {
      hasZero = true;
      count++;
      shrinkIfNeeded();
    }

    for (int i = 0; i < that.buf.length; i++) {
      if (that.buf[i] != 0) {
        add32BitsHash(that.buf[i]);
      }
    }
  }

  @Override
  public long cardinality()
  {
    if (skipDegree == 0) {
      return count;
    }
    long res = count * (1L << skipDegree);
    // Pseudo-random remainder - in order to be not visible,
    // that the number is divided by the power of two
    res += crc32(count) & ((1L << skipDegree) - 1);

    // Correction of a systematic error due to collisions during hashing in UInt32.
    // `fixedRes(res)` formula
    // with how many different elements of fixed_res,
    // when randomly scattered across 2^32 buckets,
    // filled buckets with average of res is obtained.
    long p32 = 1L << 32;
    return Math.round(p32 * (Math.log(p32) - Math.log(p32 - res)));
  }

  @Override
  public long memoryFootprint()
  {
    return 4 * (1 << sizeDegree);
  }

  @Override
  public String name()
  {
    return "uniq";
  }
}
