package org.rakam.plugin;

import org.vertx.java.core.json.JsonObject;

/**
 * Created by buremba on 26/05/14.
 */
public interface CollectionMapperPlugin {
    boolean map(JsonObject event);
}