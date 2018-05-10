package io.dbfun.sketch;

public interface CardinalityEstimator<T>
{
  void add(byte[] value);
  void add(long value);

  void merge(T that);
  long cardinality();
  long memoryFootprint();

  String name();
}
