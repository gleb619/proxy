package org.test

import groovy.util.logging.Slf4j

@Slf4j
class Cache {

    static final def save(Main.State state, String uri, RequestInfo info) {
        if (Main.Config.NONE == info.id) return
        log.info(" < Saving request ${uri} to cache, id=$info.id")
        def previous = state.cache.getIfPresent(info.id)
        if (Objects.nonNull(previous)) {
            if (previous.hash != info.hash) {
                log.warn(" < id {} already stored in cache: previous={}, current={}",
                        info.id,
                        previous.hash,
                        info.hash)
            }

            state.cache.invalidate(info.id)
        }

        state.cache.put(info.id, info)
    }

    static final RequestInfo load(Main.State state, String uri, String id) {
        if(!id || Main.Config.NONE == id) return null
        def output = state.cache.getIfPresent(id)
        def message = " > Loading request $uri for id={}"
        if(!output) {
            message += ", address=$output.address"
        }
        log.info(message, id)
        output
    }

}
