package org.test

import groovy.transform.AutoClone
import groovy.transform.CompileStatic
import groovy.transform.builder.Builder
import groovy.transform.builder.ExternalStrategy

@AutoClone
@CompileStatic
class RequestInfo {

    Integer code
    Map<String, String> headers
    byte[] body
    String hash
    Boolean success
    String id
    String address
    String downstream

    @Builder(builderStrategy = ExternalStrategy, forClass = RequestInfo.class)
    static class RequestBuilder {}

    RequestInfo zip() {
        this.code = null
        this.headers = null
        this.body = null
        this.success = null

        return this
    }

}
