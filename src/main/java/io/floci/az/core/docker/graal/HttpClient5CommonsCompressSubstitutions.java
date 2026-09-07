package io.floci.az.core.docker.graal;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.apache.hc.client5.http.entity.compress.ContentCoding;
import org.apache.hc.core5.io.IOFunction;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Keeps Apache HttpClient 5.6+ from wiring commons-compress into its content-coding registry in the
 * native image.
 *
 * <p>docker-java talks to the daemon through httpclient5, and since 5.6 the client registers every
 * commons-compress codec (xz, zstd, brotli, lzma, ...) as soon as commons-compress is on the classpath.
 * floci-az has commons-compress for tar streams only, so those codec classes become reachable for
 * GraalVM's analysis, which links them at build time and fails on their optional native libraries
 * ({@code org.tukaani.xz}, {@code com.github.luben.zstd}, ...). The Docker daemon never compresses
 * responses with any of them, so the substitutions below report commons-compress as absent and the
 * registry falls back to the JDK gzip/deflate codecs, exactly as on a classpath without commons-compress.
 */
final class HttpClient5CommonsCompressSubstitutions {

    private HttpClient5CommonsCompressSubstitutions() {
    }

    @TargetClass(className = "org.apache.hc.client5.http.entity.compress.CommonsCompressRuntime")
    static final class Target_CommonsCompressRuntime {

        @Substitute
        static boolean available() {
            return false;
        }
    }

    @TargetClass(className = "org.apache.hc.client5.http.entity.compress.CommonsCompressCodecFactory")
    static final class Target_CommonsCompressCodecFactory {

        @Substitute
        static IOFunction<InputStream, InputStream> decoder(String name) {
            return null;
        }

        @Substitute
        static IOFunction<OutputStream, OutputStream> encoder(String name) {
            return null;
        }

        @Substitute
        static boolean runtimeAvailable(ContentCoding coding) {
            return false;
        }
    }
}
