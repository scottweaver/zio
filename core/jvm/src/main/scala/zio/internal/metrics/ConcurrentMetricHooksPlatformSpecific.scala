/*
 * Copyright 2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zio.internal.metrics

import zio._
import zio.metrics._

import java.util.concurrent.atomic._
import java.util.concurrent.ConcurrentHashMap

class ConcurrentMetricHooksPlatformSpecific extends ConcurrentMetricHooks {
  def counter(key: MetricKey.Counter): MetricHook.Counter = {
    val adder = new DoubleAdder

    MetricHook(v => adder.add(v), () => MetricState.Counter(adder.sum()))
  }

  def gauge(key: MetricKey.Gauge, startAt: Double): MetricHook.Gauge = {
    val ref: AtomicReference[Double] = new AtomicReference[Double](startAt)

    MetricHook(v => ref.set(v), () => MetricState.Gauge(ref.get()))
  }

  def histogram(key: MetricKey.Histogram): MetricHook.Histogram = {
    val bounds     = key.keyType.boundaries.values
    val values     = new AtomicLongArray(bounds.length + 1)
    val boundaries = Array.ofDim[Double](bounds.length)
    val count      = new LongAdder
    val sum        = new DoubleAdder
    val size       = bounds.length
    val minMax     = new AtomicMinMax()

    bounds.sorted.zipWithIndex.foreach { case (n, i) => boundaries(i) = n }

    // Insert the value into the right bucket with a binary search
    val update = (value: Double) => {
      var from = 0
      var to   = size
      while (from != to) {
        val mid      = from + (to - from) / 2
        val boundary = boundaries(mid)
        if (value <= boundary) to = mid else from = mid

        // The special case when to / from have a distance of one
        if (to == from + 1) {
          if (value <= boundaries(from)) to = from else from = to
        }
      }
      values.getAndIncrement(from)
      count.increment()
      sum.add(value)
      minMax.update(value)
      ()
    }

    def getBuckets(): Chunk[(Double, Long)] = {
      val builder   = ChunkBuilder.make[(Double, Long)]()
      var i         = 0
      var cumulated = 0L
      while (i != size) {
        val boundary = boundaries(i)
        val value    = values.get(i)
        cumulated += value
        builder += boundary -> cumulated
        i += 1
      }
      builder.result()
    }

    MetricHook(
      update,
      { () =>
        val (min, max) = minMax.get()
        MetricState.Histogram(getBuckets(), count.longValue(), min, max, sum.doubleValue())
      }
    )
  }

  def summary(key: MetricKey.Summary): MetricHook.Summary = {
    import key.keyType.{maxSize, maxAge, error, quantiles}

    val values = new AtomicReferenceArray[(Double, java.time.Instant)](maxSize)
    val head   = new AtomicInteger(0)
    val count  = new LongAdder
    val sum    = new DoubleAdder
    val minMax = new AtomicMinMax()

    val sortedQuantiles: Chunk[Double] = quantiles.sorted(DoubleOrdering)

    def getCount(): Long =
      count.longValue

    def getSum(): Double =
      sum.doubleValue

    // Just before the Snapshot we filter out all values older than maxAge
    def snapshot(now: java.time.Instant): Chunk[(Double, Option[Double])] = {
      val builder = ChunkBuilder.make[Double]()

      // If the buffer is not full yet it contains valid items at the 0..last indices
      // and null values at the rest of the positions.
      // If the buffer is already full then all elements contains a valid measurement with timestamp.
      // At any given point in time we can enumerate all the non-null elements in the buffer and filter
      // them by timestamp to get a valid view of a time window.
      // The order does not matter because it gets sorted before passing to calculateQuantiles.

      for (idx <- 0 until maxSize) {
        val item = values.get(idx)
        if (item != null) {
          val (v, t) = item
          val age    = Duration.fromInterval(t, now)
          if (!age.isNegative && age.compareTo(maxAge) <= 0) {
            builder += v
          }
        }
      }

      zio.internal.metrics.calculateQuantiles(error, sortedQuantiles, builder.result().sorted(DoubleOrdering))
    }

    // Assuming that the instant of observed values is continuously increasing
    // While Observing we cut off the first sample if we have already maxSize samples
    def observe(tuple: (Double, java.time.Instant)): Unit = {
      if (maxSize > 0) {
        val target = head.incrementAndGet() % maxSize
        values.set(target, tuple)
      }

      val value = tuple._1
      count.increment()
      sum.add(value)
      minMax.update(value)
      ()
    }

    MetricHook(
      observe(_),
      { () =>
        val (min, max) = minMax.get()
        MetricState.Summary(
          error,
          snapshot(java.time.Instant.now()),
          getCount(),
          min,
          max,
          getSum()
        )
      }
    )
  }

  def frequency(key: MetricKey.Frequency): MetricHook.Frequency = {
    val count  = new LongAdder
    val values = new ConcurrentHashMap[String, LongAdder]

    val update = (word: String) => {
      count.increment()
      var slot = values.get(word)
      if (slot eq null) {
        val cnt = new LongAdder
        values.putIfAbsent(word, cnt)
        slot = values.get(word)
      }
      slot.increment()
    }

    def snapshot(): Map[String, Long] = {
      val builder = scala.collection.mutable.Map[String, Long]()
      val it      = values.entrySet().iterator()
      while (it.hasNext()) {
        val e = it.next()
        builder.update(e.getKey(), e.getValue().longValue())
      }

      builder.toMap
    }

    MetricHook(update, () => MetricState.Frequency(snapshot()))
  }

  private final class AtomicMinMax {
    private val minMax = new AtomicReference(Option.empty[(Double, Double)])

    def get(): (Double, Double) = minMax.get().getOrElse(0.0 -> 0.0)

    def update(value: Double): Unit =
      minMax.updateAndGet {
        case minMax @ Some((min, max)) =>
          if (value < min) Some((value, max))
          else if (value > max) Some((min, value))
          else minMax
        case None => Some((value, value))
      }
  }
}
