package org.test

import groovy.util.logging.Slf4j
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import spark.Request

import okhttp3.Request.Builder

import java.nio.charset.StandardCharsets
import java.util.regex.Matcher

@Slf4j
class Service {

    public static final String BLANK = "\"id\":\"none\""

    static final def postToSERVER(Main.State state, Request serverRequest) {
        String address = findAddress(serverRequest, state)
        createRequestToSERVER(state, serverRequest, address, { Builder builder, body ->
            builder.post(body)
        })
    }

    static final def getToSERVER(Main.State state, Request serverRequest) {
        String address = findAddress(serverRequest, state)
        createRequestToSERVER(state, serverRequest, address, { Builder builder, body ->
            builder.get()
        })
    }

    static final def postToProxiedServer(Main.State state, Request serverRequest) {
        createRequestToProxiedServer(state, serverRequest, { Builder builder, body ->
            builder.post(body)
        })
    }

    static final def getToProxiedServer(Main.State state, Request serverRequest) {
        createRequestToProxiedServer(state, serverRequest, { Builder builder, body ->
            builder.get()
        })
    }

    static final def createRequestToSERVER(Main.State state, Request serverRequest, String address, Closure builder) {
        Response response = executeRequest(state, serverRequest, address, builder)
        def result = createResponseInfo(serverRequest, response, Main.Config.NONE, address)

        return result
    }

    static final def createRequestToProxiedServer(Main.State state, Request serverRequest, Closure builder) {
        Response response = executeRequest(state, serverRequest, Main.Config.SERVER_ADDRESS, builder)
        String id = findId(response, serverRequest)
        def result = createResponseInfo(serverRequest, response, id, Main.Config.SERVER_ADDRESS)
        def clone = result.clone()

        Cache.save(state, serverRequest.uriWithoutPrefix(), clone.zip())

        return result
    }

    /* ====================== */

    private static String findAddress(Request serverRequest, Main.State state) {
        Matcher matcher
        String id
        def body = (serverRequest.body() ?: BLANK).inline()

        if ((matcher = body =~ /"id"\s?:\s?"(\d+)"/)) {
            id = matcher.group(1)
        }

        if (!body || BLANK == body && "POST".equalsIgnoreCase(serverRequest.requestMethod())) {
            throw new IllegalStateException("Request body is missing")
        }

        RequestInfo info = Cache.load(state, serverRequest.uriWithoutPrefix(), id)
        def address
        if (!info) {
            log.warn(" > Got to_proxy request ${serverRequest.uriWithoutPrefix()} without information about id=$id, redirecting to default server: $Main.Config.DEFAULT_ADDRESS")
            address = Main.Config.DEFAULT_ADDRESS
        } else {
            address = info.address
        }

        "${address}${Main.Config.PREFIX}"
    }

    private final static Object createResponseInfo(Request serverRequest, Response response, String id, String address) {
        def body = response.body().bytes()
        new RequestInfo.RequestBuilder()
                .code(response.code())
                .headers(response.headers().toMap())
                .body(body)
                .hash(body.md5())
                .success(response.isSuccessful())
                .id(id)
                .address(serverRequest.address())
                .downstream(address)
                .build()
    }

    private final static String findId(Response response, Request serverRequest) {
        def id = Main.Config.NONE
        def body = (serverRequest.body() ?: BLANK).inline()

        if (response.isSuccessful()) {
            Matcher matcher
            if ((matcher = body =~ /"id"\s?:\s?"(\d+)"/)) {
                id = matcher.group(1)
            }
        }

        def isAwaitedRequest = body.matches(/(?i).*to_proxy.*/) || body.matches(/(?i).*id.*/)
        if (isAwaitedRequest && Main.Config.NONE == id) {
            log.warn(" < Got corrupted request ${serverRequest.uriWithoutPrefix()}, can find information about to_proxy")
        }
        id
    }

    private final static Response executeRequest(Main.State state,
                                                 Request serverRequest,
                                                 String address,
                                                 Closure builder) {
        def contentType = serverRequest.headers(Main.Config.CONTENT_TYPE) ?: Main.Config.JSON
        def bytes = serverRequest.body().getBytes(StandardCharsets.UTF_8)
        def body = RequestBody.create(MediaType.parse(contentType), bytes)

        def requestBuilder = new Builder()
                .url("${address}${serverRequest.uriWithoutPrefix()}")
                .headers(Headers.of(serverRequest.headersMap()))

        builder.call(requestBuilder, body)

        try {
            state.client.newCall(requestBuilder.build())
                    .execute()
        } catch (Exception e) {
            def message = " < Can't execute request ${address}${serverRequest.uriWithoutPrefix()}"
            log.error("ERROR: $message, cause: ${Util.chain(e)}")
            throw new RuntimeException(message, e)
        }
    }

}
