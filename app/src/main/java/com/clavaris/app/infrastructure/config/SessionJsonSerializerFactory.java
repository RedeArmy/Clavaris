package com.clavaris.app.infrastructure.config;

import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.web.servlet.FlashMap;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * TD-ARCH-039: builds the serializer Spring Session uses for every session attribute stored in
 * Redis, in place of the JDK serialization it falls back to when no {@code
 * springSessionDefaultRedisSerializer} exists. Java deserialization of bytes read from a store is a
 * gadget surface if that store is ever reachable by anything but this application, and a Spring
 * Security or Spring Boot upgrade that changes a serialised class's shape used to invalidate every
 * live session at once; JSON with an explicit type allow-list does neither.
 *
 * <p>The mapper is the one Spring Security documents for its own types: {@link
 * SecurityJacksonModules} registers a module per Spring Security artefact on the classpath (core,
 * web, OAuth2 client, OAuth2 authorization server), each with the mixins for the classes it can put
 * in a session and the type allow-list for them, and turns on default typing against that list.
 * Anything not on the list fails to deserialize rather than being instantiated.
 *
 * <p>Our own session attributes are deliberately plain {@code String}s, or maps and lists of them
 * (see {@code SessionDeviceAttributes}), so nothing of ours needs to be on the allow-list. The one
 * addition is Spring MVC's own flash storage: {@code SessionFlashMapManager} keeps the flash
 * attributes of a redirect in the session as a {@code CopyOnWriteArrayList} of {@code FlashMap}s. A
 * {@code FlashMap} is a {@code HashMap}, so only its entries are written; it comes back without its
 * target path and expiry, which means a flash attribute is delivered to the next request whatever
 * it asks for and is not dropped by time, only by being read or by the session ending. Flash
 * attribute values must therefore be Strings or maps and lists of them, never a record.
 *
 * <p>The other is {@link Long} (TD-TEST-011, source identified in TD-TEST-016). It is not one of
 * our attributes: Spring Session hands the serializer a {@code HashMap} that holds the session's
 * own metadata next to its attributes, {@code creationTime} and {@code lastAccessedTime} as {@code
 * Long}s ({@code maxInactiveInterval} is an {@code Integer}). A {@code Long} on its own is written
 * as a plain number, but one inside a map is written with its type, {@code ["java.lang.Long", n]},
 * and an allow-list without {@code Long} refused to read it back: a server error in whichever
 * request next read such a map, which is what a build once showed, once in about eight. Run once
 * with the serializer logging every nested {@code Long} it wrote, over 239 sessions of the whole
 * {@code app} suite: every one was {@code creationTime} or {@code lastAccessedTime}, none an
 * attribute of ours. So {@code Long} belongs on the list for good, as the framework's own type; a
 * boxed number is not a gadget, and the list still refuses everything else. {@code
 * SessionJsonSerializerFactoryTest} pins both, including the exact shape Spring Session writes.
 */
public final class SessionJsonSerializerFactory {

  private SessionJsonSerializerFactory() {}

  public static RedisSerializer<Object> create(final ClassLoader classLoader) {
    final BasicPolymorphicTypeValidator.Builder allowed =
        BasicPolymorphicTypeValidator.builder()
            .allowIfSubType(CopyOnWriteArrayList.class)
            .allowIfSubType(FlashMap.class)
            .allowIfSubType(Long.class);
    final JsonMapper mapper =
        JsonMapper.builder()
            .addModules(SecurityJacksonModules.getModules(classLoader, allowed))
            .build();
    return new GenericJacksonJsonRedisSerializer(mapper);
  }
}
