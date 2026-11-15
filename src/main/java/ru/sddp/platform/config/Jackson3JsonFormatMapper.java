package ru.sddp.platform.config;

import java.lang.reflect.Type;
import org.hibernate.type.format.AbstractJsonFormatMapper;
import tools.jackson.databind.json.JsonMapper;

/** Keeps Hibernate JSON/JSONB values on the same Jackson version as the application. */
public final class Jackson3JsonFormatMapper extends AbstractJsonFormatMapper {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    protected <T> T fromString(CharSequence json, Type type) {
        return mapper.readValue(json.toString(), mapper.constructType(type));
    }

    @Override
    protected <T> String toString(T value, Type type) {
        return mapper.writerFor(mapper.constructType(type)).writeValueAsString(value);
    }
}
