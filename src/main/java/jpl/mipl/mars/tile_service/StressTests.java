package jpl.mipl.mars.tile_service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;

/**
 * @author Marsette Vona
 */
public class StressTests {

    public static final int DEF_TEST_THREADS = 64;
    public static final int DEF_TEST_PRNG_MERCY_MS = 0;
    public static final int DEF_TEST_S3_MERCY_MS = 0;

    public static void testPRNG(int threads, final int mercyMS, final Logger log) {

        //on Linux systems may want to compare
        //-Djava.security.egd=file:/dev/urandom
        //-Djava.security.egd=file:/dev/random
        //-Djava.security.egd=file:/dev/./urandom

        //also see in $JAVA_HOME/conf/security/java.security
        //securerandom.source
        //securerandom.strongAlgorithms

        //https://stackoverflow.com/a/59097932/4970315
        //https://issues.jenkins.io/browse/JENKINS-20108?focusedCommentId=213578

        //https://major.io/2007/07/01/check-available-entropy-in-linux
        //cat /proc/sys/kernel/random/entropy_avail
        //> anything less than 100-200, you have a problem
        //> try installing rng-tools
        
        final int bytesPerCall = 10;
        final var totalCalls = new AtomicLong();
        final var totalErrors = new AtomicLong();

        Utils.spewJVM(log);

        log.info("testing PRNG, {} threads, {} bytes per call per thread, {}ms between calls",
                 threads, bytesPerCall, mercyMS);

        runThreadedTest(threads, (threadNumber) -> {
                while (!Thread.currentThread().interrupted()) {
                    totalCalls.incrementAndGet();
                    try {
                        var prng = Utils.getBestPRNG();
                        var buf = new byte[bytesPerCall];
                        prng.nextBytes(buf);
                        Thread.sleep(mercyMS);
                    } catch (InterruptedException ex) {
                        break;
                    } catch (Exception ex) {
                        totalErrors.incrementAndGet();
                        log.error("error in thread " + threadNumber, ex);
                    }
                }
            });

        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                break;
            }
            long tc = totalCalls.get();
            long now = System.currentTimeMillis();
            double sec = 0.001 * (now - start);
            log.info("created {} SecureRandom ({}/sec), read {} random bytes ({}/sec), {} errors",
                     Utils.kmg(tc), Utils.kmg(tc / sec),
                     Utils.kmg(tc * bytesPerCall), Utils.kmg(tc * bytesPerCall / sec), Utils.kmg(totalErrors.get()));
        }
    }

    public static void testS3(int threads, final int mercyMS, final String url, final String awsProfile,
                              final String awsRegion, final Logger log) {

        //e.g. s3://m20-dev-ids-tile-service-test/foo

        final int hitsPerIteration = 10;
        final var totalIterations = new AtomicLong();
        final var totalHits = new AtomicLong();
        final var totalExists = new AtomicLong();
        final var totalNoExists = new AtomicLong();
        final var totalErrors = new AtomicLong();

        Utils.spewJVM(log);

        log.info("testing S3 access to {}, {} threads, {} hits per iteration per thread, {}ms between hits",
                 url, threads, hitsPerIteration, mercyMS);

        final var ss3 = new S3Helper(awsProfile, awsRegion); //null ok

        runThreadedTest(threads, (threadNumber) -> {
                while (!Thread.currentThread().interrupted()) {
                    totalIterations.incrementAndGet();
                    var ts3 = new S3Helper(awsProfile, awsRegion); //null ok
                    for (int i = 0; i < hitsPerIteration; i++) {
                        try {
                            totalHits.incrementAndGet();
                            if (ts3.doesObjectExist(url)) {
                                totalExists.incrementAndGet();
                            } else {
                                totalNoExists.incrementAndGet();
                            }
                            if (ss3.doesObjectExist(url)) {
                                totalExists.incrementAndGet();
                            } else {
                                totalNoExists.incrementAndGet();
                            }
                            Thread.sleep(mercyMS);
                        } catch (InterruptedException ex) {
                            break;
                        } catch (Exception ex) {
                            totalErrors.incrementAndGet();
                            log.error("error in thread " + threadNumber, ex);
                        }
                    }
                }
            });
        
        long start = System.currentTimeMillis();
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                break;
            }
            long ti = totalIterations.get();
            long th = totalHits.get();
            long now = System.currentTimeMillis();
            double sec = 0.001 * (now - start);
            log.info("created {} S3Helpers ({}/sec), {} S3 API hits ({}/sec), {} exists, {} !exists, {} errors",
                     Utils.kmg(ti), Utils.kmg(ti / sec), Utils.kmg(th), Utils.kmg(th / sec),
                     Utils.kmg(totalExists.get()), Utils.kmg(totalNoExists.get()), Utils.kmg(totalErrors.get()));
        }
    }

    private static void runThreadedTest(int numThreads, final Consumer<Integer> func) {
        for (int i = 0; i < numThreads; i++) {
            final int fi = i;
            var thread = new Thread("test thread " + i) {
                    public void run() {
                        func.accept(fi);
                    }
                };
            thread.setDaemon(true);
            thread.start();
        }
    }
}
