package io.dbfun.sketch;

import com.google.common.base.Joiner;
import com.google.common.base.Supplier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CardinalityEstimatorBenchmark
{
  private long ingest(
      Supplier<CardinalityEstimator> estimatorSupplier,
      final int card,
      final int warmUps,
      final int runs
  )
  {
    // warm ups
    for (int i = 0; i < warmUps; i++) {
      CardinalityEstimator estimator = estimatorSupplier.get();
      for (int c = 0; c < card; c++) {
        estimator.add(c);
      }
    }

    // actual runs
    long totalNanos = 0;
    for (int i = 0; i < runs; i++) {
      CardinalityEstimator estimator = estimatorSupplier.get();
      long start = System.nanoTime();
      for (int c = 0; c < card; c++) {
        estimator.add(c);
      }
      totalNanos += System.nanoTime() - start;
    }

    return (totalNanos / runs) / card;
  }

  public long[][] benchmarkAdd(long[] cards, String... estimatorNames)
  {
    long[][] result = new long[cards.length][]; // result[i] = [c, estimator1, estimator2, ...]
    for (int i = 0; i < result.length; i++) {
      result[i] = new long[1 + estimatorNames.length];
      result[i][0] = cards[i];
    }

    for (int i = 0; i < estimatorNames.length; i++) {
      final String name = estimatorNames[i];
      for (int j = 0; j < result.length; j++) {
        final int card = (int) result[j][0];
        System.out.format("Test estimator %s card %,d\n", name, card);
        result[j][i + 1] = ingest(CardinalityEstimators.lazyGet(name), card, 10, 20);
      }
    }

    return result;
  }

  public static void main(String[] args) throws IOException
  {
    // args: <counterName>..
    if (args.length < 1) {
      System.err.println("Arguments: <counterName>..");
      System.exit(1);
    }

    CardinalityEstimatorBenchmark benchmark = new CardinalityEstimatorBenchmark();
    final long[] cards = {100, 1000, 10000, 100000, 1000000, 10000000};
    long[][] result = benchmark.benchmarkAdd(cards, args);

    Path outFile = Paths.get("speed_" + Joiner.on("_").join(args) + ".tsv");
    System.out.println("Writing results to " + outFile);
    try (BufferedWriter writer = Files.newBufferedWriter(outFile)) {
      // header: card counter1 counter2
      writer.write("Card");
      for (String name : args) {
        writer.write("\t");
        writer.write(name);
      }
      writer.write("\n");

      // content
      for (long[] row : result) {
        StringBuilder sb = new StringBuilder(String.valueOf(row[0]));
        for (int i = 1; i < row.length; i++) {
          sb.append("\t").append(String.valueOf(row[i]));
        }
        sb.append("\n");

        writer.write(sb.toString());
      }
    }
  }
}
