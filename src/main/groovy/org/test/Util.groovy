package org.test

import groovy.time.TimeCategory
import groovy.time.TimeDuration
import groovy.util.logging.Slf4j
import okhttp3.Headers
import spark.Request
import spark.Response

import javax.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Slf4j
class Util {

    public static final String FORWARDED_ADDRESS = "X-Forwarded-Address"
    public static final CLAZZ = Class.forName("spark.http.matching.RequestWrapper")
    public static final String MD5 = "MD5"

    static final Closure exchange(Main.State state, Closure toProxiedServerCallable, Closure toIbCallable) {
        return { Request request, Response response ->
            def uri = request.uriWithoutPrefix()
            def uriWithoutPrefix = uri.replace(Main.Config.PREFIX, '')
            def endpoints = state.endpoints.findAll { uriWithoutPrefix.startsWith(it) }

            measure(uri, endpoints.isEmpty(), response) {
                RequestInfo info = (!endpoints.isEmpty() ? toIbCallable.call(request) : toProxiedServerCallable.call(request))
                response.status(info.code)
                response.headers(info.headers)
                response.success = info.success
                response.rawData = info.body
                response.downstream = info.downstream

                return info.body
            }
        }
    }

    static final def measure(String name, boolean toProxiedServer, Response response, Closure closure) {
        def timeStart = new Date()
        def result = closure.call()
        def timeStop = new Date()
        def direction = toProxiedServer ? 'esb' : 'ib'
        TimeDuration duration = TimeCategory.minus(timeStop, timeStart)
        log.info(" < Request was sended to $direction($response.downstream), $name took $duration")

        return result
    }

    static final String chain(Throwable throwable) {
        List<String> result = new ArrayList<String>()
        while (throwable != null && throwable.getMessage() != null) {
            result.add(throwable.getMessage())
            throwable = throwable.getCause()
        }

        result.join(" -> ")
    }

    static final Map<String, String> readHeaders(Request request, String... exclude) {
        def output = [:]
        request.headers().each { name ->
            output[name] = request.headers(name)
        }
        exclude.each { name ->
            output.remove(name)
        }

        output
    }

    static final void addHeaders(Response request, Map<String, String> headers) {
        headers.each { key, value ->
            request.header(key, value)
        }
    }

    static final Map<String, String> headersToMap(Headers headers) {
        def output = [:]
        headers.names().each { name ->
            output[name] = headers.get(name)
        }

        output
    }

    static final String getHeader(Response response, String header) {
        def output = null
        if(CLAZZ.isAssignableFrom(response.getClass())) {
            def wrapper = CLAZZ.cast(response)
            HttpServletResponse servletResponse = (HttpServletResponse) wrapper.delegate.response
            output = servletResponse.getHeader(header)
        }

        output
    }

    static final String inline(String text) {
        text.replaceAll("\n|\r|\t", "")
                .replaceAll("\\s+", " ")
    }

    static final String address(Request request) {
        def forwardedFor = request.headers(FORWARDED_ADDRESS)
        if (!forwardedFor) {
            def servletRequest
            if (CLAZZ.isAssignableFrom(request.getClass())) {
                def requestWrapper = CLAZZ.cast(request)
                servletRequest = requestWrapper.delegate.servletRequest
            } else {
                servletRequest = request.servletRequest
            }

            def port = servletRequest.remotePort
            if(port == 0) {
                port = servletRequest.scheme == "https" ? 443 : 80
            }
            forwardedFor = "$servletRequest.scheme://$servletRequest.remoteAddr:$port"
        }

        forwardedFor
    }

    static final String md5(byte[] bytes) {
        MessageDigest.getInstance(MD5).digest(bytes).encodeHex()
                .toString()
    }

    static String removePrefix(Request request, String prefix) {
        request.uri().replace(prefix, "")
    }

    static String parseBody(Response response) {
        def localResponse = response ?: new Response()
        def output = ""
        if (localResponse) {
            if (localResponse.rawData) {
                output = new String(localResponse.rawData, StandardCharsets.UTF_8)
            } else if (localResponse.body()) {
                output = localResponse.body()
            }
        }

        output
    }

}
