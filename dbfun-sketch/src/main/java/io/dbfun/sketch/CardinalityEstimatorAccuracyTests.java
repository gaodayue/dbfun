package io.dbfun.sketch;

import com.google.common.base.Supplier;
import com.google.common.hash.Hashing;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class CardinalityEstimatorAccuracyTests
{
  private static final int DEFAULT_PRECISION = 14;

  /**
   * Run estimation on random generated data set of cardinality {1*fromCard, 2*fromCard, 3*fromCard, .., toCard}.
   * `numRuns` experiments will be run for each cardinality.
   *
   * @param estimatorSupplier
   * @param fromCard
   * @param toCard
   * @param numRuns
   *
   * @return errors for each experiment, errors[i][j] = errors of cardinality i of the j-th run.
   */
  private static double[][] testDifferentCardinalities(
      Supplier<CardinalityEstimator> estimatorSupplier,
      final int fromCard,
      final int toCard,
      final int numRuns
  )
  {
    assert toCard > fromCard && toCard % fromCard == 0;
    final int numCard = toCard / fromCard;

    double[][] errors = new double[numCard][];
    for (int i = 0; i < numCard; i++) {
      errors[i] = new double[numRuns];
    }

    if (toCard <= 100_000) {
      // for low cardinality tests, generate random integer set

      ThreadLocalRandom random = ThreadLocalRandom.current();
      for (int run = 0; run < numRuns; run++) {
        // reset estimator and set at the begining of each run
        CardinalityEstimator estimator = estimatorSupplier.get();
        Set<Long> set = new HashSet<>();
        final long start = System.currentTimeMillis();

        long value;
        boolean shouldRetry;

        // estimate cardinality of 1*startCard, 2*startCard, 3*startCard, .., maxCard
        for (int card = 1; card <= toCard; card++) {
          do {
            value = random.nextLong();
            shouldRetry = !set.add(value);
          } while (shouldRetry);

          estimator.add(value);

          if (card % fromCard == 0) {
            double est = estimator.cardinality();
            double error = 100.0 * (est - card) / card;
            errors[card / fromCard - 1][run] = Math.abs(error);
          }
        }
        System.out.printf("Finish run #%d in %,d ms%n", run, System.currentTimeMillis() - start);
      }

    } else {
      // for high cardinality tests, we can't generate data using set due to limited memory,
      // use a random id generator instead

      for (int run = 0; run < numRuns; run++) {
        FastRandomIdGenerator randomIdGenerator = new FastRandomIdGenerator();
        CardinalityEstimator estimator = estimatorSupplier.get();
        final long start = System.currentTimeMillis();

        // estimate cardinality of 1*startCard, 2*startCard, 3*startCard, .., maxCard
        for (int card = 1; card <= toCard; card++) {
          estimator.add(randomIdGenerator.generate());

          if (card % fromCard == 0) {
            double est = estimator.cardinality();
            double error = 100.0 * (est - card) / card;
            errors[card / fromCard - 1][run] = Math.abs(error);
          }
        }
        System.out.printf("Finish run #%d in %,d ms%n", run, System.currentTimeMillis() - start);
      }
    }

    return errors;
  }

  public static void main(String[] args) throws IOException
  {
    if (args.length < 4 || args.length > 5) {
      System.err.println("Arguments: <estimator> <from> <to> <runs> [<outFile>]");
      System.exit(1);
    }

    Supplier<CardinalityEstimator> estimatorSupplier = CardinalityEstimators.lazyGet(args[0]);
    final int fromCard = Integer.parseInt(args[1]);
    final int toCard = Integer.parseInt(args[2]);
    final int numRuns = Integer.parseInt(args[3]);
    if (toCard <= fromCard || toCard % fromCard != 0) {
      throw new IllegalArgumentException("illegal from \"" + fromCard + "\" and to \"" + toCard + "\"");
    }

    Path outFile;
    if (args.length == 5) {
      outFile = Paths.get(args[4]);
    } else {
      outFile = Paths.get(String.format("%s_%d_%d_%d.tsv", args[0], fromCard, toCard, numRuns));
    }

    final double[][] errors = testDifferentCardinalities(estimatorSupplier, fromCard, toCard, numRuns);
    // compute min, 50%, max error for each cardinality
    List<OneResult> results = new ArrayList<>(errors.length);
    for (int i = 0; i < errors.length; i++) {
      long cardinality = (i + 1) * fromCard;
      results.add(OneResult.from(cardinality, errors[i]));
    }

    System.out.println("Writing results to " + outFile);
    try (BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
      writer.write("Card\tMin\tMedian\tMax\n");
      for (OneResult result : results) {
        writer.write(String.format(
            "%d\t%.3f\t%.3f\t%.3f\n",
            result.cardinality,
            result.minError,
            result.medianError,
            result.maxError
        ));
      }
    }
  }

  private static Supplier<CardinalityEstimator> getEstimator(String name)
  {
    if (name.startsWith("hllraw")) {
      String pStr = name.substring("hllraw".length());
      int precision = pStr.isEmpty() ? DEFAULT_PRECISION : Integer.parseInt(pStr);
      return () -> new HllRaw(precision, Hashing.murmur3_128());
    }
    if (name.startsWith("hllnobias")) {
      String pStr = name.substring("hllnobias".length());
      int precision = pStr.isEmpty() ? DEFAULT_PRECISION : Integer.parseInt(pStr);
      return () -> new Hll64WithBiasCorrection(precision, Hashing.murmur3_128());
    }
    if (name.equals("uniq")) {
      return UniqCounter::new;
    }
    throw new IllegalArgumentException("Unknown estimator : " + name);
  }

  static class OneResult
  {
    long cardinality;
    double minError;
    double medianError;
    double maxError;

    public OneResult(long cardinality, double minError, double medianError, double maxError)
    {
      this.cardinality = cardinality;
      this.minError = minError;
      this.medianError = medianError;
      this.maxError = maxError;
    }

    static OneResult from(long cardinality, double[] errors)
    {
      Arrays.sort(errors);
      return new OneResult(
          cardinality,
          errors[0],
          errors[errors.length / 2],
          errors[errors.length - 1]
      );
    }
  }
}
