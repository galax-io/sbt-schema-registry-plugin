package org.galaxio.avro

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** Wraps a live [[SchemaRegistryClient]] so selected methods park on a shared barrier, letting an integration test prove that
  * downloads actually run in parallel rather than just asserting result counts (which a sequential run satisfies identically).
  *
  * Each gated call increments a live-count, records the high-water mark, counts down a latch sized to `parallelism`, then waits
  * for the latch to reach zero. If execution were sequential the first gated call would never see the latch reach zero and
  * would time out, so [[maxConcurrent]] stays below `parallelism` and the test fails.
  */
object ConcurrencyProbe {

  final case class Probe(client: SchemaRegistryClient, maxConcurrent: AtomicInteger)

  /** @param delegate
    *   the real client to forward to
    * @param parallelism
    *   expected simultaneous in-flight gated calls — i.e. the latch size. It MUST equal the number of gated calls the test
    *   triggers concurrently. If fewer gated calls occur, each parks for up to 10s before `await` returns false and
    *   [[Probe.maxConcurrent]] stays below `parallelism` — surfacing as an assertion failure, never an indefinite hang.
    * @param gatedMethods
    *   method names that should park on the barrier (e.g. "getLatestSchemaMetadata")
    */
  def gating(delegate: SchemaRegistryClient, parallelism: Int, gatedMethods: Set[String]): Probe = {
    val latch       = new CountDownLatch(parallelism)
    val current     = new AtomicInteger(0)
    val maxObserved = new AtomicInteger(0)

    val handler = new InvocationHandler {
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef = {
        if (gatedMethods.contains(method.getName)) {
          val running = current.incrementAndGet()
          maxObserved.getAndUpdate(prev => math.max(prev, running))
          latch.countDown()
          latch.await(10, TimeUnit.SECONDS)
          current.decrementAndGet()
        }
        method.invoke(delegate, (if (args == null) Array.empty[AnyRef] else args): _*)
      }
    }

    val proxy = Proxy
      .newProxyInstance(getClass.getClassLoader, Array(classOf[SchemaRegistryClient]), handler)
      .asInstanceOf[SchemaRegistryClient]

    Probe(proxy, maxObserved)
  }
}
