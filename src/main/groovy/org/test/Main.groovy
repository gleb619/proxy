package org.test

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.codehaus.groovy.runtime.StackTraceUtils
import spark.Request
import spark.Response

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import static org.apache.commons.io.FileUtils.byteCountToDisplaySize
import static spark.Spark.*

@Slf4j
class Main {

    static final void main(String[] args) {
        State state = new State()
        try {
            log.info("Starting app at port: $Config.PORT for $Config.SERVER_ADDRESS")
            log.info("Addind exclusive backward redirect with prefix $Config.PREFIX for next endpoints:\n\t${Config.ENDPOINTS.replaceAll(';', '\n\t')}")
            log.info("All unknown ids will be redirected to $Config.DEFAULT_ADDRESS")
            log.info("Working behind proxy with prefix $Config.SELF_PREFIX")
            init(state)
            createServer(state)
        } catch (Throwable e) {
            log.error("ERROR", e)
            System.exit(-1)
        }
    }

    private static final void init(State state) {
        Response.metaClass.success = false
        Response.metaClass.rawData = (new byte[0])
        Response.metaClass.headers = { headers -> Util.addHeaders(delegate, headers) }
        Response.metaClass.downstream = ""
        Response.metaClass.clean = {
            delegate.downstream = null
            delegate.rawData = null
            delegate.success = null
        }
        Response.metaClass.header = { header -> Util.getHeader(delegate, header) }
        Request.metaClass.headersMap = { -> Util.readHeaders(delegate) }
        Request.metaClass.address = { -> Util.address(delegate) }
        Request.metaClass.uriWithoutPrefix = { -> Util.removePrefix(delegate, Config.SELF_PREFIX) }
        String.metaClass.inline = { -> Util.inline(delegate) }
        byte[].metaClass.md5 = { -> Util.md5(delegate) }
        Headers.metaClass.toMap = { -> Util.headersToMap(delegate) }

        state.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.MINUTES)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES)
                .build()
        state.cache = CacheBuilder.newBuilder()
                .concurrencyLevel(Runtime.getRuntime().availableProcessors())
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .build()
        state.endpoints = Config.ENDPOINTS.split(";")
    }

    private static final void createServer(State state) {
        port(Config.PORT)

        before("/*", { request, response ->
            def body = request.body()
            log.info(" >> Received api call from {} path {}, headers: {}, body({}): {}",
                    request.address(), request.uriWithoutPrefix(), request.headersMap(),
                    byteCountToDisplaySize(body?.size()), body?.inline())
        })
        after("/*", { request, response ->
            def body = Util.parseBody(response)
            log.info(" << Return data to {}, success: {}, status: {}, body({}): {}",
                    request.address(), response.success, response.status(),
                    byteCountToDisplaySize(body?.size()), body?.inline())
            response.clean()
        })

        get("/*", Util.exchange(state,
                { request ->
                    Service.getToProxiedServer(state, request)
                },
                { request ->
                    Service.getToSERVER(state, request)
                }
        ))
        post("/*", Util.exchange(state,
                { request ->
                    Service.postToProxiedServer(state, request)
                },
                { request ->
                    Service.postToSERVER(state, request)
                }
        ))

        exception(Throwable.class, { e, request, response ->
            def t = StackTraceUtils.sanitize(e)
            log.error(" < ERROR HANDLER", t)

            def output = [:]
            output.uri = request.uriWithoutPrefix()
            output.request = request.body()
            output.exception = Util.chain(e)

            if(!response.header(Config.CONTENT_TYPE)) {
                response.header(Config.CONTENT_TYPE, Config.JSON)
            }
            response.status(500)
            response.body(new JsonBuilder(output).toString())
        })
    }

    @CompileStatic
    static final class State {

        OkHttpClient client
        Cache<String, Object> cache
        List<String> endpoints

    }

    @CompileStatic
    static final class Config {

        public static final String CONTENT_TYPE = "Content-Type"
        public static final String JSON = 'application/json'
        public static final String NONE = "none"

        public static final Integer PORT = Integer.valueOf(System.getenv("PORT") ?: "8090")
        public static final String SERVER_ADDRESS = System.getenv("SERVER_ADDRESS") ?: "http://localhost:8080"
        public static final String DEFAULT_ADDRESS = System.getenv("DEFAULT_ADDRESS") ?: "http://localhost:8070"
        public static final String ENDPOINTS = System.getenv("ENDPOINTS") ?: "/v1"
        public static final String SELF_PREFIX = System.getenv("SELF_PREFIX") ?: "/proxy"
        public static final String PREFIX = System.getenv("PREFIX") ?: "/api"

    }

}
